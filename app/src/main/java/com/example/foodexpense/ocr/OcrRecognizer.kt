package com.example.foodexpense.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 端侧中文 OCR 识别器 —— **全项目唯一接触 ML Kit 的类**。
 *
 * 采用 **bundled** 版本（`text-recognition-chinese`）：
 * 模型随 APK 一起打包，离线可用，不需要 GMS，也不需要 `INTERNET` 权限。
 * 目标用户里有大量无 GMS 的国产机，unbundled 方案会直接不可用。
 *
 * 线程模型：
 * - [recognize] 内部切到 [Dispatchers.IO]
 * - ML Kit 返回的是 `Task`，这里用 [suspendCancellableCoroutine] 桥接成挂起函数
 *   （不引入 `kotlinx-coroutines-play-services`，避免多一个依赖）
 */
class OcrRecognizer(private val context: Context) {

    /** 首次访问时才创建 client，避免拖慢冷启动。 */
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 预热：提前把 bundled 模型与 client 初始化好，缩短用户点击「识别」后的首次等待。
     *
     * 注意不要把这里写成阻塞式的模型下载 —— bundled 模型的初始化由 ML Kit 内部按需完成，
     * 我们能做的是提前触发 client 创建，并吞掉任何异常（预热失败不影响正式识别）。
     */
    fun warmUp() {
        runCatching { recognizer }
    }

    /**
     * 识别一张图片，返回按阅读顺序（上→下、左→右）排好的文本行。
     *
     * @throws Exception 图片无法解码 / ML Kit 处理失败时抛出，由调用方捕获。
     */
    suspend fun recognize(uri: Uri): List<TextLine> = withContext(Dispatchers.IO) {
        // InputImage.fromFilePath 自带 EXIF 旋转处理，不用自己算 orientation
        val image = InputImage.fromFilePath(context, uri)
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    val lines = text.textBlocks
                        .flatMap { it.lines }
                        .map { line ->
                            val box = line.boundingBox
                            TextLine(
                                text = line.text,
                                left = box?.left ?: 0,
                                top = box?.top ?: 0,
                                right = box?.right ?: 0,
                                bottom = box?.bottom ?: 0
                            )
                        }
                        .sortedWith(compareBy({ it.top }, { it.left }))
                    if (cont.isActive) cont.resume(lines)
                }
                .addOnFailureListener { error ->
                    if (cont.isActive) cont.resumeWithException(error)
                }
                .addOnCanceledListener {
                    if (cont.isActive) cont.cancel()
                }
        }
    }

    /** 释放底层识别器（进程退出 / 长期不用时调用）。 */
    fun close() {
        runCatching { recognizer.close() }
    }
}
