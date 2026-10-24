package com.example.foodexpense.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.foodexpense.FoodExpenseApp
import com.example.foodexpense.data.entity.MealRecordEntity
import com.example.foodexpense.data.entity.Platform
import java.time.Instant
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 统计结果。
 * 外卖天数 = 该时间段内有订单的日期去重后的天数；
 * 外卖总金额 = Σ 实付金额（[MealRecordEntity.paidAmount]，缺失时回退到商品小计）。
 */
data class StatsResult(
    val orderCount: Int = 0,
    val totalAmount: Double = 0.0,
    val deliveryDays: Int = 0,
    val reviewCount: Int = 0,
    val averageStars: Double = 0.0,
    val byPlatform: Map<Platform, Double> = emptyMap()
) {
    companion object {
        val empty = StatsResult()

        fun compute(records: List<MealRecordEntity>): StatsResult {
            if (records.isEmpty()) return empty
            val zone = ZoneId.systemDefault()
            val days = records
                .map { Instant.ofEpochMilli(it.orderTime).atZone(zone).toLocalDate() }
                .distinct()
                .size
            val total = records.sumOf { it.paidAmount }
            val stars = records.mapNotNull { it.starRating }
            val byPlatform = records
                .groupBy(
                    { Platform.fromName(it.platform) ?: Platform.MEITUAN },
                    { it.paidAmount }
                )
                .mapValues { (_, amounts) -> amounts.sum() }
            return StatsResult(
                orderCount = records.size,
                totalAmount = total,
                deliveryDays = days,
                reviewCount = stars.size,
                averageStars = if (stars.isEmpty()) 0.0 else stars.average(),
                byPlatform = byPlatform
            )
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = getApplication<FoodExpenseApp>().recordRepository

    enum class PeriodMode { MONTH, YEAR }

    private val _mode = MutableStateFlow(PeriodMode.MONTH)
    val mode: StateFlow<PeriodMode> = _mode.asStateFlow()

    private val _year = MutableStateFlow(Year.now().value)
    val year: StateFlow<Int> = _year.asStateFlow()

    private val _month = MutableStateFlow(YearMonth.now().monthValue)
    val month: StateFlow<Int> = _month.asStateFlow()

    /** 当前期间的 [start, end) 毫秒区间。 */
    private val range: StateFlow<Pair<Long, Long>> =
        combine(_mode, _year, _month) { m, y, mo ->
            val zone = ZoneId.systemDefault()
            when (m) {
                PeriodMode.MONTH -> {
                    val ym = YearMonth.of(y, mo)
                    ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
                        ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                }
                PeriodMode.YEAR -> {
                    Year.of(y).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
                        Year.of(y + 1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L to 0L)

    val stats: StateFlow<StatsResult> = range
        .flatMapLatest { (start, end) ->
            repository.observeBetween(start, end).map { StatsResult.compute(it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsResult.empty)

    fun setMode(m: PeriodMode) {
        _mode.value = m
    }

    fun setYear(y: Int) {
        _year.value = y.coerceIn(2000, 2100)
    }

    fun setMonth(m: Int) {
        _month.value = m.coerceIn(1, 12)
    }

    fun previousPeriod() {
        if (_mode.value == PeriodMode.MONTH) {
            val ym = YearMonth.of(_year.value, _month.value).minusMonths(1)
            _year.value = ym.year
            _month.value = ym.monthValue
        } else {
            _year.value -= 1
        }
    }

    fun nextPeriod() {
        if (_mode.value == PeriodMode.MONTH) {
            val ym = YearMonth.of(_year.value, _month.value).plusMonths(1)
            _year.value = ym.year
            _month.value = ym.monthValue
        } else {
            _year.value += 1
        }
    }
}
