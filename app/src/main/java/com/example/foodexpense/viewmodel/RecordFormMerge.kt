package com.example.foodexpense.viewmodel

import com.example.foodexpense.data.entity.Platform
import com.example.foodexpense.ocr.ParsedOrder

/**
 * 「识别结果 → 表单」的合并规则。
 *
 * ## 为什么从 ViewModel 里抽出来
 * 这几条规则是用户**能直接感受到的契约**：
 * 识别只填空、不覆盖你已经输入的内容；数量/平台仅在还是默认值时才被覆盖；
 * 商品明细同样只填空。它们出错时的表现是「我手填的东西被吃掉了」——
 * 用户一试就知道，但只要没人试，坏上几个月也没人发现。
 *
 * 而 `mergeParsed` 原本是 `RecordFormViewModel` 的私有方法，
 * 而 ViewModel 需要 `Application` 才能构造，等于把这几条规则锁在了测试之外。
 * 抽成**同包的 internal 顶层函数**后，它可以被纯 JVM 单测直接覆盖
 * （见 `RecordFormMergeTest`），不需要 Robolectric，也不需要改 ViewModel 的构造器。
 *
 * 这里刻意**不依赖任何 Android 类型**：入参 [RecordFormState] / [ParsedOrder] 都是纯数据。
 */

/** 价格输入框的显示格式：整数不带小数点（`12` 而不是 `12.0`）。 */
internal fun formatPriceInput(price: Double): String =
    if (price == price.toLong().toDouble()) price.toLong().toString() else price.toString()

/**
 * 把识别结果合并进表单：**只填空字段，绝不覆盖用户已输入**。
 *
 * 数量与平台比较特殊：默认值（`1` / 美团）无法区分"用户主动选的"和"没填"，
 * 因此仅在仍是默认值时才用识别结果覆盖。
 *
 * @param source 标记这次结果来自哪条路径（"云端" / "本地"），会写进核对提示。
 * @param cloudError 云端失败原因；非空时界面会以 error 色显示，让降级**可见**。
 * @param usage 云端用量摘要（token 数），仅用于展示成本。
 */
internal fun mergeParsedInto(
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
    recognizeHint = buildRecognizeHint(p, source, usage),
    cloudError = cloudError
)

/**
 * 识别成功后的核对提示：说清"取了哪个商品"和"哪些没识别到"。
 *
 * @param source 标记这次结果来自哪条路径（"云端" / "本地"）。
 *   必须让用户看见——否则识别对了也不知道该继续投哪条路，错了也不知道该找谁。
 */
internal fun buildRecognizeHint(
    p: ParsedOrder,
    source: String,
    usage: String? = null
): String {
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
