package com.example.foodexpense.ocr

import com.example.foodexpense.data.entity.Platform

/**
 * 外卖订单截图的**规则解析器**（纯 Kotlin，零 Android 依赖）。
 *
 * 输入是 OCR 出来的若干行文本（含版面坐标），输出一份 [ParsedOrder] 候选。
 * 全部逻辑是确定性规则，不做任何网络请求、不猜、不硬套 —— 解析不到就是 null。
 *
 * ## 解析流水线
 * 0. **几何行重建**：把 ML Kit 的"视觉块"重建成"语义行"（见 [rebuildVisualLines]）
 * 1. 预处理：全角转半角 → 按 (top, left) 排序 → 每行派生 rawText / moneyText
 * 2. P1 店铺名：标签 → 位置启发式 → null（三级降级）
 * 3. P2 商品行与主商品：按"行内最后一个金额"取最大者
 * 4. P3 数量：只认 `x1`/`x2` 这类后缀，绝不把 `(200克)` 当数量
 * 5. P4 实付：精确标签 → 宽泛合计 → 含 ¥ 的兜底行 → null
 * 6. P5/P6 优惠/原价（辅助）与平台
 *
 * ## 为什么必须有第 0 步"几何行重建"
 * ML Kit 对密排截图是**按视觉分栏切块**的，右对齐的价格经常被切成独立的 TextLine：
 *
 * ```
 * 「三拼霸霸奶茶」        ← TextLine A（left=60）
 *          「¥12.7」      ← TextLine B（left=880，与 A 同一视觉行）
 * ```
 *
 * 如果按"一条 TextLine = 一个语义行"来解析，A 没有金额 → 不是商品行；
 * B 去掉金额后 residue 为空 → 也不是商品行。**两个真实商品全部漏掉**，
 * 而残留一个 `-` 号的 `-¥5` 反而成了唯一"商品"，实付也被单商品兜底逻辑带偏。
 * 这是"视觉行 vs 语义行"的结构性错位，靠加词表救不回来，必须先把几何还原。
 *
 * ## 关键防错点
 * - [stripNonMoneyNumbers] 是整个解析的地基：先把 `200克` / `4.8分` / `1人份` 这类
 *   "假数字"一次性消灭，否则商品名和金额全是脏数据。
 * - 主商品价格的上界校验必须用 `实付 + 优惠`，**不能**只用实付：
 *   样例中主商品 39.9 > 实付 26.4，只用实付会把正确答案判掉。
 * - 规格行（`标准,正常冰,七分糖(推荐) ¥12.8`）含金额且不含费用词，
 *   天然会被误判成商品行，而且它的划线原价往往**高于**主商品单价，会抢走主商品。
 *   所以商品行判定额外要求"金额左侧的文本不含规格词、不含逗号、长度 ≤ 14"。
 * - 任何异常都被 [parse] 最外层捕获，本类**绝不向上抛异常**。
 */
class OrderScreenshotParser {

    // ======================================================================
    // 公开入口
    // ======================================================================

    /**
     * 解析一批 OCR 文本行。
     *
     * @return 空输入或内部异常时返回 `null`；解析成功但一个字段都没识别出来时
     *         返回 `isEmpty == true` 的空对象，由调用方决定如何提示用户。
     */
    fun parse(lines: List<TextLine>): ParsedOrder? {
        if (lines.isEmpty()) return null
        return try {
            parseInternal(lines)
        } catch (_: Exception) {
            // 总闸：解析器永远不向上抛异常，失败即"没识别到"
            null
        }
    }

    // ======================================================================
    // 主流程
    // ======================================================================

    private fun parseInternal(input: List<TextLine>): ParsedOrder {
        val normalized = input
            .map { TextLine(normalize(it.text), it.left, it.top, it.right, it.bottom) }
            .filter { it.text.isNotBlank() }

        if (normalized.isEmpty()) return ParsedOrder()

        // ---------- 第 0 步：几何行重建（必须在所有规则之前） ----------
        val visualLines = LineGeometry.rebuildVisualLines(normalized)

        val prepared = visualLines.mapIndexed { i, v ->
            Line(
                index = i,
                rawText = v.text,
                moneyText = stripNonMoneyNumbers(v.text),
                blocks = v.blocks,
                left = v.left,
                right = v.right
            )
        }

        if (prepared.isEmpty()) return ParsedOrder()

        // ---------- 页面金额右界（"右对齐裸整数"价格识别的基准线） ----------
        // 外卖详情页的价格列是右对齐的，所以"带 ¥ 的行"的最右界就是这一列的位置。
        // 裸整数（OCR 把 `¥4` 认成 `4`）只有在贴着这条基准线时才算金额。
        // 全 0 坐标（无 boundingBox）时为 0，此时整条规则自动禁用，避免误判。
        val moneyRightEdge = prepared
            .filter { it.moneyText.contains('¥') }
            .maxOfOrNull { it.right } ?: 0

        // ---------- P5 辅助金额（先算，主商品上界校验要用） ----------
        val discountTotal = findLabeledAmount(prepared, DISCOUNT_LABELS)
        val originalTotal = findLabeledAmount(prepared, ORIGINAL_LABELS)

        // ---------- P2 商品行 + 主商品 ----------
        val items = prepared
            .filter { isItemLine(it, moneyRightEdge) }
            .map { Item(it, amountsOf(it, moneyRightEdge)) }

        val main = pickMainItem(items)

        // ---------- P4 实付（必须在商品行统计之后：P4-c 兜底要看 itemCount） ----------
        val actualPaid = findActualPaid(prepared, items.size)

        var productPrice = main?.lastAmount
        // ==================================================================
        // P2-e 上界校验
        // ==================================================================
        // 启用条件（四个都必须满足）：已解析出优惠、已解析出实付、
        // 主商品行内**有多个金额**（`amounts.size > 1`）。
        // 注意"行内只有一个金额"时**整块直接跳过**，价格原样保留 ——
        // 也就是说这里从来不是"单金额价格的上界保护"，别照着它去理解单金额场景。
        //
        // ---- 关于 `if (fallback.isNotEmpty())` 这个"看起来像漏了 else"的写法 ----
        // 回退集合为空时**有意保留原值**，不是遗漏。⚠️ 请勿"顺手改成 null"。
        //
        // 回退集合为空的充要条件：
        //   该行有 ≥2 个金额，且**全部**都大于 `实付 + 优惠 + 0.01`。
        //
        // 什么情况下会这样？最常见的原因不是"价格取错了"，而是
        // **优惠没被完整解析出来** —— 那么上界本身就被算小了，行内的金额反而
        // 大概率是对的（比如商品原价 39.9、实付 12.3，但"已优惠"那一行 OCR 丢了，
        // 上界只剩 12.31，行内两个金额都越界）。
        //
        // 此时置空 = 把一个**正确的值**删掉，用户得手动重填价格；
        // 这是为了防一个罕见的错价，而付出常态的丢数据。
        // 而"猜不到就不猜"这条原则的前提是**多来源互相矛盾、无法判断**
        // （例如同一行同时有 `¥45.9` 和 `¥33.9`）；这里的越界是"上界算错了"，
        // 不是"有多个候选无法取舍"，不属于"猜"。
        //
        // 所以这个位置的取舍明确偏向**保留数据**，而不是保守置空。
        //
        // ---- 将来要降低这条的风险，正确的杠杆在哪 ----
        // 去提升 `discountTotal` 的解析成功率（优先取「合计/总计」行内的优惠标签，
        // 而不是全文第一条命中的优惠标签），**不要**在这里对价格做减法。
        // 若先在这里改成 null，只会把"优惠解析不全"的后果放大成"价格丢失"。
        if (main != null && productPrice != null && discountTotal != null &&
            actualPaid != null && main.amounts.size > 1
        ) {
            val upperBound = actualPaid + discountTotal + 0.01
            if (productPrice > upperBound) {
                val fallback = main.amounts.filter { it > 0.0 && it <= upperBound }
                // 空集合时保留原值，理由见上方注释（有意为之，勿改成 null）
                if (fallback.isNotEmpty()) productPrice = fallback.max()
            }
        }

        val productName = main?.let { m -> cleanProductName(m.line, moneyRightEdge) }
        val quantity = main?.let { parseQuantity(it.line.rawText) } ?: 1

        // ---------- P1 店铺名（依赖第一个商品行的位置，故放在最后） ----------
        // 用**第一个商品行**而不是主商品行做锚点：主商品可能是靠下的那件，
        // 从它往上扫会先经过别的商品行，虽然那些行因"含 ¥+数字"被否掉，
        // 但用第一个商品行语义更直接、也更稳。
        val storeName = findStoreName(prepared, items.firstOrNull()?.line?.index)


        return ParsedOrder(
            storeName = storeName,
            productName = productName,
            productPrice = productPrice,
            productQuantity = quantity,
            actualPaid = actualPaid,
            platform = detectPlatform(prepared),
            itemCount = items.size,
            discountTotal = discountTotal,
            originalTotal = originalTotal
        )
    }


    // ======================================================================
    // P1 店铺名
    // ======================================================================

    /**
     * 三级降级找店铺名：
     * - P1-a：命中「店铺 / 商家名称 / 商家 / 门店」标签，取标签后的值
     * - P1-b：从**第一个商品行往上扫**，取最近的一条"看起来像店名"的行
     * - P1-c：都失败 → null（不猜）
     *
     * ## 为什么 P1-a 的候选也要过 [isPlausibleStoreLine]
     * 真实截图里店铺行右侧有按钮「⊙ 进商家粉丝群」，文本里含 `商家` 二字，
     * 会被 [STORE_LABEL_RE] 当成标签命中，标签后恰好是 `粉丝群` ——
     * 不去校验就会把店名解析成「粉丝群」。所以标签法的候选必须同样过白名单。
     *
     * ## 为什么 P1-b 必须"从商品行往上扫"而不是"自上而下取第一条"
     * 真机截图最上方是状态栏附近的水印/角标（实测拿到 `C684` at top=56），
     * 它完全能通过 [isPlausibleStoreLine]（长度 4、含字母、含数字但无 ¥、无排除词）。
     * 自上而下取第一条会稳定地选中这种水印。
     *
     * 而"店铺名一定紧贴在商品列表上方"是这个版式的**结构性事实**：
     * 店名、评分、按钮那一行下面紧接着就是第一件商品。
     * 所以用"空间上最近"来判定，比给 `C684` 加词表稳得多 ——
     * 也不会像"必须含汉字"那样误杀 `KFC`、`7-11便利店`。
     */
    private fun findStoreName(prepared: List<Line>, firstItemIndex: Int?): String? {
        // ---- P1-a：标签法 ----
        for (i in prepared.indices) {
            val line = prepared[i]
            val match = STORE_LABEL_RE.find(line.rawText) ?: continue
            val afterLabel = line.rawText
                .substring(match.range.last + 1)
                .trim()
                .trimStart(':', '：', '-', '—', ' ')
                .trim()
            val candidate = trimStoreTail(afterLabel.replace(Regex("""\s+"""), " ").trim())
            if (candidate.length in 2..30 && !isMoneyOnly(candidate) && isPlausibleStoreLine(candidate)) {
                return candidate
            }
            // 标签独占一行 → 取下一行（下一行不能是金额行 / 关键词行）
            val next = prepared.getOrNull(i + 1) ?: continue
            val nextCandidate = trimStoreTail(storeNameCandidate(next))
            if (nextCandidate.isBlank()) continue
            if (isMoneyOnly(nextCandidate)) continue
            if (mentionsAnyKeyword(nextCandidate)) continue
            if (isPlausibleStoreLine(nextCandidate)) return nextCandidate
        }

        // ---- P1-b：从第一个商品行往上扫，取**最近**的一条合理行 ----
        // 注意候选是"簇内最左块 + 裁掉 UI 装饰后缀"，不是整行拼接文本：
        // 整行拼接会把右侧按钮（`e进商家粉丝群`）也带上，长度超限且含排除词。
        if (firstItemIndex != null) {
            val hit = scanStoreCandidateUpward(prepared, firstItemIndex - 1)
            if (hit != null) return hit
        }

        // ---- P1-b'：没有商品行（或往上扫全被否掉）→ 退回自上而下（仍只看商品行之前） ----
        val upperBound = firstItemIndex ?: prepared.size
        val searchRange = prepared.filter { it.index < upperBound }
        val pool = searchRange.ifEmpty { prepared }
        for (line in pool) {
            val hit = storeCandidateOrNull(line) ?: continue
            return hit
        }

        // ---- P1-c：不猜 ----
        return null
    }

    /** 自上而下扫到第一条可用的店铺候选。 */
    private fun scanStoreCandidateUpward(prepared: List<Line>, fromIndex: Int): String? {
        for (i in fromIndex downTo 0) {
            val hit = storeCandidateOrNull(prepared[i]) ?: continue
            return hit
        }
        return null
    }

    /**
     * 把一条语义行转成"店铺名候选"，不合格返回 null。
     *
     * ## 为什么要有"裁剪后剩余主体长度"这道检查
     * `trimStoreTail` 会贪婪剥掉尾部的 UI 词。对真实店铺行是好事
     * （`蜜雪冰城(秋园雅苑店) h直播中>` → `蜜雪冰城(秋园雅苑店)`），
     * 但对**整行都是 UI 文案**的横幅是灾难：
     *
     * ```
     * 今日领券  --剥掉「领券」-->  今日   ← 这是个"像店名"的垃圾结果
     * ```
     *
     * `今日` 能通过 [isPlausibleStoreLine]（2 个汉字、无排除词），
     * 于是横幅被当成店名。所以：**只要发生了裁剪，且裁完只剩不到
     * [MIN_STORE_BODY_LEN] 个字符，就认为整行都是装饰，整条否决。**
     *
     * 这是"宁可留空也不填错"（与解析器整体口径一致），
     * 代价是 `瑞幸 领券` 这种"2 字店名 + 按钮"会漏掉 → 返回 null，而不是返回错名字。
     */
    private fun storeCandidateOrNull(line: Line): String? {
        val raw = storeNameCandidate(line)
        if (raw.isBlank()) return null
        val candidate = trimStoreTail(raw)
        if (candidate.isBlank()) return null
        if (candidate.length != raw.trim().length && candidate.length < MIN_STORE_BODY_LEN) {
            return null
        }
        return candidate.takeIf { isPlausibleStoreLine(it) }
    }

    /**
     * 取一条语义行的"店铺名候选"：**簇内最左的块**，并裁掉右侧 UI 装饰。
     *
     * 店铺行在真实截图里是：`蜜雪冰城（秋雅雅苑店）lu 直播中 >` + `⊙ 进商家粉丝群`，
     * 店名只在最左块里。另外 OCR 偶尔会把店名从括号处切成两块
     * （`蜜雪冰城` + `(秋雅雅苑店)`），所以对"紧随其后的括号块"做一次合并。
     */
    private fun storeNameCandidate(line: Line): String {
        val blocks = line.blocks.ifEmpty { listOf(line.rawText) }
        val head = StringBuilder()
        for (raw in blocks) {
            val block = stripNonMoneyNumbers(raw).trim()
            if (block.isEmpty()) continue
            if (head.isEmpty()) {
                head.append(block)
                continue
            }
            // 只在"下一块是括号补充说明"时合并，其余（按钮 / 直播标记）一律不并入
            if (block.startsWith("(") || block.startsWith("（")) {
                head.append(block)
            } else {
                break
            }
        }
        return head.toString().trim()
    }

    /**
     * 贪婪裁掉店名尾部的 UI 装饰：箭头、`直播中`、`进商家粉丝群`、`lu` 这类小标签。
     *
     * 反复裁到"干净"为止（`蜜雪冰城(店)lu 直播中 >` →
     * `蜜雪冰城(店)lu 直播中` → `蜜雪冰城(店)lu` → `蜜雪冰城(店)`）。
     *
     * 对已经干净的名字是**幂等的**（`麦当劳(科技园店)` / `7-11便利店` 原样返回），
     * 所以对老用例零副作用。
     */
    private fun trimStoreTail(raw: String): String {
        var s = raw.trim()
        var guard = 0
        while (s.isNotEmpty() && guard++ < 16) {
            val before = s
            // 1) 尾部箭头 / 分隔符 / 项目符号
            s = TAIL_SYMBOL_RE.replace(s, "").trim()
            if (s != before) continue
            // 1b) 尾部"纯符号"的独立 token（如块分隔符 `⊙`）：
            //     只要末段不含任何字母/数字/汉字，就一定是图标而不是店名的一部分
            val lastSpace = s.lastIndexOf(' ')
            if (lastSpace > 0) {
                val tailToken = s.substring(lastSpace + 1)
                if (tailToken.isNotEmpty() &&
                    tailToken.none { it.isLetterOrDigit() || isCjk(it) }
                ) {
                    s = s.substring(0, lastSpace).trim()
                    continue
                }
            }
            // 2) 尾部 UI 词（长的优先，避免 `进商家` 抢在 `进商家粉丝群` 前面）
            val hit = STORE_TAIL_WORDS.firstOrNull { s.length > it.length && s.endsWith(it) }
            if (hit != null) {
                s = s.dropLast(hit.length).trim()
                continue
            }
            // 3) 尾部"直播小标签"式的短拉丁串（如 `lu` / `live`）：
            //    必须紧跟在汉字或右括号之后，且剩余部分还有至少 4 个字符，
            //    这样 `KFC`、`McDonald's` 这类真英文店名不会被误裁。
            val latin = TRAILING_LATIN_TAG_RE.find(s)
            if (latin != null && latin.range.last == s.lastIndex &&
                s.length - latin.value.trim().length >= 4
            ) {
                s = s.substring(0, latin.range.first).trim()
                continue
            }
            break
        }
        return s
    }

    /**
     * 判断某行"像不像店铺名"。
     *
     * 注意这里用的是 [String.equals]（不是 contains）去比噪声词：
     * 「美团买菜」是合法店名，不能被「美团」这条噪声规则连带否掉。
     */
    private fun isPlausibleStoreLine(text: String): Boolean {
        val t = text.trim()
        if (t.length !in 2..20) return false
        if (NOISE_TEXTS.any { t.equals(it, ignoreCase = true) }) return false
        if (NOISE_PREFIX.any { t.contains(it) }) return false
        if (STORE_EXCLUDE.any { t.contains(it) }) return false
        // 纯数量标记（`x1`）不是店名
        if (QUANTITY_ONLY_RE.matches(t)) return false
        // 打码的收件人 / 手机号（`刘**(先生)182****0814`）不是店名
        if (MASKED_INFO_RE.containsMatchIn(t)) return false
        // 至少含一个字母或中日韩文字，纯数字/符号行不是店名
        if (!t.any { it.isLetter() || isCjk(it) }) return false
        // 既含数字又含 ¥ → 是金额行，不是店名
        if (t.any { it.isDigit() } && t.contains('¥')) return false
        return true
    }

    /** 只由金额与符号组成的行（如 `¥26.4`）。 */
    private fun isMoneyOnly(text: String): Boolean {
        val residue = MONEY_CLEAN_RE.replace(text, " ")
            .replace("¥", " ")
            .replace(Regex("""[\s:：,，.。]+"""), "")
        return residue.isEmpty()
    }

    private fun mentionsAnyKeyword(text: String): Boolean =
        STORE_EXCLUDE.any { text.contains(it) } ||
            EXACT_PAID_LABELS.any { text.contains(it) } ||
            SUMMARY_LABELS.any { text.contains(it) } ||
            DISCOUNT_LABELS.any { text.contains(it) }

    // ======================================================================
    // P2 商品行与主商品
    // ======================================================================

    /**
     * 判断某行是不是"商品行"。
     *
     * 除了"既有商品名又有可信金额"之外，还必须通过**规格行拦截**：
     * 重建后的规格行形如 `标准,正常冰,七分糖(推荐),换冷链... ⌄ ¥12.8`，
     * 它含金额、不含费用词，但**不是商品**；而且它的划线原价（12.8）常常比
     * 主商品折后价（12.7）还高，放进来会直接抢走主商品。
     *
     * 判定规则（三条同时满足才算商品）：
     * 1. 金额**左侧**的文本非空（拦掉被列切分出来的纯价格行 `¥12.7` / `-¥5`）；
     * 2. 左侧文本不含逗号（规格描述几乎必带逗号）且不含规格词；
     * 3. 左侧文本长度 ≤ [MAX_ITEM_LEFT_LEN]（商品名不会太长，长了多半是规格串）。
     */
    private fun isItemLine(line: Line, moneyRightEdge: Int = 0): Boolean {
        val moneyText = line.moneyText
        // 店铺标签行不是商品行
        if (STORE_LABEL_RE.containsMatchIn(line.rawText)) return false
        // 必须至少有一个"可信金额"
        if (!hasTrustedAmount(line, moneyRightEdge)) return false
        // 费用 / 优惠 / 合计类行不是商品行
        if (ITEM_EXCLUDE.any { moneyText.contains(it) }) return false
        // 去掉金额与空格后还剩东西，才可能是商品名
        val residue = MONEY_CLEAN_RE.replace(moneyText, " ")
            .replace("¥", " ")
            .replace(Regex("""\s+"""), "")
        if (residue.isEmpty()) return false

        // ---- 规格行拦截（几何重建之后才需要，因为之前规格与价格不在同一行） ----
        val leftPart = leftPartOf(moneyText)
        if (leftPart.isBlank()) return false
        val leftBare = QUANTITY_RE.replace(leftPart, " ")
            .replace(LEADING_SYMBOL_RE, "")
            .replace(Regex("""\s+"""), "")
            .trim()
        if (leftBare.isEmpty()) return false
        if (leftBare.length > MAX_ITEM_LEFT_LEN) return false
        if (leftBare.contains(',') || leftBare.contains('，')) return false
        // ---- 已知限制（2026-10-24，未修）----
        // [looksLikeSpec] 用的是 contains 匹配，所以**商品名本身包含规格词**时会被误杀，
        // 例如 `推荐套餐`、`标准双人餐`、`大杯装奶茶`（命中 [SPEC_HINTS_MULTI]）。
        //
        // 这类名字与"规格行"在纯文本上不可区分，除非改成 **token 边界匹配** ——
        // 参考 [SPEC_SINGLE_TOKEN_RE] 对 `热` / `换` 的处理：要求独立 token 而非子串。
        //
        // 暂不修：解析器已冻结，且没有真机样本证明该形状高频。
        // 将来若遇到，按 token 边界匹配处理，**不要继续加排除词**。
        // ------------------------------------
        // 判"是不是规格行"时**先剥掉括号片段**：括号的归类是 [cleanProductName] 的职责
        // （[isSpecFragment] 会区分 `(200克)` 该删、`(含可乐薯条鸡翅)` 该留），
        // 这里再用 contains 判一遍会造成两处判据打架：
        // `超值套餐(推荐)` 明明是商品 + 可剥离的角标，却因为这个 contains 被判成规格行、
        // 整条商品消失。剥完之后若什么都不剩（如 `(推荐)` 独占左半），仍按规格行处理。
        val leftForSpec = PAREN_FRAGMENT_RE.replace(leftBare, " ")
            .replace(Regex("""\s+"""), "")
            .trim()
        if (leftForSpec.isEmpty()) return false
        if (looksLikeSpec(leftForSpec)) return false

        return true
    }

    /** 取"第一个金额出现在哪里"左侧的文本（金额之前的部分就是商品名区）。 */
    private fun leftPartOf(moneyText: String): String {
        val m = MARKED_AMOUNT_ANY_RE.find(moneyText) ?: BARE_DECIMAL_RE.find(moneyText)
            ?: return moneyText
        return moneyText.substring(0, m.range.first)
    }

    /**
     * 左侧文本是否像"规格描述"。
     *
     * - 多字规格词（`标准` / `七分糖` / `大杯` …）用 contains 判定，不会误伤商品名；
     * - 单字规格词（`热` / `换`）必须成**独立 token** 才算，
     *   否则「热干面」「换」开头以外的正常商品名会被误杀。
     */
    private fun looksLikeSpec(left: String): Boolean {
        if (SPEC_HINTS_MULTI.any { left.contains(it) }) return true
        return SPEC_SINGLE_TOKEN_RE.containsMatchIn(left)
    }

    /**
     * 可信金额：带 `¥` 前缀，或形如 `d.dd` 且位于行尾（可带数量后缀），
     * 或**右对齐的裸整数**（见 [bareRightAlignedInteger]）。
     */
    private fun hasTrustedAmount(line: Line, moneyRightEdge: Int): Boolean {
        if (MARKED_AMOUNT_RE.containsMatchIn(line.moneyText)) return true
        if (LINE_END_DECIMAL_RE.containsMatchIn(line.moneyText.trim())) return true
        return bareRightAlignedInteger(line, moneyRightEdge) != null
    }

    /**
     * 识别"**右对齐的裸整数**"价格，返回其数值；不是则 null。
     *
     * ## 为什么需要这条规则
     * 真机上 ML Kit 会把 `¥4` 的 `¥` 整个漏掉，只留下 `4`。
     * 如果只认"带 ¥"或"行尾小数"，这件商品会被直接丢弃 → `itemCount` 少 1。
     * 而 `itemCount` 是 P4-c 兜底的门禁：多商品 + 无合计行时，
     * `itemCount` 低估会让 P4-c 把某个商品单价当成整单实付 —— **静默少记钱**。
     *
     * ## 三条同时满足才算金额
     * 1. **位于行尾，且前面是空格或汉字**：`雪王小宝杯 4` ✓；
     *    `C684` 的 `684` 前面是字母 `C` ✗；`x1` 的 `1` 前面是 `x` ✗；`11:41` 的 `41` 前面是 `:` ✗。
     * 2. **与页面金额右界相差 ≤ [MONEY_EDGE_TOLERANCE]**：`4` 的 right=1269、金额右界=1272 → 差 3 ✓；
     *    `x1` 的 right=342 ✗。这条是核心：**价格列是右对齐的**，而商品名/数量左对齐。
     * 3. **左侧有商品名**：`4` 单独一行时左侧为空 → 不是"商品 + 价格"。
     */
    private fun bareRightAlignedInteger(line: Line, moneyRightEdge: Int): Double? {
        // 无坐标（全 0）时整条规则禁用：否则 zero-coord 用例里所有裸整数都会被当成钱
        if (moneyRightEdge <= 0) return null
        val text = line.moneyText.trim()
        val match = TRAILING_BARE_INTEGER_RE.find(text) ?: return null
        if (kotlin.math.abs(line.right - moneyRightEdge) > MONEY_EDGE_TOLERANCE) return null
        val left = text.substring(0, match.range.first).trim()
        if (left.isEmpty()) return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return if (value > 0.0) value else null
    }

    /**
     * 取一条商品行的金额列表。
     *
     * 优先用 [amountsIn]（带 ¥ / 裸小数）；一个都没有时退回"右对齐裸整数"，
     * 这样 `雪王小宝杯 4` 的 `4` 既能让它成为商品行，也能作为它的价格。
     */
    private fun amountsOf(line: Line, moneyRightEdge: Int): List<Double> {
        val parsed = amountsIn(line.moneyText)
        if (parsed.isNotEmpty()) return parsed
        val bare = bareRightAlignedInteger(line, moneyRightEdge) ?: return emptyList()
        return listOf(bare)
    }

    /**
     * 主商品 = 行内最后一个金额最大的那一行；金额全解析失败时取最靠上的一行。
     */
    private fun pickMainItem(items: List<Item>): Item? {
        if (items.isEmpty()) return null
        val withAmount = items.filter { it.lastAmount != null }
        if (withAmount.isEmpty()) return items.minByOrNull { it.line.index }
        return withAmount.maxWithOrNull(
            compareBy<Item> { it.lastAmount ?: 0.0 }.thenByDescending { it.line.index }
        )
    }

    /**
     * 清洗商品名：删金额 → 删活动标签 → 删规格片段 → 删数量后缀 → 删行首序号。
     * 清洗后为空则退回原文前 40 字；仍为空则 null。
     */
    private fun cleanProductName(line: Line, moneyRightEdge: Int): String? {
        var s = line.moneyText
        s = MARKED_AMOUNT_ANY_RE.replace(s, " ")
        s = BARE_DECIMAL_RE.replace(s, " ")
        // 右对齐裸整数价格（真机把 `¥4` 认成 `4`）也要从名字里去掉，
        // 否则 `雪王小宝杯 4` 会留下整个带价格的串当商品名。
        if (bareRightAlignedInteger(line, moneyRightEdge) != null) {
            s = TRAILING_BARE_INTEGER_RE.replace(s.trimEnd(), " ")
        }
        s = PRODUCT_TAG_RE.replace(s, " ")
        // 裸的单字活动角标（不带方括号）：真机上 `折` 常被 OCR 单独切出来，
        // 合并后成了 `折 三拼霸霸奶茶`。只在"行首且自成 token"时剥离，
        // 所以 `折扣商品`、`新品奶茶`、`特价套餐` 这类真词不会被误剥。
        s = LEADING_ACTIVITY_TAG_RE.replaceFirst(s, " ")
        // 括号片段：**只删"像规格"的**，不无条件删。
        // 无条件删会吃掉商品名里的成分说明（`超值套餐(含可乐薯条鸡翅)`），
        // 判定逻辑见 [isSpecFragment]。
        s = PAREN_FRAGMENT_RE.replace(s) { m ->
            if (isSpecFragment(m.groupValues[1])) " " else m.value
        }
        s = QUANTITY_RE.replace(s, " ")
        s = LEADING_INDEX_RE.replace(s, " ")
        s = s.replace("¥", " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        if (s.isNotEmpty()) return s
        val fallback = line.rawText.take(40).trim()
        return fallback.ifBlank { null }
    }

    /**
     * 判断括号里的内容是否**像规格**（该删），还是商品名的一部分（该留）。
     *
     * 命中任一条即视为规格、删掉；否则**保留**：
     * - (a) 形如「数字 + 单位」： `200克` / `1人份` / `12寸` / `1.5L` → [PAREN_NUMBER_UNIT_RE]
     * - (b) **整体**等于某个规格词：`推荐` / `中杯` / `标准` / `七分糖`… → 复用 [SPEC_HINTS]
     * - (c) 只有 1 个字符：`中` / `大` / `小` / `左` / `右`
     *
     * ## 为什么 (b) 必须是整体相等而不是 contains
     * `(含推荐酱)` 是商品名里的说明，`contains("推荐")` 会把它误删。
     * 只有整体相等（`(推荐)`）才算规格写法。
     *
     * ## 为什么还要单独处理"空内容"
     * [stripNonMoneyNumbers] 会**先**把 `(200克)` 变成 `( )` —— 单位连同数字被替换成空格。
     * 所以真实输入走到这里时，规格括号**大部分已经是空的**；不删就会残留
     * `澳洲牛排( )` 这种名字。这条不是可选项，是主干路径。
     *
     * ## 这条例子的来历（同类问题第三例）
     * 前两例是"长度闸门吃掉 16 字商品名"和"无条件删括号吃掉成分说明"，
     * 都属于**为了压规格行，把真商品名一起压掉了**。所以这里的默认方向是"留"，
     * 只有能明确判定为规格时才删。
     */
    private fun isSpecFragment(content: String): Boolean {
        val t = content.trim()
        if (t.isEmpty()) return true
        if (t.length == 1) return true
        if (SPEC_HINTS.any { it.equals(t, ignoreCase = true) }) return true
        return PAREN_NUMBER_UNIT_RE.matches(t)
    }

    // ======================================================================
    // P3 数量
    // ======================================================================

    /*
     * 数量解析实现见 companion object 的 [parseQuantity]：
     * 只认 `x1` / `x 2` / `*3` 这类后缀，且只在行尾最后 14 个字符里找，取**最后一个**匹配。
     * 永远返回 >= 1，绝不返回 0 / null —— 这样 `(200克)x1` 得到 1（而不是 200），
     * `1人份` 也不会被误认。
     */

    // ======================================================================
    // P4 实付金额
    // ======================================================================

    /**
     * 四段式取"整单实付"：
     * - P4-a 精确实付标签（自下往上）
     * - P4-b 宽泛合计标签（自下往上）
     * - P4-c **仅单商品**的兜底：自下往上第一条"含 ¥ 且不含费用/优惠词"的行
     * - P4-d 全失败 → null
     *
     * ## 为什么 P4-c 必须门禁 itemCount > 1
     * 多商品且 OCR 漏掉「合计/实付」行时，P4-c 会取到**最后一个商品行**的单价
     * （例如 12.5 / 9.0 / 8.0 三件，就会返回 8.0），而真实整单实付约 29.5 ——
     * 这是**静默少记钱**，用户完全看不出异常，比留空危险得多。
     *
     * 缺合计行时，商品原价之和也不等于实付（还有打包费 / 配送费 / 优惠），
     * 任何推导都是错的。因此按本解析器"猜不到就是 null"的原则直接返回 null：
     * 调用方会回退到小计，并明确提示"未识别：实付金额"，让错误**可见**。
     *
     * 单商品时该兜底恰好正确（一行商品价 = 整单），保持原行为不变。
     */
    private fun findActualPaid(prepared: List<Line>, itemCount: Int): Double? {
        // P4-a
        for (i in prepared.indices.reversed()) {
            val line = prepared[i]
            val match = EXACT_PAID_RE.find(line.rawText) ?: continue
            val amount = lastPositiveAmountAfter(line.rawText, match.range.last + 1)
            if (amount != null) return amount
        }
        // P4-b
        for (i in prepared.indices.reversed()) {
            val line = prepared[i]
            val match = SUMMARY_LABEL_RE.find(line.rawText) ?: continue
            val amount = lastPositiveAmountAfter(line.rawText, match.range.last + 1)
            if (amount != null) return amount
        }
        // P4-c：多商品一律不兜底（取最后一个商品价 = 静默少记钱），宁可留空
        if (itemCount > 1) return null
        for (i in prepared.indices.reversed()) {
            val line = prepared[i]
            if (!line.moneyText.contains('¥')) continue
            if (ITEM_EXCLUDE.any { line.moneyText.contains(it) }) continue
            val amounts = amountsIn(line.moneyText)
            val amount = amounts.lastOrNull { it > 0.0 }
            if (amount != null) return amount
        }
        // P4-d
        return null
    }

    /**
     * 取 `start` 之后文本里的**最后一个正数金额**。
     *
     * 细节：先把「优惠标签 + 金额」整段剥掉，避免把优惠额误当实付
     * （`实付 ¥26.4 已优惠 ¥21.9` → 26.4）。
     *
     * 用"整段剥离"而不是旧版的"在优惠标签处截断"，是为了兼容真实截图的
     * `合计 已优惠8.5元 ¥12.3` —— 这里的优惠**在实付金额之前**，
     * 一截断就什么都不剩，实付会整条丢掉。剥离只吃掉标签和它自己的金额，
     * 剩下的 12.3 仍然在，取最后一个正数金额即可。
     */
    private fun lastPositiveAmountAfter(text: String, start: Int): Double? {
        if (start !in 0..text.length) return null
        val tail = stripDiscountSegments(text.substring(start))
        val amounts = amountsIn(tail)
        return amounts.lastOrNull { it > 0.0 }
    }

    /**
     * 取 `start` 之后**紧邻的**第一个正数金额（用于"标签 → 值"的取值）。
     *
     * 与 [lastPositiveAmountAfter] 的区别：这里要的是紧跟标签的那个数
     * （`已优惠8.5元 ¥12.3` 的优惠额是 **8.5**，不是行尾的 12.3）。
     */
    private fun firstPositiveAmountAfter(text: String, start: Int): Double? {
        if (start !in 0..text.length) return null
        val tail = text.substring(start)
        val match = IMMEDIATE_AMOUNT_RE.find(tail) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val abs = kotlin.math.abs(value)
        return if (abs > 0.0) abs else null
    }

    // ======================================================================
    // P5 辅助金额（优惠 / 原价）
    // ======================================================================

    /** 在全文里找带标签的金额：自上而下取第一个命中标签，取标签**紧邻**的正数金额。 */
    private fun findLabeledAmount(prepared: List<Line>, labels: List<String>): Double? {
        for (line in prepared) {
            val labelIndex = labels
                .map { line.rawText.indexOf(it) }
                .filter { it >= 0 }
                .minOrNull() ?: continue
            val labelLength = labels
                .filter { line.rawText.startsWith(it, labelIndex) }
                .maxByOrNull { it.length }
                ?.length ?: 0
            val amount = firstPositiveAmountAfter(line.rawText, labelIndex + labelLength)
            if (amount != null) return amount
        }
        return null
    }

    // ======================================================================
    // P6 平台
    // ======================================================================

    /** 全文 contains 取首个命中；饿了么等暂不支持的平台返回 null。 */
    private fun detectPlatform(prepared: List<Line>): Platform? {
        for (line in prepared) {
            val t = line.rawText
            when {
                t.contains("美团") || t.contains("meituan", ignoreCase = true) -> return Platform.MEITUAN
                t.contains("淘宝") || t.contains("闪购") ||
                    t.contains("taobao", ignoreCase = true) -> return Platform.TAOBAO_FLASH
                t.contains("京东") || t.contains("jingdong", ignoreCase = true) ||
                    JD_WORD_RE.containsMatchIn(t) -> return Platform.JD
                t.contains("饿了么") -> return null
            }
        }
        return null
    }


    // ======================================================================
    // 对外文本工具（已迁到 ParserPatterns.kt，这里只做同名转发）
    // ======================================================================

    /**
     * normalize / stripNonMoneyNumbers / stripDiscountSegments / amountsIn / parseQuantity
     * 原本是本 companion 的成员，已拆到同包的顶层函数（见 ParserPatterns.kt）。
     * 这里保留同名转发，**只是为了不破坏既有调用方**：单元测试有 23 处通过
     * OrderScreenshotParser.xxx(...) 调它们，而这次重构的目标是「行为零变化」，
     * 所以那些调用点一个都没动。
     *
     * ⚠️ 必须写**全包名**。若直接写 normalize(text)，会解析到本 companion
     * 自己的这个函数，导致无限递归。
     */
    companion object {
        fun normalize(text: String): String =
            com.example.foodexpense.ocr.normalize(text)

        fun stripNonMoneyNumbers(text: String): String =
            com.example.foodexpense.ocr.stripNonMoneyNumbers(text)

        fun stripDiscountSegments(text: String): String =
            com.example.foodexpense.ocr.stripDiscountSegments(text)

        fun amountsIn(text: String): List<Double> =
            com.example.foodexpense.ocr.amountsIn(text)

        fun parseQuantity(rawText: String): Int =
            com.example.foodexpense.ocr.parseQuantity(rawText)
    }
}