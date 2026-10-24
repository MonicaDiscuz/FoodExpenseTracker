package com.example.foodexpense.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 外卖记录：订单信息与评价信息合并为一条记录（一对一）。
 *
 * - 订单部分：平台 / 下单时间 / 店铺名称 / 商品名称 / 商品价格 / 商品数量
 * - 评价部分：星级（null 表示未评价）/ 评价内容 / 图片（多张，JSON 存储）/ 评价时间
 *
 * 商品小计 = productPrice × productQuantity。
 * **统计口径是 [paidAmount]（实付），不是 [subtotal]。**
 */
@Entity(tableName = "meal_records")
data class MealRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    // ---------- 订单 ----------
    val platform: String,
    val orderTime: Long,
    val storeName: String,
    val productName: String,
    val productPrice: Double,
    val productQuantity: Int,
    /**
     * **订单级**实付金额（元）：整单实际支付的钱，一次一笔，与本记录里的主商品无关。
     * null = 历史数据 / 旧版备份未记录，读取时由 [paidAmount] 回退到 [subtotal]。
     */
    val actualPaid: Double? = null,

    /**
     * **订单级**商品明细：整单里各件商品的名字/单价/数量。
     *
     * 与 [actualPaid] 的分工：明细回答"这顿吃了什么"，实付回答"花了多少钱"。
     * 明细**不参与任何统计**——商品单价之和通常不等于实付（还有打包费/配送费/优惠），
     * 拿它求和会算错钱。空列表 = 手动录入或旧数据，没有明细。
     *
     * `defaultValue` 必须与 [com.example.foodexpense.data.db.MIGRATION_3_4] 里的
     * `DEFAULT '[]'` 逐字一致，否则 Room 2.6 的 schema 校验会判为不一致并抛异常。
     */
    @ColumnInfo(defaultValue = "[]")
    val items: List<OrderItem> = emptyList(),

    // ---------- 评价（可选） ----------
    val starRating: Int? = null,
    val reviewContent: String = "",
    val reviewImages: List<String> = emptyList(),
    val reviewTime: Long? = null,

    val createdAt: Long = System.currentTimeMillis()
) {
    val subtotal: Double get() = productPrice * productQuantity

    /**
     * **统计口径唯一入口**：所有"金额"计算必须走这里，不要再用 [subtotal]。
     * actualPaid 缺失（旧数据 / 旧备份）时回退到商品小计。
     */
    val paidAmount: Double get() = actualPaid ?: subtotal

    val hasReview: Boolean get() = starRating != null
}
