package com.example.foodexpense.data.db

import com.example.foodexpense.data.entity.OrderItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [Converters] 中商品明细（`List<OrderItem>` ↔ JSON 字符串）的往返测试。
 *
 * 需要 Robolectric：`Converters` 用的是 `org.json`，纯 JVM 下 android.jar 的
 * `org.json` 是桩实现（返回默认值），跑不出真结果。
 *
 * 这里唯一**必须**钉死的是降级行为：库里存了坏 JSON 时，`toOrderItemList` 必须返回
 * 空列表而不是抛异常——明细是附属信息，它坏了不该让整条记录读不出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrderItemConvertersTest {

    private val converters = Converters()

    @Test
    fun `多件商品往返后完全一致`() {
        val source = listOf(
            OrderItem("三拼霸霸奶茶", 12.7, 1),
            OrderItem("冰淇淋", 4.0, 2),
            OrderItem("珍珠", 1.5, 1)
        )

        val json = converters.fromOrderItemList(source)
        val back = converters.toOrderItemList(json)

        assertEquals(source, back)
    }

    @Test
    fun `空列表往返后仍是空列表`() {
        val json = converters.fromOrderItemList(emptyList())
        assertEquals("[]", json)
        assertTrue(converters.toOrderItemList(json).isEmpty())
    }

    @Test
    fun `商品名里的引号与特殊字符不串位`() {
        val source = listOf(OrderItem("招牌\"超大\"套餐 / 加辣", 25.0, 1))

        val back = converters.toOrderItemList(converters.fromOrderItemList(source))

        assertEquals(1, back.size)
        assertEquals("招牌\"超大\"套餐 / 加辣", back[0].name)
        assertEquals(25.0, back[0].price, 0.001)
    }

    /**
     * 坏数据一律降级为空列表，绝不抛异常。
     *
     * 真实场景：老版本写入的字段格式变了、或被别的应用改过。此时损失明细可以接受，
     * 但因为一条坏记录导致整个账本打不开是不可接受的。
     */
    @Test
    fun `坏JSON降级为空列表而不抛异常`() {
        assertTrue(converters.toOrderItemList("not json at all").isEmpty())
        assertTrue(converters.toOrderItemList("").isEmpty())
        assertTrue(converters.toOrderItemList("{}").isEmpty())
        assertTrue(converters.toOrderItemList("{\"a\":1}").isEmpty())
        assertTrue(converters.toOrderItemList("[1,2,3]").isEmpty())
    }

    /** 数组里混进 name 为空的残项时，跳过该项、保留其余的。 */
    @Test
    fun `空名字的残项被跳过`() {
        val json = """[{"name":"","price":1.0,"quantity":1},{"name":"可乐","price":3.0,"quantity":2}]"""

        val back = converters.toOrderItemList(json)

        assertEquals(1, back.size)
        assertEquals("可乐", back[0].name)
        assertEquals(2, back[0].quantity)
    }

    /** 数量越界要夹回合法区间，与表单里的 1..99 上界保持一致。 */
    @Test
    fun `数量越界被夹回1到99`() {
        val json = """[{"name":"A","price":1.0,"quantity":0},{"name":"B","price":1.0,"quantity":500}]"""

        val back = converters.toOrderItemList(json)

        assertEquals(1, back[0].quantity)
        assertEquals(99, back[1].quantity)
    }
}
