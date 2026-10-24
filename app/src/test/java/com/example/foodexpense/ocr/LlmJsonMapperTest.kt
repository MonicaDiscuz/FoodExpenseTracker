package com.example.foodexpense.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LlmJsonMapper] 的回归用例。
 *
 * 这个类是把 LLM 的自然语言输出变成结构化数据的**唯一关口**：它解析错一位，
 * 表单里就是错一个金额。而 LLM 的输出格式并不总是一丝不苟（会带 ```json 包裹、
 * 会给 null、会给多余字段），所以这里逐形态锁死。
 */
class LlmJsonMapperTest {

    @Test
    fun `完整JSON_全部字段正确映射`() {
        val raw = """
            {"storeName":"蜜雪冰城(秋园雅苑店)","productName":"三拼霸霸奶茶",
             "productPrice":12.7,"quantity":1,"actualPaid":12.3,
             "platform":"MEITUAN","itemCount":2,"discountTotal":8.5,"notes":null}
        """.trimIndent()

        val parsed = LlmJsonMapper.toParsedOrder(raw)

        assertNotNull(parsed)
        assertEquals("蜜雪冰城(秋园雅苑店)", parsed!!.storeName)
        assertEquals("三拼霸霸奶茶", parsed.productName)
        assertEquals(12.7, parsed.productPrice!!, 0.001)
        assertEquals(1, parsed.productQuantity)
        assertEquals(12.3, parsed.actualPaid!!, 0.001)
        assertEquals("美团", parsed.platform?.displayName)
        assertEquals(2, parsed.itemCount)
        assertEquals(8.5, parsed.discountTotal!!, 0.001)
    }

    @Test
    fun `markdown包裹的JSON_也能解析`() {
        val raw = """
            ```json
            {"storeName":"肯德基","productName":"香辣鸡腿堡","productPrice":19.5,
             "quantity":2,"actualPaid":36.0,"platform":null,"itemCount":1,"notes":"数量存疑"}
            ```
        """.trimIndent()

        val parsed = LlmJsonMapper.toParsedOrder(raw)

        assertNotNull("LLM 经常用代码块包裹 JSON，必须兼容", parsed)
        assertEquals("肯德基", parsed!!.storeName)
        assertEquals(2, parsed.productQuantity)
        assertEquals(36.0, parsed.actualPaid!!, 0.001)
        assertNull("platform 为 null 时应映射为 Kotlin null", parsed.platform)
    }

    @Test
    fun `字段缺失或为null_不崩且其余字段保留`() {
        val raw = """{"storeName":null,"productName":"薯条","productPrice":9.0,"actualPaid":null}"""

        val parsed = LlmJsonMapper.toParsedOrder(raw)

        assertNotNull(parsed)
        assertNull(parsed!!.storeName)
        assertEquals("薯条", parsed.productName)
        assertNull("actualPaid 缺失 → null，由 paidAmount 回退到商品小计", parsed.actualPaid)
        assertEquals("quantity 缺失时按 1 计", 1, parsed.productQuantity)
        assertEquals(0, parsed.itemCount)
    }

    @Test
    fun `数量越界_被夹到合法区间`() {
        val raw = """{"productName":"可乐","productPrice":3.0,"quantity":99,"actualPaid":3.0}"""
        assertEquals(99, LlmJsonMapper.toParsedOrder(raw)!!.productQuantity)

        val tooMany = """{"productName":"可乐","productPrice":3.0,"quantity":500,"actualPaid":3.0}"""
        assertEquals("超过 99 应夹到 99，与表单 changeQuantity 的上界一致", 99, LlmJsonMapper.toParsedOrder(tooMany)!!.productQuantity)

        val zero = """{"productName":"可乐","productPrice":3.0,"quantity":0,"actualPaid":3.0}"""
        assertEquals("0 应夹到 1，数量不能为 0", 1, LlmJsonMapper.toParsedOrder(zero)!!.productQuantity)
    }

    @Test
    fun `非JSON或空对象_返回null让调用方降级`() {
        assertNull(LlmJsonMapper.toParsedOrder("抱歉，我无法处理这个请求。"))
        assertNull(LlmJsonMapper.toParsedOrder("{}"))
        assertNull(LlmJsonMapper.toParsedOrder(""))
        assertNull(LlmJsonMapper.toParsedOrder("not json at all"))
    }

    @Test
    fun `值里含转义引号与冒号_不串位`() {
        val raw = """{"storeName":"\"特色\"小吃店","productName":"招牌\"超大\"套餐","productPrice":25.0,"actualPaid":25.0}"""

        val parsed = LlmJsonMapper.toParsedOrder(raw)

        assertNotNull(parsed)
        assertEquals("\"特色\"小吃店", parsed!!.storeName)
        assertEquals("招牌\"超大\"套餐", parsed.productName)
    }

    @Test
    fun `未知平台名_不崩溃且映射为null`() {
        val raw = """{"productName":"测试","productPrice":1.0,"platform":"ELEME","actualPaid":1.0}"""

        val parsed = LlmJsonMapper.toParsedOrder(raw)

        assertNotNull(parsed)
        assertNull("饿了么不在枚举内，应为 null 而不是默认美团", parsed!!.platform)
    }

    @Test
    fun `全null字段_视为解析失败`() {
        val raw = """{"storeName":null,"productName":null,"productPrice":null,"actualPaid":null}"""

        assertNull(
            "一个有效字段都没有时应返回 null，让调用方回落到端侧规则",
            LlmJsonMapper.toParsedOrder(raw)
        )
    }

    @Test
    fun `JSON前后带解释文字_仍能取出对象`() {
        // 真机复现：response_format=json_object 下模型仍会偶尔多一句开场白，
        // 这正是"有概率识别失败"的原因。必须容错。
        val raw = """
            好的，这是从截图中提取到的订单信息：
            {"storeName":"麦当劳","productName":"巨无霸","productPrice":21.5,"quantity":1,"actualPaid":21.5}
            如果还有其它需要请告诉我。
        """.trimIndent()

        val parsed = LlmJsonMapper.toParsedOrder(raw)

        assertNotNull("JSON 前后带解释文字时必须仍能解析", parsed)
        assertEquals("麦当劳", parsed!!.storeName)
        assertEquals("巨无霸", parsed.productName)
        assertEquals(21.5, parsed.productPrice!!, 0.001)
    }

    @Test
    fun `只有一个花括号_不误判为对象`() {
        assertNull(LlmJsonMapper.toParsedOrder("这段文字里只有一个 { 花括号"))
        assertNull(LlmJsonMapper.toParsedOrder("} 反着的括号开头 {"))
    }

    // ------------------------------------------------------------------
    // 商品明细 items（方案 A 新增：整单各件商品，供回看"这顿吃了什么"）
    // ------------------------------------------------------------------

    @Test
    fun `明细数组_多件商品按顺序解析`() {
        val raw = """
            {"storeName":"蜜雪冰城","productName":"三拼霸霸奶茶","productPrice":12.7,
             "quantity":1,"actualPaid":12.3,
             "items":[{"name":"三拼霸霸奶茶","price":12.7,"quantity":1},
                      {"name":"冰淇淋","price":4.0,"quantity":2}]}
        """.trimIndent()

        val parsed = LlmJsonMapper.toParsedOrder(raw)!!

        assertEquals(2, parsed.items.size)
        assertEquals("三拼霸霸奶茶", parsed.items[0].name)
        assertEquals(12.7, parsed.items[0].price, 0.001)
        assertEquals(1, parsed.items[0].quantity)
        assertEquals("冰淇淋", parsed.items[1].name)
        assertEquals(4.0, parsed.items[1].price, 0.001)
        assertEquals(2, parsed.items[1].quantity)
        // 主字段不受影响
        assertEquals(12.3, parsed.actualPaid!!, 0.001)
    }

    @Test
    fun `没有items键或items为空数组_明细为空且不判失败`() {
        val noKey = """{"storeName":"沙县","productName":"拌面","productPrice":2.5,"actualPaid":5.0}"""
        assertTrue(LlmJsonMapper.toParsedOrder(noKey)!!.items.isEmpty())

        val emptyArr = """{"productName":"拌面","productPrice":2.5,"actualPaid":5.0,"items":[]}"""
        val parsed = LlmJsonMapper.toParsedOrder(emptyArr)
        assertTrue("items 为 [] 时主字段仍有效，不该返回 null", parsed != null)
        assertTrue(parsed!!.items.isEmpty())
    }

    /** 只有明细、主字段全空时仍然算识别失败——明细不能单独撑起一条记录。 */
    @Test
    fun `只有明细没有主字段_仍判为识别失败`() {
        val raw = """{"items":[{"name":"可乐","price":3.0,"quantity":1}]}"""

        assertNull(
            "明细不是判断识别价值的依据，主字段全空时必须返回 null 让调用方降级",
            LlmJsonMapper.toParsedOrder(raw)
        )
    }

    /** 响应被 max_tokens 截断在明细中间：能救回的前几件照常保留，绝不抛异常。 */
    @Test
    fun `明细数组被截断_救回完整的那些且不崩`() {
        val raw = """
            {"storeName":"麦当劳","productName":"巨无霸","productPrice":21.5,"actualPaid":21.5,
             "items":[{"name":"巨无霸","price":21.5,"quantity":1},
                      {"name":"薯条","price":9.0,"quantity":1},
                      {"name":"可乐","price"
        """.trimIndent()

        val parsed = LlmJsonMapper.toParsedOrder(raw)

        assertEquals("截断时主字段必须照常解析出来", "麦当劳", parsed!!.storeName)
        assertTrue("截断的明细至少应救回前两件", parsed.items.size >= 2)
        assertEquals("巨无霸", parsed.items[0].name)
        assertEquals("薯条", parsed.items[1].name)
    }

    /** 明细里某项没名字就跳过，但其余的照常保留。 */
    @Test
    fun `明细某项缺名字_跳过该项保留其余`() {
        val raw = """
            {"productName":"套餐","productPrice":20.0,"actualPaid":20.0,
             "items":[{"name":"","price":1.0,"quantity":1},
                      {"name":"可乐","price":3.0,"quantity":2}]}
        """.trimIndent()

        val items = LlmJsonMapper.toParsedOrder(raw)!!.items

        assertEquals(1, items.size)
        assertEquals("可乐", items[0].name)
        assertEquals(2, items[0].quantity)
    }

    @Test
    fun `明细商品名带转义引号_不串位`() {
        val raw = """
            {"productName":"套餐","productPrice":20.0,"actualPaid":20.0,
             "items":[{"name":"招牌\"超大\"奶茶","price":15.0,"quantity":1}]}
        """.trimIndent()

        val items = LlmJsonMapper.toParsedOrder(raw)!!.items

        assertEquals(1, items.size)
        assertEquals("招牌\"超大\"奶茶", items[0].name)
        assertEquals(15.0, items[0].price, 0.001)
    }

    /** 数量越界与缺失 quantity 的处理，与表单的 1..99 上界保持一致。 */
    @Test
    fun `明细数量越界或缺省_夹回合法区间`() {
        val raw = """
            {"productName":"套餐","productPrice":20.0,"actualPaid":20.0,
             "items":[{"name":"A","price":1.0,"quantity":500},
                      {"name":"B","price":1.0,"quantity":0},
                      {"name":"C","price":1.0}]}
        """.trimIndent()

        val items = LlmJsonMapper.toParsedOrder(raw)!!.items

        assertEquals(3, items.size)
        assertEquals(99, items[0].quantity)
        assertEquals(1, items[1].quantity)
        assertEquals("缺 quantity 时按 1 计", 1, items[2].quantity)
    }

    @Test
    fun `嵌套值不支持的形态_跳过而不崩`() {
        // LLM 偶尔会把 notes 写成对象；本场景用不到，但不能因此崩
        val raw = """{"productName":"套餐","productPrice":20.0,"actualPaid":20.0,"notes":{"a":1}}"""

        val parsed = LlmJsonMapper.toParsedOrder(raw)

        assertNotNull(parsed)
        assertEquals("套餐", parsed!!.productName)
        assertTrue("解析结果不应标记为空", !parsed.isEmpty)
    }
}
