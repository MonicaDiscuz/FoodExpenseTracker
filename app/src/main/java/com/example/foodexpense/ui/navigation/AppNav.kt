package com.example.foodexpense.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.foodexpense.FoodExpenseApp
import com.example.foodexpense.ui.components.AppBackground
import com.example.foodexpense.ui.screens.calendar.CalendarScreen
import com.example.foodexpense.ui.screens.ocr.OcrTestScreen
import com.example.foodexpense.ui.screens.records.RecordFormScreen
import com.example.foodexpense.ui.screens.records.RecordListScreen
import com.example.foodexpense.ui.screens.settings.AiSettingsScreen
import com.example.foodexpense.ui.screens.stats.StatsScreen

object Routes {
    const val CALENDAR = "calendar"
    const val RECORDS = "records"
    const val STATS = "stats"
    const val OCR_TEST = "ocr_test"
    const val AI_SETTINGS = "ai_settings"
    const val RECORD_FORM = "record_form?recordId={recordId}&date={date}"

    /**
     * @param recordId > 0 表示编辑已有记录
     * @param dateMillis 新增记录时预设的下单日期（来自日历页选中的日期）
     */
    fun recordForm(recordId: Long = -1L, dateMillis: Long = -1L): String {
        val id = if (recordId > 0) recordId else -1L
        val date = if (dateMillis > 0) dateMillis else -1L
        return "record_form?recordId=$id&date=$date"
    }
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val mainRoutes = setOf(Routes.CALENDAR, Routes.RECORDS, Routes.STATS)

    Box(modifier = Modifier.fillMaxSize()) {
        // 全 App 背景图案：各页面 Scaffold 容器设为透明即可透出
        AppBackground()

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (currentRoute in mainRoutes) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentRoute == Routes.CALENDAR,
                            onClick = { navController.navigateToMain(Routes.CALENDAR) },
                            icon = { Icon(Icons.Filled.CalendarMonth, contentDescription = "日历") },
                            label = { Text("日历") }
                        )
                        NavigationBarItem(
                            selected = currentRoute == Routes.RECORDS,
                            onClick = { navController.navigateToMain(Routes.RECORDS) },
                            icon = { Icon(Icons.Filled.ReceiptLong, contentDescription = "记录") },
                            label = { Text("记录") }
                        )
                        NavigationBarItem(
                            selected = currentRoute == Routes.STATS,
                            onClick = { navController.navigateToMain(Routes.STATS) },
                            icon = { Icon(Icons.Filled.BarChart, contentDescription = "统计") },
                            label = { Text("统计") }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.CALENDAR,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(Routes.CALENDAR) {
                    CalendarScreen(
                        onAddAtDate = { dateMillis -> navController.navigate(Routes.recordForm(dateMillis = dateMillis)) },
                        onEditRecord = { id -> navController.navigate(Routes.recordForm(recordId = id)) },
                        onOcrTest = { navController.navigate(Routes.OCR_TEST) },
                        onAiSettings = { navController.navigate(Routes.AI_SETTINGS) }
                    )
                }
                composable(Routes.RECORDS) {
                    RecordListScreen(
                        onAdd = { navController.navigate(Routes.recordForm()) },
                        onEdit = { id -> navController.navigate(Routes.recordForm(recordId = id)) }
                    )
                }
                composable(Routes.STATS) { StatsScreen() }

                composable(Routes.OCR_TEST) {
                    OcrTestScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.AI_SETTINGS) {
                    AiSettingsScreen(
                        onBack = { navController.popBackStack() },
                        settings = (LocalContext.current.applicationContext as FoodExpenseApp).aiSettings
                    )
                }

                composable(
                    route = Routes.RECORD_FORM,
                    arguments = listOf(
                        navArgument("recordId") {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                        navArgument("date") {
                            type = NavType.LongType
                            defaultValue = -1L
                        }
                    )
                ) { entry ->
                    RecordFormScreen(
                        recordId = entry.arguments?.getLong("recordId") ?: -1L,
                        initialDateMillis = entry.arguments?.getLong("date") ?: -1L,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

/** 底部导航切换：保留各 tab 状态。 */
private fun NavController.navigateToMain(route: String) {
    navigate(route) {
        popUpTo(Routes.CALENDAR) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
