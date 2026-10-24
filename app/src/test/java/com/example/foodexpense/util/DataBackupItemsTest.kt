package com.example.foodexpense.util

import androidx.test.core.app.ApplicationProvider
import com.example.foodexpense.data.entity.MealRecordEntity
import com.example.foodexpense.data.entity.OrderItem
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DataBackup] 中商品明细 `items` 的备份往返测试（备份格式 v3 起带明细）。
 *
 * 与 [DataBackupActualPaidTest] 同源：用真实 zip 走一遍导出→导入。
 * 重点是**老备份兼容**——v2 及更早的备份没有 `items` 键，导入后必须是空列表，
 * 且记录本身照常导入（绝不能因为缺一个附属字段就整条跳过）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataBackupItemsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

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

    @Test
    fun `导出导入往返_商品明细完整保留`() {
        val source = listOf(
            MealRecordEntity(
                platform = "MEITUAN",
                orderTime = 1_700_000_000_000L,
                storeName = "蜜雪冰城",
                productName = "三拼霸霸奶茶",
                productPrice = 12.7,
                productQuantity = 1,
                actualPaid = 12.3,
                items = listOf(
                    OrderItem("三拼霸霸奶茶", 12.7, 1),
                    OrderItem("冰淇淋", 4.0, 2)
                ),
                createdAt = 1_700_000_000_000L
            ),
            MealRecordEntity(
                platform = "JD",
                orderTime = 1_700_000_500_000L,
                storeName = "沙县小吃",
                productName = "拌面",
                productPrice = 2.5,
                productQuantity = 2,
                actualPaid = 5.0,
                createdAt = 1_700_000_500_000L
            )
        )

        val zipBytes = ByteArrayOutputStream().also { DataBackup.export(source, it) }.toByteArray()
        val result = zipBytes.inputStream().use { DataBackup.import(context, it) }

        assertEquals("备份格式应升到 v3", 3, result.formatVersion)
        assertEquals(2, result.records.size)

        val multi = result.records.first { it.orderTime == 1_700_000_000_000L }
        assertEquals(2, multi.items.size)
        assertEquals("三拼霸霸奶茶", multi.items[0].name)
        assertEquals(12.7, multi.items[0].price, 0.001)
        assertEquals(1, multi.items[0].quantity)
        assertEquals("冰淇淋", multi.items[1].name)
        assertEquals(2, multi.items[1].quantity)
        // 明细不参与统计：12.3 而不是 12.7 + 4×2
        assertEquals(12.3, multi.paidAmount, 0.001)

        val single = result.records.first { it.orderTime == 1_700_000_500_000L }
        assertTrue("没有明细的记录导出再导入后仍是空列表", single.items.isEmpty())
        assertEquals(5.0, single.paidAmount, 0.001)
    }

    /**
     * v2 老备份没有 `items` 键：必须导入为"没有明细"，而不是崩掉或跳过整条记录。
     */
    @Test
    fun `v2老备份_缺items键时明细为空且记录照常导入`() {
        val json = """
            {"version":2,"count":1,"records":[
              {"platform":"MEITUAN","orderTime":1700000000000,"storeName":"老店",
               "productName":"老商品","productPrice":8.5,"productQuantity":1,
               "actualPaid":8.5,"reviewImages":[]}
            ]}
        """.trimIndent()

        val result = importJson(json)

        assertEquals(2, result.formatVersion)
        assertEquals("缺 items 键也必须照常导入，不能整条跳过", 1, result.records.size)
        assertTrue(result.records.first().items.isEmpty())
        assertEquals(8.5, result.records.first().paidAmount, 0.001)
    }

    /** 备份里的明细是坏数据时，降级为空列表，主记录不受影响。 */
    @Test
    fun `明细是坏数据时降级为空列表_主记录仍在`() {
        val json = """
            {"version":3,"count":1,"records":[
              {"platform":"MEITUAN","orderTime":1700000000000,"storeName":"坏数据店",
               "productName":"商品","productPrice":10.0,"productQuantity":1,
               "actualPaid":10.0,"reviewImages":[],"items":"不是数组"}
            ]}
        """.trimIndent()

        val result = importJson(json)

        assertEquals(1, result.records.size)
        assertTrue("items 不是数组时应降级为空列表", result.records.first().items.isEmpty())
        assertEquals("坏数据店", result.records.first().storeName)
        assertEquals(10.0, result.records.first().paidAmount, 0.001)
    }
}
