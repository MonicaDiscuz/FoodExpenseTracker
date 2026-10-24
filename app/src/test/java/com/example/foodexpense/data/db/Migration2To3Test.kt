package com.example.foodexpense.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.foodexpense.data.entity.MealRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `MIGRATION_2_3`（新增订单级实付 `actualPaid`）的**真实迁移测试**。
 *
 * 测试思路（这是迁移测试唯一可信的做法）：
 * 1. 用原始 `SQLiteDatabase` 手工建出 **v2 的真实表结构**（与 [MIGRATION_1_2] 里
 *    的 CREATE TABLE 逐字一致），写入老数据，`PRAGMA user_version = 2`；
 * 2. 用 `Room.databaseBuilder(...).addMigrations(*ALL_MIGRATIONS)` 打开同一个文件。
 *    Room 会自动执行 `MIGRATION_2_3`，并在打开后**按实体定义逐列校验真实表结构**；
 *    只要迁移写错（漏列、类型不对、多加 NOT NULL / DEFAULT），这里就会抛异常；
 * 3. 断言老数据的字段一个都没丢，且 `actualPaid` 按「价格 × 数量」回填正确。
 *
 * 注意：本测试**故意不修改生产代码**，也不需要 room-testing 依赖 —— 走的是
 * 真实 startup 路径，比 `MigrationTestHelper` 更接近用户升级时的实际行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration2To3Test {

    private companion object {
        const val DB_NAME = "migration_2_to_3_test.db"

        /** v2 的建表语句，**必须**与 MIGRATION_1_2 中的 execSQL 保持一致。 */
        const val V2_CREATE_MEAL_RECORDS =
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
                "`createdAt` INTEGER NOT NULL)"

        const val T1 = 1_700_000_000_000L      // 老记录 1：单价 39.9 × 1
        const val T2 = 1_700_000_500_000L      // 老记录 2：单价 2.5 × 2
    }

    private lateinit var context: Context
    private var db: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 确保每次都从干净的 v2 文件开始（Robolectric 每个用例重置环境，这里再兜一层）
        context.deleteDatabase(DB_NAME)
        createLegacyV2Database()
    }

    @After
    fun tearDown() {
        db?.close()
        db = null
        context.deleteDatabase(DB_NAME)
    }

    // ------------------------------------------------------------------
    // 构造 v2 老库（不含 actualPaid 列）
    // ------------------------------------------------------------------

    private fun createLegacyV2Database() {
        val dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        val legacy = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            legacy.execSQL(V2_CREATE_MEAL_RECORDS)

            // 老记录 1：有评价、有星级、有图片
            legacy.execSQL(
                "INSERT INTO meal_records (" +
                    "platform, orderTime, storeName, productName, productPrice, productQuantity, " +
                    "starRating, reviewContent, reviewImages, reviewTime, createdAt) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "MEITUAN", T1, "麦当劳(科技园店)", "巨无霸汉堡", 39.9, 1,
                    5, "分量足，出餐快", "[\"img_a.jpg\"]", T1 + 100_000L, T1
                )
            )

            // 老记录 2：未评价、无星级、无图片
            legacy.execSQL(
                "INSERT INTO meal_records (" +
                    "platform, orderTime, storeName, productName, productPrice, productQuantity, " +
                    "starRating, reviewContent, reviewImages, reviewTime, createdAt) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "TAOBAO_FLASH", T2, "沙县小吃", "拌面", 2.5, 2,
                    null, "", "[]", null, T2
                )
            )

            // v2：让 Room 从 2 升到 3
            legacy.version = 2
        } finally {
            legacy.close()
        }
    }

    // ------------------------------------------------------------------
    // 打开 Room（触发 MIGRATION_2_3 + schema 校验）
    // ------------------------------------------------------------------

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
     * 核心用例：v2 → v3 迁移后
     * - Room 的 schema 校验通过（不抛 IllegalStateException）
     * - 行数不变（没清库）
     * - `actualPaid` 按「价格 × 数量」回填
     * - 其余字段逐字段不变
     */
    @Test
    fun `迁移后行数与字段完整_且实付按价格乘数量回填`() {
        val database = openRoom()
        val records = loadAll(database)

        assertEquals("迁移后行数必须不变（迁移绝不能清库）", 2, records.size)

        val r1 = records.first { it.orderTime == T1 }
        // 39.9 × 1 = 39.9
        assertEquals(39.9, r1.actualPaid!!, 0.001)
        assertEquals(39.9, r1.paidAmount, 0.001)
        assertEquals(39.9, r1.subtotal, 0.001)
        // 原有字段逐项不变
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
        // 2.5 × 2 = 5.0（不是 2.5，也不是 5.0 之外的任何值）
        assertEquals(5.0, r2.actualPaid!!, 0.001)
        assertEquals(5.0, r2.paidAmount, 0.001)
        assertEquals(5.0, r2.subtotal, 0.001)
        assertEquals("TAOBAO_FLASH", r2.platform)
        assertEquals("沙县小吃", r2.storeName)
        assertEquals("拌面", r2.productName)
        assertEquals(2, r2.productQuantity)
        assertNull(r2.starRating)
        assertEquals("", r2.reviewContent)
        assertTrue(r2.reviewImages.isEmpty())
        assertNull(r2.reviewTime)
        assertEquals(T2, r2.createdAt)
    }

    /**
     * `actualPaid` 为 NULL 时，[MealRecordEntity.paidAmount] 必须回退到 `subtotal`。
     *
     * 先写入一个与 subtotal 不同的实付值，确认 paidAmount 真的跟着实付走，
     * 再置 NULL，确认回退 —— 否则「回填值刚好等于小计」会让用例失去判别力。
     */
    @Test
    fun `实付置空后统计口径回退到商品小计`() {
        val database = openRoom()
        val writable = database.openHelper.writableDatabase

        // 1) 实付 12.34 ≠ 小计 5.0 → 统计取 12.34
        writable.execSQL("UPDATE meal_records SET actualPaid = 12.34 WHERE orderTime = $T2")
        val paid = loadAll(database).first { it.orderTime == T2 }
        assertEquals(12.34, paid.actualPaid!!, 0.001)
        assertEquals(12.34, paid.paidAmount, 0.001)

        // 2) 实付置 NULL → 统计回退到 2.5 × 2 = 5.0
        writable.execSQL("UPDATE meal_records SET actualPaid = NULL WHERE orderTime = $T2")
        val fallback = loadAll(database).first { it.orderTime == T2 }
        assertNull(fallback.actualPaid)
        assertEquals(5.0, fallback.paidAmount, 0.001)
    }

    /**
     * 迁移必须**可空且无 DEFAULT**：只有这样才能与实体里的 `Double?` 一致。
     *
     * 直接读 `PRAGMA table_info` 验证生产库的列属性，比读代码更硬。
     */
    @Test
    fun `actualPaid列可空且无默认值`() {
        val database = openRoom()
        val readable = database.openHelper.readableDatabase

        readable.query("PRAGMA table_info(`meal_records`)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
            val dfltIdx = cursor.getColumnIndexOrThrow("dflt_value")

            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIdx) == "actualPaid") {
                    found = true
                    assertEquals("actualPaid 必须可空（notnull=0）", 0, cursor.getInt(notNullIdx))
                    assertTrue("actualPaid 不允许有 DEFAULT", cursor.isNull(dfltIdx))
                }
            }
            assertTrue("迁移后必须存在 actualPaid 列", found)
        }
    }

    /** [ALL_MIGRATIONS] 必须连续无断档（当前 1→2→3→4），否则升级链会在真机上报错。 */
    @Test
    fun `ALL_MIGRATIONS连续覆盖1到4`() {
        val byStart = ALL_MIGRATIONS.associateBy { it.startVersion }
        var version = 1
        while (version < 4) {
            val migration = byStart[version]
            assertNotNull("缺少 $version → ${version + 1} 的迁移", migration)
            assertEquals(version + 1, migration!!.endVersion)
            version = migration.endVersion
        }
        assertEquals(4, version)
    }
}
