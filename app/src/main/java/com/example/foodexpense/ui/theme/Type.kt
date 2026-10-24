package com.example.foodexpense.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val base = Typography()

/** 在 M3 默认排版基础上加强层次：标题更醒目、正文行高更舒适。 */
val AppTypography = base.copy(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp),
    bodySmall = base.bodySmall.copy(lineHeight = 17.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium)
)
