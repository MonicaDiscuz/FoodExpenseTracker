package com.example.foodexpense.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.foodexpense.FoodExpenseApp
import com.example.foodexpense.data.entity.MealRecordEntity
import com.example.foodexpense.util.DateUtils
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 某一天的汇总：笔数 / 金额 / 已评价数。 */
data class DaySummary(
    val count: Int = 0,
    val amount: Double = 0.0,
    val reviewCount: Int = 0
)

/**
 * 首页日历：
 * - [monthRecords] / [daySummaries]：当月全部记录，用于在日历格子里显示每天的花费
 * - [dayRecords]：当前选中日期的记录，供下方列表展示
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = getApplication<FoodExpenseApp>().recordRepository

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    /** 当月全部记录（日历标记用，不受筛选影响）。 */
    val monthRecords: StateFlow<List<MealRecordEntity>> = _month
        .flatMapLatest { ym ->
            repository.observeBetween(
                DateUtils.monthStartMillis(ym),
                DateUtils.monthEndMillis(ym)
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 日期 -> 当天汇总。 */
    val daySummaries: StateFlow<Map<LocalDate, DaySummary>> = monthRecords
        .map { records ->
            records
                .groupBy { DateUtils.toLocalDate(it.orderTime) }
                .mapValues { (_, list) ->
                    DaySummary(
                        count = list.size,
                        amount = list.sumOf { it.paidAmount },
                        reviewCount = list.count { it.starRating != null }
                    )
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 选中日期的记录。 */
    val dayRecords: StateFlow<List<MealRecordEntity>> = _selectedDate
        .flatMapLatest { date ->
            repository.observeDay(
                DateUtils.dayStartMillis(date),
                DateUtils.dayEndMillis(date)
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun previousMonth() {
        showMonth(_month.value.minusMonths(1))
    }

    fun nextMonth() {
        showMonth(_month.value.plusMonths(1))
    }

    /** 切换月份；若选中日期不在该月，则默认选中该月 1 号。 */
    fun showMonth(target: YearMonth) {
        _month.value = target
        if (YearMonth.from(_selectedDate.value) != target) {
            _selectedDate.value = target.atDay(1)
        }
    }

    fun goToToday() {
        _month.value = YearMonth.now()
        _selectedDate.value = LocalDate.now()
    }
}
