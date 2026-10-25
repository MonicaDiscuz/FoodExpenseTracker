package com.example.foodexpense.viewmodel

import com.example.foodexpense.data.entity.OrderItem
import com.example.foodexpense.data.entity.Platform
import com.example.foodexpense.ocr.ParsedOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「识别结果 → 表单」的合并契约。
 *
 * ## 为什么这几条必须钉死
 * 用户对这个功能的全部信任，都压在这句话上：**识别只填空，不会动我手动输入的内容**。
 * 一旦破掉，用户填了一半的表单被识别结果冲掉，就是直接的数据丢失感。
 *
 * 反过来说，这条规则坏了**没有任何异常、没有任何日志**——表单里就是少了几行字。
 * 没有测试的话，它可以坏好几个月都没人发现。
 *
 * 这些用例全部是纯 JVM（[RecordFormState] / [ParsedOrder] 都不含 Android 类型），
 * 所以不需要 Robolectric，跑得很快。
 */
class RecordFormMergeTest {

    private fun parsed(
        storeName: String? = null,
        productName: String? = null,
        productPrice: Double? = null,
        quantity: Int = 1,
        actualPaid: Double? = null,
        platform: Platform? = null,
        itemCount: Int = 0,
        items: List<OrderItem> = emptyList()
    ) = ParsedOrder(
        storeName = storeName,
        productName = productName,
        productPrice = productPrice,
        productQuantity = quantity,
        actualPaid = actualPaid,
        platform = platform,
        itemCount = itemCount,
        items = items
    )

    // ------------------------------------------------------------------
    // 核心契约：只填空，不覆盖
    // ------------------------------------------------------------------

    @Test
    fun `空表单_识别结果全部填上`() {
        val out = mergeParsedInto(
            s = RecordFormState(),
            p = parsed(
                storeName = "蜜雪冰城",
                productName = "三拼霸霸奶茶",
                productPrice = 12.7,
                actualPaid = 12.3,
                platform = Platform.TAOBAO_FLASH
            ),
            source = "云端"
        )

        assertEquals("蜜雪冰城", out.storeName)
        assertEquals("三拼霸霸奶茶", out.productName)
        assertEquals("12.7", out.priceText)
        assertEquals("12.3", out.paidText)
        assertEquals(Platform.TAOBAO_FLASH, out.platform)
    }

    /**
     * 最关键的一条：用户已经输入的四个文本字段，识别结果**一个字都不能改**。
     */
    @Test
    fun `已填字段一个字都不被覆盖`() {
        val before = RecordFormState(
            storeName = "我手填的店",
            productName = "我手填的商品",
            priceText = "99.99",
            paidText = "88.88"
        )

        val out = mergeParsedInto(
            s = before,
            p = parsed(
                storeName = "识别出的店",
                productName = "识别出的商品",
                productPrice = 12.7,
                actualPaid = 12.3
            ),
            source = "云端"
        )

        assertEquals("手填店铺名被覆盖了", "我手填的店", out.storeName)
        assertEquals("手填商品名被覆盖了", "我手填的商品", out.productName)
        assertEquals("手填价格被覆盖了", "99.99", out.priceText)
        assertEquals("手填实付被覆盖了", "88.88", out.paidText)
    }

    /** 部分填写时，只补空的那几个。 */
    @Test
    fun `部分填写_只补空字段`() {
        val out = mergeParsedInto(
            s = RecordFormState(storeName = "手填店名"),
            p = parsed(storeName = "识别店名", productName = "识别商品", productPrice = 20.0),
            source = "本地"
        )

        assertEquals("手填店名", out.storeName)
        assertEquals("识别商品", out.productName)
        assertEquals("20", out.priceText)
    }

    /** 明细同样只填空：用户删改过的明细不能被新的识别结果冲掉。 */
    @Test
    fun `已填明细不被覆盖_空明细才接收`() {
        val mine = listOf(OrderItem("我自己留的", 1.0, 1))
        val recognized = listOf(OrderItem("识别的A", 2.0, 1), OrderItem("识别的B", 3.0, 2))

        val kept = mergeParsedInto(RecordFormState(items = mine), parsed(items = recognized), "云端")
        assertEquals("用户已改过的明细被覆盖了", mine, kept.items)

        val filled = mergeParsedInto(RecordFormState(), parsed(items = recognized), "云端")
        assertEquals(recognized, filled.items)
    }

    // ------------------------------------------------------------------
    // 默认值语义：只有"还是默认值"才允许被覆盖
    // ------------------------------------------------------------------

    @Test
    fun `数量为默认1时才被识别值覆盖`() {
        val default = mergeParsedInto(RecordFormState(quantity = 1), parsed(quantity = 3), "云端")
        assertEquals("默认 1 应被识别出的 3 覆盖", 3, default.quantity)

        val chosen = mergeParsedInto(RecordFormState(quantity = 5), parsed(quantity = 3), "云端")
        assertEquals("用户已选 5，不能被识别值改成 3", 5, chosen.quantity)
    }

    @Test
    fun `数量识别为1时不改动表单`() {
        val out = mergeParsedInto(RecordFormState(quantity = 1), parsed(quantity = 1), "云端")
        assertEquals(1, out.quantity)
    }

    @Test
    fun `平台为默认美团时才被识别值覆盖`() {
        val default = mergeParsedInto(
            RecordFormState(platform = Platform.MEITUAN),
            parsed(platform = Platform.JD),
            "云端"
        )
        assertEquals(Platform.JD, default.platform)

        // 用户主动选了京东，识别成美团时不能被改回去
        val chosen = mergeParsedInto(
            RecordFormState(platform = Platform.JD),
            parsed(platform = Platform.MEITUAN),
            "云端"
        )
        assertEquals("用户主动选的平台不能被识别结果改掉", Platform.JD, chosen.platform)
    }

    /** 一个字段都没识别到也不能把用户填的内容清成空串。 */
    @Test
    fun `识别结果全为空_表单内容原样保留`() {
        val before = RecordFormState(
            storeName = "店", productName = "商品", priceText = "10", paidText = "9"
        )

        val out = mergeParsedInto(before, parsed(), "云端")

        assertEquals("店", out.storeName)
        assertEquals("商品", out.productName)
        assertEquals("10", out.priceText)
        assertEquals("9", out.paidText)
    }

    // ------------------------------------------------------------------
    // 状态复位与错误透传
    // ------------------------------------------------------------------

    @Test
    fun `合并后清掉识别中状态与错误`() {
        val out = mergeParsedInto(
            RecordFormState(isRecognizing = true, recognizeError = "上次识别失败"),
            parsed(productName = "商品"),
            "云端"
        )

        assertFalse("识别结束必须复位 isRecognizing，否则按钮永远转圈", out.isRecognizing)
        assertNull("旧的错误提示必须清掉", out.recognizeError)
    }

    /** 云端失败原因必须透传到界面（降级可见，否则用户以为识别本来就不准）。 */
    @Test
    fun `云端失败原因被透传`() {
        val out = mergeParsedInto(
            RecordFormState(),
            parsed(productName = "商品"),
            source = "本地",
            cloudError = "HTTP 401：Invalid API key"
        )

        assertEquals("HTTP 401：Invalid API key", out.cloudError)
    }

    @Test
    fun `云端成功时 cloudError 为 null`() {
        val out = mergeParsedInto(RecordFormState(), parsed(productName = "商品"), "云端")
        assertNull(out.cloudError)
    }

    // ------------------------------------------------------------------
    // 核对提示文案
    // ------------------------------------------------------------------

    @Test
    fun `提示带来源前缀`() {
        val cloud = buildRecognizeHint(parsed(productName = "可乐"), "云端")
        val local = buildRecognizeHint(parsed(productName = "可乐"), "本地")

        assertTrue("必须让用户看出这次走的是哪条路：$cloud", cloud.startsWith("云端识别："))
        assertTrue("必须让用户看出这次走的是哪条路：$local", local.startsWith("本地识别："))
    }

    @Test
    fun `多商品时提示取了主商品并说明明细件数`() {
        val hint = buildRecognizeHint(
            parsed(
                productName = "三拼霸霸奶茶",
                itemCount = 3,
                items = listOf(
                    OrderItem("三拼霸霸奶茶", 12.7, 1),
                    OrderItem("冰淇淋", 4.0, 2),
                    OrderItem("珍珠", 1.5, 1)
                )
            ),
            "云端"
        )

        assertTrue("应说明商品数与取的是哪件：$hint", hint.contains("3 个商品"))
        assertTrue("应点出主商品名：$hint", hint.contains("三拼霸霸奶茶"))
        assertTrue("应说明明细保留了几件：$hint", hint.contains("已保留 3 件商品明细"))
    }

    @Test
    fun `单商品时不提多商品分支`() {
        val hint = buildRecognizeHint(
            parsed(productName = "拌面", storeName = "沙县小吃", itemCount = 1),
            "本地"
        )

        assertFalse("单商品不该出现「多个商品」的说法：$hint", hint.contains("个商品"))
        assertTrue(hint.contains("拌面"))
        assertTrue("单商品提示应带店铺名：$hint", hint.contains("沙县小吃"))
    }

    @Test
    fun `未识别字段被逐项列出`() {
        val hint = buildRecognizeHint(parsed(productName = "只有商品名"), "云端")

        assertTrue("缺店铺名应被列出：$hint", hint.contains("店铺名"))
        assertTrue("缺价格应被列出：$hint", hint.contains("商品价格"))
        assertTrue("缺实付应被列出：$hint", hint.contains("实付金额"))
        assertFalse("商品名识别到了就不该列为缺失：$hint", hint.contains("商品名、"))
    }

    @Test
    fun `全识别到时不再出现未识别字样`() {
        val hint = buildRecognizeHint(
            parsed(storeName = "店", productName = "商品", productPrice = 10.0, actualPaid = 9.0),
            "云端"
        )

        assertFalse("四个字段都识别到了，不该出现「未识别」：$hint", hint.contains("未识别"))
    }

    @Test
    fun `用量摘要被附加到提示末尾`() {
        val hint = buildRecognizeHint(parsed(productName = "可乐"), "云端", usage = "1261+794 tokens")

        assertTrue("用量应显示出来（用于核对成本）：$hint", hint.contains("1261+794 tokens"))
    }

    @Test
    fun `没用量时提示不出现空括号`() {
        val hint = buildRecognizeHint(parsed(productName = "可乐"), "云端", usage = null)
        assertFalse("没有用量就不该出现空括号：$hint", hint.contains("（）"))
    }

    // ------------------------------------------------------------------
    // 价格显示格式
    // ------------------------------------------------------------------

    @Test
    fun `整数价格不显示小数点`() {
        assertEquals("12", formatPriceInput(12.0))
        assertEquals("12.7", formatPriceInput(12.7))
        assertEquals("0.5", formatPriceInput(0.5))
    }
}
