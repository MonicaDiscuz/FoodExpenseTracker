package com.example.foodexpense.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.foodexpense.FoodExpenseApp
import com.example.foodexpense.data.entity.MealRecordEntity
import com.example.foodexpense.data.entity.Platform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * 列表筛选条件。
 * @param keyword 搜索关键字（店铺 / 商品 / 评价内容）
 * @param platform 平台，null = 全部
 * @param star null = 全部；-1 = 未评价；1..5 = 指定星级
 */
data class RecordFilter(
    val keyword: String = "",
    val platform: Platform? = null,
    val star: Int? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class RecordListViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = getApplication<FoodExpenseApp>().recordRepository

    private val _filter = MutableStateFlow(RecordFilter())
    val filter: StateFlow<RecordFilter> = _filter.asStateFlow()

    /** 跟随搜索 / 平台 / 星级条件实时刷新的记录列表。 */
    val records: StateFlow<List<MealRecordEntity>> = _filter
        .flatMapLatest { f ->
            repository.observeFiltered(
                keyword = f.keyword,
                platform = f.platform?.name,
                star = f.star
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setKeyword(keyword: String) = _filter.update { it.copy(keyword = keyword) }

    fun setPlatform(platform: Platform?) = _filter.update { it.copy(platform = platform) }

    fun setStar(star: Int?) = _filter.update { it.copy(star = star) }

    fun clearFilters() = _filter.update { RecordFilter() }
}
