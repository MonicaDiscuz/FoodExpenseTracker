package com.example.foodexpense.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.foodexpense.R

/**
 * App 内统一的背景图案（不透明，完整呈现插画）。
 * 压在图案上的文字统一用 [com.example.foodexpense.ui.theme.glowingOnBackground]（荧光绿 + 描影）保证对比度。
 */
@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    alpha: Float = 1f
) {
    Image(
        painter = painterResource(R.drawable.app_background),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = alpha,
        modifier = modifier.fillMaxSize()
    )
}
