package com.example.foodexpense.ocr

import com.example.foodexpense.data.entity.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OrderScreenshotParser] 的**几何重建（列切分）**回归测试。
 *
 * ## 为什么单开一个文件
 * 原有的 `OrderScreenshotParserTest` 只有"干净文本"用例（一条 TextLine 就是一个语义行），
 * 所以 37 条全绿但真机全错 —— 真机上 ML Kit 会按视觉分栏切块，
 * 右对齐的价格变成独立 TextLine，商品行因此整体消失。
 *
 * 本文件的用例**全部使用真实形态的列切分输入**：同一视觉行的左右两部分是两条
 * 独立 TextLine（各自带 top / left / right / bottom），用来锁住新加的几何重建层。
 *
 * 坐标全部是**编造的合理值**（真机像素无法复现），但相对关系与真实截图一致：
 * - 行高 40px、行距 60px（相邻行垂直重叠为负，绝不能合并）
 * - 左栏 `left = 60`，右栏价格右对齐到 `right = 1000~1020`
 */
class OrderScreenshotParserGeometryTest {

    private val parser = OrderScreenshotParser()

    // ==================================================================
    // 坐标构造工具
    // ==================================================================

    private companion object {
        const val ROW_HEIGHT = 40
        const val LEFT_COL = 60
        const val RIGHT_EDGE = 1000
    }

    /** 一个视觉块：文本 + 水平区间。 */
    private data class Block(val text: String, val left: Int, val right: Int)

    /** 方块构造糖：左栏文本。 */
    private fun left(text: String, right: Int = 620) = Block(text, LEFT_COL, right)

    /** 方块构造糖：右栏右对齐的价格。 */
    private fun right(text: String, left: Int = 880) = Block(text, left, RIGHT_EDGE)

    /** 一行视觉行（同一 top / bottom）展开成若干条 TextLine。 */
    private fun row(top: Int, vararg blocks: Block): List<TextLine> = blocks.map {
        TextLine(
            text = it.text,
            left = it.left,
            top = top,
            right = it.right,
            bottom = top + ROW_HEIGHT
        )
    }

    /**
     * 美团「蜜雪冰城（秋雅雅苑店）」订单详情截图的**完整列切分输入**。
     *
     * 每条视觉行的左右两块都是独立 TextLine，与真机 ML Kit 的输出形态一致。
     */
    private fun meituanMiXueColumnSplit(): List<TextLine> {
        val rows = mutableListOf<List<TextLine>>()
        var y = 100

        fun add(vararg blocks: Block) {
            rows += row(y, *blocks)
            y += 60
        }

        add(left("12:03-12:18预计送达", 620))
        add(left("配送地址 中鹏程工业园(19 栋 2 楼 235...", 900))
        add(left("刘**(先生)182****0814", 520))
        add(left("支付方式/期望时间等 ⌄", 520))
        add(left("商品费用", 220))
        // 店铺行：左块是店名 + 直播水印，右块是运营按钮
        add(left("蜜雪冰城(秋雅雅苑店)lu 直播中 >", 700), Block("⊙ 进商家粉丝群", 760, 1020))
        // 商品行：名称与折后价被切成两条 TextLine
        add(left("[折] 三拼霸霸奶茶", 500), right("¥12.7"))
        // 规格行：带划线原价 ¥12.8（比商品价还高，绝不能抢走主商品）
        add(left("标准,正常冰,七分糖(推荐),换冷链... ⌄", 650), right("¥12.8"))
        add(left("x1", 120))
        // 第二个商品
        add(left("雪王小宝杯", 400), right("¥4", 920))
        add(left("标准", 160))
        add(left("x1", 120))
        add(left("打包费", 200), right("¥0.6", 920))
        add(left("用户配送费", 260), right("¥3.4", 880), Block("免配送费", 960, 1020))
        add(left("放心吃", 200), Block("商家赠送", 880, 1020))
        add(left("已用1张美团红包 ⌄", 420), right("-¥5", 940))
        add(left("合计", 160), Block("已优惠8.5元", 400, 620), right("¥12.3", 920))

        return rows.flatten()
    }

    // ==================================================================
    // 核心回归用例
    // ==================================================================

    /**
     * [real_meituan_column_split] 整张截图逐字段核对。
     *
     * 修复前的真实表现：storeName=粉丝群、productName=-¥5、productPrice=5、
     * actualPaid=5 —— 五个字段错了四个。
     */
    @Test
    fun `real_meituan_column_split`() {
        val parsed = parser.parse(meituanMiXueColumnSplit())!!

        assertEquals("蜜雪冰城(秋雅雅苑店)", parsed.storeName)
        assertEquals("三拼霸霸奶茶", parsed.productName)
        assertEquals(12.7, parsed.productPrice!!, 0.001)
        assertEquals(1, parsed.productQuantity)
        assertEquals(12.3, parsed.actualPaid!!, 0.001)
        // 两个真实商品：三拼霸霸奶茶 + 雪王小宝杯（P4-c 门禁因此不触发）
        assertEquals(2, parsed.itemCount)
        assertEquals(Platform.MEITUAN, parsed.platform)
    }

    /** [real_x1_as_separate_line] `x1` 独立成行时：数量仍为 1，且绝不能被当成店铺名。 */
    @Test
    fun `real_x1_as_separate_line`() {
        val rows = mutableListOf<List<TextLine>>()
        rows += row(100, left("肯德基(科技园店)", 600))
        rows += row(160, left("香酥鸡排", 400), right("¥15"))
        rows += row(220, left("x1", 120))
        rows += row(280, left("合计", 160), right("¥15", 920))

        val parsed = parser.parse(rows.flatten())!!

        assertEquals(1, parsed.productQuantity)
        assertEquals(1, parsed.itemCount)
        assertEquals("肯德基(科技园店)", parsed.storeName)
        assertEquals("香酥鸡排", parsed.productName)
        assertEquals(15.0, parsed.productPrice!!, 0.001)
        assertEquals(15.0, parsed.actualPaid!!, 0.001)
        assertNotEquals("x1", parsed.storeName)
    }

    /** [real_spec_line_not_item] 规格行的划线原价 ¥12.8 不得抢走主商品（¥12.7）。 */
    @Test
    fun `real_spec_line_not_item`() {
        val rows = mutableListOf<List<TextLine>>()
        rows += row(100, left("蜜雪冰城(秋雅雅苑店)", 700))
        rows += row(160, left("杨枝甘露", 400), right("¥12.7"))
        rows += row(220, left("标准,少冰,五分糖,加珍珠", 620), right("¥12.8"))
        rows += row(280, left("合计", 160), right("¥12.3", 920))

        val parsed = parser.parse(rows.flatten())!!

        assertEquals(1, parsed.itemCount)
        assertEquals("杨枝甘露", parsed.productName)
        assertEquals(12.7, parsed.productPrice!!, 0.001)
        assertEquals(12.3, parsed.actualPaid!!, 0.001)
    }

    /** [real_store_tail_trim] 店铺名只取最左块，并裁掉 `lu 直播中 >` 这类 UI 装饰。 */
    @Test
    fun `real_store_tail_trim`() {
        val rows = mutableListOf<List<TextLine>>()
        rows += row(100, left("蜜雪冰城(秋雅雅苑店)lu 直播中 >", 700))
        rows += row(160, left("三拼霸霸奶茶", 400), right("¥12.7"))
        rows += row(220, left("合计", 160), right("¥12.3", 920))

        val parsed = parser.parse(rows.flatten())!!

        assertEquals("蜜雪冰城(秋雅雅苑店)", parsed.storeName)
    }

    /** 店铺行右侧的按钮块独立成条 TextLine 时，同样不能污染店名。 */
    @Test
    fun `real_store_button_block_not_taken_as_store`() {
        val rows = mutableListOf<List<TextLine>>()
        rows += row(100, left("蜜雪冰城(秋雅雅苑店)", 700), Block("⊙ 进商家粉丝群", 760, 1020))
        rows += row(160, left("三拼霸霸奶茶", 400), right("¥12.7"))
        rows += row(220, left("合计", 160), right("¥12.3", 920))

        val parsed = parser.parse(rows.flatten())!!

        assertEquals("蜜雪冰城(秋雅雅苑店)", parsed.storeName)
    }

    /**
     * [degenerate_all_zero_coords] 所有 top / bottom 都是 0 时**跳过几何重建**，
     * 行为必须与重构前完全一致。
     *
     * 这里把同一张截图喂成"一条 TextLine 一个语义行"（坐标全 0）的形态，
     * 断言与列切分版本得到**同样**的五个字段 —— 证明重建层对
     * "已经是一行一条的输入"零副作用。
     */
    @Test
    fun `degenerate_all_zero_coords`() {
        val alreadySingleLine = listOf(
            "12:03-12:18预计送达",
            "配送地址 中鹏程工业园(19 栋 2 楼 235...",
            "刘**(先生)182****0814",
            "支付方式/期望时间等 ⌄",
            "商品费用",
            "蜜雪冰城(秋雅雅苑店)lu 直播中 > ⊙ 进商家粉丝群",
            "[折] 三拼霸霸奶茶 ¥12.7",
            "标准,正常冰,七分糖(推荐),换冷链... ⌄ ¥12.8",
            "x1",
            "雪王小宝杯 ¥4",
            "标准",
            "x1",
            "打包费 ¥0.6",
            "用户配送费 ¥3.4 免配送费",
            "放心吃 商家赠送",
            "已用1张美团红包 ⌄ -¥5",
            "合计 已优惠8.5元 ¥12.3"
        ).map { TextLine(it) }  // top / bottom / left / right 全默认 0

        val parsed = parser.parse(alreadySingleLine)!!

        assertEquals("蜜雪冰城(秋雅雅苑店)", parsed.storeName)
        assertEquals("三拼霸霸奶茶", parsed.productName)
        assertEquals(12.7, parsed.productPrice!!, 0.001)
        assertEquals(1, parsed.productQuantity)
        assertEquals(12.3, parsed.actualPaid!!, 0.001)
        assertEquals(2, parsed.itemCount)
    }

    /**
     * 上下相邻的商品行**绝不能**被合并成一行。
     *
     * 三行商品名与价格分别被切到左右两栏，若聚类把相邻行误合并，
     * 主商品名会变成「香辣鸡腿堡 薯条 可乐」这种拼接串。
     */
    @Test
    fun `real_adjacent_item_rows_not_merged`() {
        val rows = mutableListOf<List<TextLine>>()
        rows += row(100, left("肯德基(科技园店)", 600))
        rows += row(160, left("香辣鸡腿堡", 400), right("¥12.5"))
        rows += row(220, left("薯条(中)", 400), right("¥9.0"))
        rows += row(280, left("可乐(中)", 400), right("¥8.0"))
        rows += row(340, left("合计", 160), right("¥29.5", 920))

        val parsed = parser.parse(rows.flatten())!!

        assertEquals(3, parsed.itemCount)
        assertEquals("香辣鸡腿堡", parsed.productName)
        assertEquals(12.5, parsed.productPrice!!, 0.001)
        assertEquals(29.5, parsed.actualPaid!!, 0.001)
    }

    /**
     * 合一行里"优惠在实付之前"（`合计 已优惠8.5元 ¥12.3`）时，
     * 实付必须取 **12.3**，而不是被 `8.5元` 或整个丢弃。
     */
    @Test
    fun `real_total_with_discount_before_amount`() {
        val text = "合计 已优惠8.5元 ¥12.3"
        val stripped = OrderScreenshotParser.stripDiscountSegments(text)
        assertTrue("优惠整段必须被剥掉，实际 = $stripped", !stripped.contains("已优惠"))
        assertTrue("实付金额必须留下，实际 = $stripped", stripped.contains("12.3"))
        assertEquals(listOf(12.3), OrderScreenshotParser.amountsIn(stripped))
    }

    /** `8.5元` 这样的"元"后缀金额不能被当成实付金额取走。 */
    @Test
    fun `real_yuan_suffix_not_taken_as_paid`() {
        val rows = mutableListOf<List<TextLine>>()
        rows += row(100, left("蜜雪冰城(秋雅雅苑店)", 700))
        rows += row(160, left("三拼霸霸奶茶", 400), right("¥12.7"))
        rows += row(220, left("合计", 160), Block("已优惠8.5元", 400, 620), right("¥12.3", 920))

        val parsed = parser.parse(rows.flatten())!!

        assertEquals(12.3, parsed.actualPaid!!, 0.001)
        // discountTotal 取的是"标签紧邻的那个数" = 8.5，而不是行尾的 12.3
        assertEquals(8.5, parsed.discountTotal!!, 0.001)
    }

    /** 商品行前缀的活动标签 `[折]` 不能进商品名。 */
    @Test
    fun `real_product_tag_prefix_stripped`() {
        val rows = mutableListOf<List<TextLine>>()
        rows += row(100, left("蜜雪冰城(秋雅雅苑店)", 700))
        rows += row(160, left("[折] 三拼霸霸奶茶", 500), right("¥12.7"))
        rows += row(220, left("合计", 160), right("¥12.3", 920))

        val parsed = parser.parse(rows.flatten())!!

        assertEquals("三拼霸霸奶茶", parsed.productName)
    }

    // ==================================================================
    // 高块桥接（竖排 / 损坏框）防线
    // ==================================================================

    /**
     * **高块桥接不得吞掉商品**（防"静默错价"）。
     *
     * ## 这个 bug 的形状
     * `sameVisualLine` 的判据是 `overlap > 0.5 × min(hA, hB)` —— `min` 取**小块**高度，
     * 所以一个异常高的块是"过于宽容的连接器"，能把两条彼此垂直不重叠的语义行缝在一起。
     *
     * 本例构造（坐标照抄真机版式）：
     * ```
     * 损坏高块  20×180  top 1000..1180   ← 宽高比 9，真机实际出现过 20×176
     * 商品行    三拼霸霸奶茶(1020..1066) + ¥12.7
     * 规格行    标准,正常冰,…(1086..1128) + 412.8
     * ```
     * 商品行与规格行垂直重叠 = `1066 - 1086 = -20`，**本来绝不会合并**；
     * 但高块与两者分别重叠 46 / 42，全都过阈值 → 不切断就会被缝成一行。
     *
     * 缝上之后的后果：合并文本含规格行的逗号 → `isItemLine` 把**整条商品**否掉
     * → `itemCount` 少 1；若版式让价格块排到规格前面，则更糟 ——
     * `amounts` 会同时含 `¥12.7` 与划线价 `412.8`，**`productPrice` 静默变成 412.8**。
     *
     * 修复：宽高比 `height > 3 × width` 的块不参与聚类、自成一行（`isVerticalOrCorrupt`）。
     */
    @Test
    fun `geometry_高块桥接不会吞掉商品`() {
        val input = listOf(
            // 商品行（左名右价，正常重叠 42px，应合并）
            TextLine("三拼霸霸奶茶", 302, 1020, 589, 1066),
            TextLine("¥12.7", 1167, 1024, 1269, 1066),
            // 规格行：与商品行垂直不重叠（-20px），本应独立
            TextLine("标准,正常冰,七分糖(推荐),换冷链", 302, 1086, 1037, 1128),
            TextLine("412.8", 1177, 1090, 1273, 1122),
            // 第二个商品（保证 itemCount 有区分度）
            TextLine("雪王小宝杯", 278, 1247, 560, 1302),
            TextLine("¥4", 1224, 1263, 1269, 1299),
            // 损坏的高块：20 宽 × 180 高，横跨商品行与规格行
            TextLine("广告竖排", 242, 1000, 262, 1180),
            // 合计
            TextLine("合计", 72, 2031, 185, 2086),
            TextLine("¥12.3", 860, 2032, 1269, 2095)
        )

        val parsed = parser.parse(input)!!

        assertEquals(
            "高块桥接会把商品行与规格行缝成一行、含逗号后被否掉，itemCount 应为 2",
            2,
            parsed.itemCount
        )
        assertEquals("主商品仍是 12.7", 12.7, parsed.productPrice!!, 0.001)
        // 核心护栏：规格行的划线价绝不能被当成商品价（静默错数比丢商品更坏）
        assertNotEquals(
            "productPrice 绝不能变成规格行的划线价 412.8",
            412.8,
            parsed.productPrice!!,
            0.001
        )
        assertEquals("三拼霸霸奶茶", parsed.productName)
        assertEquals(12.3, parsed.actualPaid!!, 0.001)
    }

    /**
     * 真机 36 块里那三个"高块 + 两个小块"：高块自成一行，且**不被丢弃**。
     *
     * 真机原始坐标（`OrderScreenshotParserRealDeviceTest` 的第 34/35/36 行）：
     * ```
     * 国警平机支架 3斯圈  left=242 top=2590 right=262  bottom=2766   ← 20×176，宽高比 8.8
     * A堡主理            left=368 top=2599 right=512  bottom=2636
     * s服手机令          left=364 top=2629 right=496  bottom=2660
     * ```
     * `A堡主理` 与 `s服手机令` 彼此只重叠 7px（阈值 15.5）→ 本该不合并；
     * 靠那个 176px 高块才被缝在一起。修复后三者各自成行。
     *
     * 两点都要断：
     * 1. 它们都是底部广告，**不产生任何商品**；
     * 2. 高块**没有被剔除** —— 它仍然进入后续规则，所以能作为店名候选出现。
     *    （"自成一行"≠"丢弃"；万一它真是竖排的店名/商品名还要能识别出来。）
     */
    @Test
    fun `geometry_真机36块_高块自成行`() {
        val input = listOf(
            TextLine("国警平机支架 3斯圈", 242, 2590, 262, 2766),
            TextLine("A堡主理", 368, 2599, 512, 2636),
            TextLine("s服手机令", 364, 2629, 496, 2660)
        )

        val parsed = parser.parse(input)!!

        assertEquals("这三块都是广告，不得产生商品", 0, parsed.itemCount)
        assertNull("没有商品就没有商品名", parsed.productName)
        assertNull("没有商品就没有商品价", parsed.productPrice)
        assertNull("广告区不该产出实付", parsed.actualPaid)
        // 高块未被剔除的证据：它仍在 prepared 里，能作为店名候选出现
        assertEquals(
            "高块必须仍然参与后续规则（自成一行 ≠ 被丢弃）",
            "国警平机支架 3斯圈",
            parsed.storeName
        )
    }

    // ==================================================================
    // 横向重叠防线：两行高的"宽"块不得桥接
    // ==================================================================

    /**
     * **两行高的宽块不得桥接**（QA 探针 `probe_d` 的形状，原始坐标）。
     *
     * ## 为什么这是 P1，且高度类判据治不了
     * 高块 `限时秒杀` 是 **宽 598 × 高 92**（宽高比 0.15）—— 形状完全正常，
     * `isVerticalOrCorrupt`（>3 宽高比）直接放行。但 92px ≈ 两行高，足以当"桥"：
     *
     * ```
     * A 限时秒杀   1000..1092 (h=92)
     * B 三拼霸霸奶茶 1010..1056  与 A 重叠 46 > 23  ✓
     * C ¥12.7      1014..1056  与 A 重叠 42 > 21  ✓
     * D 标准,正常冰 1060..1102  与 A 重叠 32 > 21  ✓（与 B 重叠 -4，只能靠 A 进簇）
     * E ¥39.9      1064..1106  与 A 重叠 28 > 21  ✓
     * → 合并文本含逗号 → isItemLine 否掉整条商品
     * → items 只剩 雪王小宝杯(¥4) → itemCount=1
     * → P4-c 门限失效 → actualPaid = 4.0（真实 12.3）← 静默少记钱
     * ```
     *
     * 修复靠**横向重叠**：A 与 B 横向 100% 重叠（A 是 302..900、B 是 302..589）→
     * 判为上下堆叠、拒绝合并。而 B 与 C 横向重叠 -578（左右并排）→ 正常合并。
     */
    @Test
    fun `geometry_两行高宽块不做桥接`() {
        val input = listOf(
            TextLine("限时秒杀", 302, 1000, 900, 1092),          // 宽 598 × 高 92
            TextLine("三拼霸霸奶茶", 302, 1010, 589, 1056),
            TextLine("¥12.7", 1167, 1014, 1269, 1056),
            TextLine("标准,正常冰,七分糖", 302, 1060, 900, 1102),
            TextLine("¥39.9", 1167, 1064, 1269, 1106),
            TextLine("雪王小宝杯", 302, 1300, 560, 1346),
            TextLine("¥4", 1224, 1304, 1269, 1340),
            TextLine("合计", 72, 1400, 185, 1446),
            TextLine("¥12.3", 1169, 1404, 1269, 1446)
        )

        val parsed = parser.parse(input)!!

        assertEquals(
            "两件商品都得保住（桥接时只剩 1 件），实际 itemCount=${parsed.itemCount}",
            2,
            parsed.itemCount
        )
        assertEquals("三拼霸霸奶茶", parsed.productName)
        assertEquals(12.7, parsed.productPrice!!, 0.001)
        assertEquals("实付必须取合计行的 12.3", 12.3, parsed.actualPaid!!, 0.001)
        // 核心护栏：桥接时 itemCount 掉到 1，P4-c 兜底会把 4.0 当成整单实付（静默少记钱）
        assertNotEquals("actualPaid 绝不能变成某个商品单价 4.0", 4.0, parsed.actualPaid!!, 0.001)
    }

    /**
     * **商品名换行被合成一个高 TextLine 时，必须仍能与它的价格合并。**
     *
     * 这条专门保护"高度类判据"的误伤路径 —— 防止以后有人把横向判据改回
     * "包络高度上限"或"height > 2×中位行高就自成一行"之类的方案。
     *
     * ML Kit 常把两行商品名合成一个 h=92 的 `TextLine`，这是**完全正常**的：
     * ```
     * 名称块 巨无霸汉堡套餐  h=92  1010..1102
     * 价格   ¥12.7        h=42  1040..1082   ← 夹在名称块的纵向范围内
     * ```
     * 若按"包络高度 > 2×中位行高(42)=84 就拒绝合并"→ 会拆开 →
     * 名称块无金额、价格块 residue 为空 → **整条商品消失**。
     * 横向判据看的是"是否左右并排"（重叠 -578），与高度无关，所以正确合并。
     */
    @Test
    fun `geometry_商品名换行成高块仍能与价格合并`() {
        val input = listOf(
            TextLine("巨无霸汉堡套餐", 302, 1010, 589, 1102),   // 两行商品名合成一个块
            TextLine("¥12.7", 1167, 1040, 1269, 1082),          // 价格块纵向夹在其中
            TextLine("合计", 72, 1400, 185, 1446),
            TextLine("¥12.3", 1169, 1404, 1269, 1446)
        )

        val parsed = parser.parse(input)!!

        assertEquals("换行的高名称块必须仍算一件商品", 1, parsed.itemCount)
        assertEquals("巨无霸汉堡套餐", parsed.productName)
        assertEquals(12.7, parsed.productPrice!!, 0.001)
        assertEquals(12.3, parsed.actualPaid!!, 0.001)
    }

    /**
     * **真机 `合计` 行不得被误拆**：这是 factor 调小/改成高度判据时的第一条断链。
     *
     * 真机原始坐标：`合计`(2031..2086) + `已优惠8.5元¥12.3`(2032..2095)，包络 = 64。
     * 若用"包络 > 2×中位行高(42)=84"，64 < 84 侥幸能过；但 factor 一旦取 1.5（阈值 63），
     * 这一行就会被拆开 → `合计` 行没有金额 → `actualPaid` 直接归零。
     * 横向判据下两块重叠 -675（左右并排），永远合并。
     */
    @Test
    fun `geometry_合计行不被误拆_实付仍取12_3`() {
        val input = listOf(
            TextLine("合计", 72, 2031, 185, 2086),
            TextLine("已优惠8.5元¥12.3", 860, 2032, 1269, 2095)
        )

        val parsed = parser.parse(input)!!

        assertEquals("合计行的实付必须取到", 12.3, parsed.actualPaid!!, 0.001)
        assertEquals("优惠额取标签紧邻的 8.5，不是行尾的 12.3", 8.5, parsed.discountTotal!!, 0.001)
    }

    /**
     * **长商品名不得被长度闸门误杀**（QA 第三轮 `G2`，原坐标，`h` 改为单行正常高度）。
     *
     * `超大份双人套餐(含可乐薯条鸡翅)` 的左侧文本是 **16 字**，属于完全正常的商品名。
     * 长度闸门原值 `MAX_ITEM_LEFT_LEN = 14` 会把它整行否掉 →
     * `itemCount=0`、`productName=null`，用户看到"商品未识别"。
     *
     * 长度闸门本身是**冗余**的：规格行已经被"含逗号"和 `SPEC_HINTS` 两道拦住，
     * 再叠一个 14 字上限收益接近零、代价是误杀长名字。现放宽到 30。
     *
     * 说明：这里把名称块高度从 QA 原始的 92 改成 46，是为了让**失败原因单一** ——
     * 只可能是长度闸门，而不是"两行高块"的几何问题（那个由
     * `geometry_商品名换行成高块仍能与价格合并` 单独覆盖）。
     */
    @Test
    fun `real_长商品名不被长度闸门误杀`() {
        val input = listOf(
            TextLine("超大份双人套餐(含可乐薯条鸡翅)", 302, 1000, 1100, 1046),
            TextLine("¥88.8", 1167, 1014, 1269, 1056),
            TextLine("合计", 72, 1400, 185, 1446),
            TextLine("¥88.8", 1169, 1404, 1269, 1446)
        )

        val parsed = parser.parse(input)!!

        assertEquals("16 字的正常商品名不得被整行否掉", 1, parsed.itemCount)
        assertEquals(88.8, parsed.productPrice!!, 0.001)
        assertEquals(88.8, parsed.actualPaid!!, 0.001)
        // 括号内容必须**完整保留**：`(含可乐薯条鸡翅)` 是商品名的一部分，不是规格。
        // （normalize 会把全角括号转半角，所以这里断言半角。）
        assertEquals(
            "商品名必须完整保留括号内的成分说明",
            "超大份双人套餐(含可乐薯条鸡翅)",
            parsed.productName
        )
    }

    // ==================================================================
    // 括号片段：只删"像规格"的，别吃商品名
    // ==================================================================

    /**
     * 括号里是「数字 + 单位」→ 是规格，删掉。
     *
     * 注意这条实际走的是**空内容分支**：`stripNonMoneyNumbers` 会先把 `200克`
     * 整段替换成空格，所以到 `cleanProductName` 时括号里已经只剩空格。
     */
    @Test
    fun `paren_数字加单位是规格_删掉`() {
        val parsed = parser.parse(
            listOf(
                TextLine("巨无霸汉堡(200克)", 302, 1000, 700, 1046),
                TextLine("¥39.9", 1167, 1014, 1269, 1056)
            )
        )!!

        assertEquals(1, parsed.itemCount)
        assertEquals("巨无霸汉堡", parsed.productName)
        assertEquals(39.9, parsed.productPrice!!, 0.001)
    }

    /** 括号里只有 1 个字符（`中` / `大` / `小`）→ 是规格（薯条规格），删掉。 */
    @Test
    fun `paren_单字符是规格_删掉`() {
        val parsed = parser.parse(
            listOf(
                TextLine("薯条(中)", 302, 1000, 500, 1046),
                TextLine("¥9", 1167, 1014, 1269, 1056)
            )
        )!!

        assertEquals(1, parsed.itemCount)
        assertEquals("薯条", parsed.productName)
        assertEquals(9.0, parsed.productPrice!!, 0.001)
    }

    /**
     * 括号里**整体**等于某个规格词（`推荐`）→ 删掉。
     *
     * 与上一条 `含可乐薯条鸡翅` 配对：证明判据是"整体相等"而不是 `contains` ——
     * 否则 `(含推荐酱)` 这种商品名说明也会被误删。
     */
    @Test
    fun `paren_整词命中规格词_删掉`() {
        val parsed = parser.parse(
            listOf(
                TextLine("超值套餐(推荐)", 302, 1000, 700, 1046),
                TextLine("¥25", 1167, 1014, 1269, 1056)
            )
        )!!

        assertEquals(1, parsed.itemCount)
        assertEquals("超值套餐", parsed.productName)
        assertEquals(25.0, parsed.productPrice!!, 0.001)
    }
}
