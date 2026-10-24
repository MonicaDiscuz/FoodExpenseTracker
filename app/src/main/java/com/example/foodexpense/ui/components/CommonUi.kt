package com.example.foodexpense.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.foodexpense.data.entity.Platform
import com.example.foodexpense.ui.theme.NeonGreenSoft
import com.example.foodexpense.ui.theme.badgeColor
import com.example.foodexpense.ui.theme.glowingOnBackground
import java.io.File

/** 空状态提示：图标 + 说明文案。 */
@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = lerp(
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.primaryContainer,
                0.75f
            )
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(20.dp).size(34.dp)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.glowingOnBackground(NeonGreenSoft),
            textAlign = TextAlign.Center
        )
    }
}

/** 平台彩色小徽章。 */
@Composable
fun PlatformBadge(platform: Platform, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = platform.badgeColor().copy(alpha = 0.16f)
    ) {
        Text(
            text = platform.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = platform.badgeColor(),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
        )
    }
}

/** 金额高亮文本。 */
@Composable
fun MoneyText(
    amount: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium
) {
    Text(
        text = formatMoney(amount),
        style = style,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}

/**
 * 评价图片缩略图行。
 * 点击 / 长按缩略图都会回调 [onImageClick]（用于放大查看）；提供 [onRemove] 时右上角显示移除按钮。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReviewImages(
    images: List<String>,
    modifier: Modifier = Modifier,
    thumbnailSize: Dp = 72.dp,
    onImageClick: ((Int) -> Unit)? = null,
    onRemove: ((String) -> Unit)? = null
) {
    if (images.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(images, key = { _, path -> path }) { index, path ->
            Box {
                AsyncImage(
                    model = File(path),
                    contentDescription = "评价图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(thumbnailSize)
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = { onImageClick?.invoke(index) },
                            onLongClick = { onImageClick?.invoke(index) }
                        )
                )
                if (onRemove != null) {
                    IconButton(
                        onClick = { onRemove(path) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(22.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "移除图片",
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

fun formatMoney(amount: Double): String = "¥" + String.format("%.2f", amount)

/** 价格展示：整数不带小数位（25.0 -> "25"）。 */
fun formatPrice(price: Double): String =
    if (price == price.toLong().toDouble()) price.toLong().toString() else price.toString()
