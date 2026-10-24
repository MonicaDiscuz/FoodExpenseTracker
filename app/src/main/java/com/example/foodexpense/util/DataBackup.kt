package com.example.foodexpense.util

import android.content.Context
import com.example.foodexpense.data.entity.MealRecordEntity
import com.example.foodexpense.data.entity.OrderItem
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一键备份 / 恢复。
 *
 * 备份文件是单个 zip：
 * ```
 * 外卖记账备份_20260920_1705.zip
 *  ├── data.json       # 全部记录（含评价、星级、图片文件名）
 *  └── images/1.jpg    # 评价图片原文件（多张依次编号）
 * ```
 * 恢复时图片会重新落盘到本机应用私有目录，并把记录里的图片路径换成新路径，
 * 因此「导出 → 在另一台设备导入」后数据完全一致。
 */
object DataBackup {

    private const val ENTRY_DATA = "data.json"
    private const val ENTRY_IMAGE_PREFIX = "images/"

    /**
     * 备份格式版本：
     * - 2 起包含订单级实付金额 `actualPaid`
     * - 3 起包含订单级商品明细 `items`
     */
    private const val FORMAT_VERSION = 3

    /**
     * 导入结果：可直接写库的记录 + 恢复了多少张图片 + 备份文件自身的格式版本。
     *
     * [formatVersion] 默认 1，老备份文件里没有 version 字段时按 v1 处理，
     * 调用方（例如提示"实付金额缺失"）可以据此区分数据来源。
     */
    data class ImportResult(
        val records: List<MealRecordEntity>,
        val imageCount: Int,
        val formatVersion: Int = 1
    )

    /** 导出全部记录（含图片）到指定输出流。 */
    fun export(records: List<MealRecordEntity>, out: OutputStream) {
        // 原图片路径 -> zip 内文件名，避免重复打包同一张图
        val imageEntries = LinkedHashMap<String, String>()

        ZipOutputStream(out.buffered()).use { zip ->
            val array = JSONArray()
            records.forEach { record ->
                val images = JSONArray()
                record.reviewImages.forEach { path ->
                    val entryName = imageEntries.getOrPut(path) {
                        "$ENTRY_IMAGE_PREFIX${imageEntries.size + 1}.jpg"
                    }
                    images.put(entryName)
                }
                array.put(
                    JSONObject().apply {
                        put("platform", record.platform)
                        put("orderTime", record.orderTime)
                        put("storeName", record.storeName)
                        put("productName", record.productName)
                        put("productPrice", record.productPrice)
                        put("productQuantity", record.productQuantity)
                        put("starRating", record.starRating ?: JSONObject.NULL)
                        put("reviewContent", record.reviewContent)
                        put("reviewImages", images)
                        put("reviewTime", record.reviewTime ?: JSONObject.NULL)
                        // 订单级实付金额：null 表示"未记录"，导出时保留 null 语义，不要写成 0
                        put("actualPaid", record.actualPaid ?: JSONObject.NULL)
                        // 商品明细：只用于回看，不参与统计。没有明细时存空数组，保持字段存在
                        put("items", JSONArray().apply {
                            record.items.forEach { item ->
                                put(
                                    JSONObject()
                                        .put("name", item.name)
                                        .put("price", item.price)
                                        .put("quantity", item.quantity)
                                )
                            }
                        })
                        put("createdAt", record.createdAt)
                    }
                )
            }

            val root = JSONObject().apply {
                put("version", FORMAT_VERSION)
                put("exportedAt", System.currentTimeMillis())
                put("count", records.size)
                put("records", array)
            }
            zip.putNextEntry(ZipEntry(ENTRY_DATA))
            zip.write(root.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            imageEntries.forEach { (path, entryName) ->
                val file = File(path)
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** 从备份 zip 读取记录：图片先落地到应用私有目录，再返回可直接入库的记录。 */
    fun import(context: Context, input: InputStream): ImportResult {
        var payload: String? = null
        // zip 内图片名 -> 本机新路径
        val extracted = HashMap<String, String>()

        ZipInputStream(input.buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    when {
                        name == ENTRY_DATA -> payload = zip.readBytes().toString(Charsets.UTF_8)
                        name.startsWith(ENTRY_IMAGE_PREFIX) -> {
                            // 走统一的压缩落盘逻辑：即使旧备份里是大图，导入后也会变成压缩图
                            val saved = ImageStorage.saveCompressed(context, zip.readBytes())
                            if (saved != null) extracted[name] = saved
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val json = payload ?: throw IllegalArgumentException("备份文件缺少 data.json，可能不是本应用导出的备份")
        val root = JSONObject(json)
        val version = root.optInt("version", 1)
        val array = root.optJSONArray("records") ?: JSONArray()
        val records = ArrayList<MealRecordEntity>(array.length())

        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val images = ArrayList<String>()
            o.optJSONArray("reviewImages")?.let { imgArray ->
                for (j in 0 until imgArray.length()) {
                    extracted[imgArray.optString(j)]?.let { newPath -> images.add(newPath) }
                }
            }
            val storeName = o.optString("storeName")
            val productName = o.optString("productName")
            if (storeName.isBlank() && productName.isBlank()) continue

            // 商品明细：老版本备份（v2 及更早）没有这个字段，缺失时按"没有明细"处理，
            // 绝不能因为缺字段就把整条记录跳过或抛异常。
            val items = ArrayList<OrderItem>()
            o.optJSONArray("items")?.let { itemArray ->
                for (j in 0 until itemArray.length()) {
                    val io = itemArray.optJSONObject(j) ?: continue
                    val itemName = io.optString("name").takeIf { it.isNotBlank() } ?: continue
                    items.add(
                        OrderItem(
                            name = itemName,
                            price = io.optDouble("price", 0.0).takeIf { !it.isNaN() && it >= 0.0 } ?: 0.0,
                            quantity = io.optInt("quantity", 1).coerceIn(1, 99)
                        )
                    )
                }
            }

            records.add(
                MealRecordEntity(
                    id = 0L,
                    platform = o.optString("platform", "MEITUAN"),
                    orderTime = o.optLong("orderTime", System.currentTimeMillis()),
                    storeName = storeName,
                    productName = productName,
                    productPrice = o.optDouble("productPrice", 0.0),
                    productQuantity = o.optInt("productQuantity", 1).coerceAtLeast(1),
                    // 关键：绝不能用 optDouble("actualPaid", 0.0)。
                    // 键缺失时它返回 0.0，会把"未记录"变成"实付 0 元"，导致全表统计归零。
                    actualPaid = if (o.isNull("actualPaid")) {
                        null
                    } else {
                        o.optDouble("actualPaid").takeIf { !it.isNaN() && it >= 0.0 }
                    },
                    items = items,
                    starRating = if (o.isNull("starRating")) null else o.optInt("starRating").takeIf { it in 1..5 },
                    reviewContent = o.optString("reviewContent"),
                    reviewImages = images,
                    reviewTime = if (o.isNull("reviewTime")) null else o.optLong("reviewTime"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }

        return ImportResult(records, extracted.size, version)
    }
}
