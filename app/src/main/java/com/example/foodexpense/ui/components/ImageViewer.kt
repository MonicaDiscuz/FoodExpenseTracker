package com.example.foodexpense.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.abs

/**
 * 全屏图片查看器：
 * - **左右滑动可切换上一张 / 下一张**（放大后自动禁用，改为拖动查看细节）
 * - 双指缩放、双击放大/还原、拖动平移
 * - 顶部关闭按钮 + 页码，两侧箭头也可切换
 */
@Composable
fun ImageViewerDialog(
    images: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    if (images.isEmpty()) return
    var index by remember(images, initialIndex) {
        mutableIntStateOf(initialIndex.coerceIn(0, images.lastIndex))
    }
    // 当前图片是否处于放大状态（放大时禁用左右翻页，避免和拖拽冲突）
    val zoomed = remember { mutableStateOf(false) }

    fun goNext() {
        if (images.size > 1) {
            index = if (index == images.lastIndex) 0 else index + 1
            zoomed.value = false
        }
    }

    fun goPrev() {
        if (images.size > 1) {
            index = if (index == 0) images.lastIndex else index - 1
            zoomed.value = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // 左右滑动切换图片：只观察指针事件、不消费，
                // 因此不影响子级（缩放/拖拽）的手势
                .pointerInput(images.size) {
                    val threshold = 56.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalX = 0f
                        var totalY = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            // 用 IgnoreConsumed：子级（缩放/拖拽）会消费事件，否则这里读到的位移恒为 0
                            val delta = change.positionChangeIgnoreConsumed()
                            totalX += delta.x
                            totalY += delta.y
                        }
                        if (!zoomed.value &&
                            abs(totalX) > threshold &&
                            abs(totalX) > abs(totalY) * 1.2f
                        ) {
                            if (totalX < 0) goNext() else goPrev()
                        }
                    }
                }
        ) {
            key(index) {
                ZoomableImage(
                    path = images[index],
                    onZoomChanged = { zoomed.value = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 56.dp)
                )
            }

            // 顶部：关闭 + 页码
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                }
                Text(
                    text = "${index + 1} / ${images.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // 左右切换
            if (images.size > 1) {
                IconButton(
                    onClick = { goPrev() },
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = "上一张",
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = { goNext() },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "下一张",
                        tint = Color.White
                    )
                }
            }

            // 底部提示
            Text(
                text = if (images.size > 1) {
                    "左右滑动切换图片 · 双指缩放 · 双击放大"
                } else {
                    "双指缩放 · 双击放大 · 拖动查看"
                },
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    path: String,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 通知外层当前是否放大（放大时禁用左右翻页）
    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 6f)
                    scale = newScale
                    offset = if (newScale > 1f) {
                        val maxX = (newScale - 1f) * size.width / 2f
                        val maxY = (newScale - 1f) * size.height / 2f
                        Offset(
                            x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                            y = (offset.y + pan.y).coerceIn(-maxY, maxY)
                        )
                    } else {
                        Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = File(path),
            contentDescription = "评价图片",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}
