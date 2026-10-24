package com.example.foodexpense.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/**
 * 评价图片存储。
 *
 * 相机原图常有 3~4MB，直接保存会占满存储、备份包也会非常大，因此：
 * 1. 入库时先**压缩**：解码 → 长边限制 [MAX_EDGE_PX] → 校正 EXIF 方向 → JPEG [JPEG_QUALITY] 重编码，
 *    典型 3~4MB 照片会降到 **200~400KB**；
 * 2. 压缩失败时回退为原样复制，保证不丢图；
 * 3. 提供 [compressInPlace] 对**已保存的大图**做批量原地压缩（⋮ → 压缩已有评价图片）。
 */
object ImageStorage {

    private const val DIR_NAME = "review_images"

    /** 压缩后最长边（px）：够手机上全屏查看，再大纯属浪费。 */
    private const val MAX_EDGE_PX = 1600

    /** JPEG 质量：85 在体积与观感之间比较均衡。 */
    private const val JPEG_QUALITY = 85

    /** 已存在的图片超过这个体积才值得再压。 */
    private const val COMPRESS_THRESHOLD_BYTES = 300L * 1024L

    /** 源图本身就不大（截图、已压过的图）时直接原样保存，省掉一次解码+重编码。 */
    private const val SMALL_IMAGE_BYTES = 300L * 1024L

    fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** 把内容 Uri 压缩后复制为私有目录下的 jpg 文件，返回绝对路径；失败返回 null。 */
    fun copyToInternal(context: Context, uri: Uri): String? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        saveCompressed(context, bytes)
    } catch (_: Exception) {
        null
    }

    /** 把字节内容压缩后保存到私有目录（备份导入也走这里），返回路径；解码失败时退化为原样写入。 */
    fun saveCompressed(context: Context, bytes: ByteArray): String? = try {
        val dest = File(dir(context), "${UUID.randomUUID()}.jpg")
        // 小图没必要再解一遍码（截图通常 100~300KB），直接落盘
        if (bytes.size <= SMALL_IMAGE_BYTES || !compressBytesToFile(bytes, dest)) {
            dest.outputStream().use { it.write(bytes) }
        }
        dest.absolutePath
    } catch (_: Exception) {
        null
    }

    fun deleteFile(path: String?) {
        if (path.isNullOrBlank()) return
        try {
            File(path).delete()
        } catch (_: Exception) { /* 忽略删除失败 */ }
    }

    /**
     * 原地压缩一张已存在的图片。
     * @return 节省的字节数（未压缩或没有收益时返回 0）
     */
    fun compressInPlace(path: String): Long {
        return try {
            val file = File(path)
            if (!file.exists() || file.length() <= COMPRESS_THRESHOLD_BYTES) return 0L
            val before = file.length()
            val bytes = file.readBytes()
            val temp = File(file.parentFile, "tmp_${file.name}")
            if (!compressBytesToFile(bytes, temp)) {
                temp.delete()
                return 0L
            }
            val after = temp.length()
            if (after >= before) {
                temp.delete()
                return 0L
            }
            temp.copyTo(file, overwrite = true)
            temp.delete()
            before - after
        } catch (_: Exception) {
            0L
        }
    }

    /** 解码 + 缩放 + 方向校正 + JPEG 重编码，写入 [dest]。 */
    private fun compressBytesToFile(bytes: ByteArray, dest: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
        bitmap = scaleDown(bitmap, MAX_EDGE_PX)
        bitmap = applyExifRotation(bytes, bitmap)
        val saved = try {
            dest.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } catch (_: Exception) {
            false
        } finally {
            bitmap.recycle()
        }
        return saved
    }

    /** 计算 2 的幂采样率：先把超大原图按比例缩小再解码，避免 OOM。 */
    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= MAX_EDGE_PX || height / (sample * 2) >= MAX_EDGE_PX) {
            sample *= 2
        }
        return sample
    }

    /** 把长边限制到 [maxEdge]，返回新位图（原图会被回收）。 */
    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val max = maxOf(width, height)
        if (max <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / max
        val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** 相机照片的方向信息在 EXIF 里，重编码会丢掉它，这里先按 EXIF 把图转正。 */
    @Suppress("DEPRECATION")
    private fun applyExifRotation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (_: Exception) {
            bitmap
        }
    }
}
