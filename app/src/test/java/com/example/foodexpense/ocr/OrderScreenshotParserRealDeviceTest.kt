package com.example.foodexpense.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OrderScreenshotParser] 的**真机 OCR 原始输出**回归测试。
 *
 * ## 数据来源
 * 美团「蜜雪冰城」订单详情截图，在真机上跑 ML Kit bundled 中文识别后，
 * 由 POC 调试页「复制识别原文（含坐标）」按钮导出的 36 行原始输出。
 * **坐标不是编造的**，是那台机型的真实像素值，可在
 * `OrderScreenshotParserGeometryTest`（编造坐标）之外提供第二层保障。
 *
 * ## 为什么单开一个文件
 * `OrderScreenshotParserGeometryTest` 的坐标是我按"行高 40 / 行距 60"的理想值编的，
 * 只能证明算法在理想情况下成立。真机数据暴露了三类理想值下根本不会出现的问题：
 * 1. 顶部状态栏水印 `C684` 会被"自上而下取第一条合理行"的店铺名启发式选中；
 * 2. `折` 被单独切成一块，合并后黏在商品名前面；
 * 3. `¥4` 的 `¥` 被整个漏识别，只剩裸整数 `4`，导致商品被丢弃。
 *
 * ## 关于字符误识
 * `秋雅`→`秋园`、`¥12.8`→`412.8`、`¥4`→`4`、`方式`→`万式` 都是 ML Kit 的字符误识，
 * **不是解析器 bug**。断言一律按 OCR 的**实际文本**写，不按人眼看到的文本写。
 */
class OrderScreenshotParserRealDeviceTest {

    private val parser = OrderScreenshotParser()

    // ==================================================================
    // 真机原始 36 行（导出的原文，原样粘贴，坐标一字未改）
    // ==================================================================

    /**
     * 真机导出的 36 条 TextLine。
     *
     * 格式为 `文本|left,top,right,bottom`，与 POC 页导出的顺序一致。
     */
    private fun realDeviceLines(): List<TextLine> = listOf(
        tl("C684", 1160, 56, 1280, 97),
        tl("11:41", 464, 61, 576, 98),
        tl("く12:03-12:18预计送达", 74, 221, 635, 272),
        tl("今日领券", 1027, 343, 1247, 402),
        tl("配送地址中鹏程工业园(19栋2楼235)", 72, 394, 950, 437),
        tl("刘*(先生)182****0814", 307, 461, 820, 507),
        tl("支付万式期望时间等v", 452, 581, 886, 620),
        tl("商品费用", 72, 773, 287, 824),
        tl("e进商家粉丝群", 973, 905, 1272, 947),
        tl("蜜雪冰城(秋园雅苑店) h直播中>", 72, 906, 683, 946),
        tl("三拼霸霸奶茶", 302, 1020, 589, 1066),
        tl("¥12.7", 1167, 1024, 1269, 1066),
        tl("折", 83, 1034, 113, 1069),
        tl("标准,正常冰,七分糖(推荐),換冷链...", 302, 1086, 1037, 1128),
        tl("412.8", 1177, 1090, 1273, 1122),
        tl("x1", 302, 1155, 342, 1186),
        tl("雪王小宝杯", 278, 1247, 560, 1302),
        tl("4", 1224, 1263, 1269, 1299),
        tl("标准", 302, 1321, 383, 1360),
        tl("x1", 302, 1390, 342, 1421),
        tl("打包费", 72, 1509, 213, 1554),
        tl("¥0.6", 1169, 1512, 1272, 1554),
        tl("用戶配送费", 73, 1620, 311, 1666),
        tl("34免配送费", 1004, 1621, 1271, 1667),
        tl("放心吃", 69, 1732, 208, 1778),
        tl("商家赠送", 1097, 1737, 1272, 1779),
        tl("已用1张美团红包ン", 75, 1851, 476, 1897),
        tl("-¥5", 1202, 1854, 1271, 1895),
        tl("合计", 72, 2031, 185, 2086),
        tl("已优惠8.5元¥12.3", 860, 2032, 1269, 2095),
        tl("点击收起、", 566, 2147, 772, 2186),
        tl("为汉堡主理鹅登场", 151, 2363, 896, 2465),
        tl("立即参与》", 1004, 2392, 1218, 2435),
        tl("国警平机支架 3斯圈", 242, 2590, 262, 2766),
        tl("A堡主理", 368, 2599, 512, 2636),
        tl("s服手机令", 364, 2629, 496, 2660)
    )

    private fun tl(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        TextLine(text = text, left = left, top = top, right = right, bottom = bottom)

    // ==================================================================
    // 1. 全量真机数据
    // ==================================================================

    /**
     * 36 行真机数据 → 七个字段全部正确。
     *
     * 修复前的三处错误（都在这一条里有体现）：
     * - `storeName` = `C684`（顶部状态栏水印，比店铺行更靠上）
     * - `productName` = `折 三拼霸霸奶茶`（裸角标没剥）
     * - `itemCount` = 1（`¥4` 的 `¥` 被漏识别，`雪王小宝杯` 整条丢失）
     */
    @Test
    fun `real_meituan_2026_10_24_full`() {
        val parsed = parser.parse(realDeviceLines())!!

        // 注意：OCR 把「秋雅」认成「秋园」，断言按 OCR 实际文本
        assertEquals("蜜雪冰城(秋园雅苑店)", parsed.storeName)
        assertEquals("三拼霸霸奶茶", parsed.productName)
        assertEquals(12.7, parsed.productPrice!!, 0.001)
        assertEquals(1, parsed.productQuantity)
        assertEquals(12.3, parsed.actualPaid!!, 0.001)
        // 三拼霸霸奶茶 + 雪王小宝杯；打包费/配送费/放心吃/红包/合计都不算
        assertEquals(2, parsed.itemCount)
        assertEquals(8.5, parsed.discountTotal!!, 0.001)
    }

    // ==================================================================
    // 2. 顶部状态栏水印不得被当成店铺名
    // ==================================================================

    /**
     * 只留顶部水印 + 一个商品行：店铺名必须为 null，绝不能是 `C684`。
     *
     * `C684` 能通过 `isPlausibleStoreLine`（长度 4、含字母 C、含数字但无 ¥、无排除词），
     * 所以"自上而下取第一条"必然选中它。修法是**从商品行往上扫取最近的一条**：
     * `今日领券` 是促销横幅（含"领券"，已在 STORE_EXCLUDE 里），被否掉；
     * 再往上 `11:41` / `C684` 的候选块 `11:41` 不含任何字母与汉字，被否掉。
     * 于是诚实地返回 null（"猜不到就不猜"），而不是填一个假店名。
     */
    @Test
    fun `real_status_bar_not_taken_as_store`() {
        val input = listOf(
            tl("C684", 1160, 56, 1280, 97),
            tl("11:41", 464, 61, 576, 98),
            tl("今日领券", 1027, 343, 1247, 402),
            tl("三拼霸霸奶茶", 302, 1020, 589, 1066),
            tl("¥12.7", 1167, 1024, 1269, 1066)
        )

        val parsed = parser.parse(input)!!

        assertNotEquals("顶部水印绝不能当店铺名", "C684", parsed.storeName)
        assertNotEquals("促销横幅不能当店铺名", "今日领券", parsed.storeName)
        assertNull("没有任何像店名的行时应返回 null，而不是硬猜", parsed.storeName)
        // 商品本身仍要正常识别，降级必须是局部的
        assertEquals("三拼霸霸奶茶", parsed.productName)
        assertEquals(12.7, parsed.productPrice!!, 0.001)
    }

    /** 有真店铺行时，仍然要选中真店铺行（而不是顶部水印）。 */
    @Test
    fun `real_status_bar_skipped_when_store_line_present`() {
        val input = listOf(
            tl("C684", 1160, 56, 1280, 97),
            tl("11:41", 464, 61, 576, 98),
            tl("商品费用", 72, 773, 287, 824),
            tl("蜜雪冰城(秋园雅苑店) h直播中>", 72, 906, 683, 946),
            tl("三拼霸霸奶茶", 302, 1020, 589, 1066),
            tl("¥12.7", 1167, 1024, 1269, 1066)
        )

        val parsed = parser.parse(input)!!

        assertEquals("蜜雪冰城(秋园雅苑店)", parsed.storeName)
    }

    // ==================================================================
    // 3. 右对齐裸整数价格
    // ==================================================================

    /**
     * `¥4` 被 OCR 漏掉 `¥` 只剩 `4` 时，这件商品**不能丢**。
     *
     * 这是 P0：`itemCount` 低估会让 P4-c 兜底在"多商品 + 无合计行"场景下
     * 把某个商品单价当成整单实付 —— 静默少记钱。
     */
    @Test
    fun `real_bare_integer_price_counted`() {
        val input = listOf(
            tl("雪王小宝杯", 278, 1247, 560, 1302),
            tl("4", 1224, 1263, 1269, 1299),
            tl("合计", 72, 2031, 185, 2086),
            tl("¥4", 1160, 2032, 1269, 2095)
        )

        val parsed = parser.parse(input)!!

        assertEquals("裸整数价格必须让商品成立", 1, parsed.itemCount)
        assertEquals("雪王小宝杯", parsed.productName)
        assertEquals(4.0, parsed.productPrice!!, 0.001)
        assertEquals(4.0, parsed.actualPaid!!, 0.001)
    }

    /**
     * 但**左对齐**的裸整数（`x1`，right=342）不是金额。
     *
     * 判据是"右对齐"：价格列在页面右侧，数量/规格在左侧。
     * 若写成"任何行尾裸整数都算金额"，`x1` 会被当商品，`itemCount` 又会虚高。
     */
    @Test
    fun `real_left_aligned_number_not_amount`() {
        val input = listOf(
            tl("三拼霸霸奶茶", 302, 1020, 589, 1066),
            tl("¥12.7", 1167, 1024, 1269, 1066),
            tl("x1", 302, 1155, 342, 1186),
            tl("合计", 72, 2031, 185, 2086),
            tl("¥12.7", 1160, 2032, 1269, 2095)
        )

        val parsed = parser.parse(input)!!

        assertEquals("左对齐的 x1 不得成为商品行", 1, parsed.itemCount)
        assertEquals("三拼霸霸奶茶", parsed.productName)
        assertEquals(12.7, parsed.productPrice!!, 0.001)
        // x1 也不是数量后缀（它是独立行，没并进商品行）→ 数量取默认 1
        assertEquals(1, parsed.productQuantity)
    }

    /** 时间 `11:41`（right=576）也不得被当成金额。 */
    @Test
    fun `real_clock_not_amount`() {
        val input = listOf(
            tl("11:41", 464, 61, 576, 98),
            tl("三拼霸霸奶茶", 302, 1020, 589, 1066),
            tl("¥12.7", 1167, 1024, 1269, 1066)
        )

        val parsed = parser.parse(input)!!

        assertEquals(1, parsed.itemCount)
        assertEquals("三拼霸霸奶茶", parsed.productName)
        assertNull("11:41 不得成为任何商品的价格", parsed.productPrice?.takeIf { it == 41.0 })
    }

    // ==================================================================
    // 4. 行首裸角标
    // ==================================================================

    /**
     * 裸 `折` 角标（真机被单独切块，left=83）不得进商品名；
     * 而 `新品奶茶` 这种以活动字开头的**真商品名**不得被剥成 `奶茶`。
     */
    @Test
    fun `real_product_tag_stripped`() {
        // (a) 真机形态：`折` 独立块 + 商品名 + 价格
        val withTag = parser.parse(
            listOf(
                tl("折", 83, 1034, 113, 1069),
                tl("三拼霸霸奶茶", 302, 1020, 589, 1066),
                tl("¥12.7", 1167, 1024, 1269, 1066)
            )
        )!!
        assertEquals("三拼霸霸奶茶", withTag.productName)

        // (b) 反例：`新品奶茶` 的「新」后面紧跟「品」，不是独立 token → 不得剥离
        val realProductName = parser.parse(
            listOf(
                tl("新品奶茶", 302, 1020, 589, 1066),
                tl("¥8", 1167, 1024, 1269, 1066)
            )
        )!!
        assertEquals("新品奶茶", realProductName.productName)

        // (c) 反例：`折后套餐` 的「折」后面紧跟「后」→ 不得剥离
        //     注意这里不能用 `折扣套餐`：`折扣` 本身是 ITEM_EXCLUDE 词（优惠行），
        //     那样测到的是"优惠行不算商品"，测不到"角标剥离"。
        val discountWord = parser.parse(
            listOf(
                tl("折后套餐", 302, 1020, 589, 1066),
                tl("¥18", 1167, 1024, 1269, 1066)
            )
        )!!
        assertEquals("折后套餐", discountWord.productName)
    }

    // ==================================================================
    // 5. 回归护栏：真机数据里"本来就对"的部分不能被改坏
    // ==================================================================

    /**
     * 真机数据里已经验证正确的几个点，单独抽出固化，避免后续改动无声破坏。
     *
     * - `三拼霸霸奶茶`(1020-1066) 与 `¥12.7`(1024-1066) 必须合并（重叠 42 > 21）
     * - 规格行 `标准,正常冰,...` + `412.8` 合并后因含逗号被排除，不得抢走主商品
     * - `已用1张美团红包ン` + `-¥5` 合并后含"红包"被排除
     */
    @Test
    fun `real_spec_and_coupon_rows_still_excluded`() {
        val parsed = parser.parse(realDeviceLines())!!

        // 规格行的 412.8（OCR 把 ¥12.8 认成 412.8）绝不能抢走主商品 12.7
        assertEquals(12.7, parsed.productPrice!!, 0.001)
        assertTrue(
            "规格行不得进入商品列表（itemCount 只能是 2），实际 = ${parsed.itemCount}",
            parsed.itemCount == 2
        )
    }

    /**
     * 店铺行里同时有直播水印 `h` 与右侧按钮块，必须裁干净。
     *
     * 真机文本是 `蜜雪冰城(秋园雅苑店) h直播中>`（左块）+ `e进商家粉丝群`（右块）。
     */
    @Test
    fun `real_store_line_tail_trimmed`() {
        val parsed = parser.parse(
            listOf(
                tl("e进商家粉丝群", 973, 905, 1272, 947),
                tl("蜜雪冰城(秋园雅苑店) h直播中>", 72, 906, 683, 946),
                tl("三拼霸霸奶茶", 302, 1020, 589, 1066),
                tl("¥12.7", 1167, 1024, 1269, 1066)
            )
        )!!

        assertEquals("蜜雪冰城(秋园雅苑店)", parsed.storeName)
    }
}
