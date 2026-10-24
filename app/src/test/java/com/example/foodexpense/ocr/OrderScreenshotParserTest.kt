package com.example.foodexpense.ocr

import com.example.foodexpense.data.entity.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OrderScreenshotParser] 的纯 JVM 单元测试。
 *
 * 解析器刻意不依赖任何 Android 类型，所以这些用例跑在普通 JVM 上，无需 Robolectric / 真机。
 * 用例里的文本来自真实外卖订单截图的 OCR 结果（含打包费、优惠、多商品等干扰项）。
 */
class OrderScreenshotParserTest {

    private val parser = OrderScreenshotParser()

    /** 按行号递增 top，模拟"自上而下"的版面。 */
    private fun lines(vararg texts: String): List<TextLine> = texts.mapIndexed { i, text ->
        TextLine(
            text = text,
            left = 40,
            top = 100 + i * 80,
            right = 1000,
            bottom = 160 + i * 80
        )
    }

    /**
     * 产品确认过的那张样例截图：
     * 商品原价合计 ¥43.4、打包费 ¥1、已优惠 ¥21.9、实付 ¥26.4 → 必须记 **26.4**；
     * 多商品只记主商品（¥39.9 的巨无霸汉堡）。
     */
    @Test
    fun `真实样例_记实付26_4而不是合计43_4_且取金额最大的主商品`() {
        val parsed = parser.parse(
            lines(
                "美团",
                "订单详情",
                "麦当劳(北京西路店)",
                "巨无霸汉堡(200克)x1 ¥39.9",
                "薯条(中)x1 ¥12.5",
                "可乐(中)x1 ¥9.0",
                "打包费 ¥1",
                "已优惠 ¥21.9",
                "合计 ¥43.4",
                "实付 ¥26.4"
            )
        )!!

        assertEquals(26.4, parsed.actualPaid!!, 0.001)
        assertEquals(39.9, parsed.productPrice!!, 0.001)
        assertEquals("巨无霸汉堡", parsed.productName)
        assertEquals("麦当劳(北京西路店)", parsed.storeName)
        assertEquals(1, parsed.productQuantity)
        assertEquals(3, parsed.itemCount)
        assertEquals(21.9, parsed.discountTotal!!, 0.001)
        assertEquals(Platform.MEITUAN, parsed.platform)
        assertTrue(parsed.isEmpty.not())
    }

    /** 「已优惠 ¥21.9」绝不能当实付。 */
    @Test
    fun `优惠金额不会被当成实付`() {
        val parsed = parser.parse(
            lines(
                "沙县小吃",
                "拌面 ¥8.5",
                "已优惠 ¥3"
            )
        )!!
        // 没有实付/合计行时，P4-c 兜底也会跳过含"优惠"的行，
        // 最后只能取到商品行（含 ¥ 且不含费用词）的 8.5
        assertEquals(8.5, parsed.actualPaid!!, 0.001)
    }

    /** 标签后跟优惠时，不能把优惠额当成实付。 */
    @Test
    fun `实付标签后面的优惠额要被截断`() {
        val parsed = parser.parse(
            lines(
                "肯德基",
                "香辣鸡腿堡 ¥19.9",
                "实付 ¥16.9 已优惠 ¥3"
            )
        )!!
        assertEquals(16.9, parsed.actualPaid!!, 0.001)
    }

    /** `(200克)x1` 里的 200 既不是金额也不是数量。 */
    @Test
    fun `规格里的数字不会被当成金额或数量`() {
        val parsed = parser.parse(
            lines(
                "某某超市",
                "澳洲牛排(200克)x1 ¥59.9",
                "实付 ¥59.9"
            )
        )!!
        assertEquals("澳洲牛排", parsed.productName)
        assertEquals(1, parsed.productQuantity)
        assertEquals(59.9, parsed.productPrice!!, 0.001)
    }

    /** 店铺标签独占一行时取下一行。 */
    @Test
    fun `店铺标签独占一行时取下一行`() {
        val parsed = parser.parse(
            lines(
                "商家名称：",
                "华莱士(科技园店)",
                "鸡腿堡 ¥12.9",
                "实付 ¥12.9"
            )
        )!!
        assertEquals("华莱士(科技园店)", parsed.storeName)
    }

    /** 「美团买菜」是合法店名，不能被噪声词「美团」连带否掉。 */
    @Test
    fun `美团买菜是合法店名`() {
        val parsed = parser.parse(
            lines(
                "美团买菜",
                "鸡蛋 30 枚 ¥25.8",
                "实付 ¥25.8"
            )
        )!!
        assertEquals("美团买菜", parsed.storeName)
    }

    /** 空输入返回 null；纯噪声输入返回 isEmpty 而不是抛异常。 */
    @Test
    fun `空输入返回null`() {
        assertNull(parser.parse(emptyList()))
    }

    @Test
    fun `纯噪声输入不抛异常`() {
        val parsed = parser.parse(lines("查看详情", "再来一单"))
        assertTrue(parsed == null || parsed.isEmpty)
    }

    // ---------------- 工具方法级用例 ----------------

    @Test
    fun `stripNonMoneyNumbers清理假数字`() {
        assertEquals("( )x1", OrderScreenshotParser.stripNonMoneyNumbers("(200克)x1"))
        assertEquals("评分 ", OrderScreenshotParser.stripNonMoneyNumbers("评分4.8分"))
        assertEquals(" ", OrderScreenshotParser.stripNonMoneyNumbers("1人份"))
        assertEquals("¥26.4", OrderScreenshotParser.stripNonMoneyNumbers("¥26.4"))
    }

    @Test
    fun `amountsIn只取可信金额并剥掉负号`() {
        assertEquals(listOf(26.4), OrderScreenshotParser.amountsIn("实付 ¥26.4"))
        assertEquals(listOf(21.9), OrderScreenshotParser.amountsIn("已优惠 -¥21.9"))
        assertEquals(listOf(43.4, 26.4), OrderScreenshotParser.amountsIn("合计 ¥43.4 实付 ¥26.4"))
        assertTrue(OrderScreenshotParser.amountsIn("x2 数量 3").isEmpty())
    }

    @Test
    fun `parseQuantity永远返回至少1`() {
        assertEquals(1, OrderScreenshotParser.parseQuantity("巨无霸汉堡(200克)x1"))
        assertEquals(2, OrderScreenshotParser.parseQuantity("可乐 x2"))
        assertEquals(1, OrderScreenshotParser.parseQuantity("没有数量后缀"))
        assertEquals(1, OrderScreenshotParser.parseQuantity("x0"))
    }

    @Test
    fun `normalize全角转半角`() {
        assertEquals("(200克)x1 ¥39.9", OrderScreenshotParser.normalize("（200克）×1 ￥39.9"))
    }

    // ==================================================================
    // 验收用例（14 条，逐条对应已确认的产品口径 / 硬约束）
    // 用例名保持英文短标识，便于和提测清单一一对照。
    // ==================================================================

    /**
     * [1] sample_screenshot_full
     *
     * 产品确认过的那张样例截图的 **原始 9 行文本**（商品原价 43.4、打包费 1、
     * 已优惠 21.9、实付 26.4）。这是全量回归的锚点用例。
     */
    @Test
    fun `sample_截图样例_整单实付26_4_主商品39_9_三件商品`() {
        val parsed = parser.parse(
            lines(
                "美团",
                "订单详情",
                "麦当劳(科技园店)",
                "巨无霸汉堡(200克)x1   ¥39.9",
                "可口可乐 1人份        ¥2.6",
                "薯条                  ¥0.9",
                "打包费                ¥1.0",
                "已优惠 ¥21.9",
                "合计 ¥43.4  实付 ¥26.4"
            )
        )!!

        assertEquals("麦当劳(科技园店)", parsed.storeName)
        assertEquals("巨无霸汉堡", parsed.productName)
        assertEquals(39.9, parsed.productPrice!!, 0.001)
        assertEquals(1, parsed.productQuantity)
        // 记实付、不记合计：26.4 而不是 43.4
        assertEquals(26.4, parsed.actualPaid!!, 0.001)
        // 打包费 / 优惠 / 合计 不算商品行
        assertEquals(3, parsed.itemCount)
        assertEquals(21.9, parsed.discountTotal!!, 0.001)
        assertEquals(Platform.MEITUAN, parsed.platform)
    }

    /** [2] weight_spec_not_quantity：`(200克)x2` → 数量 2，绝不是 200。 */
    @Test
    fun `weight_spec_克重不是数量`() {
        val parsed = parser.parse(
            lines(
                "某某汉堡",
                "双层牛肉堡(200克)x2 ¥39.8",
                "实付 ¥39.8"
            )
        )!!

        assertEquals(2, parsed.productQuantity)
        assertEquals(39.8, parsed.productPrice!!, 0.001)
        assertEquals("双层牛肉堡", parsed.productName)
    }

    /** [3] portion_spec_not_quantity：`可乐 1人份 ¥2.6` → 数量 1（1人份 是规格）。 */
    @Test
    fun `portion_spec_人份不是数量`() {
        val parsed = parser.parse(
            lines(
                "某某奶茶",
                "可乐 1人份 ¥2.6",
                "实付 ¥2.6"
            )
        )!!

        assertEquals(1, parsed.productQuantity)
        assertEquals(2.6, parsed.productPrice!!, 0.001)
        assertEquals("可乐", parsed.productName)
    }

    /** [4] struck_price_takes_rightmost：同一行 `¥45.9 ¥33.9`（划线价在前）→ 取最右 33.9。 */
    @Test
    fun `struck_price_同两价取最右`() {
        val parsed = parser.parse(
            lines(
                "某某炸鸡",
                "全家桶 ¥45.9 ¥33.9",
                "实付 ¥33.9"
            )
        )!!

        assertEquals(33.9, parsed.productPrice!!, 0.001)
    }

    /** [5] total_and_paid_same_line：`合计 ¥43.4 实付 ¥26.4` 同一行 → 26.4（不是 43.4）。 */
    @Test
    fun `total_and_paid_合计与实付同行取实付`() {
        val parsed = parser.parse(
            lines(
                "麦当劳(科技园店)",
                "巨无霸汉堡(200克)x1 ¥39.9",
                "可口可乐 1人份 ¥2.6",
                "薯条 ¥0.9",
                "打包费 ¥1.0",
                "已优惠 ¥21.9",
                "合计 ¥43.4  实付 ¥26.4"
            )
        )!!

        assertEquals(26.4, parsed.actualPaid!!, 0.001)
    }

    /** [6] paid_label_variants：实付款 / 实际支付 / 支付金额 / 已支付 四种标签都能取到。 */
    @Test
    fun `paid_label_四种实付标签变体`() {
        val labels = listOf("实付款", "实际支付", "支付金额", "已支付")
        for (label in labels) {
            val parsed = parser.parse(
                lines(
                    "某某便利店",
                    "关东煮 ¥12.9",
                    "$label ¥9.9"
                )
            )
            assertEquals("标签「$label」没取到实付金额", 9.9, parsed!!.actualPaid!!, 0.001)
        }
    }

    /** [7] discount_not_taken_as_paid：只有「已优惠」、没有任何实付/合计行 → null，绝不能当实付。 */
    @Test
    fun `discount_not_paid_只有优惠时实付为null`() {
        val parsed = parser.parse(
            lines(
                "沙县小吃",
                "已优惠 ¥21.9"
            )
        )!!

        assertNull(parsed.actualPaid)
        assertEquals(21.9, parsed.discountTotal!!, 0.001)
    }

    /** [8] zero_paid_rejected：`实付 ¥0` → null（设计取舍：防止「配送费 ¥0」之类误命中）。 */
    @Test
    fun `zero_paid_实付为零时拒绝`() {
        val parsed = parser.parse(
            lines(
                "某某小吃",
                "实付 ¥0"
            )
        )!!

        assertNull(parsed.actualPaid)
        assertNull(parsed.productPrice)
    }

    /** [9] multi_item_takes_max：3 个商品 → 取金额最大的 12.5，itemCount = 3。 */
    @Test
    fun `multi_item_多商品只取金额最大`() {
        val parsed = parser.parse(
            lines(
                "肯德基",
                "香辣鸡腿堡 ¥12.5",
                "薯条(中) ¥9.0",
                "可乐(中) ¥8.0",
                "合计 ¥29.5  实付 ¥25.5"
            )
        )!!

        assertEquals(12.5, parsed.productPrice!!, 0.001)
        assertEquals(3, parsed.itemCount)
        assertEquals(25.5, parsed.actualPaid!!, 0.001)
    }

    /**
     * [9-b] 产品口径变更（**不是回归**）：多商品 + OCR 漏掉合计/实付行 → `actualPaid` 必须是 null。
     *
     * 变更原因：旧口径下 P4-c 兜底会取"自下而上第一条商品行的最后一个金额"，
     * 这里会得到 **8.0**，而真实整单约 29.5 —— 少记 21.5 且用户完全看不出来（静默错误）。
     * 新口径：多商品时任何推导都不可靠（还有打包费/配送费/优惠），
     * 按解析器"猜不到就是 null"的原则留空，由调用方回退到小计并提示"未识别：实付金额"，
     * 让错误**可见**。
     */
    @Test
    fun `p4c_多商品缺合计行时实付为null`() {
        val parsed = parser.parse(
            lines(
                "肯德基",
                "香辣鸡腿堡 ¥12.5",
                "薯条 ¥9.0",
                "可乐 ¥8.0"
            )
        )!!

        // 关键：绝不能是 8.0（旧口径）也绝不能是 29.5（推导值）
        assertNull(parsed.actualPaid)
        assertEquals(3, parsed.itemCount)
        // 商品信息仍要正常识别，不能因为实付留空就整体降级
        assertEquals(12.5, parsed.productPrice!!, 0.001)
        assertEquals("香辣鸡腿堡", parsed.productName)
    }

    /**
     * [9-c] 单商品 + 无合计行 → `actualPaid == 12.5`，**兜底仍必须工作**。
     *
     * 这条是给上一条改动上的保险：把 P4-c 门禁写成 `itemCount > 1 → null` 时，
     * 很容易顺手写成 `if (itemCount != 0) return null` 或直接删掉兜底，
     * 那样单商品订单就再也拿不到实付了（一行商品价 = 整单，本来是准的）。
     */
    @Test
    fun `p4c_单商品缺合计行时实付取商品价`() {
        val parsed = parser.parse(
            lines(
                "肯德基",
                "香辣鸡腿堡 ¥12.5"
            )
        )!!

        assertEquals(1, parsed.itemCount)
        assertEquals(12.5, parsed.actualPaid!!, 0.001)
        assertEquals(12.5, parsed.productPrice!!, 0.001)
    }

    /**
     * [10] noise_lines_ignored：时间 / 订单号 / 「再来一单」不得进入 storeName / productName。
     *
     * 这里刻意把噪声行放在**真实截图里的位置**（店铺名上方是订单号与时间），
     * 因为位置启发式（P1-b）是从上往下取第一条"像店名"的行，最容易在这里翻车。
     */
    @Test
    fun `noise_lines_时间订单号再来一单不进店名`() {
        val parsed = parser.parse(
            lines(
                "美团",
                "订单详情",
                "订单号 8123456789012",
                "2024-10-20 12:31",
                "麦当劳(科技园店)",
                "巨无霸汉堡 ¥39.9",
                "实付 ¥26.4",
                "再来一单"
            )
        )!!

        assertEquals("麦当劳(科技园店)", parsed.storeName)
        assertEquals("巨无霸汉堡", parsed.productName)
    }

    /**
     * [10-b] 防回归：修「订单号被当店名」时，**不能**改成"含数字就否决店名"。
     *
     * `7-11便利店`、`3号门炸鸡` 都是含数字的合法店名，必须仍能被识别出来。
     * 这条用例是给 [OrderScreenshotParser] 里 NOISE_PREFIX 白名单式的修法上保险：
     * 一旦有人图省事写成"含数字且不含 ¥ 就 return false"，这里立刻红。
     */
    @Test
    fun `noise_lines_含数字的合法店名不能被误杀`() {
        val withHyphenDigit = parser.parse(
            lines(
                "7-11便利店",
                "关东煮 ¥12.9",
                "实付 ¥9.9"
            )
        )!!
        assertEquals("7-11便利店", withHyphenDigit.storeName)

        val withCjkDigit = parser.parse(
            lines(
                "3号门炸鸡",
                "鸡腿 ¥12.9",
                "实付 ¥9.9"
            )
        )!!
        assertEquals("3号门炸鸡", withCjkDigit.storeName)
    }

    /** [11] store_name_from_label：`店铺：XX炸鸡` → "XX炸鸡"（标签后取值，保留标签后的店名）。 */
    @Test
    fun `store_name_from_label_店铺标签取值`() {
        val parsed = parser.parse(
            lines(
                "店铺：XX炸鸡",
                "香辣鸡翅 ¥18.8",
                "实付 ¥15.8"
            )
        )!!

        assertEquals("XX炸鸡", parsed.storeName)
    }

    /** [12] empty_input_returns_null：空列表 → null；全空白行 → null 或 isEmpty。 */
    @Test
    fun `empty_input_空列表与全空白都算识别失败`() {
        assertNull(parser.parse(emptyList()))

        val blanks = parser.parse(lines("", "   ", "\t"))
        assertTrue(blanks == null || blanks.isEmpty)
    }

    /** [13] garbage_does_not_throw：乱码 / 纯数字 / emoji → 返回 null 或 isEmpty，绝不抛异常。 */
    @Test
    fun `garbage_乱码输入绝不抛异常`() {
        val cases = listOf(
            listOf("@@@@@", "####", "\$\$\$"),      // 纯符号
            listOf("123", "456.78", "0"),           // 纯数字
            listOf("\uD83C\uDF54"),                 // 单个 emoji（汉堡）
            listOf("a", "b"),                        // 极短拉丁字母
            listOf("¥", "¥.", "¥..")                 // 残缺金额符号
        )

        for (case in cases) {
            val parsed = parser.parse(case.map { TextLine(it) })
            assertTrue(
                "输入 $case 应返回 null 或 isEmpty，实际 = $parsed",
                parsed == null || parsed.isEmpty
            )
        }
    }

    /** [14] paid_upper_bound_correction：行内 `¥99.9 ¥39.9` + 实付 26.4 + 优惠 21.9 → 39.9。 */
    @Test
    fun `paid_upper_bound_划掉的原价不会被取走`() {
        val parsed = parser.parse(
            lines(
                "某某快餐",
                "招牌套餐 ¥99.9 ¥39.9",
                "已优惠 ¥21.9",
                "实付 ¥26.4"
            )
        )!!

        assertEquals(39.9, parsed.productPrice!!, 0.001)
    }

    /**
     * [14-b] 上界校验的**真实生效路径**：原价在行尾（`¥39.9 ¥99.9`），
     * 行内最后一个金额是 99.9 > 实付+优惠 的上界 48.31，必须回退到 39.9。
     *
     * 上一条用例里 39.9 本身就在上界内，等于没走到校验分支，所以这里单独补一条。
     */
    @Test
    fun `paid_upper_bound_行尾是原价时回退到折扣价`() {
        val parsed = parser.parse(
            lines(
                "某某快餐",
                "招牌套餐 ¥39.9 ¥99.9",
                "已优惠 ¥21.9",
                "实付 ¥26.4"
            )
        )!!

        assertEquals(39.9, parsed.productPrice!!, 0.001)
    }
}
