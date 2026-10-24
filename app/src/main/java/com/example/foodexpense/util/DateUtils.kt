package com.example.foodexpense.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object DateUtils {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** epoch 毫秒 -> "yyyy-MM-dd HH:mm"（本地时区）。 */
    fun formatTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime().format(timeFormatter)

    /** epoch 毫秒 -> "yyyy-MM-dd"（本地时区）。 */
    fun formatDate(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().format(dateFormatter)

    private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 列表分组标题：今天 / 昨天 / 前天 / M月d日 周X。 */
    fun dayLabel(date: LocalDate): String {
        val today = LocalDate.now(zone)
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            today.minusDays(2) -> "前天"
            else -> "${date.monthValue}月${date.dayOfMonth}日 ${weekdays[date.dayOfWeek.value - 1]}"
        }
    }

    /** 同上，入参为 epoch 毫秒。 */
    fun dayLabel(epochMillis: Long): String = dayLabel(toLocalDate(epochMillis))

    /** 取某时刻当天的本地日期。 */
    fun toLocalDate(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    /**
     * 解析导入文本中的时间。支持：
     *  - epoch 毫秒数字
     *  - yyyy-MM-dd HH:mm:ss / yyyy-MM-dd HH:mm / yyyy-MM-dd
     *  - yyyy/MM/dd、yyyy年M月d日 等常见格式
     */
    fun parseDateTime(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        t.toLongOrNull()?.let { return it }

        val withTime = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm",
            "yyyy年M月d日 HH:mm:ss", "yyyy年M月d日 HH:mm"
        )
        for (p in withTime) {
            try {
                return LocalDateTime.parse(t, DateTimeFormatter.ofPattern(p))
                    .atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) { /* 尝试下一个格式 */ }
        }
        val dateOnly = listOf("yyyy-MM-dd", "yyyy/MM/dd", "yyyy年M月d日", "yyyy.MM.dd")
        for (p in dateOnly) {
            try {
                return LocalDate.parse(t, DateTimeFormatter.ofPattern(p))
                    .atStartOfDay(zone).toInstant().toEpochMilli()
            } catch (_: Exception) { /* 尝试下一个格式 */ }
        }
        return null
    }

    /** 取某时刻当天的 UTC 零点（用于 DatePicker 的初始值）。 */
    fun utcMidnightMillis(epochMillis: Long): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** DatePicker 返回 UTC 零点毫秒，用其日期 + [currentMillis] 的时分秒合成新时间。 */
    fun combineDateUtcMidnightWithCurrentTime(utcMidnightMillis: Long, currentMillis: Long): Long {
        val date = Instant.ofEpochMilli(utcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val time = Instant.ofEpochMilli(currentMillis).atZone(zone).toLocalTime()
        return LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
    }

    /** 用当前日期 + 指定时分秒合成新时间（TimePicker 使用）。 */
    fun combineCurrentDateWithTime(hour: Int, minute: Int, currentMillis: Long): Long {
        val date = Instant.ofEpochMilli(currentMillis).atZone(zone).toLocalDate()
        return LocalDateTime.of(date, LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()
    }

    /** 用给定日期的年月日 + 当前时刻的时分秒合成时间（日历页选定日期后新增记录用）。 */
    fun combineLocalDateWithCurrentTime(dateMillis: Long, currentMillis: Long): Long {
        val date = toLocalDate(dateMillis)
        val time = Instant.ofEpochMilli(currentMillis).atZone(zone).toLocalTime()
        return LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
    }

    // ---------- 区间边界（日历与统计查询用） ----------

    fun dayStartMillis(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayEndMillis(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun monthStartMillis(month: YearMonth): Long =
        month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun monthEndMillis(month: YearMonth): Long =
        month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
