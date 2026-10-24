package com.example.foodexpense.ocr

import com.example.foodexpense.data.entity.OrderItem
import com.example.foodexpense.data.entity.Platform

/**
 * 把 LLM 返回的 JSON 文本转成 [ParsedOrder]。
 *
 * ## 为什么不直接用 `org.json`
 * 项目的单元测试跑在 JVM 上，`org.json` 属于 `android.jar`，在 `isReturnDefaultValues = true`
 * 下只会返回默认值（null / 0），测不出任何东西。所以这里用一个**极简的扁平 JSON 对象解析器**
 * （纯 Kotlin，无 Android 依赖），既能在设备上跑，也能被 JVM 单测直接覆盖。
 *
 * 之所以敢用极简解析器，是因为请求里带了 `response_format = json_object`，
 * LLM 被约束为输出一个扁平 JSON 对象，字段全是 string / number / null，没有嵌套结构。
 */
object LlmJsonMapper {

    /** 解析失败 / 字段全空时返回 null（调用方据此降级到端侧规则）。 */
    fun toParsedOrder(raw: String): ParsedOrder? {
        val fields = parseFlatObject(raw) ?: return null
        if (fields.isEmpty()) return null

        val storeName = fields["storeName"]?.takeIf { it.isNotBlank() }
        val productName = fields["productName"]?.takeIf { it.isNotBlank() }
        val productPrice = fields["productPrice"]?.toDoubleOrNull()
        val actualPaid = fields["actualPaid"]?.toDoubleOrNull()
        val quantity = fields["quantity"]?.toDoubleOrNull()?.toInt()?.coerceIn(1, 99) ?: 1
        val itemCount = fields["itemCount"]?.toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0
        val discountTotal = fields["discountTotal"]?.toDoubleOrNull()
        val originalTotal = fields["originalTotal"]?.toDoubleOrNull()
        val notes = fields["notes"]?.takeIf { it.isNotBlank() }
        val platform = fields["platform"]?.takeIf { it.isNotBlank() }?.let { Platform.fromName(it) }

        val parsed = ParsedOrder(
            storeName = storeName,
            productName = productName,
            productPrice = productPrice,
            productQuantity = quantity,
            actualPaid = actualPaid,
            platform = platform,
            itemCount = itemCount,
            discountTotal = discountTotal,
            originalTotal = originalTotal,
            items = parseItems(raw)
        )
        // 一个有效字段都没有 → 视为解析失败
        return if (parsed.isEmpty && notes == null) null else parsed
    }

    /**
     * 解析扁平 JSON 对象的顶层字段。
     *
     * 支持 `"key": "str"` / `"key": 12.5` / `"key": null` / `"key": true`，
     * 忽略嵌套对象与数组（本场景不会出现）。值里的转义引号按 `\"` 处理。
     *
     * **容错**：LLM 即使在 `response_format: json_object` 下，也可能在 JSON 前后
     * 带一句解释（"好的，结果如下："）。所以这里取**第一个 `{` 到最后一个 `}`** 之间
     * 的内容，而不是要求整个字符串就是一个 JSON 对象。
     */
    internal fun parseFlatObject(raw: String): Map<String, String?>? {
        var text = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        // 全角括号偶尔会被模型用出来（尤其是复制粘贴场景），归一成半角
        text = text.replace('｛', '{').replace('｝', '}')

        val start = text.indexOf('{')
        if (start < 0) return null
        // 没有收尾 `}` 时（响应被 max_tokens 截断）也尝试解析到结尾，
        // 前面的关键字段照样能取到，总比整体失败好。
        val end = text.lastIndexOf('}').let { if (it > start) it else text.length }
        val body = text.substring(start + 1, end)
        val result = LinkedHashMap<String, String?>()
        var i = 0
        while (i < body.length) {
            // 找 key
            val keyStart = body.indexOf('"', i)
            if (keyStart < 0) break
            val keyEnd = body.indexOf('"', keyStart + 1)
            if (keyEnd < 0) break
            val key = body.substring(keyStart + 1, keyEnd)
            val colon = body.indexOf(':', keyEnd + 1)
            if (colon < 0) break
            var j = colon + 1
            while (j < body.length && body[j].isWhitespace()) j++
            if (j >= body.length) break

            when (body[j]) {
                '"' -> {
                    val sb = StringBuilder()
                    var k = j + 1
                    while (k < body.length) {
                        val c = body[k]
                        if (c == '\\' && k + 1 < body.length) {
                            sb.append(body[k + 1]); k += 2; continue
                        }
                        if (c == '"') break
                        sb.append(c); k++
                    }
                    result[key] = sb.toString()
                    i = k + 1
                }
                'n', 'N' -> { // null
                    result[key] = null
                    i = body.indexOf(',', j).let { if (it < 0) body.length else it + 1 }
                }
                't', 'T' -> { // true
                    result[key] = "true"
                    i = body.indexOf(',', j).let { if (it < 0) body.length else it + 1 }
                }
                'f', 'F' -> { // false
                    result[key] = "false"
                    i = body.indexOf(',', j).let { if (it < 0) body.length else it + 1 }
                }
                '{', '[' -> {
                    // 嵌套结构：本场景不应出现，跳过整个值
                    result[key] = null
                    i = skipNested(body, j)
                }
                else -> { // number
                    val end = body.indexOf(',', j).let { if (it < 0) body.length else it }
                    result[key] = body.substring(j, end).trim()
                    i = end + 1
                }
            }
        }
        return result
    }

    /**
     * 解析顶层 `items` 数组，得到商品明细列表。
     *
     * 为什么单独写一套而不是扩展 [parseFlatObject]：平铺解析器遇到 `[` 会整段跳过
     * （当初设计时就假定"没有嵌套结构"）。现在明细是唯一的嵌套结构，
     * 与其把主流程改复杂，不如单独把数组拆开，**每个元素再交给 [parseFlatObject]** ——
     * 转义引号、截断容错这些已经踩过的坑就能直接复用。
     *
     * **一律不抛异常**：明细是附属信息，解析不出来就当"没有明细"（返回空列表），
     * 绝不能因为它把整条识别结果判成失败。
     */
    internal fun parseItems(raw: String): List<OrderItem> {
        val text = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
            .replace('｛', '{').replace('｝', '}')

        val keyIdx = text.indexOf("\"items\"")
        if (keyIdx < 0) return emptyList()
        val arrStart = text.indexOf('[', keyIdx)
        if (arrStart < 0) return emptyList()

        // 数组被 max_tokens 截断时没有收尾 `]`，skipNested 会返回 text.length，
        // 此时按"到结尾为止"处理，能救回多少算多少。
        val arrEnd = skipNested(text, arrStart)
        val bodyEnd = if (arrEnd <= text.length && arrEnd > arrStart && text[arrEnd - 1] == ']') {
            arrEnd - 1
        } else {
            text.length
        }
        val body = text.substring((arrStart + 1).coerceAtMost(bodyEnd), bodyEnd)

        return body.splitTopLevelObjects().mapNotNull { chunk ->
            val item = parseFlatObject(chunk) ?: return@mapNotNull null
            val name = item["name"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            OrderItem(
                name = name,
                price = item["price"]?.toDoubleOrNull() ?: 0.0,
                quantity = item["quantity"]?.toDoubleOrNull()?.toInt()?.coerceIn(1, 99) ?: 1
            )
        }
    }

    /**
     * 按**顶层**深度切出每一个 `{ ... }` 片段。
     *
     * 只按括号深度切，不识别字符串内的括号——商品名里出现 `{`/`}` 的概率极低，
     * 换来的是实现简单、且不会在 JSON 被截断时抛异常。
     */
    private fun String.splitTopLevelObjects(): List<String> {
        val chunks = ArrayList<String>()
        var depth = 0
        var start = -1
        for (i in indices) {
            when (this[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        chunks += substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return chunks
    }

    /** 跳过配对的 {} / []，返回其后的下标。 */
    private fun skipNested(body: String, start: Int): Int {
        var depth = 0
        var k = start
        while (k < body.length) {
            when (body[k]) {
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return k + 1
                }
            }
            k++
        }
        return body.length
    }
}
