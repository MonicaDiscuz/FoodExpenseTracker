package com.example.foodexpense.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle

/**
 * 直接压在背景图案上的文字样式：荧光绿 + 深色描影。
 * 背景图不再加白色底衬，靠"亮绿 + 描影"保证对比度。
 */
fun TextStyle.glowingOnBackground(color: Color = NeonGreen): TextStyle =
    copy(
        color = color,
        shadow = Shadow(
            color = Color.Black.copy(alpha = 0.9f),
            offset = Offset(0f, 1.5f),
            blurRadius = 4f
        )
    )
