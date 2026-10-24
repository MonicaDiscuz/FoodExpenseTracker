package com.example.foodexpense.ui.screens.records

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.foodexpense.data.entity.Platform
import com.example.foodexpense.ui.components.ImageViewerDialog
import com.example.foodexpense.ui.components.formatPrice
import com.example.foodexpense.ui.components.ReviewImages
import com.example.foodexpense.ui.components.StarRatingBar
import com.example.foodexpense.ui.theme.glowingOnBackground
import com.example.foodexpense.util.DateUtils
import com.example.foodexpense.viewmodel.RecordFormViewModel
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordFormScreen(
    recordId: Long,
    initialDateMillis: Long = -1L,
    onBack: () -> Unit,
    onDeleted: () -> Unit = onBack
) {
    val vm: RecordFormViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDateDialog by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (recordId > 0) {
            vm.load(recordId)
        } else {
            // 从日历页进入：默认使用选中的日期
            vm.setInitialDate(initialDateMillis)
            // 新增场景才需要识图：提前预热 OCR 模型，点了按钮就不用等模型加载
            vm.warmUpOcr()
        }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    // 系统相册多选（一次可选多张，可多次追加）
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isNotEmpty()) vm.addImages(uris)
    }

    // 订单截图单选：选完立刻交给 ViewModel 做端侧 OCR + 规则解析
    val pickScreenshot = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.recognizeScreenshot(uri)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    // 完全透明，背景图案直接透出
                    containerColor = Color.Transparent
                ),
                title = {
                    Text(
                        text = if (recordId > 0) "编辑记录" else "记一笔",
                        style = MaterialTheme.typography.titleLarge.glowingOnBackground()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 仅在编辑已有记录时提供删除入口（点后需二次确认）
                    if (recordId > 0) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = "删除这条记录",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 键盘弹出时自动避让，评价输入框不会被输入法遮挡
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---------- 截图识图（仅新增记录时提供） ----------
            if (recordId <= 0) {
                SectionCard(icon = Icons.Filled.DocumentScanner, title = "截图识图（可选）") {
                    OutlinedButton(
                        onClick = {
                            pickScreenshot.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = !state.isRecognizing && !state.isProcessingImages,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.DocumentScanner, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("从订单截图识别")
                    }

                    if (state.isRecognizing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "正在识别截图（本机离线处理，不上传）…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    state.recognizeHint?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 云端失败原因：必须可见，且要**可全选复制**——
                    // 之前靠用户截图辨认长报错，既累又容易看漏字符。
                    state.cloudError?.let { msg ->
                        SelectionContainer {
                            Text(
                                text = "云端识别未生效，已回退本机规则：$msg",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    state.recognizeError?.let {
                        SelectionContainer {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    if (!state.isRecognizing && state.recognizeHint == null && state.recognizeError == null) {
                        Text(
                            text = "识别结果只填空字段，不会覆盖你已输入的内容；" +
                                "是否走云端、用哪个模型，可在 日历 ⋮ → AI 识别设置 里调整",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---------- 订单信息 ----------
            SectionCard(icon = Icons.Filled.ReceiptLong, title = "订单信息") {
                Text("平台", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Platform.entries.forEach { p ->
                        FilterChip(
                            selected = state.platform == p,
                            onClick = { vm.updatePlatform(p) },
                            label = { Text(p.displayName) },
                            shape = RoundedCornerShape(50)
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("下单时间", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showDateDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(
                            Icons.Filled.DateRange,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(DateUtils.formatDate(state.orderTime))
                    }
                    OutlinedButton(
                        onClick = { showTimeDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(DateUtils.formatTime(state.orderTime).substringAfter(' '))
                    }
                }

                OutlinedTextField(
                    value = state.storeName,
                    onValueChange = vm::updateStoreName,
                    label = { Text("店铺名称") },
                    singleLine = true,
                    isError = vm.validateStoreName(state.storeName) != null,
                    supportingText = { vm.validateStoreName(state.storeName)?.let { Text(it) } },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.productName,
                    onValueChange = vm::updateProductName,
                    label = { Text("商品名称") },
                    singleLine = true,
                    isError = vm.validateProductName(state.productName) != null,
                    supportingText = { vm.validateProductName(state.productName)?.let { Text(it) } },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    OutlinedTextField(
                        value = state.priceText,
                        onValueChange = vm::updatePrice,
                        label = { Text("价格（元）") },
                        singleLine = true,
                        isError = vm.validatePrice(state.priceText) != null,
                        supportingText = { vm.validatePrice(state.priceText)?.let { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f)
                    )

                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text("数量", style = MaterialTheme.typography.labelSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedIconButton(
                                onClick = { vm.changeQuantity(-1) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Remove,
                                    contentDescription = "减少",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = state.quantity.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(40.dp)
                            )
                            OutlinedIconButton(
                                onClick = { vm.changeQuantity(1) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "增加",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // 整单实付：产品的统计口径就是这笔钱（含打包/配送、已扣优惠）
                OutlinedTextField(
                    value = state.paidText,
                    onValueChange = vm::updatePaidText,
                    label = { Text("实付金额（元）") },
                    placeholder = {
                        Text(
                            "整单实付，含打包费/配送费、扣掉优惠",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    singleLine = true,
                    isError = vm.validatePaid(state.paidText) != null,
                    supportingText = {
                        val err = vm.validatePaid(state.paidText)
                        Text(
                            text = err ?: "留空则按「商品价格 × 数量」统计",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )

                // 商品明细：截图识别出来的整单各件商品。
                // 明说"不影响统计金额"——否则用户会以为这个列表的和就是花费，
                // 而它通常不等于实付（还差打包费/配送费/优惠）。
                if (state.items.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "商品明细（${state.items.size} 件）",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "明细仅用于回看这顿吃了什么，不影响统计金额",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        state.items.forEachIndexed { index, item ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "¥${formatPrice(item.price)} × ${item.quantity}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    IconButton(
                                        onClick = { vm.removeItem(index) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "删除「${item.name}」",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 评价 ----------
            SectionCard(icon = Icons.Filled.RateReview, title = "评价（可选）") {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StarRatingBar(
                            rating = state.starRating,
                            starSize = 38.dp,
                            interactive = true,
                            onRatingChange = vm::setStar
                        )
                        Text(
                            text = if (state.starRating in 1..5) {
                                "已选 ${state.starRating} 星 · 再点一次可取消"
                            } else {
                                "点击星星评分，不评分则保存为未评价"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                vm.reviewHint()?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text("评价图片（可多选，最多 9 张）", style = MaterialTheme.typography.labelLarge)

                if (state.reviewImages.isNotEmpty()) {
                    ReviewImages(
                        images = state.reviewImages,
                        thumbnailSize = 86.dp,
                        onImageClick = { index -> viewerIndex = index },
                        onRemove = vm::removeImage
                    )
                    Text(
                        text = "已选 ${state.reviewImages.size} / 9 张 · 长按图片可放大",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.imageError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (state.isProcessingImages) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "正在压缩图片（在后台处理，界面不卡）…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        pickImages.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !state.isProcessingImages,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("从相册选择图片（可多选）")
                }

                OutlinedTextField(
                    value = state.reviewContent,
                    onValueChange = vm::setReviewContent,
                    label = { Text("评价内容（可选）") },
                    minLines = 3,
                    maxLines = 6,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = { vm.save(onSuccess = onBack) },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("保存", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // 日期选择
    if (showDateDialog) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = DateUtils.utcMidnightMillis(state.orderTime)
        )
        DatePickerDialog(
            onDismissRequest = { showDateDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        vm.updateOrderTime(
                            DateUtils.combineDateUtcMidnightWithCurrentTime(it, state.orderTime)
                        )
                    }
                    showDateDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDateDialog = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    // 时间选择
    if (showTimeDialog) {
        val zone = ZoneId.systemDefault()
        val timeState = rememberTimePickerState(
            initialHour = Instant.ofEpochMilli(state.orderTime).atZone(zone).hour,
            initialMinute = Instant.ofEpochMilli(state.orderTime).atZone(zone).minute
        )
        AlertDialog(
            onDismissRequest = { showTimeDialog = false },
            title = { Text("选择时间") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateOrderTime(
                        DateUtils.combineCurrentDateWithTime(
                            timeState.hour,
                            timeState.minute,
                            state.orderTime
                        )
                    )
                    showTimeDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimeDialog = false }) { Text("取消") }
            }
        )
    }

    // 删除前的二次确认
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除这条记录？") },
            text = {
                Text(
                    "「${state.storeName.ifBlank { "这条记录" }} · ${state.productName.ifBlank { "外卖" }}」" +
                        "及其评价、图片都会被删除，删除后无法恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    vm.delete(onDeleted = onDeleted)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    // 图片放大查看
    viewerIndex?.let { index ->
        ImageViewerDialog(
            images = state.reviewImages,
            initialIndex = index,
            onDismiss = { viewerIndex = null }
        )
    }
}

/** 表单分区卡片：带图标标题的圆角容器。 */
@Composable
private fun SectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}
