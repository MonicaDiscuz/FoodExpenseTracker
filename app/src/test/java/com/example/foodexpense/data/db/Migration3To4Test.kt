package com.example.foodexpense.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.foodexpense.data.entity.MealRecordEntity
import com.example.foodexpense.data.entity.OrderItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `MIGRATION_3_4`（新增订单级商品明细 `items`）的**真实迁移测试**。
 *
 * 写法与 [Migration2To3Test] 一致：手工建出 v3 真实表 → 写老数据 → `PRAGMA user_version = 3`
 * → Room 打开触发迁移并**按实体定义逐列校验**。
 *
 * 这一版要特别盯住两件事：
 * 1. `items` 在实体里是**非空**类型，所以列必须是 `NOT NULL DEFAULT '[]'`，
 *    照抄 v2→v3 的"可空无 DEFAULT"写法会当场抛 `Migration didn't properly handle`；
 * 2. 老数据迁移后明细必须是**空列表**，不能是 null、更不能让记录读不出来。
 *
 * **已反向验证（2026-10-24）**：把 [MIGRATION_3_4] 改成 `ADD COLUMN items TEXT`
 * （照搬 v2→v3 的可空写法）后，本文件 3 个用例**全部变红**，报错是
 * `IllegalStateException at RoomOpenHelper.kt:93`（Room 的 schema 校验）。
 * 说明这些用例确实有判别力，不是"怎么写都绿"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration3To4Test {

    private companion object {
        const val DB_NAME = "migration_3_to_4_test.db"

        /** v3 的建表语句：v2 的全部列 + `actualPaid REAL`（可空、无 DEFAULT）。 */
        const val V3_CREATE_MEAL_RECORDS =
            "CREATE TABLE IF NOT EXISTS `meal_records` (" +
                "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "`platform` TEXT NOT NULL, " +
                "`orderTime` INTEGER NOT NULL, " +
                "`storeName` TEXT NOT NULL, " +
                "`productName` TEXT NOT NULL, " +
                "`productPrice` REAL NOT NULL, " +
                "`productQuantity` INTEGER NOT NULL, " +
                "`starRating` INTEGER, " +
                "`reviewContent` TEXT NOT NULL, " +
                "`reviewImages` TEXT NOT NULL, " +
                "`reviewTime` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`actualPaid` REAL)"

        const val T1 = 1_700_000_000_000L
        const val T2 = 1_700_000_500_000L
    }

    private lateinit var context: Context
    private var db: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        createLegacyV3Database()
    }

    @After
    fun tearDown() {
        db?.close()
        db = null
        context.deleteDatabase(DB_NAME)
    }

    // ------------------------------------------------------------------
    // 构造 v3 老库（不含 items 列）
    // ------------------------------------------------------------------

    private fun createLegacyV3Database() {
        val dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        val legacy = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            legacy.execSQL(V3_CREATE_MEAL_RECORDS)

            legacy.execSQL(
                "INSERT INTO meal_records (" +
                    "platform, orderTime, storeName, productName, productPrice, productQuantity, " +
                    "starRating, reviewContent, reviewImages, reviewTime, createdAt, actualPaid) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "MEITUAN", T1, "麦当劳(科技园店)", "巨无霸汉堡", 39.9, 1,
                    5, "分量足，出餐快", "[\"img_a.jpg\"]", T1 + 100_000L, T1, 26.4
                )
            )

            legacy.execSQL(
                "INSERT INTO meal_records (" +
                    "platform, orderTime, storeName, productName, productPrice, productQuantity, " +
                    "starRating, reviewContent, reviewImages, reviewTime, createdAt, actualPaid) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "TAOBAO_FLASH", T2, "沙县小吃", "拌面", 2.5, 2,
                    null, "", "[]", null, T2, null
                )
            )

            legacy.version = 3
        } finally {
            legacy.close()
        }
    }

    private fun openRoom(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    private fun loadAll(database: AppDatabase): List<MealRecordEntity> =
        runBlocking { database.mealRecordDao().getAll() }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    /**
     * 核心用例：v3 → v4 迁移后
     * - Room 的 schema 校验通过（不抛 IllegalStateException）
     * - 行数不变、原有字段逐项不变
     * - `items` 为空列表（老数据本来就没有明细）
     */
    @Test
    fun `迁移后行数与字段完整_且明细为空列表`() {
        val database = openRoom()
        val records = loadAll(database)

        assertEquals("迁移后行数必须不变（迁移绝不能清库）", 2, records.size)

        val r1 = records.first { it.orderTime == T1 }
        assertTrue("老数据迁移后 items 必须是空列表", r1.items.isEmpty())
        assertEquals(26.4, r1.actualPaid!!, 0.001)
        assertEquals(26.4, r1.paidAmount, 0.001)
        assertEquals("MEITUAN", r1.platform)
        assertEquals("麦当劳(科技园店)", r1.storeName)
        assertEquals("巨无霸汉堡", r1.productName)
        assertEquals(39.9, r1.productPrice, 0.001)
        assertEquals(1, r1.productQuantity)
        assertEquals(5, r1.starRating)
        assertEquals("分量足，出餐快", r1.reviewContent)
        assertEquals(listOf("img_a.jpg"), r1.reviewImages)
        assertEquals(T1 + 100_000L, r1.reviewTime)
        assertEquals(T1, r1.createdAt)

        val r2 = records.first { it.orderTime == T2 }
        assertTrue(r2.items.isEmpty())
        assertEquals("实付为 null 时统计回退到 2.5 × 2", 5.0, r2.paidAmount, 0.001)
        assertEquals("TAOBAO_FLASH", r2.platform)
        assertEquals("沙县小吃", r2.storeName)
        assertEquals(2, r2.productQuantity)
    }

    /**
     * 迁移之后（真实 v4 库）写入带明细的记录再读出来：
     * 这条链路同时验证 `MIGRATION_3_4` 的列定义 与 `Converters` 的 JSON 存取。
     */
    @Test
    fun `迁移后新增带明细的记录_明细能完整存回读回`() {
        val database = openRoom()
        val dao = database.mealRecordDao()

        val id = runBlocking {
            dao.insert(
                MealRecordEntity(
                    platform = "MEITUAN",
                    orderTime = 1_800_000_000_000L,
                    storeName = "蜜雪冰城",
                    productName = "三拼霸霸奶茶",
                    productPrice = 12.7,
                    productQuantity = 1,
                    actualPaid = 12.3,
                    items = listOf(
                        OrderItem("三拼霸霸奶茶", 12.7, 1),
                        OrderItem("冰淇淋", 4.0, 2)
                    )
                )
            )
        }

        val back = runBlocking { dao.getById(id) }
        assertEquals(2, back!!.items.size)
        assertEquals("三拼霸霸奶茶", back.items[0].name)
        assertEquals(12.7, back.items[0].price, 0.001)
        assertEquals(1, back.items[0].quantity)
        assertEquals("冰淇淋", back.items[1].name)
        assertEquals(4.0, back.items[1].price, 0.001)
        assertEquals(2, back.items[1].quantity)

        // 明细绝不能改变统计口径：实付 12.3，而不是 12.7 + 4×2 = 20.7
        assertEquals("统计必须走实付，明细求和不得参与", 12.3, back.paidAmount, 0.001)
    }

    /**
     * `items` 列必须**非空且默认 `[]`**：实体里声明为非空 `List<OrderItem>`，
     * 若迁移写成可空列，Room 的 schema 校验会直接失败（且失败点在真机升级时才暴露）。
     */
    @Test
    fun `items列非空且默认空数组`() {
        val database = openRoom()
        val readable = database.openHelper.readableDatabase

        readable.query("PRAGMA table_info(`meal_records`)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
            val dfltIdx = cursor.getColumnIndexOrThrow("dflt_value")

            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIdx) == "items") {
                    found = true
                    assertEquals("items 必须非空（notnull=1），与实体里的非空声明一致", 1, cursor.getInt(notNullIdx))
                    val dflt = cursor.getString(dfltIdx)?.trim('\'') ?: ""
                    assertEquals("items 必须有 DEFAULT '[]'，否则 ALTER 加不出非空列", "[]", dflt)
                }
            }
            assertTrue("迁移后必须存在 items 列", found)
        }
    }
}
