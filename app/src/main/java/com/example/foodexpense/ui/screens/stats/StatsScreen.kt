package com.example.foodexpense.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.foodexpense.data.entity.Platform
import com.example.foodexpense.ui.components.EmptyState
import com.example.foodexpense.ui.components.formatMoney
import com.example.foodexpense.ui.theme.NeonGreenSoft
import com.example.foodexpense.ui.theme.badgeColor
import com.example.foodexpense.ui.theme.glowingOnBackground
import com.example.foodexpense.viewmodel.StatsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val vm: StatsViewModel = viewModel()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val year by vm.year.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()

    var yearMenu by remember { mutableStateOf(false) }
    var monthMenu by remember { mutableStateOf(false) }

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
                            text = "统计看板",
                            style = MaterialTheme.typography.titleLarge.glowingOnBackground()
                        )
                        Text(
                            text = if (mode == StatsViewModel.PeriodMode.MONTH) {
                                "${year}年${month}月"
                            } else {
                                "${year}年"
                            },
                            style = MaterialTheme.typography.bodySmall.glowingOnBackground(NeonGreenSoft)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 按月 / 按年
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == StatsViewModel.PeriodMode.MONTH,
                    onClick = { vm.setMode(StatsViewModel.PeriodMode.MONTH) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("按月查看") }
                SegmentedButton(
                    selected = mode == StatsViewModel.PeriodMode.YEAR,
                    onClick = { vm.setMode(StatsViewModel.PeriodMode.YEAR) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("按年查看") }
            }

            // 期间切换
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(onClick = vm::previousPeriod) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "上一期间")
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { yearMenu = true },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${year}年")
                            Icon(
                                Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(expanded = yearMenu, onDismissRequest = { yearMenu = false }) {
                            (year - 3..year + 1).forEach { y ->
                                DropdownMenuItem(
                                    text = { Text("${y}年") },
                                    onClick = {
                                        vm.setYear(y)
                                        yearMenu = false
                                    }
                                )
                            }
                        }
                    }
                    if (mode == StatsViewModel.PeriodMode.MONTH) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { monthMenu = true },
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("${month}月")
                                Icon(
                                    Icons.Filled.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = monthMenu,
                                onDismissRequest = { monthMenu = false }
                            ) {
                                (1..12).forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text("${m}月") },
                                        onClick = {
                                            vm.setMonth(m)
                                            monthMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    IconButton(onClick = vm::nextPeriod) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "下一期间")
                    }
                }
            }

            if (stats.orderCount == 0) {
                EmptyState(icon = Icons.Filled.BarChart, text = "该时间段内暂无外卖记录")
            } else {
                // 核心指标
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HighlightCard(
                        icon = Icons.Filled.CalendarMonth,
                        label = "外卖天数",
                        value = stats.deliveryDays.toString(),
                        unit = "天",
                        container = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    HighlightCard(
                        icon = Icons.Filled.AttachMoney,
                        label = "外卖总金额",
                        value = formatMoney(stats.totalAmount),
                        unit = null,
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 明细
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DetailItem(
                            label = "订单数",
                            value = "${stats.orderCount} 笔",
                            modifier = Modifier.weight(1f)
                        )
                        DividerDot()
                        DetailItem(
                            label = "已评价",
                            value = "${stats.reviewCount} 笔",
                            modifier = Modifier.weight(1f)
                        )
                        DividerDot()
                        DetailItem(
                            label = "平均星级",
                            value = if (stats.reviewCount == 0) {
                                "—"
                            } else {
                                "%.1f 星".format(stats.averageStars)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 平台分布
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "平台分布",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Platform.entries.forEach { p ->
                            val amount = stats.byPlatform[p] ?: 0.0
                            if (amount > 0) {
                                val fraction = (amount / stats.totalAmount).toFloat().coerceIn(0f, 1f)
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = p.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = formatMoney(amount),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "  ${(fraction * 100).toInt()}%",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(fraction)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(p.badgeColor())
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 强调指标卡：图标 + 标签 + 大数字（浅色拟物底）。 */
@Composable
private fun HighlightCard(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String?,
    container: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.85f)
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = contentColor,
                    maxLines = 1
                )
                if (unit != null) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DividerDot() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
