package com.example.foodexpense.ui.screens.records

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.foodexpense.data.entity.MealRecordEntity
import com.example.foodexpense.data.entity.Platform
import com.example.foodexpense.ui.components.EmptyState
import com.example.foodexpense.ui.components.ImageViewerDialog
import com.example.foodexpense.ui.components.RecordCard
import com.example.foodexpense.ui.components.formatMoney
import com.example.foodexpense.ui.theme.NeonGreenSoft
import com.example.foodexpense.ui.theme.glowingOnBackground
import com.example.foodexpense.ui.theme.starColor
import com.example.foodexpense.util.DateUtils
import com.example.foodexpense.viewmodel.RecordFilter
import com.example.foodexpense.viewmodel.RecordListViewModel
import kotlin.math.abs

/** 星级卡片布局：5★/4★、3★/2★、1★/未评级（0 代表未评级）。 */
private val STAR_CARD_ROWS = listOf(
    listOf(5, 4),
    listOf(3, 2),
    listOf(1, 0)
)

/**
 * 记录页：顶部只有搜索框（+ 平台筛选），下方默认是 6 张星级卡片；
 * 点卡片进入该星级的结果列表，**左右滑动可返回卡片视图**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordListScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit
) {
    val vm: RecordListViewModel = viewModel()
    val records by vm.records.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    // 下滑列表时收起搜索栏，上滑或回到顶部时自动展开
    val headerVisible = rememberHeaderVisibleOnScroll(listState)

    var platformMenuExpanded by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }

    val filtering = filter.keyword.isNotBlank() || filter.star != null || filter.platform != null

    // 进入星级/筛选结果后：系统返回手势（屏幕左右边缘内滑）直接回到星级卡片
    BackHandler(enabled = filtering) { vm.clearFilters() }

    // 未筛选时 records 即全部记录，可直接统计各星级卡片
    val starBuckets = remember(records) { records.groupBy { it.starRating } }

    // 结果列表按日期分组（records 已按下单时间倒序）
    val groupedDays = remember(records) {
        records.groupBy { DateUtils.toLocalDate(it.orderTime) }
            .entries
            .sortedByDescending { it.key }
    }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "记一笔")
            }
        }
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 顶部留白：让背景插画的人物脸部露出来（按可用高度取比例，各尺寸屏幕都适用）
            val topGap = (maxHeight * 0.17f).coerceIn(84.dp, 168.dp)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topGap)
                    // 结果页任意位置左右滑动即可返回星级卡片（只观察事件、不消费，列表滚动不受影响）
                    .horizontalSwipeToBack(
                        enabled = filtering,
                        onSwipe = { vm.clearFilters() }
                    )
            ) {
                // ---------- 搜索框（+ 平台筛选） ----------
                AnimatedVisibility(
                    visible = headerVisible,
                    enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(200)),
                    exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(140))
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = filter.keyword,
                                onValueChange = vm::setKeyword,
                                singleLine = true,
                                placeholder = { Text("搜索店铺、商品或评价内容") },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (filter.keyword.isNotEmpty()) {
                                        IconButton(onClick = { vm.setKeyword("") }) {
                                            Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(28.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    // 半透明底衬：背景图案可见，输入的文字依然清晰
                                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Box {
                                IconButton(onClick = { platformMenuExpanded = true }) {
                                    Icon(
                                        Icons.Filled.FilterList,
                                        contentDescription = "筛选平台",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                DropdownMenu(
                                    expanded = platformMenuExpanded,
                                    onDismissRequest = { platformMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("全部平台") },
                                        onClick = {
                                            vm.setPlatform(null)
                                            platformMenuExpanded = false
                                        }
                                    )
                                    Platform.entries.forEach { p ->
                                        DropdownMenuItem(
                                            text = { Text(p.displayName) },
                                            onClick = {
                                                vm.setPlatform(p)
                                                platformMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        if (filtering) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 20.dp, end = 8.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = filterLabel(filter),
                                    style = MaterialTheme.typography.bodyMedium.glowingOnBackground(),
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { vm.clearFilters() }) {
                                    Text(
                                        text = "清除筛选",
                                        style = MaterialTheme.typography.labelLarge.glowingOnBackground()
                                    )
                                }
                            }
                            Text(
                                text = "⇠ 左右滑动返回星级卡片 · 长按卡片编辑 · 长按图片放大",
                                style = MaterialTheme.typography.bodySmall.glowingOnBackground(NeonGreenSoft),
                                modifier = Modifier.padding(start = 20.dp, bottom = 6.dp)
                            )
                        } else {
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }

                if (!filtering) {
                    // ---------- 默认：6 张星级小卡片铺满剩余空间 ----------
                    StarBucketGrid(
                        buckets = starBuckets,
                        onPick = { star -> vm.setStar(star) },
                        modifier = Modifier.weight(1f)
                    )
                } else if (records.isEmpty()) {
                    EmptyState(
                        icon = if (filter.keyword.isNotBlank()) Icons.Filled.Search else Icons.Filled.ReceiptLong,
                        text = when {
                            filter.keyword.isNotBlank() -> "没有找到与「${filter.keyword}」相关的记录"
                            filter.star == -1 -> "暂无未评级的记录"
                            filter.star != null -> "暂无 ${filter.star} 星记录"
                            filter.platform != null -> "该平台暂无记录"
                            else -> "还没有外卖记录\n点击右下角 + 记第一笔"
                        }
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        groupedDays.forEach { (day, dayRecords) ->
                            item(key = "day-$day") {
                                DayHeader(
                                    label = DateUtils.dayLabel(day),
                                    count = dayRecords.size,
                                    total = dayRecords.sumOf { it.paidAmount }
                                )
                            }
                            items(dayRecords, key = { it.id }) { record ->
                                RecordCard(
                                    record = record,
                                    onLongClick = { onEdit(record.id) },
                                    onImageClick = { index -> viewer = record.reviewImages to index }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 图片放大查看
    viewer?.let { (images, index) ->
        ImageViewerDialog(
            images = images,
            initialIndex = index,
            onDismiss = { viewer = null }
        )
    }
}

/** 6 张星级卡片：2 个一行、共 3 行，铺满剩余空间。 */
@Composable
private fun StarBucketGrid(
    buckets: Map<Int?, List<MealRecordEntity>>,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        STAR_CARD_ROWS.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { star ->
                    // 0 表示"未评级"，对应 DAO 约定的 -1
                    val key = if (star == 0) null else star
                    val list = buckets[key].orEmpty()
                    StarBucketCard(
                        star = key,
                        count = list.size,
                        amount = list.sumOf { it.paidAmount },
                        onClick = { onPick(if (star == 0) -1 else star) },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

/** 单个星级卡片：星标放大 + 记录条数 + 金额（条数与金额字号不变），每个星级一种颜色。 */
@Composable
private fun StarBucketCard(
    star: Int?,
    count: Int,
    amount: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = starColor(star)
    val shape = MaterialTheme.shapes.medium

    Card(
        modifier = modifier.clip(shape).clickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(
            // 不透明淡彩底：与背景图案叠加也不会发灰
            containerColor = lerp(MaterialTheme.colorScheme.surface, accent, 0.22f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (star != null) {
                // 星标行使用 weight(1f)：吃掉卡片剩余高度，
                // 卡片再矮也只会缩小星标、不会把下方条数/金额挤出卡片
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(5) { i ->
                        Icon(
                            imageVector = if (i < star) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = null,
                            tint = if (i < star) accent else accent.copy(alpha = 0.28f),
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
                Text(
                    text = "$star 星",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.RateReview,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.fillMaxHeight().aspectRatio(1f)
                    )
                }
                Text(
                    text = "未评级",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent
                )
            }

            Text(
                text = "$count 条",
                style = MaterialTheme.typography.titleLarge,
                color = accent
            )
            Text(
                text = formatMoney(amount),
                style = MaterialTheme.typography.bodySmall,
                color = accent.copy(alpha = 0.85f),
                maxLines = 1
            )
        }
    }
}

/** 当前筛选条件的文字描述。 */
private fun filterLabel(filter: RecordFilter): String {
    val parts = mutableListOf<String>()
    if (filter.keyword.isNotBlank()) parts += "搜索「${filter.keyword}」"
    filter.star?.let { parts += if (it == -1) "未评级" else "$it 星" }
    filter.platform?.let { parts += it.displayName }
    return parts.joinToString(" · ").ifBlank { "全部记录" }
}

/**
 * 结果页"左右滑动返回"：只观察指针事件、不消费，因此列表上下滚动依旧正常。
 * 横向位移超过 [threshold] 且大于纵向位移时触发一次 [onSwipe]。
 */
private fun Modifier.horizontalSwipeToBack(
    enabled: Boolean,
    threshold: Dp = 56.dp,
    onSwipe: () -> Unit
): Modifier = if (!enabled) {
    this
} else {
    this.pointerInput(enabled, threshold) {
        val limit = threshold.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var totalX = 0f
            var totalY = 0f
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                // IgnoreConsumed：列表滚动会消费事件，否则读到的位移恒为 0
                val delta = change.positionChangeIgnoreConsumed()
                totalX += delta.x
                totalY += delta.y
                if (abs(totalX) > limit && abs(totalX) > abs(totalY)) {
                    onSwipe()
                    break
                }
            }
        }
    }
}

/**
 * 根据列表滚动方向决定是否展开顶部搜索区：
 * 向下滚动超过阈值时收起；向上滚动或回到顶部时展开（带阈值避免抖动）。
 */
@Composable
private fun rememberHeaderVisibleOnScroll(listState: LazyListState): Boolean {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var lastPosition = 0
        var accumulated = 0
        snapshotFlow {
            listState.firstVisibleItemIndex * 10_000 + listState.firstVisibleItemScrollOffset
        }.collect { position ->
            val delta = position - lastPosition
            lastPosition = position
            accumulated = if ((delta > 0) == (accumulated > 0)) accumulated + delta else delta
            when {
                position <= 40 -> {
                    visible = true
                    accumulated = 0
                }
                accumulated > 220 -> visible = false
                accumulated < -100 -> visible = true
            }
        }
    }
    return visible
}

/** 日期分组标题：日期 + 当天笔数与金额。 */
@Composable
private fun DayHeader(label: String, count: Int, total: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.glowingOnBackground()
        )
        Text(
            text = "$count 笔 · ${formatMoney(total)}",
            style = MaterialTheme.typography.bodySmall.glowingOnBackground(NeonGreenSoft)
        )
    }
}
