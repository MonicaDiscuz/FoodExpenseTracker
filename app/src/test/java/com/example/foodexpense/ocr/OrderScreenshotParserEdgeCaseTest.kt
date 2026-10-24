package com.example.foodexpense.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OrderScreenshotParser] 几何重建层的**边界 / 反例**测试（QA 独立复核补写）。
 *
 * ## 与 `OrderScreenshotParserGeometryTest` 的分工
 * 那个文件锁的是"真实截图形态必须解析正确"（正向回归）；
 * 本文件锁的是**重构引入的假设在边界处是否成立**（反向 / 反例），
 * 每条用例都对应重构说明里的一个具体假设，断言必须能在假设失效时变红。
 *
 * 覆盖的假设：
 * 1. 聚类判据 `overlap > 0.5 * min(heightA, heightB)` 的**精确边界**（等于 / 略超 / 大字号压小字号）
 * 2. 单字规格词 `热` / `换` 不会误杀 `热干面` / `换骨鱼粉`
 * 3. `trimStoreTail` 的"尾随短拉丁水印"不会误裁合法英文店名
 * 4. `stripDiscountSegments` 的剥离边界（`优惠后 ¥9.9` 不得被剥成 null）
 * 5. 部分行缺坐标（top/bottom 全 0）时不崩溃、不错乱
 * 6. 已知取舍：价格在左栏时整条商品漏识别（P2 取舍，非 bug）
 * 7. 已知缺陷：上界校验的回退集合为空时，越界价格被原样保留
 */
class OrderScreenshotParserEdgeCaseTest {

    private val parser = OrderScreenshotParser()

    // ==================================================================
    // 构造工具
    // ==================================================================

    /** 走位：`left` 栏 / `right` 栏 / 任意区间，均为 [TextLine] 的直接映射。 */
    private fun tl(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        TextLine(text = text, left = left, top = top, right = right, bottom = bottom)

    /** 一条"整行"（单块），默认位于首行。 */
    private fun one(text: String, top: Int = 100, right: Int = 620) = tl(text, 60, top, right, top + 40)

    /** 一条"右栏价格"块。 */
    private fun price(text: String, top: Int) = tl(text, 880, top, 1000, top + 40)

    // ==================================================================
    // 1. 聚类判据的精确边界
    // ==================================================================

    /**
     * 垂直重叠 **恰好等于** `0.5 * min(height)` 时，判据是严格大于 `>`，所以**不得合并**。
     *
     * 两块都是 40px 高，上一块 100..140、下一块 120..160 → 重叠 20 = 0.5×40，边界值。
     * 若有人把判据写成 `>=`，商品名与价格就会被拆成两条、整条商品消失（itemCount 变 0），
     * 因此这里断言 itemCount == 0 是**刻意的**：这条用例守护的是"不该合并的绝不合并"。
     */
    @Test
    fun `geometry_重叠恰好一半时不合并`() {
        val input = listOf(
            tl("全家桶", 60, 100, 200, 140),
            tl("¥45.9", 880, 120, 1000, 160)
        )

        val parsed = parser.parse(input)!!

        // 未合并 → 两条都不可能是商品行："全家桶"无金额，"¥45.9"去掉金额后 residue 为空
        assertTrue("重叠恰好 0.5 倍行高时不得合并，实际 itemCount=${parsed.itemCount}", parsed.itemCount == 0)
        assertNull("未合并时不应有商品名", parsed.productName)
        assertNull("未合并时不应有商品价", parsed.productPrice)
    }

    /**
     * 重叠 **只比一半多 1px**（21 > 20）时必须合并 —— 否则上一像素的边界就变成"永远合并/永远不合并"。
     *
     * 合并后 `全家桶 ¥45.9` 成为一条可识别商品行，这是几何重建层存在的全部意义。
     */
    @Test
    fun `geometry_重叠略超一半时合并成商品行`() {
        val input = listOf(
            tl("全家桶", 60, 100, 200, 140),
            tl("¥45.9", 880, 119, 1000, 159)   // 重叠 = 140 - 119 = 21 > 0.5 * 40
        )

        val parsed = parser.parse(input)!!

        assertEquals(1, parsed.itemCount)
        assertEquals("全家桶", parsed.productName)
        assertEquals(45.9, parsed.productPrice!!, 0.001)
    }

    /**
     * 大字号商品名（48px）紧贴小字号规格行（24px，仅 2px 重叠）时，**绝不能**合并。
     *
     * 这是重构说明里"用 `min(height)` 会让判据变宽松"的直接反例验证：
     * 判据虽然取短边，但 2px 重叠远小于 `0.5 × 24 = 12`，仍不会合并。
     * 一旦合并，规格行的逗号会让 `isItemLine` 把**整条商品**否掉（itemCount 变 0）。
     */
    @Test
    fun `geometry_大字号商品名与紧贴规格行不合并`() {
        val input = listOf(
            tl("超值双人餐", 60, 100, 400, 148),      // 大字号 h=48
            tl("¥28.0", 880, 100, 1000, 148),         // 与商品名同高同顶 → 必合并
            tl("标准,少冰", 60, 146, 300, 170),        // 小字号 h=24，与商品名重叠仅 2px
            tl("¥32.0", 880, 146, 1000, 170),         // 与规格行同顶 → 合并到规格行
            tl("合计", 60, 220, 300, 260),
            tl("¥28.0", 880, 220, 1000, 260)
        )

        val parsed = parser.parse(input)!!

        assertTrue("规格行不得与商品行合并，也不得自成商品行，实际 itemCount=${parsed.itemCount}", parsed.itemCount == 1)
        assertEquals("超值双人餐", parsed.productName)
        assertEquals(28.0, parsed.productPrice!!, 0.001)
        // 规格行的划线原价 32.0 更高，若合并/误判就会抢走主商品
        assertTrue("主商品价不得被规格行抢走", parsed.productPrice!! < 32.0)
    }

    // ==================================================================
    // 2. 单字规格词 热 / 换
    // ==================================================================

    /** `热干面 ¥10` / `换骨鱼粉 ¥15` 是真实商品名，单字规格词不得误杀。 */
    @Test
    fun `single_char_spec_热干面与换骨鱼粉不被误杀`() {
        val reGanMian = parser.parse(
            listOf(
                one("蔡林记(总店)"),
                tl("热干面", 60, 160, 400, 200), price("¥10", 160),
                tl("蛋酒", 60, 220, 400, 260), price("¥5", 220),
                tl("合计", 60, 280, 300, 320), price("¥15", 280)
            )
        )!!
        assertEquals("热干面", reGanMian.productName)
        assertEquals(10.0, reGanMian.productPrice!!, 0.001)
        assertEquals(2, reGanMian.itemCount)

        val huanGu = parser.parse(
            listOf(
                one("柳州螺蛳粉(科技园店)"),
                tl("换骨鱼粉", 60, 160, 400, 200), price("¥15", 160),
                tl("合计", 60, 220, 300, 260), price("¥15", 220)
            )
        )!!
        assertEquals("换骨鱼粉", huanGu.productName)
        assertEquals(1, huanGu.itemCount)
    }

    /**
     * 但"孤零零一个 `热`"确实是规格行，必须仍被拦截。
     *
     * 与上一条配对：证明单字规格词规则既没死、也没误杀
     * （规则里 `(?:^|[\s,、])` 与 `(?:$|[\s,、(（])` 同时要求前后边界）。
     */
    @Test
    fun `single_char_spec_独立的热仍被拦截`() {
        val parsed = parser.parse(
            listOf(
                one("某某奶茶"),
                tl("热", 60, 160, 120, 200), price("¥10", 160),
                tl("合计", 60, 220, 300, 260), price("¥10", 220)
            )
        )!!

        assertTrue("独立的单字规格词不得算商品行，实际 itemCount=${parsed.itemCount}", parsed.itemCount == 0)
        assertNull(parsed.productName)
    }

    // ==================================================================
    // 3. trimStoreTail 的尾随拉丁水印裁剪
    // ==================================================================

    /** 合法英文 / 混排店名不得被"尾随短拉丁水印"规则误裁。 */
    @Test
    fun `store_tail_合法拉丁店名不被误裁`() {
        val cases = mapOf(
            "KFC 24h" to "KFC 24h",        // 尾部 h 前是数字，不是汉字/右括号 → 不裁
            "华莱士 live店" to "华莱士 live店",  // 尾部是汉字"店" → 不裁
            "肯德基KFC" to "肯德基KFC"        // 拉丁串仅 3 字符，剩余 < 4 → 不裁
        )
        for ((input, expected) in cases) {
            val parsed = parser.parse(
                listOf(
                    one(input, 100, 600),
                    tl("全家桶", 60, 160, 400, 200), price("¥29.9", 160),
                    tl("合计", 60, 220, 300, 260), price("¥29.9", 220)
                )
            )!!
            assertEquals("店名「$input」被误裁", expected, parsed.storeName)
        }
    }

    /**
     * **已知取舍（P3，非回归）**：尾部 2~4 个拉丁字符会被当水印裁掉。
     *
     * `星巴克(万象城店)ds` → `星巴克(万象城店)`。真实外卖店铺名不会带这种尾巴，
     * 而直播间水印 `lu` 的收益更大，故接受该取舍。本条用例**固化当前行为**：
     * 一旦裁剪阈值/规则变化，这里会红，提醒复核是否影响 `蜜雪冰城(店)lu` 那条主用例。
     */
    @Test
    fun `store_tail_已知取舍_尾部短拉丁被裁`() {
        val parsed = parser.parse(
            listOf(
                one("星巴克(万象城店)ds", 100, 700),
                tl("全家桶", 60, 160, 400, 200), price("¥29.9", 160),
                tl("合计", 60, 220, 300, 260), price("¥29.9", 220)
            )
        )!!

        assertEquals("星巴克(万象城店)", parsed.storeName)
    }

    // ==================================================================
    // 4. stripDiscountSegments 的剥离边界
    // ==================================================================

    /**
     * 优惠标签**后面紧跟别的词**（`优惠后 ¥9.9`）时不得剥离 —— 这里的 9.9 是实付。
     *
     * 剥离正则要求"标签 + 可选负号/¥ + 数字"紧邻；`优惠后` 中间夹了"后"，
     * 因此不匹配、金额保留。这条用例守住"整段剥离"不会把实付吃掉。
     */
    @Test
    fun `strip_discount_优惠后的金额不被剥离`() {
        val parsed = parser.parse(
            listOf(
                one("某某超市"),
                tl("套餐", 60, 160, 400, 200), price("¥19.9", 160),
                tl("合计", 60, 220, 300, 260),
                tl("优惠后", 400, 220, 520, 260), price("¥9.9", 220)
            )
        )!!

        assertEquals("「优惠后 ¥9.9」的 9.9 必须当实付", 9.9, parsed.actualPaid!!, 0.001)
        // 「优惠」后面紧跟"后"，不是金额 → 不得被当成优惠额
        assertNull("「优惠后」不得产出 discountTotal", parsed.discountTotal)
    }

    /** 剥离边界的四个关键形态（工具方法级，逐个断言金额集合）。 */
    @Test
    fun `strip_discount_四种边界形态`() {
        // 优惠在实付之后 → 剥掉，留 26.4
        assertEquals(
            listOf(26.4),
            OrderScreenshotParser.amountsIn(
                OrderScreenshotParser.stripDiscountSegments("实付 ¥26.4 已优惠 ¥21.9")
            )
        )
        // 优惠在实付之前且带「元」→ 剥掉，留 12.3
        assertEquals(
            listOf(12.3),
            OrderScreenshotParser.amountsIn(
                OrderScreenshotParser.stripDiscountSegments("合计 已优惠8.5元 ¥12.3")
            )
        )
        // 「优惠后」是"折后实付"语义 → 不剥离
        assertEquals(
            listOf(9.9),
            OrderScreenshotParser.amountsIn(
                OrderScreenshotParser.stripDiscountSegments("优惠后 ¥9.9")
            )
        )
        // 「立减5元」是优惠段 → 剥掉
        assertEquals(
            listOf(9.9),
            OrderScreenshotParser.amountsIn(
                OrderScreenshotParser.stripDiscountSegments("立减5元 ¥9.9")
            )
        )
    }

    /**
     * **已知缺陷（P3）**：单字规格词的"独立 token"规则在**空格分隔**时失效。
     *
     * [OrderScreenshotParser] 的 `isItemLine` 里先把金额左侧文本的空格全部删掉
     * （`replace(Regex("\\s+"), "")`）再调 `looksLikeSpec`，而 `SPEC_SINGLE_TOKEN_RE`
     * 要求 `热` 前后有 `^`/空格/逗号边界 —— 空格已被删，规则永远等不到边界。
     * 结果：真正由空格分隔的规格行 `热 拿铁 ¥18` 会被当成商品行。
     *
     * 目前影响有限（多字规格词 `大杯`/`少冰` 等仍由 `SPEC_HINTS_MULTI` 拦截，
     * 且 `热干面` 这类商品名因此不会被误杀），但规则与注释不一致。
     * 修法：在**折叠空格之前**的 `leftPart` 上做单字规格词判定。
     */
    @Test
    fun `single_char_spec_空格分隔时独立token规则失效`() {
        val parsed = parser.parse(
            listOf(
                one("某某咖啡馆"),
                tl("热", 60, 160, 120, 200),          // 规格：温度
                tl("拿铁", 200, 160, 400, 200),        // 商品名
                price("¥18", 160),
                tl("合计", 60, 220, 300, 260), price("¥18", 220)
            )
        )!!

        assertTrue(
            "现状（缺陷）：空格分隔的独立「热」未被拦截，itemCount=${parsed.itemCount}",
            parsed.itemCount == 1
        )
        assertEquals("热 拿铁", parsed.productName)
    }

    // ==================================================================
    // 5. 部分行缺坐标
    // ==================================================================

    /**
     * "部分行有坐标、部分行全 0"时不得崩溃，且关键字段仍要正确。
     *
     * 全 0 坐标的行会被排到最前面（top=0），若解析器对行序敏感（店铺名取"主商品行之前最靠上"、
     * 实付取"自下而上"），这里立刻暴露。真实 OCR 不会产出这种混合输入，但调用方可能。
     */
    @Test
    fun `mixed_zero_coords_不崩溃且字段正确`() {
        val input = listOf(
            one("某某超市"),
            tl("全家桶", 60, 160, 400, 200), price("¥29.9", 160),
            TextLine("合计 ¥29.9")   // 四条坐标全 0，会被排到最前面
        )

        val parsed = parser.parse(input)!!

        assertEquals("某某超市", parsed.storeName)
        assertEquals("全家桶", parsed.productName)
        assertEquals(29.9, parsed.productPrice!!, 0.001)
        assertEquals(29.9, parsed.actualPaid!!, 0.001)
        assertEquals(1, parsed.itemCount)
    }

    /**
     * **已知缺陷（P3）**：全 0 坐标的行会与 `top == 0` 的真实行**合并**，导致商品静默丢失。
     *
     * 退化保护只在"**所有**行都缺坐标"时才跳过重建。一旦混合输入里有一条
     * `top=0,bottom=0`（行高 0）和一条真实但贴着屏幕顶边（`top=0,bottom=40`）的行，
     * `sameVisualLine` 会走"高度缺失"分支：`|0 - 20| * 2 = 40 <= 40` → 判定同一视觉行。
     * 合并后块按 left 排序，价格 `¥29.9` 落在最左 → `leftPart` 为空 →
     * 这条商品被 `isItemLine` 否掉，**不抛异常、不报错，静默少一件商品**。
     *
     * 真实 ML Kit 不会给出这种混合坐标，故定级 P3；用例固化现状以便回归。
     */
    @Test
    fun `mixed_zero_coords_与顶边行合并会静默丢商品`() {
        val input = listOf(
            TextLine("¥29.9"),                        // 四条坐标全 0
            tl("全家桶", 60, 0, 400, 40),              // 真实行，紧贴屏幕顶边
            tl("合计", 60, 220, 300, 260), price("¥29.9", 220)
        )

        val parsed = parser.parse(input)!!

        assertTrue(
            "现状（缺陷）：合并后该商品被否掉，itemCount=${parsed.itemCount}",
            parsed.itemCount == 0
        )
        assertNull("商品价随之丢失", parsed.productPrice)
        assertNull("商品名随之丢失", parsed.productName)
        // 实付仍能取到 → 失败是局部的，用户看到的是"商品未识别、实付已填"
        assertEquals(29.9, parsed.actualPaid!!, 0.001)
    }

    // ==================================================================
    // 6./7. 两条已知取舍 / 缺陷（固化现状，防无声变化）
    // ==================================================================

    /**
     * **已知取舍（P2）**：价格在**左**栏时整条商品漏识别。
     *
     * `isItemLine` 要求"金额左侧文本非空"，隐含假设是"价格总在商品名右边"。
     * 京东 / 饿了么 / 美团的外卖详情页都是左名右价，所以对现有平台成立；
     * 但若接入价格前置的版式，`itemCount` 会变 0、`productName` 为 null。
     * 本条用例固化现状：一旦接入新平台，这里会红，提醒补兜底而不是静默错。
     */
    @Test
    fun `tradeoff_价格在左栏时不算商品行`() {
        val input = listOf(
            one("某某奶茶"),
            tl("¥12.7", 60, 160, 200, 200),          // 价格在左
            tl("三拼霸霸奶茶", 400, 160, 700, 200),   // 商品名在右
            tl("合计", 60, 220, 300, 260), price("¥12.3", 220)
        )

        val parsed = parser.parse(input)!!

        assertTrue("价格前置的版式当前不被识别为商品，实际 itemCount=${parsed.itemCount}", parsed.itemCount == 0)
        assertNull(parsed.productName)
        // 实付仍能拿到，说明降级是局部的、不是整体失效
        assertEquals(12.3, parsed.actualPaid!!, 0.001)
    }

    /**
     * **已知缺陷（P3）**：上界校验的回退集合为空时，越界价格被**原样保留**。
     *
     * `if (fallback.isNotEmpty()) productPrice = fallback.max()` —— 回退为空时什么都不做，
     * 于是 `productPrice` 仍是那个超上界的值（此处 30.0 > 12.3 + 8.5 + 0.01 = 20.81）。
     * 更安全的行为应是回退到 null（"猜不到就不猜"），与解析器整体口径一致。
     * 本条用例固化现状：一旦改成 null，这里会红，须同步更新本注释与报告。
     */
    @Test
    fun `known_limitation_上界回退为空时保留越界价`() {
        val parsed = parser.parse(
            listOf(
                one("某某快餐"),
                tl("套餐", 60, 160, 300, 200),
                tl("¥22.5", 500, 160, 700, 200),
                tl("¥30.0", 880, 160, 1000, 200),
                tl("合计", 60, 220, 300, 260),
                tl("已优惠8.5元", 400, 220, 620, 260), price("¥12.3", 220)
            )
        )!!

        val upperBound = parsed.actualPaid!! + parsed.discountTotal!! + 0.01
        assertEquals("现状：越界价被保留（应改为回退 null）", 30.0, parsed.productPrice!!, 0.001)
        assertTrue(
            "越界事实必须成立：${parsed.productPrice} > 上界 $upperBound",
            parsed.productPrice!! > upperBound
        )
    }

    // ==================================================================
    // BUG MARKER（注意：本条断言的是**已知错误行为**）
    // ==================================================================

    /**
     * ⚠️ BUG MARKER —— 本用例断言的是**已知错误行为**，不是期望行为。
     *
     * 这条存在的唯一目的：让"已修复 / 未修复"的边界长期固化，防止将来有人
     * 声称修好了"栏间空隙的桥接"，或悄悄改动几何判据而不自知。
     *
     * ## 未修复的是什么
     * `horizontallyStacked` 的前提是"同一视觉行的块左右并排、横向不相接"。
     * 但当一个**两行高的窄块落在左右两栏之间的水平空隙**时，它与左右两栏都
     * 横向不相接，判据看不见它，于是它仍然把两条语义行缝成一条：
     *
     * ```
     * 限时(950..1050, top 1000..1092)  ← 桥块，落在栏间空隙
     *   商品名 302..589 (1010..1056)   ← 与桥块横向 -361 → 放行
     *   规格行 302..900 (1060..1102)   ← 与桥块横向  -50 → 放行
     * → 合并成「三拼霸霸奶茶 标准,正常冰,七分糖 …」→ 含逗号 → 整条商品被否
     * ```
     *
     * ## 定级与处置
     * 已与 team-lead 确认**本轮不修**：治它只能再加"窄块 + 超两行高 = 可疑"
     * 这类高度类启发式，而高度类判据已被证明会误伤"商品名换行成 h=92 宽块"
     * 这个正常形态；三个条件（落空隙 + ≥2 行高 + 左右都不接）同时成立的概率低，
     * 且目前**没有真机证据**。等塔斯特那批真实 dump 到手再议。
     *
     * ## 修复后请**删除**本用例
     * 不要更新断言。同时把"栏间空隙"从 `horizontallyStacked` 的 KDoc
     * 已知限制段落里移除 —— 两处必须一起改，否则文件与代码会各说一套。
     */
    @Test
    fun `bug_marker_栏间空隙桥接仍未修复`() {
        val parsed = parser.parse(
            listOf(
                tl("限时", 950, 1000, 1050, 1092),              // 桥块：落在两栏之间的空隙
                tl("三拼霸霸奶茶", 302, 1010, 589, 1056),
                tl("¥12.7", 1167, 1014, 1269, 1056),
                tl("标准,正常冰,七分糖", 302, 1060, 900, 1102),
                tl("¥39.9", 1167, 1064, 1269, 1106),
                tl("雪王小宝杯", 302, 1300, 560, 1346),
                tl("¥4", 1224, 1304, 1269, 1340),
                tl("合计", 72, 1400, 185, 1446),
                tl("¥12.3", 1169, 1404, 1269, 1446)
            )
        )!!

        // ① 两件商品被缝成一件：真商品「三拼霸霸奶茶」整条消失
        assertEquals("BUG：桥接后 itemCount 只剩 1（应 2）", 1, parsed.itemCount)
        // ② 主商品降级成第二件，价格跟着错
        assertEquals("BUG：主商品变成 雪王小宝杯", "雪王小宝杯", parsed.productName)
        assertEquals("BUG：主商品价变成 4.0（应 12.7）", 4.0, parsed.productPrice!!, 0.001)
        // ③ 店名被商品名抢走（第一条语义行被缝掉后的连带伤害）
        assertEquals("BUG：店名变成商品名", "三拼霸霸奶茶", parsed.storeName)
        // ④ 实付仍正确 —— 说明失败是局部的，"合计"行没被波及
        assertEquals("实付不受影响（证明失败范围仅限商品区）", 12.3, parsed.actualPaid!!, 0.001)
    }
}
