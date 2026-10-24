package com.example.foodexpense.ocr

/**
 * OCR 识别出的**一行文本**及其版面位置（像素）。
 *
 * 设计约束：本类**不允许**依赖任何 Android / androidx 类型，
 * 这样解析器（[OrderScreenshotParser]）可以脱离设备在纯 JVM 上做单元测试。
 *
 * 版面坐标用于：
 * - 排序（自上而下、自左而右还原阅读顺序）
 * - 判断哪一行是店铺名（最靠上）、哪个数字是商品价格（同一行最靠右）
 */
data class TextLine(
    val text: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    /** 行高：部分 OCR 结果的 boundingBox 可能缺失（全 0），这里做非负保护。 */
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    /** 行的垂直中心，用于同一视觉行内的粗略纵向对齐。 */
    val centerY: Int get() = (top + bottom) / 2
}
