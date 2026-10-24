package com.example.foodexpense.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.foodexpense.ui.theme.AmberStar

/**
 * 星级展示 / 选择组件。
 * [interactive] 为 true 且提供 [onRatingChange] 时可点击选星（表单用）；
 * 否则只读展示（列表用）。
 */
@Composable
fun StarRatingBar(
    rating: Int,
    modifier: Modifier = Modifier,
    starSize: Dp = 26.dp,
    interactive: Boolean = false,
    onRatingChange: ((Int) -> Unit)? = null
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..5) {
            val filled = i <= rating
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "$i 星",
                tint = if (filled) AmberStar else MaterialTheme.colorScheme.outlineVariant,
                modifier = if (interactive && onRatingChange != null) {
                    Modifier
                        .clickable { onRatingChange(i) }
                        .padding(2.dp)
                        .size(starSize)
                } else {
                    Modifier.padding(end = 1.dp).size(starSize)
                }
            )
        }
    }
}
