package com.example.foodexpense.data.db

import androidx.room.TypeConverter
import com.example.foodexpense.data.entity.OrderItem
import org.json.JSONArray
import org.json.JSONObject

/** 把 List<String>（评价图片路径）与数据库中的 JSON 字符串互转。 */
class Converters {

    @TypeConverter
    fun fromStringList(list: List<String>): String = JSONArray(list).toString()

    @TypeConverter
    fun toStringList(value: String): List<String> = try {
        val arr = JSONArray(value)
        buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }
    } catch (_: Exception) {
        emptyList()
    }

    // ---------- 商品明细 ----------

    /**
     * 商品明细 ↔ JSON 字符串。
     *
     * 解析失败一律返回空列表——与 [toStringList] 口径一致：损坏数据降级为"没有明细"，
     * 而不是让整条记录读不出来（明细是附属信息，不该影响主记录可用）。
     */
    @TypeConverter
    fun fromOrderItemList(list: List<OrderItem>): String {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("name", item.name)
                    put("price", item.price)
                    put("quantity", item.quantity)
                }
            )
        }
        return arr.toString()
    }

    @TypeConverter
    fun toOrderItemList(value: String): List<OrderItem> = try {
        val arr = JSONArray(value)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
                add(
                    OrderItem(
                        name = name,
                        price = o.optDouble("price", 0.0),
                        quantity = o.optInt("quantity", 1).coerceIn(1, 99)
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
