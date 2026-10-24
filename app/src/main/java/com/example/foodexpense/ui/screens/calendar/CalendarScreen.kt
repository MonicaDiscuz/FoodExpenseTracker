package com.example.foodexpense.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.foodexpense.BuildConfig
import com.example.foodexpense.ui.components.BackupMenu
import com.example.foodexpense.ui.components.EmptyState
import com.example.foodexpense.ui.components.ImageViewerDialog
import com.example.foodexpense.ui.components.RecordCard
import com.example.foodexpense.ui.components.formatMoney
import com.example.foodexpense.ui.theme.NeonGreen
import com.example.foodexpense.ui.theme.NeonGreenSoft
import com.example.foodexpense.ui.theme.glowingOnBackground
import com.example.foodexpense.util.DateUtils
import com.example.foodexpense.viewmodel.CalendarViewModel
import com.example.foodexpense.viewmodel.DaySummary
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

/** 周日开头（与国内日历习惯一致）。 */
private val WEEKDAYS = listOf("日", "一", "二", "三", "四", "五", "六")

/**
 * 首页：日历。
 * 有外卖的日期整格高亮并显示当天花费；点击日期后，下方列出当天的外卖记录。
 * 卡片单击给提示，**长按进入编辑页**（删除在编辑页内进行）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onAddAtDate: (Long) -> Unit,
    onEditRecord: (Long) -> Unit,
    onOcrTest: () -> Unit,
    onAiSettings: () -> Unit
) {
    val vm: CalendarViewModel = viewModel()
    val month by vm.month.collectAsStateWithLifecycle()
    val selectedDate by vm.selectedDate.collectAsStateWithLifecycle()
    val summaries by vm.daySummaries.collectAsStateWithLifecycle()
    val dayRecords by vm.dayRecords.collectAsStateWithLifecycle()

    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val today = LocalDate.now()
    val daysWithRecords = remember(summaries) { summaries.values.count { it.count > 0 } }
    val monthAmount = remember(summaries) { summaries.values.sumOf { it.amount } }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    // 完全透明，背景图案直接透出
                    containerColor = Color.Transparent
                ),
                title = {
                    Column {
                        Text(
                            text = "外卖日历",
                            style = MaterialTheme.typography.titleLarge.glowingOnBackground()
                        )
                        Text(
                            text = "点日期看当天记录 · 长按卡片编辑",
                            style = MaterialTheme.typography.bodySmall.glowingOnBackground(NeonGreenSoft)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = vm::goToToday) {
                        Text(
                            text = "今天",
                            style = MaterialTheme.typography.labelLarge.glowingOnBackground()
                        )
                    }
                    // 识图 POC：端上 OCR 识别订单截图（实验入口，不写库）
                    // 仅 debug 包可见——正式包不应暴露调试入口。
                    // 注意：只包这一个按钮，"今天" 与 BackupMenu 保持同级不受影响。
                    if (BuildConfig.DEBUG) {
                        IconButton(onClick = onOcrTest) {
                            Icon(
                                Icons.Filled.DocumentScanner,
                                contentDescription = "识图识别率测试",
                                tint = NeonGreen
                            )
                        }
                    }
                    // 一键导出 / 导入全部数据（含评价图片）
                    BackupMenu(
                        snackbarHostState = snackbarHostState,
                        onAiSettings = onAiSettings
                    )
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onAddAtDate(DateUtils.dayStartMillis(selectedDate)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "记一笔")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                CalendarCard(
                    month = month,
                    selectedDate = selectedDate,
                    today = today,
                    summaries = summaries,
                    daysWithRecords = daysWithRecords,
                    monthAmount = monthAmount,
                    onPreviousMonth = vm::previousMonth,
                    onNextMonth = vm::nextMonth,
                    onSelectDate = vm::selectDate
                )
            }

            item {
                SelectedDayHeader(
                    date = selectedDate,
                    count = dayRecords.size,
                    total = dayRecords.sumOf { it.paidAmount }
                )
            }

            if (dayRecords.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.ReceiptLong,
                        text = "这天还没有外卖记录\n点右下角 + 补记一笔"
                    )
                }
            } else {
                item {
                    Text(
                        text = "长按卡片进入编辑 · 删除在编辑页内 · 长按图片放大",
                        style = MaterialTheme.typography.bodySmall.glowingOnBackground(NeonGreenSoft),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                items(dayRecords, key = { it.id }) { record ->
                    RecordCard(
                        record = record,
                        onLongClick = { onEditRecord(record.id) },
                        onImageClick = { index -> viewer = record.reviewImages to index }
                    )
                }
            }
        }
    }

    viewer?.let { (images, index) ->
        ImageViewerDialog(
            images = images,
            initialIndex = index,
            onDismiss = { viewer = null }
        )
    }
}

/**
 * 月历卡片：整体使用与记录区不同的暖色底，便于分区；
 * 有外卖的日期整格高亮并突出显示金额。
 */
@Composable
private fun CalendarCard(
    month: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    summaries: Map<LocalDate, DaySummary>,
    daysWithRecords: Int,
    monthAmount: Double,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            // 不透明暖色底：与记录卡片区分，且不被背景图案干扰
            containerColor = lerp(
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.primaryContainer,
                0.72f
            )
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 月份切换 + 本月概览
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPreviousMonth) {
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = "上一月",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${month.year}年${month.monthValue}月",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = if (daysWithRecords == 0) {
                            "本月暂无外卖记录"
                        } else {
                            "本月 $daysWithRecords 天有外卖 · ${formatMoney(monthAmount)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }
                IconButton(onClick = onNextMonth) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "下一月",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // 星期表头
            Row(modifier = Modifier.fillMaxWidth()) {
                WEEKDAYS.forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                }
            }

            // 日期网格
            val leadingBlanks = month.atDay(1).dayOfWeek.value % 7
            val cells: List<LocalDate?> = buildList {
                repeat(leadingBlanks) { add(null) }
                for (day in 1..month.lengthOfMonth()) add(month.atDay(day))
            }
            cells.chunked(7).forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        DayCell(
                            date = date,
                            isSelected = date != null && date == selectedDate,
                            isToday = date != null && date == today,
                            summary = date?.let { summaries[it] },
                            onClick = { date?.let(onSelectDate) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(7 - week.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 单个日期格子：有外卖的日子整格高亮，金额加粗放大。 */
@Composable
private fun DayCell(
    date: LocalDate?,
    isSelected: Boolean,
    isToday: Boolean,
    summary: DaySummary?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (date == null) {
        Box(modifier = modifier.aspectRatio(0.92f))
        return
    }

    val hasRecords = summary != null && summary.count > 0
    val primary = MaterialTheme.colorScheme.primary
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer

    Column(
        modifier = modifier
            .aspectRatio(0.92f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isSelected -> primary.copy(alpha = 0.22f)
                    hasRecords -> MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 日期数字
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isSelected -> primary
                        isToday -> MaterialTheme.colorScheme.surface
                        else -> Color.Transparent
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected || hasRecords) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    hasRecords -> onPrimaryContainer
                    isToday -> primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                }
            )
        }

        // 当天花费：有外卖的日子更醒目
        if (hasRecords) {
            Text(
                text = shortAmount(summary!!.amount),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) primary else onPrimaryContainer,
                maxLines = 1
            )
            if (summary.count > 1) {
                Text(
                    text = "${summary.count}笔",
                    style = MaterialTheme.typography.labelSmall,
                    color = onPrimaryContainer.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
        } else {
            Text(
                text = " ",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

/** 选中日期的标题：日期 + 当天笔数与金额。 */
@Composable
private fun SelectedDayHeader(date: LocalDate, count: Int, total: Double) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = DateUtils.dayLabel(date),
                style = MaterialTheme.typography.titleMedium.glowingOnBackground()
            )
            Text(
                text = DateUtils.formatDate(DateUtils.dayStartMillis(date)),
                style = MaterialTheme.typography.bodySmall.glowingOnBackground(NeonGreenSoft)
            )
        }
        if (count > 0) {
            Text(
                text = "$count 笔 · ${formatMoney(total)}",
                style = MaterialTheme.typography.titleSmall.glowingOnBackground()
            )
        }
    }
}

/** 日历格子里的紧凑金额：超过 1000 显示为 k。 */
private fun shortAmount(amount: Double): String =
    if (amount >= 1000) {
        "¥%.1fk".format(amount / 1000)
    } else {
        "¥" + amount.roundToInt().toString()
    }
