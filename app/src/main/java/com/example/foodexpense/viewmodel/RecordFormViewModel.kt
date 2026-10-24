package com.example.foodexpense.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.foodexpense.FoodExpenseApp
import com.example.foodexpense.data.entity.MealRecordEntity
import com.example.foodexpense.data.entity.OrderItem
import com.example.foodexpense.data.entity.Platform
import com.example.foodexpense.ocr.OrderScreenshotParser
import com.example.foodexpense.ocr.ParsedOrder
import com.example.foodexpense.util.DateUtils
import com.example.foodexpense.util.ImageStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val MAX_IMAGES = 9

/** 识图超时：超过这个时间就提示手动填写，避免用户对着转圈干等。 */
private const val OCR_TIMEOUT_MS = 20_000L

/**
 * 云端 LLM 单独的超时。
 *
 * **必须与 [OCR_TIMEOUT_MS] 分开**：端侧 OCR 一两秒就回，而 LLM 带长 system prompt、
 * 平台默认 `effort=high` 时可能要十几秒。若共用一个短超时，外层会先掐断，
 * 表现是"每次都静默走本地兜底"，极难排查。
 */
private const val CLOUD_TIMEOUT_MS = 60_000L

/** 识别失败时的统一提示（明确告知"没有改动任何字段"，避免用户以为是表单自己变了）。 */
private const val OCR_FAIL_MESSAGE = "识别失败，请手动填写（本次未修改任何字段）"

/**
 * 订单 + 评价合并表单状态。
 * starRating = 0 表示尚未评分（保存时按"未评价"存储）。
 */
data class RecordFormState(
    val id: Long = 0L,
    // 订单
    val platform: Platform = Platform.MEITUAN,
    val orderTime: Long = System.currentTimeMillis(),
    val storeName: String = "",
    val productName: String = "",
    val priceText: String = "",
    val quantity: Int = 1,
    /**
     * 整单实付金额（元）的输入文本。空 = 用户没填，保存时写入 null，
     * 统计时会回退到「商品价格 × 数量」。
     */
    val paidText: String = "",
    /**
     * 商品明细：整单各件商品的名字/单价/数量，供回看"这顿吃了什么"。
     *
     * **不参与任何金额计算**——保存后落在 [MealRecordEntity.items]，统计仍走
     * [MealRecordEntity.paidAmount]。明细里的单价之和通常不等于实付
     * （还有打包费/配送费/优惠），拿它求和会算错钱。
     */
    val items: List<OrderItem> = emptyList(),
    // 评价（可选）
    val starRating: Int = 0,
    val reviewContent: String = "",
    val reviewImages: List<String> = emptyList(),
    val reviewTime: Long? = null,
    val imageError: String? = null,
    /** 正在后台压缩图片（用于界面提示，避免误以为卡死）。 */
    val isProcessingImages: Boolean = false,
    /** 正在识别订单截图（本机离线 OCR）。 */
    val isRecognizing: Boolean = false,
    /** 识别失败提示（error 色）。 */
    val recognizeError: String? = null,
    /** 识别成功后的核对提示（onSurfaceVariant 色）。 */
    val recognizeHint: String? = null,
    /** 云端调用失败的原因。null = 没走云端或云端成功。**必须让用户看见**，否则静默降级无法排查。 */
    val cloudError: String? = null
)

class RecordFormViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = getApplication<FoodExpenseApp>().recordRepository
    private val app = getApplication<FoodExpenseApp>()

    /** 进程级单例（见 [FoodExpenseApp.ocrRecognizer]），这里只是取引用。 */
    private val ocrRecognizer = getApplication<FoodExpenseApp>().ocrRecognizer

    /** 云端结构化解析器（方案 B）。仅在用户配了 key 时才会被真正调用。 */
    private val llmParser = getApplication<FoodExpenseApp>().llmOrderParser

    private val aiSettings = getApplication<FoodExpenseApp>().aiSettings

    /** 纯规则解析器，无状态、可复用。同时是云端失败后的兜底。 */
    private val parser = OrderScreenshotParser()

    private val _state = MutableStateFlow(RecordFormState())
    val state: StateFlow<RecordFormState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    /** 编辑时删除掉的旧图片，保存后清理文件。 */
    private val removedImages = mutableListOf<String>()

    fun load(recordId: Long) {
        if (recordId <= 0) return
        viewModelScope.launch {
            repository.getRecord(recordId)?.let { r ->
                _state.value = RecordFormState(
                    id = r.id,
                    platform = Platform.fromName(r.platform) ?: Platform.MEITUAN,
                    orderTime = r.orderTime,
                    storeName = r.storeName,
                    productName = r.productName,
                    priceText = formatPriceInput(r.productPrice),
                    quantity = r.productQuantity,
                    // 历史记录 actualPaid 为 null 时，用商品小计预填，用户可直接改
                    paidText = formatPriceInput(r.actualPaid ?: r.subtotal),
                    // 明细必须回填：编辑旧记录保存时若不带上，会被当成"清空明细"覆盖掉
                    items = r.items,
                    starRating = r.starRating ?: 0,
                    reviewContent = r.reviewContent,
                    reviewImages = r.reviewImages,
                    reviewTime = r.reviewTime
                )
            }
        }
    }

    private fun formatPriceInput(price: Double): String =
        if (price == price.toLong().toDouble()) price.toLong().toString() else price.toString()

    /**
     * 新增记录时用日历中选中的日期作为下单日期（保留当前时刻的时分）。
     */
    fun setInitialDate(dateMillis: Long) {
        if (dateMillis <= 0) return
        val combined = DateUtils.combineLocalDateWithCurrentTime(dateMillis, System.currentTimeMillis())
        _state.update { it.copy(orderTime = combined) }
    }

    // ---------- 订单字段 ----------

    fun updatePlatform(p: Platform) = _state.update { it.copy(platform = p) }

    fun updateOrderTime(t: Long) = _state.update { it.copy(orderTime = t) }

    fun updateStoreName(v: String) = _state.update { it.copy(storeName = v) }

    fun updateProductName(v: String) = _state.update { it.copy(productName = v) }

    /** 价格输入限制：最多 6 位整数 + 2 位小数。 */
    fun updatePrice(v: String) {
        if (v.matches(Regex("^\\d{0,6}(\\.\\d{0,2})?$"))) {
            _state.update { it.copy(priceText = v) }
        }
    }

    fun changeQuantity(delta: Int) =
        _state.update { it.copy(quantity = (it.quantity + delta).coerceIn(1, 99)) }

    /** 实付金额输入限制：与价格同规则（最多 6 位整数 + 2 位小数）。 */
    fun updatePaidText(v: String) {
        if (v.matches(Regex("^\\d{0,6}(\\.\\d{0,2})?$"))) {
            _state.update { it.copy(paidText = v) }
        }
    }

    // ---------- 截图识图（端侧 OCR + 规则解析） ----------

    /**
     * 预热 OCR：进入表单页时调一次，把 ML Kit 模型加载的代价提前到用户点按钮之前。
     * 预热失败不影响后续识别，因此整段用 runCatching 吞掉。
     */
    fun warmUpOcr() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ocrRecognizer.warmUp() }
        }
    }

    /**
     * 识别一张订单截图并**只填空字段**。
     *
     * 三级降级链（任何一级失败都自动落到下一级，用户无感）：
     * 1. **云端结构化**（配了 key 才走）：ML Kit 认字 → LLM 理解版面。图片不出手机。
     * 2. **端侧规则**：ML Kit 认字 → [OrderScreenshotParser] 纯规则解析。
     * 3. **手动填写**：两级都失败时只提示，绝不清空用户已填内容。
     *
     * 失败路径（异常 / 超时 / 解析为 null / 什么都没识别出来）统一走 [OCR_FAIL_MESSAGE]，
     * 并且**不修改任何表单字段** —— 用户已经手填的内容绝不能因为一次失败的识别被清空。
     */
    fun recognizeScreenshot(uri: Uri) {
        viewModelScope.launch {
            _state.update {
                it.copy(isRecognizing = true, recognizeError = null, recognizeHint = null)
            }
            val useCloud = aiSettings.isCloudReady
            var usedCloud = false
            var cloudError: String? = null

            // OCR 与 LLM **分开超时**：之前共用一个 20s 总超时，而 LLM 的读取超时
            // 设的是 45s —— 外层先掐断，云端永远来不及返回，表现为"静默走本地"。
            val lines = runCatching {
                withTimeout(OCR_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { ocrRecognizer.recognize(uri) }
                }
            }.getOrNull()

            val parsed = if (lines == null) {
                null
            } else if (!useCloud) {
                runCatching { parser.parse(lines) }.getOrNull()
            } else {
                val cloud = runCatching {
                    withTimeout(CLOUD_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { llmParser.parse(lines) }
                    }
                }
                cloud.onFailure { e -> cloudError = e.message ?: e::class.java.simpleName }
                cloud.getOrNull()?.also { usedCloud = true } ?: parser.parse(lines)
            }
            val usage = if (usedCloud) llmParser.lastUsageSummary else null

            if (parsed == null || parsed.isEmpty) {
                _state.update {
                    it.copy(
                        isRecognizing = false,
                        recognizeError = cloudError?.let { msg -> "$OCR_FAIL_MESSAGE（云端失败：$msg）" }
                            ?: OCR_FAIL_MESSAGE
                    )
                }
            } else {
                _state.update {
                    mergeParsed(it, parsed, if (usedCloud) "云端" else "本地", cloudError, usage)
                }
            }
        }
    }

    /**
     * 把识别结果合并进表单：**只填空字段，绝不覆盖用户已输入**。
     *
     * 数量与平台比较特殊：默认值（1 / 美团）无法区分"用户主动选的"和"没填"，
     * 因此仅在仍是默认值时才用识别结果覆盖。
     */
    private fun mergeParsed(
        s: RecordFormState,
        p: ParsedOrder,
        source: String,
        cloudError: String? = null,
        usage: String? = null
    ): RecordFormState = s.copy(
        storeName = s.storeName.ifBlank { p.storeName ?: "" },
        productName = s.productName.ifBlank { p.productName ?: "" },
        priceText = s.priceText.ifBlank { p.productPrice?.let(::formatPriceInput) ?: "" },
        paidText = s.paidText.ifBlank { p.actualPaid?.let(::formatPriceInput) ?: "" },
        quantity = if (s.quantity == 1 && p.productQuantity > 1) p.productQuantity else s.quantity,
        // 明细同样"只填空"：用户已经手工删改过的明细，不被新的识别结果覆盖
        items = if (s.items.isEmpty()) p.items else s.items,
        platform = if (s.platform == Platform.MEITUAN && p.platform != null) p.platform else s.platform,
        isRecognizing = false, recognizeError = null,
        recognizeHint = buildHint(p, source, usage),
        cloudError = cloudError
    )

    /** 识别成功后的核对提示：说清"取了哪个商品"和"哪些没识别到"。 */
    /**
     * @param source 标记这次结果来自哪条路径（"云端" / "本地"）。
     *   必须让用户看见——否则识别对了也不知道该继续投哪条路，错了也不知道该找谁。
     */
    private fun buildHint(p: ParsedOrder, source: String, usage: String? = null): String {
        val mainName = p.productName ?: "主商品"
        val store = p.storeName
        val head = if (p.itemCount > 1) {
            "识别到 ${p.itemCount} 个商品，已取金额最大的「$mainName」，请核对后保存"
        } else {
            val suffix = if (store.isNullOrBlank()) "" else "（$store）"
            "识别到「$mainName」$suffix，请核对后保存"
        }
        val missing = buildList {
            if (p.storeName == null) add("店铺名")
            if (p.productName == null) add("商品名")
            if (p.productPrice == null) add("商品价格")
            if (p.actualPaid == null) add("实付金额")
        }
        // 明细是"回看"用的附属信息，提示里说清留了几件，用户才知道这个功能生效了
        val detail = if (p.items.size > 1) "；已保留 ${p.items.size} 件商品明细" else ""
        val body = if (missing.isEmpty()) {
            "$head$detail"
        } else {
            "$head$detail；未识别：${missing.joinToString("、")}"
        }
        val cost = usage?.let { "（$it）" }.orEmpty()
        return "${source}识别：$body$cost"
    }

    // ---------- 评价字段 ----------

    /** 点击星星评分；再次点击同一星级表示取消评分。 */
    fun setStar(rating: Int) = _state.update {
        it.copy(starRating = if (it.starRating == rating) 0 else rating)
    }

    fun setReviewContent(v: String) = _state.update { it.copy(reviewContent = v) }

    /** 相册多选的图片：在 IO 线程压缩后追加（最多 9 张），主线程不会卡顿。 */
    fun addImages(uris: List<Uri>) = viewModelScope.launch {
        val current = _state.value.reviewImages
        val remaining = (MAX_IMAGES - current.size).coerceAtLeast(0)
        if (remaining <= 0) {
            _state.update { it.copy(imageError = "最多添加 $MAX_IMAGES 张图片") }
            return@launch
        }
        val toCopy = uris.take(remaining)
        _state.update { it.copy(isProcessingImages = true, imageError = null) }
        try {
            // 关键：解码/缩放/重编码都很吃 CPU，必须放到 IO 线程
            val copied = withContext(Dispatchers.IO) {
                toCopy.mapNotNull { ImageStorage.copyToInternal(app, it) }
            }
            _state.update {
                it.copy(
                    isProcessingImages = false,
                    reviewImages = it.reviewImages + copied,
                    imageError = when {
                        copied.size < toCopy.size -> "有图片导入失败，请重试"
                        uris.size > remaining -> "最多 $MAX_IMAGES 张，已忽略多余的图片"
                        else -> null
                    }
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    isProcessingImages = false,
                    imageError = "图片处理失败：${e.message ?: "未知错误"}"
                )
            }
        }
    }

    fun removeImage(path: String) {
        removedImages += path
        _state.update { it.copy(reviewImages = it.reviewImages - path, imageError = null) }
    }

    // ---------- 校验 ----------

    fun validateStoreName(v: String): String? = if (v.isBlank()) "店铺名称不能为空" else null

    fun validateProductName(v: String): String? = if (v.isBlank()) "商品名称不能为空" else null

    fun validatePrice(v: String): String? {
        val p = v.trim().toDoubleOrNull()
        return when {
            v.isBlank() -> "请输入商品价格"
            p == null -> "请输入有效数字"
            p <= 0.0 -> "价格必须大于 0"
            else -> null
        }
    }

    /**
     * 实付金额校验：**空是合法的**（老用户可能不想填，统计会回退到商品小计）。
     * 只有"填了但不是数字 / 负数"才报错。输入框的正则已挡住大部分非法字符，
     * 这里兜住粘贴、输入法自动补全等边界。
     */
    fun validatePaid(v: String): String? {
        if (v.isBlank()) return null
        val p = v.trim().toDoubleOrNull() ?: return "请输入有效数字"
        return if (p < 0.0) "实付金额不能为负数" else null
    }

    fun validateQuantity(q: Int): String? = if (q < 1) "数量必须至少为 1" else null

    /** 写了评价内容或加了图片却没评分时的提示。 */
    fun reviewHint(): String? {
        val s = _state.value
        return if (s.starRating !in 1..5 && (s.reviewContent.isNotBlank() || s.reviewImages.isNotEmpty())) {
            "已填写评价内容/图片，请同时选择星级"
        } else null
    }

    /**
     * 删除一件商品明细（识别错了、或不想留）。
     *
     * 只影响"回看内容"，**不影响金额**：统计走实付/商品小计，跟明细无关。
     */
    fun removeItem(index: Int) = _state.update {
        if (index !in it.items.indices) it else it.copy(items = it.items.filterIndexed { i, _ -> i != index })
    }

    /**
     * 保存。
     *
     * 已知取舍（不是 bug）：明细里的第 0 项通常是识别出的主商品，
     * 若用户之后手工改了商品名/单价，明细**不会**自动跟着同步——
     * 明细的定位是"截图上当时写了什么"，让它跟随编辑反而会丢失原始信息。
     */
    fun save(onSuccess: () -> Unit) {
        val s = _state.value
        val errors = listOfNotNull(
            validateStoreName(s.storeName),
            validateProductName(s.productName),
            validatePrice(s.priceText),
            validateQuantity(s.quantity),
            reviewHint()
        )
        if (errors.isNotEmpty()) {
            viewModelScope.launch { _messages.emit(errors.joinToString("；")) }
            return
        }

        val price = s.priceText.trim().toDouble()
        val hasReview = s.starRating in 1..5
        viewModelScope.launch {
            val entity = MealRecordEntity(
                id = s.id,
                platform = s.platform.name,
                orderTime = s.orderTime,
                storeName = s.storeName.trim(),
                productName = s.productName.trim(),
                productPrice = price,
                productQuantity = s.quantity,
                // 整单实付：空字符串 → null（"未记录"），不要写成 0
                actualPaid = s.paidText.trim().toDoubleOrNull(),
                // 商品明细：整单各件商品，只用于回看，不参与统计
                items = s.items,
                starRating = if (hasReview) s.starRating else null,
                reviewContent = if (hasReview) s.reviewContent.trim() else "",
                reviewImages = if (hasReview) s.reviewImages else emptyList(),
                reviewTime = if (hasReview) (s.reviewTime ?: System.currentTimeMillis()) else null
            )
            repository.save(entity)
            // 清理编辑过程中移除的旧图片文件
            removedImages.forEach { ImageStorage.deleteFile(it) }
            removedImages.clear()
            _messages.emit(if (hasReview) "已保存（含评价）" else "已保存")
            onSuccess()
        }
    }

    /** 在编辑页删除该记录（含评价图片文件）。 */
    fun delete(onDeleted: () -> Unit) {
        val id = _state.value.id
        if (id <= 0L) return
        viewModelScope.launch {
            repository.getRecord(id)?.let { record ->
                record.reviewImages.forEach { ImageStorage.deleteFile(it) }
                repository.delete(record)
            }
            removedImages.forEach { ImageStorage.deleteFile(it) }
            removedImages.clear()
            _messages.emit("已删除")
            onDeleted()
        }
    }
}
