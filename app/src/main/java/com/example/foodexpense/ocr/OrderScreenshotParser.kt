package com.example.foodexpense.ocr

import com.example.foodexpense.data.entity.Platform

/**
 * 外卖订单截图的**规则解析器**（纯 Kotlin，零 Android 依赖）。
 *
 * 输入是 OCR 出来的若干行文本（含版面坐标），输出一份 [ParsedOrder] 候选。
 * 全部逻辑是确定性规则，不做任何网络请求、不猜、不硬套 —— 解析不到就是 null。
 *
 * ## 解析流水线
 * 0. **几何行重建**：把 ML Kit 的"视觉块"重建成"语义行"（见 [rebuildVisualLines]）
 * 1. 预处理：全角转半角 → 按 (top, left) 排序 → 每行派生 rawText / moneyText
 * 2. P1 店铺名：标签 → 位置启发式 → null（三级降级）
 * 3. P2 商品行与主商品：按"行内最后一个金额"取最大者
 * 4. P3 数量：只认 `x1`/`x2` 这类后缀，绝不把 `(200克)` 当数量
 * 5. P4 实付：精确标签 → 宽泛合计 → 含 ¥ 的兜底行 → null
 * 6. P5/P6 优惠/原价（辅助）与平台
 *
 * ## 为什么必须有第 0 步"几何行重建"
 * ML Kit 对密排截图是**按视觉分栏切块**的，右对齐的价格经常被切成独立的 TextLine：
 *
 * ```
 * 「三拼霸霸奶茶」        ← TextLine A（left=60）
 *          「¥12.7」      ← TextLine B（left=880，与 A 同一视觉行）
 * ```
 *
 * 如果按"一条 TextLine = 一个语义行"来解析，A 没有金额 → 不是商品行；
 * B 去掉金额后 residue 为空 → 也不是商品行。**两个真实商品全部漏掉**，
 * 而残留一个 `-` 号的 `-¥5` 反而成了唯一"商品"，实付也被单商品兜底逻辑带偏。
 * 这是"视觉行 vs 语义行"的结构性错位，靠加词表救不回来，必须先把几何还原。
 *
 * ## 关键防错点
 * - [stripNonMoneyNumbers] 是整个解析的地基：先把 `200克` / `4.8分` / `1人份` 这类
 *   "假数字"一次性消灭，否则商品名和金额全是脏数据。
 * - 主商品价格的上界校验必须用 `实付 + 优惠`，**不能**只用实付：
 *   样例中主商品 39.9 > 实付 26.4，只用实付会把正确答案判掉。
 * - 规格行（`标准,正常冰,七分糖(推荐) ¥12.8`）含金额且不含费用词，
 *   天然会被误判成商品行，而且它的划线原价往往**高于**主商品单价，会抢走主商品。
 *   所以商品行判定额外要求"金额左侧的文本不含规格词、不含逗号、长度 ≤ 14"。
 * - 任何异常都被 [parse] 最外层捕获，本类**绝不向上抛异常**。
 */
class OrderScreenshotParser {

    // ======================================================================
    // 公开入口
    // ======================================================================

    /**
     * 解析一批 OCR 文本行。
     *
     * @return 空输入或内部异常时返回 `null`；解析成功但一个字段都没识别出来时
     *         返回 `isEmpty == true` 的空对象，由调用方决定如何提示用户。
     */
    fun parse(lines: List<TextLine>): ParsedOrder? {
        if (lines.isEmpty()) return null
        return try {
            parseInternal(lines)
        } catch (_: Exception) {
            // 总闸：解析器永远不向上抛异常，失败即"没识别到"
            null
        }
    }

    // ======================================================================
    // 内部数据结构
    // ======================================================================

    /** 预处理后的语义行：序号 + 原文 + 去噪后文本 + 组成它的原始视觉块。 */
    private data class Line(
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
    private data class VisualLine(
        val text: String,
        val blocks: List<String>,
        val top: Int,
        val left: Int,
        val right: Int,
        val bottom: Int
    )

    /** 候选商品行及其行内金额（按出现位置从左到右）。 */
    private data class Item(
        val line: Line,
        val amounts: List<Double>
    ) {
        val lastAmount: Double? get() = amounts.lastOrNull()
    }

    // ======================================================================
    // 主流程
    // ======================================================================

    private fun parseInternal(input: List<TextLine>): ParsedOrder {
        val normalized = input
            .map { TextLine(normalize(it.text), it.left, it.top, it.right, it.bottom) }
            .filter { it.text.isNotBlank() }

        if (normalized.isEmpty()) return ParsedOrder()

        // ---------- 第 0 步：几何行重建（必须在所有规则之前） ----------
        val visualLines = rebuildVisualLines(normalized)

        val prepared = visualLines.mapIndexed { i, v ->
            Line(
                index = i,
                rawText = v.text,
                moneyText = stripNonMoneyNumbers(v.text),
                blocks = v.blocks,
                left = v.left,
                right = v.right
            )
        }

        if (prepared.isEmpty()) return ParsedOrder()

        // ---------- 页面金额右界（"右对齐裸整数"价格识别的基准线） ----------
        // 外卖详情页的价格列是右对齐的，所以"带 ¥ 的行"的最右界就是这一列的位置。
        // 裸整数（OCR 把 `¥4` 认成 `4`）只有在贴着这条基准线时才算金额。
        // 全 0 坐标（无 boundingBox）时为 0，此时整条规则自动禁用，避免误判。
        val moneyRightEdge = prepared
            .filter { it.moneyText.contains('¥') }
            .maxOfOrNull { it.right } ?: 0

        // ---------- P5 辅助金额（先算，主商品上界校验要用） ----------
        val discountTotal = findLabeledAmount(prepared, DISCOUNT_LABELS)
        val originalTotal = findLabeledAmount(prepared, ORIGINAL_LABELS)

        // ---------- P2 商品行 + 主商品 ----------
        val items = prepared
            .filter { isItemLine(it, moneyRightEdge) }
            .map { Item(it, amountsOf(it, moneyRightEdge)) }

        val main = pickMainItem(items)

        // ---------- P4 实付（必须在商品行统计之后：P4-c 兜底要看 itemCount） ----------
        val actualPaid = findActualPaid(prepared, items.size)

        var productPrice = main?.lastAmount
        // ==================================================================
        // P2-e 上界校验
        // ==================================================================
        // 启用条件（四个都必须满足）：已解析出优惠、已解析出实付、
        // 主商品行内**有多个金额**（`amounts.size > 1`）。
        // 注意"行内只有一个金额"时**整块直接跳过**，价格原样保留 ——
        // 也就是说这里从来不是"单金额价格的上界保护"，别照着它去理解单金额场景。
        //
        // ---- 关于 `if (fallback.isNotEmpty())` 这个"看起来像漏了 else"的写法 ----
        // 回退集合为空时**有意保留原值**，不是遗漏。⚠️ 请勿"顺手改成 null"。
        //
        // 回退集合为空的充要条件：
        //   该行有 ≥2 个金额，且**全部**都大于 `实付 + 优惠 + 0.01`。
        //
        // 什么情况下会这样？最常见的原因不是"价格取错了"，而是
        // **优惠没被完整解析出来** —— 那么上界本身就被算小了，行内的金额反而
        // 大概率是对的（比如商品原价 39.9、实付 12.3，但"已优惠"那一行 OCR 丢了，
        // 上界只剩 12.31，行内两个金额都越界）。
        //
        // 此时置空 = 把一个**正确的值**删掉，用户得手动重填价格；
        // 这是为了防一个罕见的错价，而付出常态的丢数据。
        // 而"猜不到就不猜"这条原则的前提是**多来源互相矛盾、无法判断**
        // （例如同一行同时有 `¥45.9` 和 `¥33.9`）；这里的越界是"上界算错了"，
        // 不是"有多个候选无法取舍"，不属于"猜"。
        //
        // 所以这个位置的取舍明确偏向**保留数据**，而不是保守置空。
        //
        // ---- 将来要降低这条的风险，正确的杠杆在哪 ----
        // 去提升 `discountTotal` 的解析成功率（优先取「合计/总计」行内的优惠标签，
        // 而不是全文第一条命中的优惠标签），**不要**在这里对价格做减法。
        // 若先在这里改成 null，只会把"优惠解析不全"的后果放大成"价格丢失"。
        if (main != null && productPrice != null && discountTotal != null &&
            actualPaid != null && main.amounts.size > 1
        ) {
            val upperBound = actualPaid + discountTotal + 0.01
            if (productPrice > upperBound) {
                val fallback = main.amounts.filter { it > 0.0 && it <= upperBound }
                // 空集合时保留原值，理由见上方注释（有意为之，勿改成 null）
                if (fallback.isNotEmpty()) productPrice = fallback.max()
            }
        }

        val productName = main?.let { m -> cleanProductName(m.line, moneyRightEdge) }
        val quantity = main?.let { parseQuantity(it.line.rawText) } ?: 1

        // ---------- P1 店铺名（依赖第一个商品行的位置，故放在最后） ----------
        // 用**第一个商品行**而不是主商品行做锚点：主商品可能是靠下的那件，
        // 从它往上扫会先经过别的商品行，虽然那些行因"含 ¥+数字"被否掉，
        // 但用第一个商品行语义更直接、也更稳。
        val storeName = findStoreName(prepared, items.firstOrNull()?.line?.index)


        return ParsedOrder(
            storeName = storeName,
            productName = productName,
            productPrice = productPrice,
            productQuantity = quantity,
            actualPaid = actualPaid,
            platform = detectPlatform(prepared),
            itemCount = items.size,
            discountTotal = discountTotal,
            originalTotal = originalTotal
        )
    }

    // ======================================================================
    // 第 0 步：几何行重建（视觉块 → 语义行）
    // ======================================================================

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
    private fun rebuildVisualLines(lines: List<TextLine>): List<VisualLine> {
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

    // ======================================================================
    // P1 店铺名
    // ======================================================================

    /**
     * 三级降级找店铺名：
     * - P1-a：命中「店铺 / 商家名称 / 商家 / 门店」标签，取标签后的值
     * - P1-b：从**第一个商品行往上扫**，取最近的一条"看起来像店名"的行
     * - P1-c：都失败 → null（不猜）
     *
     * ## 为什么 P1-a 的候选也要过 [isPlausibleStoreLine]
     * 真实截图里店铺行右侧有按钮「⊙ 进商家粉丝群」，文本里含 `商家` 二字，
     * 会被 [STORE_LABEL_RE] 当成标签命中，标签后恰好是 `粉丝群` ——
     * 不去校验就会把店名解析成「粉丝群」。所以标签法的候选必须同样过白名单。
     *
     * ## 为什么 P1-b 必须"从商品行往上扫"而不是"自上而下取第一条"
     * 真机截图最上方是状态栏附近的水印/角标（实测拿到 `C684` at top=56），
     * 它完全能通过 [isPlausibleStoreLine]（长度 4、含字母、含数字但无 ¥、无排除词）。
     * 自上而下取第一条会稳定地选中这种水印。
     *
     * 而"店铺名一定紧贴在商品列表上方"是这个版式的**结构性事实**：
     * 店名、评分、按钮那一行下面紧接着就是第一件商品。
     * 所以用"空间上最近"来判定，比给 `C684` 加词表稳得多 ——
     * 也不会像"必须含汉字"那样误杀 `KFC`、`7-11便利店`。
     */
    private fun findStoreName(prepared: List<Line>, firstItemIndex: Int?): String? {
        // ---- P1-a：标签法 ----
        for (i in prepared.indices) {
            val line = prepared[i]
            val match = STORE_LABEL_RE.find(line.rawText) ?: continue
            val afterLabel = line.rawText
                .substring(match.range.last + 1)
                .trim()
                .trimStart(':', '：', '-', '—', ' ')
                .trim()
            val candidate = trimStoreTail(afterLabel.replace(Regex("""\s+"""), " ").trim())
            if (candidate.length in 2..30 && !isMoneyOnly(candidate) && isPlausibleStoreLine(candidate)) {
                return candidate
            }
            // 标签独占一行 → 取下一行（下一行不能是金额行 / 关键词行）
            val next = prepared.getOrNull(i + 1) ?: continue
            val nextCandidate = trimStoreTail(storeNameCandidate(next))
            if (nextCandidate.isBlank()) continue
            if (isMoneyOnly(nextCandidate)) continue
            if (mentionsAnyKeyword(nextCandidate)) continue
            if (isPlausibleStoreLine(nextCandidate)) return nextCandidate
        }

        // ---- P1-b：从第一个商品行往上扫，取**最近**的一条合理行 ----
        // 注意候选是"簇内最左块 + 裁掉 UI 装饰后缀"，不是整行拼接文本：
        // 整行拼接会把右侧按钮（`e进商家粉丝群`）也带上，长度超限且含排除词。
        if (firstItemIndex != null) {
            val hit = scanStoreCandidateUpward(prepared, firstItemIndex - 1)
            if (hit != null) return hit
        }

        // ---- P1-b'：没有商品行（或往上扫全被否掉）→ 退回自上而下（仍只看商品行之前） ----
        val upperBound = firstItemIndex ?: prepared.size
        val searchRange = prepared.filter { it.index < upperBound }
        val pool = searchRange.ifEmpty { prepared }
        for (line in pool) {
            val hit = storeCandidateOrNull(line) ?: continue
            return hit
        }

        // ---- P1-c：不猜 ----
        return null
    }

    /** 自上而下扫到第一条可用的店铺候选。 */
    private fun scanStoreCandidateUpward(prepared: List<Line>, fromIndex: Int): String? {
        for (i in fromIndex downTo 0) {
            val hit = storeCandidateOrNull(prepared[i]) ?: continue
            return hit
        }
        return null
    }

    /**
     * 把一条语义行转成"店铺名候选"，不合格返回 null。
     *
     * ## 为什么要有"裁剪后剩余主体长度"这道检查
     * `trimStoreTail` 会贪婪剥掉尾部的 UI 词。对真实店铺行是好事
     * （`蜜雪冰城(秋园雅苑店) h直播中>` → `蜜雪冰城(秋园雅苑店)`），
     * 但对**整行都是 UI 文案**的横幅是灾难：
     *
     * ```
     * 今日领券  --剥掉「领券」-->  今日   ← 这是个"像店名"的垃圾结果
     * ```
     *
     * `今日` 能通过 [isPlausibleStoreLine]（2 个汉字、无排除词），
     * 于是横幅被当成店名。所以：**只要发生了裁剪，且裁完只剩不到
     * [MIN_STORE_BODY_LEN] 个字符，就认为整行都是装饰，整条否决。**
     *
     * 这是"宁可留空也不填错"（与解析器整体口径一致），
     * 代价是 `瑞幸 领券` 这种"2 字店名 + 按钮"会漏掉 → 返回 null，而不是返回错名字。
     */
    private fun storeCandidateOrNull(line: Line): String? {
        val raw = storeNameCandidate(line)
        if (raw.isBlank()) return null
        val candidate = trimStoreTail(raw)
        if (candidate.isBlank()) return null
        if (candidate.length != raw.trim().length && candidate.length < MIN_STORE_BODY_LEN) {
            return null
        }
        return candidate.takeIf { isPlausibleStoreLine(it) }
    }

    /**
     * 取一条语义行的"店铺名候选"：**簇内最左的块**，并裁掉右侧 UI 装饰。
     *
     * 店铺行在真实截图里是：`蜜雪冰城（秋雅雅苑店）lu 直播中 >` + `⊙ 进商家粉丝群`，
     * 店名只在最左块里。另外 OCR 偶尔会把店名从括号处切成两块
     * （`蜜雪冰城` + `(秋雅雅苑店)`），所以对"紧随其后的括号块"做一次合并。
     */
    private fun storeNameCandidate(line: Line): String {
        val blocks = line.blocks.ifEmpty { listOf(line.rawText) }
        val head = StringBuilder()
        for (raw in blocks) {
            val block = stripNonMoneyNumbers(raw).trim()
            if (block.isEmpty()) continue
            if (head.isEmpty()) {
                head.append(block)
                continue
            }
            // 只在"下一块是括号补充说明"时合并，其余（按钮 / 直播标记）一律不并入
            if (block.startsWith("(") || block.startsWith("（")) {
                head.append(block)
            } else {
                break
            }
        }
        return head.toString().trim()
    }

    /**
     * 贪婪裁掉店名尾部的 UI 装饰：箭头、`直播中`、`进商家粉丝群`、`lu` 这类小标签。
     *
     * 反复裁到"干净"为止（`蜜雪冰城(店)lu 直播中 >` →
     * `蜜雪冰城(店)lu 直播中` → `蜜雪冰城(店)lu` → `蜜雪冰城(店)`）。
     *
     * 对已经干净的名字是**幂等的**（`麦当劳(科技园店)` / `7-11便利店` 原样返回），
     * 所以对老用例零副作用。
     */
    private fun trimStoreTail(raw: String): String {
        var s = raw.trim()
        var guard = 0
        while (s.isNotEmpty() && guard++ < 16) {
            val before = s
            // 1) 尾部箭头 / 分隔符 / 项目符号
            s = TAIL_SYMBOL_RE.replace(s, "").trim()
            if (s != before) continue
            // 1b) 尾部"纯符号"的独立 token（如块分隔符 `⊙`）：
            //     只要末段不含任何字母/数字/汉字，就一定是图标而不是店名的一部分
            val lastSpace = s.lastIndexOf(' ')
            if (lastSpace > 0) {
                val tailToken = s.substring(lastSpace + 1)
                if (tailToken.isNotEmpty() &&
                    tailToken.none { it.isLetterOrDigit() || isCjk(it) }
                ) {
                    s = s.substring(0, lastSpace).trim()
                    continue
                }
            }
            // 2) 尾部 UI 词（长的优先，避免 `进商家` 抢在 `进商家粉丝群` 前面）
            val hit = STORE_TAIL_WORDS.firstOrNull { s.length > it.length && s.endsWith(it) }
            if (hit != null) {
                s = s.dropLast(hit.length).trim()
                continue
            }
            // 3) 尾部"直播小标签"式的短拉丁串（如 `lu` / `live`）：
            //    必须紧跟在汉字或右括号之后，且剩余部分还有至少 4 个字符，
            //    这样 `KFC`、`McDonald's` 这类真英文店名不会被误裁。
            val latin = TRAILING_LATIN_TAG_RE.find(s)
            if (latin != null && latin.range.last == s.lastIndex &&
                s.length - latin.value.trim().length >= 4
            ) {
                s = s.substring(0, latin.range.first).trim()
                continue
            }
            break
        }
        return s
    }

    /**
     * 判断某行"像不像店铺名"。
     *
     * 注意这里用的是 [String.equals]（不是 contains）去比噪声词：
     * 「美团买菜」是合法店名，不能被「美团」这条噪声规则连带否掉。
     */
    private fun isPlausibleStoreLine(text: String): Boolean {
        val t = text.trim()
        if (t.length !in 2..20) return false
        if (NOISE_TEXTS.any { t.equals(it, ignoreCase = true) }) return false
        if (NOISE_PREFIX.any { t.contains(it) }) return false
        if (STORE_EXCLUDE.any { t.contains(it) }) return false
        // 纯数量标记（`x1`）不是店名
        if (QUANTITY_ONLY_RE.matches(t)) return false
        // 打码的收件人 / 手机号（`刘**(先生)182****0814`）不是店名
        if (MASKED_INFO_RE.containsMatchIn(t)) return false
        // 至少含一个字母或中日韩文字，纯数字/符号行不是店名
        if (!t.any { it.isLetter() || isCjk(it) }) return false
        // 既含数字又含 ¥ → 是金额行，不是店名
        if (t.any { it.isDigit() } && t.contains('¥')) return false
        return true
    }

    /** 只由金额与符号组成的行（如 `¥26.4`）。 */
    private fun isMoneyOnly(text: String): Boolean {
        val residue = MONEY_CLEAN_RE.replace(text, " ")
            .replace("¥", " ")
            .replace(Regex("""[\s:：,，.。]+"""), "")
        return residue.isEmpty()
    }

    private fun mentionsAnyKeyword(text: String): Boolean =
        STORE_EXCLUDE.any { text.contains(it) } ||
            EXACT_PAID_LABELS.any { text.contains(it) } ||
            SUMMARY_LABELS.any { text.contains(it) } ||
            DISCOUNT_LABELS.any { text.contains(it) }

    // ======================================================================
    // P2 商品行与主商品
    // ======================================================================

    /**
     * 判断某行是不是"商品行"。
     *
     * 除了"既有商品名又有可信金额"之外，还必须通过**规格行拦截**：
     * 重建后的规格行形如 `标准,正常冰,七分糖(推荐),换冷链... ⌄ ¥12.8`，
     * 它含金额、不含费用词，但**不是商品**；而且它的划线原价（12.8）常常比
     * 主商品折后价（12.7）还高，放进来会直接抢走主商品。
     *
     * 判定规则（三条同时满足才算商品）：
     * 1. 金额**左侧**的文本非空（拦掉被列切分出来的纯价格行 `¥12.7` / `-¥5`）；
     * 2. 左侧文本不含逗号（规格描述几乎必带逗号）且不含规格词；
     * 3. 左侧文本长度 ≤ [MAX_ITEM_LEFT_LEN]（商品名不会太长，长了多半是规格串）。
     */
    private fun isItemLine(line: Line, moneyRightEdge: Int = 0): Boolean {
        val moneyText = line.moneyText
        // 店铺标签行不是商品行
        if (STORE_LABEL_RE.containsMatchIn(line.rawText)) return false
        // 必须至少有一个"可信金额"
        if (!hasTrustedAmount(line, moneyRightEdge)) return false
        // 费用 / 优惠 / 合计类行不是商品行
        if (ITEM_EXCLUDE.any { moneyText.contains(it) }) return false
        // 去掉金额与空格后还剩东西，才可能是商品名
        val residue = MONEY_CLEAN_RE.replace(moneyText, " ")
            .replace("¥", " ")
            .replace(Regex("""\s+"""), "")
        if (residue.isEmpty()) return false

        // ---- 规格行拦截（几何重建之后才需要，因为之前规格与价格不在同一行） ----
        val leftPart = leftPartOf(moneyText)
        if (leftPart.isBlank()) return false
        val leftBare = QUANTITY_RE.replace(leftPart, " ")
            .replace(LEADING_SYMBOL_RE, "")
            .replace(Regex("""\s+"""), "")
            .trim()
        if (leftBare.isEmpty()) return false
        if (leftBare.length > MAX_ITEM_LEFT_LEN) return false
        if (leftBare.contains(',') || leftBare.contains('，')) return false
        // ---- 已知限制（2026-10-24，未修）----
        // [looksLikeSpec] 用的是 contains 匹配，所以**商品名本身包含规格词**时会被误杀，
        // 例如 `推荐套餐`、`标准双人餐`、`大杯装奶茶`（命中 [SPEC_HINTS_MULTI]）。
        //
        // 这类名字与"规格行"在纯文本上不可区分，除非改成 **token 边界匹配** ——
        // 参考 [SPEC_SINGLE_TOKEN_RE] 对 `热` / `换` 的处理：要求独立 token 而非子串。
        //
        // 暂不修：解析器已冻结，且没有真机样本证明该形状高频。
        // 将来若遇到，按 token 边界匹配处理，**不要继续加排除词**。
        // ------------------------------------
        // 判"是不是规格行"时**先剥掉括号片段**：括号的归类是 [cleanProductName] 的职责
        // （[isSpecFragment] 会区分 `(200克)` 该删、`(含可乐薯条鸡翅)` 该留），
        // 这里再用 contains 判一遍会造成两处判据打架：
        // `超值套餐(推荐)` 明明是商品 + 可剥离的角标，却因为这个 contains 被判成规格行、
        // 整条商品消失。剥完之后若什么都不剩（如 `(推荐)` 独占左半），仍按规格行处理。
        val leftForSpec = PAREN_FRAGMENT_RE.replace(leftBare, " ")
            .replace(Regex("""\s+"""), "")
            .trim()
        if (leftForSpec.isEmpty()) return false
        if (looksLikeSpec(leftForSpec)) return false

        return true
    }

    /** 取"第一个金额出现在哪里"左侧的文本（金额之前的部分就是商品名区）。 */
    private fun leftPartOf(moneyText: String): String {
        val m = MARKED_AMOUNT_ANY_RE.find(moneyText) ?: BARE_DECIMAL_RE.find(moneyText)
            ?: return moneyText
        return moneyText.substring(0, m.range.first)
    }

    /**
     * 左侧文本是否像"规格描述"。
     *
     * - 多字规格词（`标准` / `七分糖` / `大杯` …）用 contains 判定，不会误伤商品名；
     * - 单字规格词（`热` / `换`）必须成**独立 token** 才算，
     *   否则「热干面」「换」开头以外的正常商品名会被误杀。
     */
    private fun looksLikeSpec(left: String): Boolean {
        if (SPEC_HINTS_MULTI.any { left.contains(it) }) return true
        return SPEC_SINGLE_TOKEN_RE.containsMatchIn(left)
    }

    /**
     * 可信金额：带 `¥` 前缀，或形如 `d.dd` 且位于行尾（可带数量后缀），
     * 或**右对齐的裸整数**（见 [bareRightAlignedInteger]）。
     */
    private fun hasTrustedAmount(line: Line, moneyRightEdge: Int): Boolean {
        if (MARKED_AMOUNT_RE.containsMatchIn(line.moneyText)) return true
        if (LINE_END_DECIMAL_RE.containsMatchIn(line.moneyText.trim())) return true
        return bareRightAlignedInteger(line, moneyRightEdge) != null
    }

    /**
     * 识别"**右对齐的裸整数**"价格，返回其数值；不是则 null。
     *
     * ## 为什么需要这条规则
     * 真机上 ML Kit 会把 `¥4` 的 `¥` 整个漏掉，只留下 `4`。
     * 如果只认"带 ¥"或"行尾小数"，这件商品会被直接丢弃 → `itemCount` 少 1。
     * 而 `itemCount` 是 P4-c 兜底的门禁：多商品 + 无合计行时，
     * `itemCount` 低估会让 P4-c 把某个商品单价当成整单实付 —— **静默少记钱**。
     *
     * ## 三条同时满足才算金额
     * 1. **位于行尾，且前面是空格或汉字**：`雪王小宝杯 4` ✓；
     *    `C684` 的 `684` 前面是字母 `C` ✗；`x1` 的 `1` 前面是 `x` ✗；`11:41` 的 `41` 前面是 `:` ✗。
     * 2. **与页面金额右界相差 ≤ [MONEY_EDGE_TOLERANCE]**：`4` 的 right=1269、金额右界=1272 → 差 3 ✓；
     *    `x1` 的 right=342 ✗。这条是核心：**价格列是右对齐的**，而商品名/数量左对齐。
     * 3. **左侧有商品名**：`4` 单独一行时左侧为空 → 不是"商品 + 价格"。
     */
    private fun bareRightAlignedInteger(line: Line, moneyRightEdge: Int): Double? {
        // 无坐标（全 0）时整条规则禁用：否则 zero-coord 用例里所有裸整数都会被当成钱
        if (moneyRightEdge <= 0) return null
        val text = line.moneyText.trim()
        val match = TRAILING_BARE_INTEGER_RE.find(text) ?: return null
        if (kotlin.math.abs(line.right - moneyRightEdge) > MONEY_EDGE_TOLERANCE) return null
        val left = text.substring(0, match.range.first).trim()
        if (left.isEmpty()) return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return if (value > 0.0) value else null
    }

    /**
     * 取一条商品行的金额列表。
     *
     * 优先用 [amountsIn]（带 ¥ / 裸小数）；一个都没有时退回"右对齐裸整数"，
     * 这样 `雪王小宝杯 4` 的 `4` 既能让它成为商品行，也能作为它的价格。
     */
    private fun amountsOf(line: Line, moneyRightEdge: Int): List<Double> {
        val parsed = amountsIn(line.moneyText)
        if (parsed.isNotEmpty()) return parsed
        val bare = bareRightAlignedInteger(line, moneyRightEdge) ?: return emptyList()
        return listOf(bare)
    }

    /**
     * 主商品 = 行内最后一个金额最大的那一行；金额全解析失败时取最靠上的一行。
     */
    private fun pickMainItem(items: List<Item>): Item? {
        if (items.isEmpty()) return null
        val withAmount = items.filter { it.lastAmount != null }
        if (withAmount.isEmpty()) return items.minByOrNull { it.line.index }
        return withAmount.maxWithOrNull(
            compareBy<Item> { it.lastAmount ?: 0.0 }.thenByDescending { it.line.index }
        )
    }

    /**
     * 清洗商品名：删金额 → 删活动标签 → 删规格片段 → 删数量后缀 → 删行首序号。
     * 清洗后为空则退回原文前 40 字；仍为空则 null。
     */
    private fun cleanProductName(line: Line, moneyRightEdge: Int): String? {
        var s = line.moneyText
        s = MARKED_AMOUNT_ANY_RE.replace(s, " ")
        s = BARE_DECIMAL_RE.replace(s, " ")
        // 右对齐裸整数价格（真机把 `¥4` 认成 `4`）也要从名字里去掉，
        // 否则 `雪王小宝杯 4` 会留下整个带价格的串当商品名。
        if (bareRightAlignedInteger(line, moneyRightEdge) != null) {
            s = TRAILING_BARE_INTEGER_RE.replace(s.trimEnd(), " ")
        }
        s = PRODUCT_TAG_RE.replace(s, " ")
        // 裸的单字活动角标（不带方括号）：真机上 `折` 常被 OCR 单独切出来，
        // 合并后成了 `折 三拼霸霸奶茶`。只在"行首且自成 token"时剥离，
        // 所以 `折扣商品`、`新品奶茶`、`特价套餐` 这类真词不会被误剥。
        s = LEADING_ACTIVITY_TAG_RE.replaceFirst(s, " ")
        // 括号片段：**只删"像规格"的**，不无条件删。
        // 无条件删会吃掉商品名里的成分说明（`超值套餐(含可乐薯条鸡翅)`），
        // 判定逻辑见 [isSpecFragment]。
        s = PAREN_FRAGMENT_RE.replace(s) { m ->
            if (isSpecFragment(m.groupValues[1])) " " else m.value
        }
        s = QUANTITY_RE.replace(s, " ")
        s = LEADING_INDEX_RE.replace(s, " ")
        s = s.replace("¥", " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        if (s.isNotEmpty()) return s
        val fallback = line.rawText.take(40).trim()
        return fallback.ifBlank { null }
    }

    /**
     * 判断括号里的内容是否**像规格**（该删），还是商品名的一部分（该留）。
     *
     * 命中任一条即视为规格、删掉；否则**保留**：
     * - (a) 形如「数字 + 单位」： `200克` / `1人份` / `12寸` / `1.5L` → [PAREN_NUMBER_UNIT_RE]
     * - (b) **整体**等于某个规格词：`推荐` / `中杯` / `标准` / `七分糖`… → 复用 [SPEC_HINTS]
     * - (c) 只有 1 个字符：`中` / `大` / `小` / `左` / `右`
     *
     * ## 为什么 (b) 必须是整体相等而不是 contains
     * `(含推荐酱)` 是商品名里的说明，`contains("推荐")` 会把它误删。
     * 只有整体相等（`(推荐)`）才算规格写法。
     *
     * ## 为什么还要单独处理"空内容"
     * [stripNonMoneyNumbers] 会**先**把 `(200克)` 变成 `( )` —— 单位连同数字被替换成空格。
     * 所以真实输入走到这里时，规格括号**大部分已经是空的**；不删就会残留
     * `澳洲牛排( )` 这种名字。这条不是可选项，是主干路径。
     *
     * ## 这条例子的来历（同类问题第三例）
     * 前两例是"长度闸门吃掉 16 字商品名"和"无条件删括号吃掉成分说明"，
     * 都属于**为了压规格行，把真商品名一起压掉了**。所以这里的默认方向是"留"，
     * 只有能明确判定为规格时才删。
     */
    private fun isSpecFragment(content: String): Boolean {
        val t = content.trim()
        if (t.isEmpty()) return true
        if (t.length == 1) return true
        if (SPEC_HINTS.any { it.equals(t, ignoreCase = true) }) return true
        return PAREN_NUMBER_UNIT_RE.matches(t)
    }

    // ======================================================================
    // P3 数量
    // ======================================================================

    /*
     * 数量解析实现见 companion object 的 [parseQuantity]：
     * 只认 `x1` / `x 2` / `*3` 这类后缀，且只在行尾最后 14 个字符里找，取**最后一个**匹配。
     * 永远返回 >= 1，绝不返回 0 / null —— 这样 `(200克)x1` 得到 1（而不是 200），
     * `1人份` 也不会被误认。
     */

    // ======================================================================
    // P4 实付金额
    // ======================================================================

    /**
     * 四段式取"整单实付"：
     * - P4-a 精确实付标签（自下往上）
     * - P4-b 宽泛合计标签（自下往上）
     * - P4-c **仅单商品**的兜底：自下往上第一条"含 ¥ 且不含费用/优惠词"的行
     * - P4-d 全失败 → null
     *
     * ## 为什么 P4-c 必须门禁 itemCount > 1
     * 多商品且 OCR 漏掉「合计/实付」行时，P4-c 会取到**最后一个商品行**的单价
     * （例如 12.5 / 9.0 / 8.0 三件，就会返回 8.0），而真实整单实付约 29.5 ——
     * 这是**静默少记钱**，用户完全看不出异常，比留空危险得多。
     *
     * 缺合计行时，商品原价之和也不等于实付（还有打包费 / 配送费 / 优惠），
     * 任何推导都是错的。因此按本解析器"猜不到就是 null"的原则直接返回 null：
     * 调用方会回退到小计，并明确提示"未识别：实付金额"，让错误**可见**。
     *
     * 单商品时该兜底恰好正确（一行商品价 = 整单），保持原行为不变。
     */
    private fun findActualPaid(prepared: List<Line>, itemCount: Int): Double? {
        // P4-a
        for (i in prepared.indices.reversed()) {
            val line = prepared[i]
            val match = EXACT_PAID_RE.find(line.rawText) ?: continue
            val amount = lastPositiveAmountAfter(line.rawText, match.range.last + 1)
            if (amount != null) return amount
        }
        // P4-b
        for (i in prepared.indices.reversed()) {
            val line = prepared[i]
            val match = SUMMARY_LABEL_RE.find(line.rawText) ?: continue
            val amount = lastPositiveAmountAfter(line.rawText, match.range.last + 1)
            if (amount != null) return amount
        }
        // P4-c：多商品一律不兜底（取最后一个商品价 = 静默少记钱），宁可留空
        if (itemCount > 1) return null
        for (i in prepared.indices.reversed()) {
            val line = prepared[i]
            if (!line.moneyText.contains('¥')) continue
            if (ITEM_EXCLUDE.any { line.moneyText.contains(it) }) continue
            val amounts = amountsIn(line.moneyText)
            val amount = amounts.lastOrNull { it > 0.0 }
            if (amount != null) return amount
        }
        // P4-d
        return null
    }

    /**
     * 取 `start` 之后文本里的**最后一个正数金额**。
     *
     * 细节：先把「优惠标签 + 金额」整段剥掉，避免把优惠额误当实付
     * （`实付 ¥26.4 已优惠 ¥21.9` → 26.4）。
     *
     * 用"整段剥离"而不是旧版的"在优惠标签处截断"，是为了兼容真实截图的
     * `合计 已优惠8.5元 ¥12.3` —— 这里的优惠**在实付金额之前**，
     * 一截断就什么都不剩，实付会整条丢掉。剥离只吃掉标签和它自己的金额，
     * 剩下的 12.3 仍然在，取最后一个正数金额即可。
     */
    private fun lastPositiveAmountAfter(text: String, start: Int): Double? {
        if (start !in 0..text.length) return null
        val tail = stripDiscountSegments(text.substring(start))
        val amounts = amountsIn(tail)
        return amounts.lastOrNull { it > 0.0 }
    }

    /**
     * 取 `start` 之后**紧邻的**第一个正数金额（用于"标签 → 值"的取值）。
     *
     * 与 [lastPositiveAmountAfter] 的区别：这里要的是紧跟标签的那个数
     * （`已优惠8.5元 ¥12.3` 的优惠额是 **8.5**，不是行尾的 12.3）。
     */
    private fun firstPositiveAmountAfter(text: String, start: Int): Double? {
        if (start !in 0..text.length) return null
        val tail = text.substring(start)
        val match = IMMEDIATE_AMOUNT_RE.find(tail) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val abs = kotlin.math.abs(value)
        return if (abs > 0.0) abs else null
    }

    // ======================================================================
    // P5 辅助金额（优惠 / 原价）
    // ======================================================================

    /** 在全文里找带标签的金额：自上而下取第一个命中标签，取标签**紧邻**的正数金额。 */
    private fun findLabeledAmount(prepared: List<Line>, labels: List<String>): Double? {
        for (line in prepared) {
            val labelIndex = labels
                .map { line.rawText.indexOf(it) }
                .filter { it >= 0 }
                .minOrNull() ?: continue
            val labelLength = labels
                .filter { line.rawText.startsWith(it, labelIndex) }
                .maxByOrNull { it.length }
                ?.length ?: 0
            val amount = firstPositiveAmountAfter(line.rawText, labelIndex + labelLength)
            if (amount != null) return amount
        }
        return null
    }

    // ======================================================================
    // P6 平台
    // ======================================================================

    /** 全文 contains 取首个命中；饿了么等暂不支持的平台返回 null。 */
    private fun detectPlatform(prepared: List<Line>): Platform? {
        for (line in prepared) {
            val t = line.rawText
            when {
                t.contains("美团") || t.contains("meituan", ignoreCase = true) -> return Platform.MEITUAN
                t.contains("淘宝") || t.contains("闪购") ||
                    t.contains("taobao", ignoreCase = true) -> return Platform.TAOBAO_FLASH
                t.contains("京东") || t.contains("jingdong", ignoreCase = true) ||
                    JD_WORD_RE.containsMatchIn(t) -> return Platform.JD
                t.contains("饿了么") -> return null
            }
        }
        return null
    }

    // ======================================================================
    // 金额工具
    // ======================================================================

    companion object {

        private const val QUANTITY_TAIL_WINDOW = 14

        /**
         * 商品名区（金额左侧文本）的**长度兜底上界**，超过基本就是规格串。
         *
         * ## 为什么是 30，而不是更紧的值
         * 这个闸门**只是兜底**，主要拦截靠另外两道：
         * 1. **含逗号直接否** —— 规格描述几乎必带逗号（`标准,正常冰,七分糖(推荐),换冷链`）；
         * 2. [SPEC_HINTS] 规格词表（`标准` / `七分糖` / `大杯` …）。
         *
         * 有这两道之后，长度闸门的真实收益已接近零，代价却是**误杀长商品名**：
         * `超大份双人套餐(含可乐薯条鸡翅)` 是 16 字，属于完全正常的商品名，
         * 之前取 14 会把它整行否掉 → `itemCount` 归零、用户看到"商品未识别"。
         *
         * ## 将来若要收紧，请改这里以外的地方
         * 如果发现规格行漏进来了，**优先加强 [SPEC_HINTS] 词表**，
         * 不要回头收紧这个长度；长度是最后一道、也是最容易误伤真商品名的一道。
         */
        private const val MAX_ITEM_LEFT_LEN = 30

        /**
         * 竖排文字 / 损坏框的宽高比阈值：`height > 3 × width` 判为竖排或坏框。
         *
         * 3 是踩在真机数据上选的（横排中文行宽高比普遍 < 1，正常块离 3 很远；
         * 实测唯一的命中项是 20×176、宽高比 8.8 的坏框）。
         */
        private const val VERTICAL_ASPECT_RATIO = 3

        /**
         * 横向重叠比例阈值：重叠 ≥ 较窄者宽度的 50% 就判为"上下堆叠、不同行"。
         *
         * 为什么是 0.5：真机数据的分布是两极分化的 —— 要合并的并排块横向重叠
         * **全为负**（-140 ~ -956），不该合并的堆叠块横向重叠**是 100%**。
         * 0.5 落在中间的真空地带，两边都有巨大余量，不是靠调参调出来的。
         *
         * 判据对"块高度"完全不敏感，所以不会像高度类判据那样误伤
         * "商品名换行被合成一个高 TextLine"的正常情况。
         */
        private const val HORIZONTAL_OVERLAP_RATIO = 0.5

        /**
         * 裸整数价格与"页面金额右界"的最大允许偏差（像素）。
         *
         * 60 是按真机实测选的：`4` 的 right=1269、金额右界=1272（差 3）；
         * 而左对齐的 `x1` right=342、`11:41` right=576，都远超 60。
         * 取 60 而不是 20，是为了容忍不同 DPI 下价格列的小幅抖动。
         */
        private const val MONEY_EDGE_TOLERANCE = 60

        /**
         * 店铺名"裁剪掉 UI 装饰后"至少要剩这么多字符，否则认为整行都是装饰（横幅），整条否决。
         *
         * 4 是踩在真机数据上选的：`今日领券` → `今日`(2) 被否决，
         * `蜜雪冰城(秋园雅苑店) h直播中>` → `蜜雪冰城(秋园雅苑店)`(12) 通过。
         */
        private const val MIN_STORE_BODY_LEN = 4

        private const val MAX_QUANTITY = 99

        /**
         * 店铺 / 商家标签。
         * 「店铺名称」必须排在「店铺」前面，否则 `店铺名称：麦当劳` 会取到「名称:麦当劳」。
         *
         * `(?<!进)` 是必须的：店铺行右侧的按钮是「⊙ 进商家粉丝群」，含 `商家` 二字，
         * 不加负向断言就会被当成"商家名称"标签，把店名解析成 `粉丝群`。
         */
        private val STORE_LABEL_RE = Regex("(?<!进)(店铺名称|店铺|商家名称|商家|门店)")

        /** 精确实付标签（长的排前面，避免「实付款」被「实付」截断）。 */
        val EXACT_PAID_LABELS: List<String> = listOf(
            "实付款", "实付金额", "实际支付", "支付金额", "应付金额", "已支付", "实付"
        )

        /** 宽泛合计标签。 */
        val SUMMARY_LABELS: List<String> = listOf(
            "订单合计", "订单金额", "合计", "总计", "总价"
        )

        /** 由 [EXACT_PAID_LABELS] 生成的正则（长标签在前，保证「实付款」优先于「实付」命中）。 */
        private val EXACT_PAID_RE = Regex(EXACT_PAID_LABELS.joinToString("|"))

        /** 由 [SUMMARY_LABELS] 生成的正则（同上，长标签优先）。 */
        private val SUMMARY_LABEL_RE = Regex(SUMMARY_LABELS.joinToString("|"))

        /** 优惠类标签：仅用于 [ParsedOrder.discountTotal]，**绝不**能当实付。 */
        val DISCOUNT_LABELS: List<String> = listOf(
            "已优惠", "店铺优惠", "共优惠", "优惠", "立减", "红包", "满减"
        )

        /** 原价类标签：仅用于提示 / 上界校验，不参与取值。 */
        val ORIGINAL_LABELS: List<String> = listOf(
            "原价", "商品总价", "商品金额", "小计"
        )

        /** 明确的界面噪声文本（用 equals 精确比较，不要用 contains）。 */
        val NOISE_TEXTS: List<String> = listOf(
            "美团", "饿了么", "淘宝", "京东", "淘宝闪购",
            "订单详情", "订单已完成", "订单已送达",
            "查看详情", "申请退款", "联系商家", "再来一单", "开发票", "评价赚积分"
        )

        /**
         * 带标签的元信息行：内部含数字也可能被判为合法店名，必须用 contains 前置否决。
         *
         * 为什么不能写成"含数字且不含 ¥ 就否决"：那会误杀 `7-11便利店`、`3号门炸鸡`
         * 这类合法店名。所以只否决**明确带标签**的订单元信息行。
         */
        private val NOISE_PREFIX: List<String> = listOf(
            "订单号", "订单编号", "下单时间", "支付时间", "配送时间", "预计送达",
            "收货地址", "联系电话", "手机号", "发票抬头", "取餐号", "桌号",
            // 真实截图里的折叠行 / 店铺行右侧的运营按钮
            "支付方式", "期望时间", "配送方式", "送达时间", "收货人", "联系人", "备注",
            "粉丝群", "进商家", "直播中"
        )

        /** 店铺名里不该出现的词（费用 / 优惠 / 合计 / 操作按钮等）。 */
        val STORE_EXCLUDE: List<String> = listOf(
            "打包费", "餐盒", "配送费", "配送", "运费", "服务费",
            "优惠", "红包", "满减", "立减", "折扣",
            "合计", "总计", "小计", "实付", "原价", "商品总价",
            "会员", "发票", "备注", "预计", "已减", "待支付",
            "餐具", "联系商家", "全部商品", "商品", "评价", "再来一单",
            // 真机截图：店铺行右侧的运营按钮 / 赠送类文案 / 顶部促销横幅
            "粉丝群", "进商家", "直播", "赠送", "已用", "领券"
        )

        /** 商品行里不该出现的词（与店铺排除词同源）。 */
        val ITEM_EXCLUDE: List<String> = STORE_EXCLUDE

        /**
         * 规格词总表（对外暴露，便于用例与后续维护对照）。
         * 实际判定见 [SPEC_HINTS_MULTI] 与 [SPEC_SINGLE_TOKEN_RE]。
         */
        val SPEC_HINTS: List<String> = listOf(
            "标准", "正常冰", "少冰", "去冰", "常温", "热", "七分糖", "五分糖", "三分糖",
            "无糖", "推荐", "换", "加料", "规格", "份量", "口味", "温度", "甜度",
            "大杯", "中杯", "小杯"
        )

        /** 多字规格词：contains 即可判定，不会误伤商品名。 */
        private val SPEC_HINTS_MULTI: List<String> = listOf(
            "标准", "正常冰", "少冰", "去冰", "常温", "七分糖", "五分糖", "三分糖",
            "无糖", "推荐", "加料", "规格", "份量", "口味", "温度", "甜度",
            "大杯", "中杯", "小杯"
        )

        /**
         * 单字规格词必须是"独立 token"才算规格，否则 `热干面`（热）、
         * `换` 之外的商品名会被误杀。
         */
        private val SPEC_SINGLE_TOKEN_RE = Regex("""(?:^|[\s,、])(?:热|换)(?:$|[\s,、(（])""")

        /** 非金额数字的后缀单位：`200克` / `4.8分` / `1人份` / `500ml` 等。 */
        private val NON_MONEY_SUFFIX_RE = Regex(
            """\d+(?:\.\d+)?\s*(克|kg|KG|g|G|ml|ML|毫升|升|人份|份|寸|英寸|个装|串|片|块|公里|km|分钟|分|星|月售|人|条|%|℃)"""
        )

        /** 带 `¥` 的金额（可带负号，如「已优惠 -¥3」）。 */
        private val MARKED_AMOUNT_RE = Regex("""¥\s*(-?\d{1,6}(?:\.\d{1,2})?)""")

        /** 用于清洗商品名 / 判断"只剩符号"的带 ¥ 金额（不做分组）。 */
        private val MARKED_AMOUNT_ANY_RE = Regex("""¥\s*-?\d{1,6}(?:\.\d{1,2})?""")

        /** 裸小数金额：`43.4` / `26.40`；整数不算（整数是数量、序号、日期）。 */
        private val BARE_DECIMAL_RE = Regex("""(?<![\d.])-?\d{1,6}\.\d{1,2}(?![\d])""")

        /** 行尾（可跟数量后缀）的 `d.dd`。 */
        private val LINE_END_DECIMAL_RE = Regex("""(?<![\d.])\d{1,6}\.\d{1,2}\s*(?:[x*]\s*\d{1,2})?\s*$""")

        /** 标签之后"紧邻的"金额（可带 `¥` / 负号，可带 `元` 后缀）。 */
        private val IMMEDIATE_AMOUNT_RE = Regex("""^\s*[-−]?\s*¥?\s*(\d{1,6}(?:\.\d{1,2})?)""")

        /** 清洗用的"任意数字"（金额 / 整数都删）。 */
        private val MONEY_CLEAN_RE = Regex("""\d+(?:\.\d+)?(?:[x]\d{1,2})?""")

        /**
         * 括号片段：**捕获**括号内的内容，由 [isSpecFragment] 决定删不删。
         *
         * 保留原有的"括号内不含嵌套括号、长度 ≤14"约束（避免正则回溯炸掉）。
         */
        private val PAREN_FRAGMENT_RE = Regex("""[（(]([^（()）]{0,14})[)）]""")

        /**
         * 括号内"数字 + 单位"形态：`200克` / `1人份` / `1.5L` / `12寸` 等。
         *
         * 允许数字与单位之间有空格（`200 克`）；要求**整体匹配**，不是 contains ——
         * `含2块饼干` 这种商品名说明不能被当规格删掉。
         */
        private val PAREN_NUMBER_UNIT_RE = Regex(
            """^\s*\d+(?:\.\d+)?\s*(?:克|kg|KG|g|G|ml|ML|毫升|升|人份|份|寸|英寸|个装|串|片|块)\s*$"""
        )

        /** 行首活动标签：`[折]` / `【券】` / `[赠]`（美团列表页的商品前缀）。 */
        private val PRODUCT_TAG_RE = Regex("""[\[【]\s*(?:折|券|赠|特|秒|新品|满减|限时|活动)\s*[\]】]""")

        /**
         * 裸的单字活动角标（真机上 `折` 会被单独切成一块）。
         *
         * 必须是"行首 + 自成 token"：前后都要有边界，这样
         * `折 三拼霸霸奶茶` 会剥离，而 `折扣商品` / `新品奶茶` / `特价套餐` 不会。
         */
        private val LEADING_ACTIVITY_TAG_RE =
            Regex("""^[\s·•*\-|、]*[折券新赠特惠](?=$|[\s,、(（)）\]】])""")

        /**
         * 行尾的裸整数（1~3 位），且前面必须是空格或汉字。
         *
         * `雪王小宝杯 4` → 命中 `4`；
         * `C684` 的 `684` 前面是字母 `C` → 不命中；
         * `x1` 的 `1` 前面是 `x` → 不命中；
         * `11:41` 的 `41` 前面是 `:` → 不命中；
         * `182****0814` 的 `0814` 前面是 `*` → 不命中。
         */
        private val TRAILING_BARE_INTEGER_RE = Regex("""(?<=[\s\u4E00-\u9FFF])(\d{1,3})$""")

        /** 数量后缀：`x2` / `×1` / `*3`（normalize 后 × 已是 x）。 */
        private val QUANTITY_RE = Regex("""[xX×*]\s*(\d{1,2})(?!\d)""")

        /** 整行只有一个数量标记（`x1`）——绝不是商品名 / 店名。 */
        private val QUANTITY_ONLY_RE = Regex("""^[xX×*]\s*\d{1,2}$""")

        /** 打码的个人信息（`刘**(先生)` / `182****0814`）——不是店名。 */
        private val MASKED_INFO_RE = Regex("""\*{2,}""")

        /** 行首序号 / 项目符号。 */
        private val LEADING_INDEX_RE = Regex("""^[\s·•*\-|、]+|^\s*\d{1,2}\s*[.、)）]\s*""")

        /** 语义行清理用的行首符号（不删 `[` / `【`，活动标签交给 [PRODUCT_TAG_RE]）。 */
        private val LEADING_SYMBOL_RE = Regex("""^[\s·•*\-|、]+""")

        /** 店名尾部的箭头 / 分隔符 / 项目符号。 */
        private val TAIL_SYMBOL_RE = Regex("""[\s>›≫»«|·•*—^~⌃]+$""")

        /**
         * 店名尾部的 UI 词表。**长的排前面**，否则 `进商家` 会抢在 `进商家粉丝群` 之前命中。
         */
        private val STORE_TAIL_WORDS: List<String> = listOf(
            "进商家粉丝群", "商家粉丝群", "正在直播", "直播中", "进店逛逛", "查看全部",
            "粉丝群", "进商家", "直播", "进店", "关注", "已关注", "收藏",
            "领券", "抢购", "展开", "收起", "更多"
        )

        /** 紧跟在汉字 / 右括号之后的短拉丁标签（如直播间水印 `lu`）。 */
        private val TRAILING_LATIN_TAG_RE = Regex("""(?<=[)\]）】\u4E00-\u9FFF])\s*[A-Za-z]{1,4}$""")

        /**
         * 由 [DISCOUNT_LABELS] 生成的"优惠整段"正则（标签 + 自带金额，可带负号与 `元`）。
         *
         * 标签按长度倒序，保证 `已优惠` 优先于 `优惠` 命中，不会只咬掉半截。
         */
        private val DISCOUNT_SEGMENT_RE = Regex(
            "(" + DISCOUNT_LABELS.sortedByDescending { it.length }.joinToString("|") + ")" +
                """\s*[-−]?\s*¥?\s*\d{1,6}(?:\.\d{1,2})?\s*(?:元)?"""
        )

        /** `jd` 作为独立单词出现（避免误命中普通单词里的 jd）。 */
        private val JD_WORD_RE = Regex("""(?i)(?<![a-z])jd(?![a-z])""")

        /** 是否 CJK 汉字（中文店名的主体）。 */
        private fun isCjk(ch: Char): Boolean =
            ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF'

        /**
         * 全角 → 半角，并折叠空白。
         *
         * 处理：`（）`→`()`、`￥`→`¥`、`×`→`x`、`＊`→`*`、`．`→`.`、
         * 全角数字/字母 → 半角、全角空格 → 半角空格。
         */
        fun normalize(text: String): String {
            if (text.isEmpty()) return ""
            val sb = StringBuilder(text.length)
            for (ch in text) {
                val converted = when (ch) {
                    '（' -> '('
                    '）' -> ')'
                    '￥' -> '¥'
                    '×' -> 'x'
                    '＊' -> '*'
                    '．' -> '.'
                    '：' -> ':'
                    '，' -> ','
                    '　' -> ' '
                    '\n', '\r', '\t' -> ' '
                    else -> {
                        if (ch in '！'..'～') {
                            // 全角 ASCII 区（U+FF01..U+FF5E）→ 半角
                            (ch.code - 0xFEE0).toChar()
                        } else {
                            ch
                        }
                    }
                }
                sb.append(converted)
            }
            return sb.toString().replace(Regex("""\s+"""), " ").trim()
        }

        /**
         * **整个解析的地基**：把"非金额数字"（带单位的量词）替换成空格。
         *
         * 例：`(200克)x1` → `( )x1`；`4.8分` → ` `；`1人份` → ` `。
         * 不先做这一步，商品名里会残留一堆假数字，金额也会被误读。
         */
        fun stripNonMoneyNumbers(text: String): String =
            NON_MONEY_SUFFIX_RE.replace(text, " ")

        /**
         * 剥掉文本里的「优惠标签 + 它自己的金额」整段。
         *
         * `合计 已优惠8.5元 ¥12.3` → `合计   ¥12.3`
         * `实付 ¥26.4 已优惠 ¥21.9` → `实付 ¥26.4`
         */
        fun stripDiscountSegments(text: String): String =
            if (text.isEmpty()) text else DISCOUNT_SEGMENT_RE.replace(text, " ")

        /**
         * 抽取文本中的金额（按出现位置从左到右，值取绝对值 —— 前置 `-` 号剥掉）。
         *
         * 优先取带 `¥` 前缀的数字；若一个都没有，再退回"裸小数"（`d.dd`）：
         * 整数一律不当金额，避免把数量 / 序号 / 日期算成钱。
         */
        fun amountsIn(text: String): List<Double> {
            if (text.isBlank()) return emptyList()
            val marked = MARKED_AMOUNT_RE.findAll(text)
                .mapNotNull { it.groupValues[1].toDoubleOrNull() }
                .map { kotlin.math.abs(it) }
                .toList()
            if (marked.isNotEmpty()) return marked
            return BARE_DECIMAL_RE.findAll(text)
                .mapNotNull { it.value.toDoubleOrNull() }
                .map { kotlin.math.abs(it) }
                .toList()
        }

        /**
         * 解析数量：只认 `x1`/`×2`/`*3` 后缀，只在行尾最后 14 字符内找，取最后一个。
         * 永远返回 >= 1。
         */
        fun parseQuantity(rawText: String): Int {
            val tail = if (rawText.length <= QUANTITY_TAIL_WINDOW) {
                rawText
            } else {
                rawText.takeLast(QUANTITY_TAIL_WINDOW)
            }
            val match = QUANTITY_RE.findAll(tail).lastOrNull() ?: return 1
            val value = match.groupValues[1].toIntOrNull() ?: return 1
            return value.coerceIn(1, MAX_QUANTITY)
        }
    }
}
