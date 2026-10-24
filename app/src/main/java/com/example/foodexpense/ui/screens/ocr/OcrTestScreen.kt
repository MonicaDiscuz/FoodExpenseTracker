package com.example.foodexpense.ui.screens.ocr

import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.foodexpense.FoodExpenseApp
import com.example.foodexpense.ocr.OrderScreenshotParser
import com.example.foodexpense.ocr.ParsedOrder
import com.example.foodexpense.ocr.TextLine
import com.example.foodexpense.ui.components.formatMoney
import com.example.foodexpense.ui.theme.NeonGreenSoft
import com.example.foodexpense.ui.theme.glowingOnBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 本页一次识别的状态。 */
private data class OcrTestUiState(
    val imageUri: Uri? = null,
    val loading: Boolean = false,
    val lines: List<TextLine> = emptyList(),
    val parsed: ParsedOrder? = null,
    val error: String? = null,
    val elapsedMs: Long = 0L
)

/**
 * 识图识别率测试页（实验，仅 `BuildConfig.DEBUG` 可达）：选一张订单截图，
 * 用 ML Kit 本地识别，再跑一遍 [OrderScreenshotParser] 看解析结果。
 *
 * - 只识别、**不写库**，用于在真机上评估中文识字率与解析可用性。
 * - 复用正式实现（[com.example.foodexpense.ocr.OcrRecognizer]）与
 *   [OrderScreenshotParser]，本页不持有独立 OCR 逻辑，避免两套逻辑漂移。
 * - 引擎是进程级单例（见 FoodExpenseApp），本页退出时不 close，表单页仍要使用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val app = LocalContext.current.applicationContext as FoodExpenseApp
    val parser = remember { OrderScreenshotParser() }
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(OcrTestUiState()) }
    val fixtureText = remember(state.lines) { formatLinesForFixture(state.lines) }
    var copiedLineCount by remember(state.lines) { mutableStateOf(0) }

    /** 跑一次识别（内部含计时）。 */
    fun runRecognition(uri: Uri) {
        state = state.copy(imageUri = uri, loading = true, error = null)
        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val lines = app.ocrRecognizer.recognize(uri)
                    lines to parser.parse(lines)
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            state = outcome.fold(
                onSuccess = { (lines, parsed) ->
                    OcrTestUiState(
                        imageUri = uri,
                        loading = false,
                        lines = lines,
                        parsed = parsed,
                        elapsedMs = elapsed,
                        error = when {
                            lines.isEmpty() -> "没有识别到任何文字，请换一张更清晰的截图"
                            parsed == null || parsed.isEmpty ->
                                "未解析出有效字段，请结合下方 OCR 原文判断是识字问题还是规则问题"
                            else -> null
                        }
                    )
                },
                onFailure = { e ->
                    OcrTestUiState(
                        imageUri = uri,
                        loading = false,
                        elapsedMs = elapsed,
                        error = "识别失败：${e.message ?: e::class.java.simpleName}" +
                            "（本机离线处理，未发生网络请求）"
                    )
                }
            )
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runRecognition(uri)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Text(
                        "识图识别率测试（实验）",
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
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "本页识别完全在手机本地进行，不上传、不联网、不需要权限。" +
                            "引擎为 ML Kit bundled 中文模型。",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Button(
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = !state.loading,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.DocumentScanner, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("选择订单截图开始识别")
                    }
                }
            }

            if (state.loading) {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("正在本地识别，请稍候…")
                    }
                }
            }

            state.error?.let { err ->
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            state.imageUri?.let { uri ->
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("原始截图")
                        AsyncImage(
                            model = uri,
                            contentDescription = "待识别的订单截图",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            if (state.lines.isNotEmpty()) {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("识别结果")
                        KeyValue("耗时", "${state.elapsedMs} ms")
                        KeyValue("文本行数", state.lines.size.toString())
                    }
                }
            }

            state.parsed?.let { parsed ->
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("规则解析结果（与表单识图同一套逻辑，未入库）")
                        KeyValue("店铺名", parsed.storeName ?: "未识别")
                        KeyValue("主商品", parsed.productName ?: "未识别")
                        KeyValue(
                            "主商品单价",
                            parsed.productPrice?.let { formatMoney(it) } ?: "未识别"
                        )
                        KeyValue("数量", "×${parsed.productQuantity}")
                        KeyValue(
                            "整单实付",
                            parsed.actualPaid?.let { formatMoney(it) } ?: "未识别"
                        )
                        KeyValue("平台", parsed.platform?.displayName ?: "未识别/不支持")
                        KeyValue("识别商品行数", parsed.itemCount.toString())
                        parsed.discountTotal?.let { KeyValue("优惠合计（仅提示）", formatMoney(it)) }
                        parsed.originalTotal?.let { KeyValue("原价/小计（仅提示）", formatMoney(it)) }
                        Text(
                            "关键字段命中 ${parsed.filledCount}/4",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (state.lines.isNotEmpty()) {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("OCR 原始全文（评估识字率看这里）")
                        Text(
                            text = state.lines.joinToString("\n") { it.text }
                                .ifBlank { "（未识别到任何文字）" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel("分行与坐标（调试规则用，共 ${state.lines.size} 行）")
                        state.lines.forEach { line ->
                            Text(
                                "top=${line.top} left=${line.left}  ${line.text}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // ------------------------------------------------------------------
                // 真机数据「录音机」：把引擎输出原样导出，方便贴回来做 fixture。
                // 几何行重建能否生效完全取决于真实 top/bottom 的错位程度，
                // 这个按钮就是把真机坐标带回来的唯一途径。
                // ------------------------------------------------------------------
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("真机数据回归用（含坐标）")
                        Text(
                            "格式：序号|原始文本|left,top,right,bottom（一行一条）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                val copied = runCatching {
                                    clipboard.setText(AnnotatedString(fixtureText))
                                }.isSuccess
                                if (copied) {
                                    copiedLineCount = state.lines.size
                                    Toast.makeText(
                                        context,
                                        "已复制 ${state.lines.size} 行",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    copiedLineCount = 0
                                    Toast.makeText(
                                        context,
                                        "复制失败，请长按下方文本手动复制",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("复制识别原文（含坐标）")
                        }
                        if (copiedLineCount > 0) {
                            Text(
                                "已复制 $copiedLineCount 行到剪贴板",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        // 剪贴板不可用时（部分定制 ROM 会拦）用户可长按这里手动复制
                        SelectionContainer {
                            Text(
                                text = fixtureText.ifBlank { "（未识别到任何文字）" },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Text(
                "提示：重点核对原始全文有无错字漏字，以及划线价、打包费、优惠是否被误当成商品价格；实付应取整单实付，不是商品原价合计。",
                style = MaterialTheme.typography.bodySmall.glowingOnBackground(NeonGreenSoft)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.32f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.68f))
    }
}

/**
 * 把一批 [TextLine] 序列化成"可直接贴回单测当 fixture"的文本。
 *
 * 每行一条，字段用竖线分隔：
 * ```
 * 0|三拼霸霸奶茶|60,412,268,452
 * 1|¥12.7|880,412,1000,452
 * ```
 * 即 `序号|原始文本|left,top,right,bottom`。
 *
 * 为了保证"一行一条、竖线只做字段分隔"这个不变量（否则贴回来解析时会错位）：
 * - 文本里的半角/全角竖线统一替换成 `/`
 * - 文本里的换行 / 回车 / 制表符替换成空格
 *
 * 序号是**输入顺序**（引擎已按 (top, left) 排好序），
 * 这样真机上的原始阅读顺序也能一并带回来。
 */
private fun formatLinesForFixture(lines: List<TextLine>): String =
    lines.mapIndexed { index, line ->
        val safeText = line.text
            .replace('|', '/')
            .replace('｜', '/')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
        "$index|$safeText|${line.left},${line.top},${line.right},${line.bottom}"
    }.joinToString("\n")
