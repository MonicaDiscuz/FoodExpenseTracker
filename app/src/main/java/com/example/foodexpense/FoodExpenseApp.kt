package com.example.foodexpense

import android.app.Application
import com.example.foodexpense.data.db.AppDatabase
import com.example.foodexpense.data.prefs.AiSettings
import com.example.foodexpense.data.repository.MealRecordRepository
import com.example.foodexpense.ocr.LlmOrderParser
import com.example.foodexpense.ocr.OcrRecognizer

class FoodExpenseApp : Application() {

    val database by lazy { AppDatabase.getInstance(this) }

    val recordRepository by lazy {
        MealRecordRepository(database.mealRecordDao(), database)
    }

    /** AI 识图的用户配置（key / 模型 / 开关），key 存加密存储、不硬编码。 */
    val aiSettings by lazy { AiSettings(this) }

    /** 云端结构化解析器。未配置 key 时不会被调用（见 RecordFormViewModel 的降级链）。 */
    val llmOrderParser by lazy { LlmOrderParser(aiSettings) }

    /**
     * 端侧 OCR 主引擎：ML Kit bundled 中文模型。
     *
     * 与 [recordRepository] 同构：进程级单例，避免每次进表单都重建 ML Kit client
     * （重建设意味着重新加载模型，首次识别会明显变慢）。
     */
    val ocrRecognizer by lazy { OcrRecognizer(this) }
}
