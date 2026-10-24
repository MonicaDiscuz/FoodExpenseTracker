package com.example.foodexpense.ocr

import com.example.foodexpense.data.entity.Platform
import com.example.foodexpense.data.prefs.AiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端结构化解析器（方案 B）：**ML Kit 负责认字，LLM 负责版面理解**。
 *
 * ## 为什么这样分工
 * 端侧规则（[OrderScreenshotParser]）三年都调不好的那一环不是"字认不出来"，而是：
 * 价格被切成独立文本行、规格行混进商品、`x1` 不跟商品行合并、划线价与折后价难分。
 * 这些恰恰是 LLM 的强项；而 ML Kit 的识字率对截图这种印刷体其实够用。
 *
 * ## 隐私
 * **图片永远不上传**：只把 OCR 出来的文字行（含坐标）发出去，且发送前会过滤掉
 * 含收货地址 / 手机号 / 订单号的行（见 [PRIVACY_LINE_RE]）。
 *
 * ## 成本
 * 一张订单截图 OCR 后约 20~40 行文字，输入几百 token，输出一两百 token，
 * 按 Flash 档位计价，一次识别大概不到一分钱。
 *
 * ## 失败处理
 * 任何异常（无网、超时、key 无效、返回非 JSON）都由调用方捕获并**回落到端侧规则**，
 * 本类不吞异常也不崩。
 */
class LlmOrderParser(private val settings: AiSettings) {

    /**
     * 把 OCR 文本行发给 DeepSeek，返回结构化结果。
     *
     * @param lines ML Kit 输出的文本行（按阅读顺序）
     * @return 解析成功返回 [ParsedOrder]；任何失败抛异常（由调用方决定降级）
     */
    suspend fun parse(lines: List<TextLine>): ParsedOrder = withContext(Dispatchers.IO) {
        val payload = buildPayload(lines)
        // 失败时**必须带上 finish_reason 与正文长度**。
        // 实测过一次"HTTP 200 但 content 是空字符串"——那是模型的推理过程把
        // max_tokens 吃光了（finish_reason=length），正文还没开始写就被截断。
        // 不看这两个信息，只会看到"未返回可解析的 JSON："后面一片空白，无从下手。
        val raw = chat(payload, maxTokens = MAX_OUTPUT_TOKENS, wantJson = true)
        LlmJsonMapper.toParsedOrder(raw)
            ?: throw IllegalStateException(
                "LLM 未返回可解析的 JSON" +
                    "（finish_reason=${lastFinishReason ?: "未知"}，正文长度=${raw.trim().length}）" +
                    "：${raw.trim().take(600)}"
            )
    }

    /**
     * 最近一次成功调用的用量摘要，供界面展示（诊断成本用）。
     *
     * 为什么需要它：实测单次调用成本比预估高一个量级，怀疑是推理 token 在计费
     * （V4 系默认 `effort=high`）。不把用量显示出来，就只能靠猜。
     *
     * 同时带上**实际生效的参数档位**：参数降级逻辑会在收到 400 时自动去掉
     * `response_format` / `effort`，但原先不记录结果——于是"我以为关了的开关
     * 其实没关"这种事从外表看不出来。现在把生效情况一并显示。
     */
    var lastUsageSummary: String? = null
        private set

    /** 测试连通性：发一句最小请求，返回模型回复的原文（供设置页展示）。 */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        chat("只回复两个字：正常", maxTokens = 32, wantJson = false)
    }

    // ------------------------------------------------------------------
    // 请求构造
    // ------------------------------------------------------------------

    /**
     * 调一次 Chat Completions，返回 message.content 原文。
     *
     * 参数做了**逐级降级**：平台对不同模型支持的参数不完全一致
     * （`response_format`、`effort` 都可能不被接受），任一参数返回 400 就去掉它重试，
     * 避免"key 是好的、模型也对，却因为一个参数不支持而全程失败"。
     *
     * @param wantJson true 时带 `response_format: json_object`
     * @param wantEffort true 时带 `effort: low`（结构化提取用低档即可，更快更便宜）
     */
    private fun chat(userContent: String, maxTokens: Int, wantJson: Boolean, wantEffort: Boolean = true): String {
        fun attempt(json: Boolean, effort: Boolean): String {
            val body = JSONObject().apply {
                put("model", settings.modelId)
                put("temperature", 0.0)
                put("max_tokens", maxTokens)
                if (json) put("response_format", JSONObject().put("type", "json_object"))
                if (effort) put("effort", "low")
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        put(JSONObject().put("role", "user").put("content", userContent))
                    }
                )
            }
            return postJson("${AiSettings.BASE_URL}/chat/completions", body.toString())
        }

        // 记录实际生效的参数档位：降级重试会静默丢掉参数，
        // 不记录的话"开关到底有没有关上"从外表永远看不出来。
        var profile = "json=${if (wantJson) "on" else "off"},effort=${if (wantEffort) "low" else "default"}"
        val raw = try {
            attempt(wantJson, wantEffort)
        } catch (e: IllegalStateException) {
            // 只有"参数不被接受"才值得降级重试；key/网络/额度问题重试也没用
            if (!e.message.orEmpty().contains("400")) throw e
            try {
                attempt(wantJson, false).also { profile = "json=${if (wantJson) "on" else "off"},effort=rejected" }
            } catch (e2: IllegalStateException) {
                if (!wantJson || !e2.message.orEmpty().contains("400")) throw e2
                attempt(false, false).also { profile = "json=rejected,effort=rejected" }
            }
        }
        lastRequestProfile = profile
        captureUsage(raw, profile)
        val content = extractContent(raw)
            ?: throw IllegalStateException(
                "响应里没有 choices[0].message.content" +
                    "（finish_reason=${lastFinishReason ?: "未知"}）：${raw.take(300)}"
            )
        // content 为空但响应成功：通常是推理吃光了 max_tokens（finish_reason=length）。
        // 有些模型会把正文放在 reasoning_content 里，这里做一次兜底读取。
        if (content.isBlank()) {
            val reasoning = extractReasoning(raw)
            if (!reasoning.isNullOrBlank()) return reasoning
            throw IllegalStateException(
                "模型返回了空正文" +
                    "（finish_reason=${lastFinishReason ?: "未知"}，" +
                    "很可能推理过程占满了 max_tokens，可尝试提高 MAX_OUTPUT_TOKENS）"
            )
        }
        return content
    }

    /** 最近一次请求实际生效的参数档位，例如 "json=on,effort=low"。 */
    var lastRequestProfile: String? = null
        private set

    /**
     * 从响应里读 usage，连同生效的参数档位一起格式化成摘要。
     *
     * 输出形如：`1847+326 tokens` 或 `1847+326 tokens（推理 700，effort 参数被拒）`。
     * 没有 usage 字段时也至少返回参数档位——那本身就是诊断信息。
     */
    private fun captureUsage(raw: String, profile: String) {
        lastUsageSummary = runCatching {
            val usage = JSONObject(raw).optJSONObject("usage")
            val prompt = usage?.optInt("prompt_tokens", 0) ?: 0
            val completion = usage?.optInt("completion_tokens", 0) ?: 0
            val reasoning = usage?.optJSONObject("completion_tokens_details")
                ?.optInt("reasoning_tokens", 0) ?: 0
            val tokens = when {
                prompt == 0 && completion == 0 -> "用量未知"
                reasoning > 0 -> "$prompt+$completion tokens（其中推理 $reasoning）"
                else -> "$prompt+$completion tokens"
            }
            val note = if (profile.contains("rejected")) "，$profile" else ""
            "$tokens$note"
        }.getOrNull() ?: profile
    }

    /** 最近一次响应的 finish_reason，仅供错误信息使用。 */
    private var lastFinishReason: String? = null

    /** 从 Chat Completions 响应里取出 message.content 原文（不做 JSON 假设）。 */
    private fun extractContent(raw: String): String? = runCatching {
        val root = JSONObject(raw)
        lastFinishReason = root.getJSONArray("choices")
            .getJSONObject(0)
            .optString("finish_reason")
            .takeIf { it.isNotBlank() && it != "null" }
        root.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }.getOrNull()

    /** 兜底：个别模型把正文放在 reasoning_content 里。 */
    private fun extractReasoning(raw: String): String? = runCatching {
        JSONObject(raw)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("reasoning_content")
    }.getOrNull()

    // ------------------------------------------------------------------
    // 请求构造
    // ------------------------------------------------------------------

    /**
     * 把文本行拼成给 LLM 的用户消息。
     *
     * 每行带上坐标：LLM 看得懂"这两行 top 几乎相同、一个在左一个在右"，
     * 这对判断"商品名 + 价格"是否属于同一行至关重要——也正是端侧规则最脆弱的地方。
     */
    private fun buildPayload(lines: List<TextLine>): String {
        val kept = lines.filter { !PRIVACY_LINE_RE.containsMatchIn(it.text) }
        val dropped = lines.size - kept.size
        val sb = StringBuilder()
        sb.append("下面是一张外卖订单详情页截图的 OCR 识别结果。")
        sb.append("每行格式为「序号|文本|left,top,right,bottom」，坐标为像素。")
        if (dropped > 0) {
            sb.append("\n（已按隐私要求过滤掉 $dropped 行含个人信息的内容）")
        }
        sb.append("\n\n")
        kept.forEachIndexed { index, line ->
            sb.append("$index|${line.text}|${line.left},${line.top},${line.right},${line.bottom}\n")
        }
        if (kept.isEmpty()) {
            sb.append("（OCR 未识别到任何可用文字）\n")
        }
        sb.append("\n请按要求输出 JSON。")
        return sb.toString()
    }

    private fun postJson(url: String, jsonBody: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        try {
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code：${text.take(300)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /**
         * 输出 token 上限。
         *
         * 原来是 800，实测踩坑：V4 系模型带推理，推理过程可能把 800 token 吃光，
         * 导致 HTTP 200 但 `content` 是空字符串（finish_reason=length）——
         * 表现为"有概率识别失败"，且报错里什么都没有，极难排查。
         * 3000 对"输出一个几百字节的 JSON"绰绰有余，且只有真正用到的 token 才计费。
         */
        private const val MAX_OUTPUT_TOKENS = 3000

        /**
         * 含个人信息的行：发送前过滤掉。
         *
         * 覆盖：收货地址、门牌号、联系人、手机号、订单号。
         * 宁可多滤（多滤一行不影响结构化，LLM 靠商品区就够），不能漏。
         */
        private val PRIVACY_LINE_RE = Regex(
            """(收货地址|配送地址|地址|联系|手机|电话|订单号|订单编号|骑手|门牌|\d{3}\*{2,}|\d{11})"""
        )

        /**
         * system prompt。
         *
         * **刻意压到最短**：这段每次请求都会重发，按输入 token 计费。
         * 原版 600+ 字，重写为约 300 字，判定规则一条没少，省掉的是解释性文字。
         *
         * ⚠️ 已验证**无效**的做法（2026-10-24，别再试）：
         * 曾试过在开头加"不要任何推理、分析、思考过程，第一个字符必须是 {"，
         * 想以此压低推理 token。实测**反而暴涨**：汉堡 1234→1655，
         * 煎饼王 596→2411（+304%）。模型会花更多 token 去思考"怎么才能不思考"。
         * 已回退。要降成本请从别处想办法（换模型、减输入），不要动这句话。
         */
        private val SYSTEM_PROMPT = """
            你是外卖订单截图 OCR 文本的结构化提取器。输入是「序号|文本|left,top,right,bottom」格式的文本行（含像素坐标）。只输出一个 JSON 对象，不要解释、不要 Markdown 代码块。

            字段（缺失填 null）：storeName 店铺名（去掉"直播中"">"等装饰）/ productName 主商品名（金额最大的那件，去掉规格与数量后缀）/ productPrice 主商品单价 / quantity 数量（取不到填 1）/ actualPaid 整单实付 / platform（MEITUAN|TAOBAO_FLASH|JD）/ itemCount 商品件数 / discountTotal 优惠合计 / notes 疑点 / items 商品明细数组，每项 {"name":商品名,"price":单价,"quantity":数量}，按金额从大到小，识别不到给 []。

            规则：
            1 实付只认「实付/实付款/实际支付」后的金额，其次「合计/总计/订单金额」后的金额；绝不能拿商品价、打包费、配送费当实付。
            2 商品原价合计不等于实付（有优惠），不要用加总推导。
            3 top 几乎相同、一左一右的两行，很可能是「商品名+价格」，按同一件商品处理。
            4 带逗号的口味规格描述（如"标准,正常冰,七分糖"）不是商品，即使旁边有价格（常是划线原价）。
            5 打包费/餐盒费/配送费/准时宝/放心吃/红包/满减/立减 都不是商品。
            6 数量只认 x2、×1 这类明确写法；规格里的"200克""1人份""中杯"都不是数量。
            7 同一行两个价格时，取右侧/较小的（通常是当前价）。
            8 平台：美团→MEITUAN；淘宝/闪购→TAOBAO_FLASH；京东→JD。
            9 拿不准填 null 并在 notes 说明，绝不编造数值。
            10 items 只放真正的商品，打包费/餐盒费/配送费/优惠券/红包都不进；不要把商品价加总去凑实付。
        """.trimIndent()
    }
}
