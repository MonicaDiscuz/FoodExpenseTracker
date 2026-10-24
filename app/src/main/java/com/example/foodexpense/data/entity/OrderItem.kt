package com.example.foodexpense.data.entity

/**
 * 订单里的单件商品明细。
 *
 * ## 为什么单独加这个，而不是把一条记录拆成多条
 * 记账的核心口径是**整单实付**（[MealRecordEntity.actualPaid]），而商品明细的价值
 * 只在"回看得起那顿吃了什么"。如果为了记明细把一条记录拆成 N 条，统计就会把
 * 商品单价之和当成花费——但那个数**不等于实付**（中间还有打包费、配送费、优惠）。
 * 所以明细作为**附属信息**挂在主记录上，不参与任何统计。
 */
data class OrderItem(
    /** 商品名（已去掉规格后缀）。 */
    val name: String,
    /** 单价（元）。 */
    val price: Double,
    /** 数量。 */
    val quantity: Int = 1
) {
    /** 小计 = 单价 × 数量。仅用于展示，不参与统计。 */
    val subtotal: Double get() = price * quantity
}
