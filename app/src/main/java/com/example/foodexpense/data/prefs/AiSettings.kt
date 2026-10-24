package com.example.foodexpense.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * AI 识图（云端结构化）的用户配置。
 *
 * **API key 一律不硬编码进代码或 APK**：由用户在「AI 识别设置」页自己粘贴，
 * 存进 [EncryptedSharedPreferences]。这样做的三个理由：
 * 1. APK 能被反编译，写进常量等于把钱包送人；
 * 2. 换 key / 换模型不用重新打包；
 * 3. key 只在用户主动开启该功能时才存在于设备上。
 *
 * 未配置 key 时，识图功能自动回落到端侧规则解析（[com.example.foodexpense.ocr.OrderScreenshotParser]），
 * 不会弹错、也不会阻断记账。
 */
class AiSettings(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ai_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as SharedPreferences
    }

    /** DeepSeek API key；空字符串表示未配置。 */
    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    /** 模型 ID。默认 [DEFAULT_MODEL]，用户可在设置页改（模型名会随平台更新）。 */
    var modelId: String
        get() = prefs.getString(KEY_MODEL, "").orEmpty().ifBlank { DEFAULT_MODEL }
        set(value) = prefs.edit()
            .putString(KEY_MODEL, value.trim().ifBlank { DEFAULT_MODEL })
            .apply()

    /** 是否启用云端结构化。默认开启——但只有配了 key 才会真正走云端。 */
    var cloudEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 是否真的可以走云端：开关打开 + key 非空。 */
    val isCloudReady: Boolean
        get() = cloudEnabled && apiKey.isNotBlank()

    companion object {
        private const val KEY_API = "deepseek_api_key"
        private const val KEY_MODEL = "deepseek_model_id"
        private const val KEY_ENABLED = "cloud_enabled"

        /**
         * 默认模型：DeepSeek-V4.1-Flash。
         *
         * 该模型同时接受 text 与 image 输入（见平台 /models 返回的 input_modalities），
         * 是当前最便宜的一档。**模型 ID 会随平台更新而变化**，所以做成可配置项，
         * 不要在代码里写死判断。
         */
        const val DEFAULT_MODEL = "deepseek-flash"

        const val BASE_URL = "https://api.deepseek.com"
    }
}
