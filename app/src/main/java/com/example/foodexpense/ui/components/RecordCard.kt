package com.example.foodexpense.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.foodexpense.data.entity.MealRecordEntity
import com.example.foodexpense.data.entity.Platform
import com.example.foodexpense.ui.theme.badgeColor
import com.example.foodexpense.util.DateUtils

/**
 * 记录卡片（订单 + 评价合并展示），日历页与记录页共用。
 * - **长按卡片**：[onLongClick]（进入编辑页；删除在编辑页内进行）
 * - 点击/长按缩略图：[onImageClick]（放大查看）
 */
@Composable
fun RecordCard(
    record: MealRecordEntity,
    onLongClick: () -> Unit,
    onImageClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val platform = Platform.fromName(record.platform) ?: Platform.MEITUAN
    val accent = platform.badgeColor()
    val star = record.starRating

    Card(
        modifier = modifier
            .fillMaxWidth()
            // 只响应长按，单击不做任何事（避免误触进入编辑）
            .pointerInput(record.id) {
                detectTapGestures(onLongPress = { onLongClick() })
            },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 左侧平台色条
                .drawBehind {
                    drawRect(color = accent, size = Size(6.dp.toPx(), size.height))
                }
                .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = record.storeName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                MoneyText(record.paidAmount, style = MaterialTheme.typography.titleMedium)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlatformBadge(platform)
                Text(
                    text = record.productName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "¥${formatPrice(record.productPrice)} × ${record.productQuantity}" +
                    "  ·  ${DateUtils.formatTime(record.orderTime)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 多商品订单：把整单各件商品列出来，供回看"这顿吃了什么"。
            // 只有 1 件时不展示——那和上面的主商品行重复。
            // 刻意**不显示合计**：单价之和通常不等于实付（还差打包费/配送费/优惠），
            // 显示出来会被误当成花费。
            if (record.items.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    record.items.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "¥${formatPrice(item.price)} × ${item.quantity}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (star != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StarRatingBar(rating = star, starSize = 15.dp)
                            record.reviewTime?.let {
                                Text(
                                    text = DateUtils.formatTime(it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (record.reviewContent.isNotBlank()) {
                            Text(
                                text = record.reviewContent,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        if (record.reviewImages.isNotEmpty()) {
                            ReviewImages(
                                images = record.reviewImages,
                                thumbnailSize = 62.dp,
                                onImageClick = onImageClick
                            )
                        }
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.RateReview,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "未评价 · 长按卡片补写",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}
