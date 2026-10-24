package com.example.foodexpense.ocr

import com.example.foodexpense.data.entity.OrderItem
import com.example.foodexpense.data.entity.Platform

/**
 * 从订单截图里解析出的**候选订单信息**。
 *
 * 所有字段都可空：解析是"尽力而为"的启发式过程，识别不到就是 null，
 * 由调用方（[com.example.foodexpense.viewmodel.RecordFormViewModel]）决定
 * 只把非空字段填进表单、绝不覆盖用户已输入的内容。
 *
 * 设计约束：本类**不允许**依赖任何 Android / androidx / org.json 类型，
 * 以便纯 JVM 单元测试（唯一的业务依赖是 [Platform] 枚举）。
 *
 * 产品口径（已与产品确认）：
 * - [actualPaid] 是**整单实付金额**（含打包费/配送费、已扣优惠），统计以此为口径
 * - 一单多商品时只记**主商品**（金额最大那条），但 [actualPaid] 仍是整单的
 */
data class ParsedOrder(
    /** 店铺名，如「麦当劳(北京西路店)」。 */
    val storeName: String? = null,
    /** 主商品名（已清洗掉金额、规格、数量后缀）。 */
    val productName: String? = null,
    /** 主商品单价（截图上的折后价）。 */
    val productPrice: Double? = null,
    /** 主商品数量，永远 >= 1。 */
    val productQuantity: Int = 1,
    /** 整单实付金额，统计口径的核心字段。 */
    val actualPaid: Double? = null,
    /** 识别到的平台，饿了么等未支持平台为 null。 */
    val platform: Platform? = null,
    /** 识别到的商品行数量（>1 时说明是"多商品只记主商品"场景，用于给用户提示）。 */
    val itemCount: Int = 0,
    /** 整单优惠合计（仅用于「主商品价上界校验」与提示，不参与统计）。 */
    val discountTotal: Double? = null,
    /** 整单原价/商品总价（仅用于提示，不参与统计）。 */
    val originalTotal: Double? = null,
    /**
     * 整单商品明细：各件商品的名字/单价/数量。
     *
     * 与 [productName] 的关系：[productName] 是主商品（金额最大那件），
     * 本字段保留**所有**商品，用于回看"这顿吃了什么"。
     * 只填字段、不参与任何统计，也不参与 [filledCount] / [isEmpty] 的判定——
     * "主字段一个都没认出来但明细认出来了"没有意义，仍然算识别失败。
     */
    val items: List<OrderItem> = emptyList()
) {
    /** 关键字段里成功识别出几个（0..4），用于判断识别是否有价值。 */
    val filledCount: Int
        get() = listOf<Any?>(storeName, productName, productPrice, actualPaid).count { it != null }

    /** 一个字段都没识别出来 —— 等同于识别失败（调用方应提示用户手动填写）。 */
    val isEmpty: Boolean get() = filledCount == 0
}
