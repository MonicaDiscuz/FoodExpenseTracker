package com.example.foodexpense.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.foodexpense.data.prefs.AiSettings
import com.example.foodexpense.ocr.LlmOrderParser
import com.example.foodexpense.ui.theme.glowingOnBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 识别设置页：配 DeepSeek key、选模型、测连通性。
 *
 * 设计要点：
 * - **key 不硬编码进 APK**，由用户粘贴，存 [AiSettings] 的加密存储；
 * - 没配 key / 关掉开关时，识图自动回落到端侧规则解析，功能不会坏；
 * - 「测试连接」用一次最小请求验证 key 与模型是否可用，避免用户录完一张图才发现配错了。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(onBack: () -> Unit, settings: AiSettings) {
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var modelId by remember { mutableStateOf(settings.modelId) }
    var enabled by remember { mutableStateOf(settings.cloudEnabled) }
    var keyVisible by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Text(
                        "AI 识别设置",
                        style = MaterialTheme.typography.titleLarge.glowingOnBackground()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "开启后，识图会先用云端大模型理解订单版面，失败时自动回落到手机本地解析。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用云端结构化", modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    Text(
                        "不上传图片：只把 OCR 识别出的文字（已过滤地址/手机号/订单号）发给模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("DeepSeek API Key", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("sk- 开头") },
                        singleLine = true,
                        visualTransformation = if (keyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (keyVisible) "点右侧隐藏" else "点右侧显示",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { keyVisible = !keyVisible }) {
                            Text(if (keyVisible) "隐藏" else "显示")
                        }
                    }

                    Text("模型 ID", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { modelId = it },
                        label = { Text(AiSettings.DEFAULT_MODEL) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "默认 ${AiSettings.DEFAULT_MODEL}（DeepSeek-V4.1-Flash）。" +
                            "平台更新模型名时改这里即可，不用重新打包。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = {
                            settings.apiKey = apiKey
                            settings.modelId = modelId
                            settings.cloudEnabled = enabled
                            testResult = "已保存"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存") }

                    Button(
                        onClick = {
                            settings.apiKey = apiKey
                            settings.modelId = modelId
                            settings.cloudEnabled = enabled
                            testing = true
                            testResult = null
                            scope.launch {
                                val outcome = runCatching {
                                    withContext(Dispatchers.IO) {
                                        LlmOrderParser(settings).testConnection()
                                    }
                                }
                                testing = false
                                testResult = outcome.fold(
                                    onSuccess = { reply ->
                                        // 开关关着却显示"连接成功"会误导用户以为云端在工作，
                                        // 必须把"此时识图不会走云端"一并说清。
                                        val note = if (!enabled) {
                                            "（但「启用云端结构化」开关已关闭，识图不会走云端）"
                                        } else {
                                            ""
                                        }
                                        "连接成功，模型回复：$reply$note"
                                    },
                                    onFailure = { "连接失败：${it.message ?: "未知错误"}" }
                                )
                            }
                        },
                        enabled = !testing && apiKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text("  测试中…")
                        } else {
                            Text("测试连接")
                        }
                    }

                    testResult?.let { msg ->
                        SelectionContainer {
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (msg.startsWith("连接失败")) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                    }
                }
            }

            Text(
                "key 只保存在本机加密存储里，不会随 APK 分发。换 key 或换模型都不用重新打包。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TextButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { content() }
}
