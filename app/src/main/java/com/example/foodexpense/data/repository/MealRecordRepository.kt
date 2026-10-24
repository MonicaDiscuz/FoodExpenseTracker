package com.example.foodexpense.data.repository

import androidx.room.withTransaction
import com.example.foodexpense.data.dao.MealRecordDao
import com.example.foodexpense.data.db.AppDatabase
import com.example.foodexpense.data.entity.MealRecordEntity
import kotlinx.coroutines.flow.Flow

class MealRecordRepository(
    private val dao: MealRecordDao,
    private val database: AppDatabase
) {

    /**
     * 组合筛选查询。
     * @param keyword 搜索关键字（店铺 / 商品 / 评价内容）
     * @param platform 平台，null 表示全部
     * @param star null = 全部；-1 = 未评价；1..5 = 指定星级
     */
    fun observeFiltered(
        keyword: String,
        platform: String?,
        star: Int?
    ): Flow<List<MealRecordEntity>> {
        val trimmed = keyword.trim()
        return dao.observeFiltered(
            keyword = trimmed,
            pattern = "%" + escapeLike(trimmed) + "%",
            platform = platform,
            star = star
        )
    }

    /** 统计看板用：区间内的记录。 */
    fun observeBetween(start: Long, end: Long): Flow<List<MealRecordEntity>> =
        dao.observeBetween(start, end)

    /** 日历页用：某一天的记录。 */
    fun observeDay(start: Long, end: Long): Flow<List<MealRecordEntity>> =
        dao.observeDay(start, end)

    suspend fun getRecord(id: Long): MealRecordEntity? = dao.getById(id)

    /** 新增（id == 0）或更新（id > 0），返回最终 id。 */
    suspend fun save(record: MealRecordEntity): Long {
        if (record.id == 0L) return dao.insert(record)
        dao.update(record)
        return record.id
    }

    suspend fun delete(record: MealRecordEntity) = dao.delete(record)

    // ---------- 备份 / 恢复 ----------

    /** 导出用：全部记录。 */
    suspend fun getAllRecords(): List<MealRecordEntity> = dao.getAll()

    /** 覆盖导入：清空现有记录后写入（同一事务，失败则回滚）。 */
    suspend fun replaceAll(records: List<MealRecordEntity>) {
        database.withTransaction {
            dao.deleteAll()
            dao.insertAll(records)
        }
    }

    /** 追加导入：保留现有记录，合并写入。 */
    suspend fun appendAll(records: List<MealRecordEntity>) {
        database.withTransaction {
            dao.insertAll(records)
        }
    }

    /** 转义 SQLite LIKE 通配符，避免用户输入的 % _ 被当作通配符。 */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
