package com.example.foodexpense.data.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 商品明细 [OrderItem] 与统计口径的关系。
 *
 * 这个文件存在的唯一理由，是把**方案 A 最危险的一条边界**钉死：
 * 明细是给"回看这顿吃了什么"用的，**绝不能**被拿去算钱。
 * 商品单价之和通常不等于实付（还差打包费/配送费，也还没扣优惠），
 * 一旦有人图省事写了 `record.items.sumOf { it.subtotal }`，全表统计就错了，
 * 而且错得很隐蔽——单看某条记录还挺合理。
 */
class OrderItemTest {

    private fun record(
        actualPaid: Double?,
        items: List<OrderItem>,
        price: Double = 12.7,
        quantity: Int = 1
    ) = MealRecordEntity(
        platform = "MEITUAN",
        orderTime = 1_700_000_000_000L,
        storeName = "蜜雪冰城",
        productName = "三拼霸霸奶茶",
        productPrice = price,
        productQuantity = quantity,
        actualPaid = actualPaid,
        items = items
    )

    @Test
    fun `单件商品小计等于单价乘数量`() {
        assertEquals(12.7, OrderItem("奶茶", 12.7, 1).subtotal, 0.001)
        assertEquals(8.0, OrderItem("冰淇淋", 4.0, 2).subtotal, 0.001)
    }

    @Test
    fun `统计口径不受明细影响_实付为准`() {
        val items = listOf(
            OrderItem("三拼霸霸奶茶", 12.7, 1),
            OrderItem("冰淇淋", 4.0, 2)
        )
        val r = record(actualPaid = 12.3, items = items)

        assertEquals(2, r.items.size)
        // 明细求和 = 12.7 + 4×2 = 20.7，但实付只有 12.3（有优惠）
        assertEquals(20.7, r.items.sumOf { it.subtotal }, 0.001)
        assertEquals("统计必须走实付 12.3，不是明细求和 20.7", 12.3, r.paidAmount, 0.001)
    }

    @Test
    fun `没有实付时_统计回退到商品小计而不是明细求和`() {
        val items = listOf(
            OrderItem("三拼霸霸奶茶", 12.7, 1),
            OrderItem("冰淇淋", 4.0, 2)
        )
        val r = record(actualPaid = null, items = items, price = 12.7, quantity = 1)

        // 回退口径是 productPrice × productQuantity = 12.7，不是 20.7
        assertEquals(12.7, r.paidAmount, 0.001)
    }

    @Test
    fun `未识别明细的记录_明细为空列表且统计不受影响`() {
        val r = record(actualPaid = 5.0, items = emptyList(), price = 2.5, quantity = 2)

        assertTrue(r.items.isEmpty())
        assertEquals(5.0, r.paidAmount, 0.001)
    }

    /** 显式确认默认值：手动录入时不用传 items。 */
    @Test
    fun `构造时不传明细_默认空列表`() {
        val r = MealRecordEntity(
            platform = "MEITUAN",
            orderTime = 0L,
            storeName = "店",
            productName = "商品",
            productPrice = 1.0,
            productQuantity = 1
        )

        assertTrue(r.items.isEmpty())
        assertEquals(1.0, r.paidAmount, 0.001)
    }
}
