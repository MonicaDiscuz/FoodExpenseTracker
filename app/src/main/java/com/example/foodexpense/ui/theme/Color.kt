package com.example.foodexpense.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.foodexpense.data.entity.Platform

// ---------- 品牌色：暖橙 ----------
val OrangePrimary = Color(0xFFE8590C)
val OrangeOnPrimary = Color(0xFFFFFFFF)
val OrangeContainer = Color(0xFFFFE2D0)
val OrangeOnContainer = Color(0xFF3B1500)

// ---------- 辅助色：暖棕（评价区） ----------
val WarmSecondary = Color(0xFF8A5A44)
val WarmSecondaryContainer = Color(0xFFFFDBCB)
val WarmOnSecondaryContainer = Color(0xFF33180C)

// ---------- 强调色：清爽绿（统计） ----------
val FreshTertiary = Color(0xFF3F6B32)
val FreshTertiaryContainer = Color(0xFFC0F0AB)

// ---------- 中性色 ----------
val LightBackground = Color(0xFFFFFBF8)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF6E4DA)
val LightOnSurface = Color(0xFF231A15)
val LightOnSurfaceVariant = Color(0xFF5A4136)
val LightOutline = Color(0xFF9A8177)
val LightOutlineVariant = Color(0xFFE4CFC3)

val DarkBackground = Color(0xFF19120E)
val DarkSurface = Color(0xFF201813)
val DarkSurfaceVariant = Color(0xFF53433B)
val DarkOnSurface = Color(0xFFF1E0D8)
val DarkOnSurfaceVariant = Color(0xFFD9C2B7)
val DarkOutline = Color(0xFFA38C82)
val DarkOutlineVariant = Color(0xFF53433B)

val DarkPrimary = Color(0xFFFFB68C)
val DarkOnPrimary = Color(0xFF55200A)
val DarkPrimaryContainer = Color(0xFF7A3411)
val DarkOnPrimaryContainer = Color(0xFFFFDBC8)
val DarkSecondaryContainer = Color(0xFF5E4032)
val DarkOnSecondaryContainer = Color(0xFFFFDBCB)

// 星级金色
val AmberStar = Color(0xFFF5A524)

// 各平台品牌色
private val PlatformColorMap = mapOf(
    Platform.MEITUAN to Color(0xFFFFB300),      // 美团黄
    Platform.TAOBAO_FLASH to Color(0xFFFF5000), // 淘宝橙红
    Platform.JD to Color(0xFFE1251B)            // 京东红
)

fun Platform.badgeColor(): Color = PlatformColorMap[this] ?: Color(0xFF757575)

// ---------- 记录页星级卡片配色（每个星级一种颜色） ----------
private val StarColorMap = mapOf(
    1 to Color(0xFFD32F2F),  // 1 星：红
    2 to Color(0xFFEF6C00),  // 2 星：橙
    3 to Color(0xFFF9A825),  // 3 星：琥珀
    4 to Color(0xFF00897B),  // 4 星：青
    5 to Color(0xFF2E7D32)   // 5 星：绿
)

private val StarUnratedColor = Color(0xFF546E7A) // 未评级：蓝灰

fun starColor(star: Int?): Color =
    if (star == null) StarUnratedColor else StarColorMap[star] ?: StarUnratedColor

// ---------- 压在背景图案上的文字：荧光绿 ----------
val NeonGreen = Color(0xFF39FF14)      // 主文字（高对比荧光绿）
val NeonGreenSoft = Color(0xFFB4FF8A)  // 次级文字（稍浅的荧光绿）
