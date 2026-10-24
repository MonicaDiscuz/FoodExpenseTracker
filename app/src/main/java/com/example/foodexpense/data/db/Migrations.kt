package com.example.foodexpense.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 全部数据库迁移集中在本文件维护。
 *
 * 约定：
 * - 每新增一版 schema，必须同时新增一个 [Migration]，并把它追加进 [ALL_MIGRATIONS]。
 * - **不要**在 [AppDatabase] 上使用 `fallbackToDestructiveMigration()`：
 *   那会让遗漏的迁移变成"静默清空用户账本"，数据损失不可恢复。
 */

/**
 * v1（orders + reviews 两张表）→ v2（meal_records 单表）：
 * 每条订单与它最新的一条评价合并为一条记录，旧数据不丢失。
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
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
        )
        db.execSQL(
            "INSERT INTO `meal_records` (" +
                "platform, orderTime, storeName, productName, productPrice, productQuantity, " +
                "starRating, reviewContent, reviewImages, reviewTime, createdAt) " +
                "SELECT o.platform, o.orderTime, o.storeName, o.productName, o.productPrice, o.productQuantity, " +
                "(SELECT r.starRating FROM reviews r WHERE r.orderId = o.id ORDER BY r.createdAt DESC LIMIT 1), " +
                "COALESCE((SELECT r.content FROM reviews r WHERE r.orderId = o.id ORDER BY r.createdAt DESC LIMIT 1), ''), " +
                "COALESCE((SELECT r.images FROM reviews r WHERE r.orderId = o.id ORDER BY r.createdAt DESC LIMIT 1), '[]'), " +
                "(SELECT r.createdAt FROM reviews r WHERE r.orderId = o.id ORDER BY r.createdAt DESC LIMIT 1), " +
                "o.createdAt " +
                "FROM orders o"
        )
        db.execSQL("DROP TABLE IF EXISTS `reviews`")
        db.execSQL("DROP TABLE IF EXISTS `orders`")
    }
}

/**
 * v2 → v3：新增订单级实付金额 `actualPaid`。
 *
 * 注意：
 * - 该列**必须可空且不加 DEFAULT**，否则 Room 的 schema 校验会与
 *   [com.example.foodexpense.data.entity.MealRecordEntity.actualPaid] 的
 *   `Double?` 声明不一致，直接抛 IllegalStateException。
 * - 老数据用「商品价格 × 数量」回填，保证统计口径平滑过渡（与 paidAmount 的回退逻辑一致）。
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `meal_records` ADD COLUMN `actualPaid` REAL")
        db.execSQL("UPDATE `meal_records` SET `actualPaid` = `productPrice` * `productQuantity`")
    }
}

/**
 * v3 → v4：新增订单级商品明细 `items`（整单各件商品的名字/单价/数量，JSON 数组字符串）。
 *
 * 与 v2→v3 **相反**的一点，别搞混：
 * - `actualPaid` 在实体里声明为 `Double?`（可空），所以迁移必须"可空且不加 DEFAULT"；
 * - `items` 在实体里声明为非空 `List<OrderItem>`，Room 期望的 schema 是 `NOT NULL`，
 *   因此这里**必须**写 `NOT NULL DEFAULT '[]'`，同时实体上要标
 *   `@ColumnInfo(defaultValue = "[]")`，让两边的默认值一致。
 *   照抄 v2→v3 的"可空无 DEFAULT"写法会让 Room 的逐列校验直接抛
 *   `IllegalStateException: Migration didn't properly handle`。
 */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `meal_records` ADD COLUMN `items` TEXT NOT NULL DEFAULT '[]'")
    }
}

/** 建库时必须一次性注册全部迁移，顺序即版本演进顺序。 */
internal val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
