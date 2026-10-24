package com.example.foodexpense.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.foodexpense.data.entity.MealRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MealRecordDao {

    @Insert
    suspend fun insert(record: MealRecordEntity): Long

    @Update
    suspend fun update(record: MealRecordEntity)

    @Delete
    suspend fun delete(record: MealRecordEntity)

    @Query("SELECT * FROM meal_records WHERE id = :id")
    suspend fun getById(id: Long): MealRecordEntity?

    /**
     * 列表查询：关键字搜索 + 平台筛选 + 星级筛选，三种条件可任意组合。
     *
     * @param keyword 原始关键字（为空表示不搜索）
     * @param pattern 已转义的 LIKE 模式，例如 %鸡腿%
     * @param platform 平台 name，null 表示全部平台
     * @param star null = 全部；-1 = 未评价；1..5 = 指定星级
     */
    @Query(
        """
        SELECT * FROM meal_records
        WHERE (:keyword = ''
               OR storeName LIKE :pattern ESCAPE '\'
               OR productName LIKE :pattern ESCAPE '\'
               OR reviewContent LIKE :pattern ESCAPE '\')
          AND (:platform IS NULL OR platform = :platform)
          AND (:star IS NULL
               OR (:star = -1 AND starRating IS NULL)
               OR (:star > 0 AND starRating = :star))
        ORDER BY orderTime DESC
        """
    )
    fun observeFiltered(
        keyword: String,
        pattern: String,
        platform: String?,
        star: Int?
    ): Flow<List<MealRecordEntity>>

    /** 统计看板：观察 [start, end) 区间内的记录。 */
    @Query("SELECT * FROM meal_records WHERE orderTime >= :start AND orderTime < :end ORDER BY orderTime DESC")
    fun observeBetween(start: Long, end: Long): Flow<List<MealRecordEntity>>

    /** 日历页：观察某一天（[start, end) 区间）的记录，按时间正序。 */
    @Query("SELECT * FROM meal_records WHERE orderTime >= :start AND orderTime < :end ORDER BY orderTime ASC")
    fun observeDay(start: Long, end: Long): Flow<List<MealRecordEntity>>

    // ---------- 备份 / 恢复用 ----------

    @Query("SELECT * FROM meal_records ORDER BY orderTime DESC")
    suspend fun getAll(): List<MealRecordEntity>

    @Insert
    suspend fun insertAll(records: List<MealRecordEntity>): List<Long>

    @Query("DELETE FROM meal_records")
    suspend fun deleteAll()
}
