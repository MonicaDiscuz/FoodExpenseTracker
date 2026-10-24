package com.example.foodexpense.util

import androidx.test.core.app.ApplicationProvider
import com.example.foodexpense.data.entity.MealRecordEntity
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DataBackup] 里 `actualPaid` 的**空值语义**测试。
 *
 * 为什么必须有这个用例：硬约束只要求"导入时不能用 `optDouble(key, 0.0)`"，
 * 但那是**代码审查**能看出来的；真正要防的事故是——
 * 老备份没有该字段 / 字段为 null 时，导入后变成"实付 0 元"，把全表统计拉低。
 * 这里用真实的 zip 走一遍导出→导入，把该语义钉死。
 *
 * 用例不需要真实图片：备份里没有 `images/` 条目时不会触碰 Bitmap，
 * 因此可以在 Robolectric 下纯逻辑地跑（但 org.json 必须是真实现，故不能用纯 JVM）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataBackupActualPaidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** 造一个只含 data.json 的备份 zip。 */
    private fun backupZip(json: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun importJson(json: String): DataBackup.ImportResult =
        backupZip(json).inputStream().use { DataBackup.import(context, it) }

    /**
     * v2 备份：`actualPaid` 为数字时正常读入；为 `null` 时必须仍是 null（不是 0.0）。
     */
    @Test
    fun `v2备份_实付有值读值_为null仍为null`() {
        val json = """
            {"version":2,"count":2,"records":[
              {"platform":"MEITUAN","orderTime":1700000000000,"storeName":"A店",
               "productName":"A商品","productPrice":39.9,"productQuantity":1,
               "actualPaid":12.34,"reviewImages":[]},
              {"platform":"MEITUAN","orderTime":1700000001000,"storeName":"B店",
               "productName":"B商品","productPrice":10.0,"productQuantity":2,
               "actualPaid":null,"reviewImages":[]}
            ]}
        """.trimIndent()

        val result = importJson(json)

        assertEquals(2, result.formatVersion)
        assertEquals(2, result.records.size)

        val withPaid = result.records.first { it.orderTime == 1_700_000_000_000L }
        assertEquals(12.34, withPaid.actualPaid!!, 0.001)
        assertEquals(12.34, withPaid.paidAmount, 0.001)

        val nullPaid = result.records.first { it.orderTime == 1_700_000_001_000L }
        assertNull("备份里是 null 就必须是 null，绝不能变成 0.0", nullPaid.actualPaid)
        // 统计口径回退到 10.0 × 2
        assertEquals(20.0, nullPaid.paidAmount, 0.001)
    }

    /**
     * v1 老备份：**根本没有** `actualPaid` 这个键。
     * 缺键时 `JSONObject.isNull` 也为 true，因此必须得到 null，而不是 `optDouble` 的 0.0。
     */
    @Test
    fun `v1老备份_缺actualPaid键时必须是null而不是0`() {
        val json = """
            {"records":[
              {"platform":"MEITUAN","orderTime":1600000000000,"storeName":"老店",
               "productName":"老商品","productPrice":8.5,"productQuantity":1,
               "reviewImages":[]}
            ]}
        """.trimIndent()

        val result = importJson(json)

        assertEquals("缺 version 字段应按 v1 处理", 1, result.formatVersion)
        assertEquals(1, result.records.size)
        val record = result.records.first()
        assertNull(record.actualPaid)
        assertEquals(8.5, record.paidAmount, 0.001)
    }

    /**
     * 导出 → 导入 往返：`actualPaid` 的 null 与具体值都必须原样保留。
     */
    @Test
    fun `导出导入往返_实付语义不丢失`() {
        val source = listOf(
            MealRecordEntity(
                platform = "MEITUAN",
                orderTime = 1_700_000_000_000L,
                storeName = "有实付的店",
                productName = "巨无霸汉堡",
                productPrice = 39.9,
                productQuantity = 1,
                actualPaid = 26.4,
                createdAt = 1_700_000_000_000L
            ),
            MealRecordEntity(
                platform = "JD",
                orderTime = 1_700_000_500_000L,
                storeName = "无实付的店",
                productName = "拌面",
                productPrice = 2.5,
                productQuantity = 2,
                actualPaid = null,
                createdAt = 1_700_000_500_000L
            )
        )

        val zipBytes = ByteArrayOutputStream().also { DataBackup.export(source, it) }.toByteArray()
        val result = zipBytes.inputStream().use { DataBackup.import(context, it) }

        assertEquals(2, result.records.size)
        val back1 = result.records.first { it.orderTime == 1_700_000_000_000L }
        assertEquals(26.4, back1.actualPaid!!, 0.001)
        assertEquals("有实付的店", back1.storeName)

        val back2 = result.records.first { it.orderTime == 1_700_000_500_000L }
        assertNull(back2.actualPaid)
        assertEquals(5.0, back2.paidAmount, 0.001)
    }
}
