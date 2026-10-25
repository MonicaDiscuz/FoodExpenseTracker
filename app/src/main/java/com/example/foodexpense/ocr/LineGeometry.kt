package com.example.foodexpense.ocr

/**
 * 第 0 步「几何行重建」：把 ML Kit 的**视觉块**还原成**语义行**。
 *
 * 单独成文件的理由：这一层只跟坐标打交道，完全不关心「这行是店名还是价格」，
 * 是解析器里唯一能脱离业务规则、单独理解与单独验证的部分
 * （`OrderScreenshotParserGeometryTest` 就是专打这一层的）。
 *
 * 数据类 Line / VisualLine / Item 也放这里，因为它们就是这一层的契约。
 */

/** 预处理后的语义行：序号 + 原文 + 去噪后文本 + 组成它的原始视觉块。 */
internal data class Line(
    val index: Int,
    val rawText: String,
    val moneyText: String,
    /** 组成这一语义行的原始块文本，按 left 升序。店铺名要取最左块。 */
    val blocks: List<String> = emptyList(),
    /** 语义行最左坐标，仅用于调试与稳定排序。 */
    val left: Int = 0,
    /** 语义行最右坐标。用于"右对齐裸整数"的价格识别（见 [bareRightAlignedInteger]）。 */
    val right: Int = 0
)

/** 几何重建后的"语义行"。 */
internal data class VisualLine(
    val text: String,
    val blocks: List<String>,
    val top: Int,
    val left: Int,
    val right: Int,
    val bottom: Int
)

/** 候选商品行及其行内金额（按出现位置从左到右）。 */
internal data class Item(
    val line: Line,
    val amounts: List<Double>
) {
    val lastAmount: Double? get() = amounts.lastOrNull()
}

internal object LineGeometry {
    /**
     * 把 ML Kit 的视觉块重建成"语义行"。
     *
     * ## 聚类算法
     * 1. 按 `(top, left)` 排序，自上而下扫描；
     * 2. 依次判断当前行能否并入"上一个簇"（因为已按 top 排序，能合并的簇一定是相邻的）；
     * 3. 判定用**垂直重叠比例**而不是距离阈值：
     *    `min(bottomA, bottomB) - max(topA, topB) > 0.5 * min(heightA, heightB)`。
     *    用重叠比例的好处是大字号（店铺标题）与小字号（价格）混排时同样稳：
     *    只要两块有一半以上的行高重合，就一定是同一视觉行。
     * 4. 簇内按 `left` 升序拼接成一条文本（用单个空格分隔），并保留原始块列表。
     *
     * ## 为什么不会把上下相邻的商品行误合并
     * 相邻商品行是**堆叠**关系：垂直重叠量约为 0（甚至为负），
     * 远达不到 `0.5 * min(height)` 的门槛。以真实截图为例，行高 40px、行距 60px，
     * 相邻行重叠 = `40 - 60 = -20 < 20`，绝不会合并。
     *
     * ## 退化保护（关键，保证老用例零副作用）
     * 如果**所有**行都缺 boundingBox（`top == 0 && bottom == 0`，例如纯文本输入、
     * ML Kit 未给出框、或旧版本调用方只填了 text），聚类会把它们全部归成一行，
     * 必须跳过重建、按原顺序逐行处理 —— 这样"已经是一行一条的输入"行为完全不变。
     */
    fun rebuildVisualLines(lines: List<TextLine>): List<VisualLine> {
        if (lines.isEmpty()) return emptyList()

        // ---- 退化保护：完全没有纵向坐标信息 → 放弃重建，原样逐行处理 ----
        if (lines.all { it.top == 0 && it.bottom == 0 }) {
            return lines.map { toSingletonVisualLine(it) }
        }

        val sorted = lines.sortedWith(compareBy({ it.top }, { it.left }))
        val clusters = ArrayList<MutableList<TextLine>>()
        // 竖排文字 / 损坏框：不参与聚类，直接自成一条视觉行
        val forcedSingletons = ArrayList<VisualLine>()
        var current = ArrayList<TextLine>()

        for (line in sorted) {
            // ---- 桥接防线（见 [isVerticalOrCorrupt] 的说明）----
            // 命中的块必须先"切断"当前簇，否则它会成为跨行桥梁，把互不重叠的
            // 两条语义行缝成一条。注意：**不从输入里剔除它** —— 它仍要进入后续规则，
            // 万一它真是竖排的店名/商品名，还要能被识别出来。
            if (isVerticalOrCorrupt(line)) {
                if (current.isNotEmpty()) {
                    clusters.add(current)
                    current = ArrayList()
                }
                forcedSingletons.add(toSingletonVisualLine(line))
                continue
            }
            if (current.isEmpty()) {
                current = arrayListOf(line)
                continue
            }
            if (current.any { sameVisualLine(it, line) }) {
                current.add(line)
            } else {
                clusters.add(current)
                current = arrayListOf(line)
            }
        }
        if (current.isNotEmpty()) clusters.add(current)

        return (clusters.map { cluster -> buildVisualLine(cluster) } + forcedSingletons)
            .sortedWith(compareBy({ it.top }, { it.left }))
    }

    /**
     * 是否是**竖排文字 / 损坏框**：高远大于宽（`height > 3 × width`）。
     *
     * ## 为什么必须单独识别这类块
     * 真机数据里出现过 `国警平机支架 3斯圈` 这样 **20px 宽 × 176px 高**（宽高比 8.8）
     * 的框 —— 20px 装 8 个字符物理上不可能，是 ML Kit 给出的坏框。
     *
     * 这种块的危害在于 [sameVisualLine] 的判据是 `overlap > 0.5 * min(heightA, heightB)`：
     * `min` 取的是**小块**的高度，所以高块是个"过于宽容的连接器"。实测三块
     * `国警…`(h=176) / `A堡主理`(h=37) / `s服手机令`(h=31)：
     *
     * ```
     * A堡主理 vs s服手机令   overlap = 7  <  0.5×31 = 15.5   → 本该不合并
     * 国警… vs A堡主理       overlap = 37 >  0.5×37 = 18.5   → 合并
     * 国警… vs s服手机令     overlap = 31 >  0.5×31 = 15.5   → 合并
     * ```
     *
     * 于是靠"与簇内任一成员同行即可"的并查集式合并，三条互不相容的块被缝进同一行。
     *
     * ## 为什么用宽高比而不是"高"
     * 单看高度没有判别力（大字号标题本来就高）。而宽高比 8.8 的框无论怎么解释
     * 都不该与横排文字同行：横排中文行高≈字号的 1.2 倍、宽度远大于高度；
     * 竖排文字本是独立一列，本来就该自成一行。**这是语义正确的判据，不是阈值调参。**
     *
     * ## 为什么只加这一条条件
     * 已用真机 36 块 + 全部既有用例的坐标验过 `height > 3 × width`：
     * 只有那一个 20×176 命中（`x1` 40×31、`4` 19×20、`折` 30×35、`¥12.7` 102×42、
     * 店铺块 611×40、`超值双人餐` 340×48、`标准,少冰` 240×24 全部不命中）。
     * 再叠加"宽度绝对值""字符数"之类的条件只会引入新的误判面，收益为零。
     */
    private fun isVerticalOrCorrupt(line: TextLine): Boolean {
        val w = (line.right - line.left).coerceAtLeast(1)
        return line.height > VERTICAL_ASPECT_RATIO * w
    }

    /** 单行的退化包装（跳过重建时使用，块列表只有它自己）。 */
    private fun toSingletonVisualLine(line: TextLine): VisualLine = VisualLine(
        text = line.text,
        blocks = listOf(line.text),
        top = line.top,
        left = line.left,
        right = line.right,
        bottom = line.bottom
    )

    /** 把同一视觉行的若干块合成一条语义行（块内部按 left 升序，用空格拼接）。 */
    private fun buildVisualLine(cluster: List<TextLine>): VisualLine {
        val ordered = cluster.sortedWith(compareBy({ it.left }, { it.top }))
        val blocks = ordered.map { it.text }
        val joined = blocks.joinToString(" ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return VisualLine(
            text = joined,
            blocks = blocks,
            top = cluster.minOf { it.top },
            left = cluster.minOf { it.left },
            right = cluster.maxOf { it.right },
            bottom = cluster.maxOf { it.bottom }
        )
    }

    /**
     * 判断两个视觉块是否属于**同一视觉行**（垂直方向）。
     *
     * 判定顺序：
     * 1. **横向先否决** —— 见 [horizontallyStacked]：同一视觉行内的块是左右并排的，
     *    横向大幅重叠说明是**上下堆叠**的两行，直接判不同行。这一条是"桥接"的正面防线。
     * 2. 垂直重叠量 > 较短块行高的一半。
     * 3. 高度缺失（`boundingBox` 只剩 top 或只剩 bottom）时退回"中心距 ≤ 参照行高的一半"。
     */
    private fun sameVisualLine(a: TextLine, b: TextLine): Boolean {
        // 横向不重叠 = 并排 = 可能是同一行；横向大幅重叠 = 上下堆叠 = 一定不同行
        if (horizontallyStacked(a, b)) return false

        val ha = a.height
        val hb = b.height
        if (ha <= 0 || hb <= 0) {
            val referenceHeight = maxOf(ha, hb)
            if (referenceHeight <= 0) return false
            return kotlin.math.abs(a.centerY - b.centerY) * 2 <= referenceHeight
        }
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return overlap > 0.5 * minOf(ha, hb)
    }

    /**
     * 两块是否**横向大幅重叠**（即上下堆叠，而不该是同一视觉行）。
     *
     * ## 为什么这条判据能治"桥接"，而高度类判据不行
     * 同一视觉行内的块是**左右并排**的（左名右价、左文案右按钮），横向几乎不重叠；
     * 而上下相邻的两行，横向必然大幅重叠。真机 36 块实测：
     *
     * ```
     * 应合并的（横向重叠为负，左右并排）：
     *   三拼霸霸奶茶(302..589) + ¥12.7(1167..1269)      → -578
     *   合计(72..185)          + 已优惠8.5元¥12.3(860..1269) → -675
     *   折(83..113)            + 三拼霸霸奶茶(302..589)  → -189
     * 不该合并的（横向重叠 100%，上下堆叠）：
     *   三拼霸霸奶茶(302..589) vs 规格行(302..1037)      → 重叠 287 = 较窄者 100%
     * ```
     *
     * 高度类判据（比如"簇包络高度 > 2×中位行高就拒绝"）会**误伤商品名换行**：
     * ML Kit 常把两行商品名合成一个 h=92 的 `TextLine`，那是完全正常的，
     * 它的价格块 h=42 夹在中间 → 包络 92 超阈值 → 拒绝合并 → 整条商品消失。
     * **换个地方犯同样的错。** 横向重叠判据不受高度影响，所以没有这个问题。
     *
     * ## 为什么 `overlap <= 0` 直接返回 false
     * 完全不重叠（或左右相接）说明两块**并排**，属于同一视觉行的正常形态，
     * 必须允许合并。这里不能用"算比例"的写法兜，否则除以负值/零会把并排判成堆叠。
     *
     * ## 已知限制（2026-10-24，未修）
     * 横向判据的前提是"同一视觉行的块左右并排"。若某块落在左右两栏之间的
     * **水平空隙**（对两侧块的横向重叠都为负），它仍可能把上下两条语义行缝成一条。
     *
     * QA 已用构造数据复现该路径：
     * ```
     * 桥块    限时      950..1050   top 1000..1092 (h=92, w=100)
     * 商品名  三拼霸霸奶茶 302..589    1010..1056
     * 规格行  标准,正常冰  302..900    1060..1102
     * 桥块 vs 商品名：589 - 950 = -361 → 放行
     * 桥块 vs 规格行：900 - 950 =  -50 → 放行
     * → 两条语义行仍被缝成一条 → 含逗号 → 整条商品消失
     * ```
     *
     * **暂不修复**，原因：
     * - 这个形状**没有真机证据**（塔斯特那批图还没拿到，不知道真实版式里栏间空隙有没有块）；
     * - 这已经是第三层几何启发式了（宽高比 + 横向重叠），再加一层会显著提高过拟合风险。
     *
     * 将来若在**真实截图**上遇到，采用"跨行块 pre-pass"而不是继续放宽横向判据：
     * > 某块的纵向带内若存在 **≥2 个彼此中心距 > 0.5 × 行高** 的其它块，
     * > 说明它在跨行 → 该块不参与合并、自成一行。
     *
     * （该 pre-pass 方案已在构造数据上验证可行：能把栏间空隙那组修好，
     * 且不误伤"商品名换行成 h=92 高块 + 价格 h=42"的正常情况 —— 后者纵向带内
     * 只有单一中心。但它需要真机样本支撑才值得引入。）
     */
    private fun horizontallyStacked(a: TextLine, b: TextLine): Boolean {
        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        if (overlap <= 0) return false
        val narrower = minOf(a.right - a.left, b.right - b.left).coerceAtLeast(1)
        return overlap >= HORIZONTAL_OVERLAP_RATIO * narrower
    }
}