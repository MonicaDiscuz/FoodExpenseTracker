package com.example.foodexpense.ocr

/**
 * 解析器的**全部常量、正则与文本工具**（从 [OrderScreenshotParser] 拆出）。
 *
 * ## 为什么是顶层声明而不是 object
 * 这些成员被 [OrderScreenshotParser] 和 [LineGeometry] 两个文件同时使用。
 * 放在**同包的顶层**（internal），两个文件都能直接引用、**不需要写限定前缀**，
 * 所以这次拆分对原有调用点是零改动 —— 这也是「重构不改行为」最容易验证的做法。
 *
 * ## 改这里之前先读注释
 * 每个阈值后面都记了它是**从哪条真机数据**量出来的。
 * 尤其 [MAX_ITEM_LEFT_LEN] 与 [HORIZONTAL_OVERLAP_RATIO]：
 * 这两处都有过「调紧一次、误杀掉正常数据」的历史。
 *
 * ## 文本工具为什么是 public
 * normalize / stripNonMoneyNumbers / stripDiscountSegments / amountsIn / parseQuantity
 * 原本是 companion object 的公开成员，单元测试直接调用。迁到顶层后保持 public，
 * 并由 [OrderScreenshotParser.Companion] 保留同名转发。
 */


internal const val QUANTITY_TAIL_WINDOW = 14

/**
 * 商品名区（金额左侧文本）的**长度兜底上界**，超过基本就是规格串。
 *
 * ## 为什么是 30，而不是更紧的值
 * 这个闸门**只是兜底**，主要拦截靠另外两道：
 * 1. **含逗号直接否** —— 规格描述几乎必带逗号（`标准,正常冰,七分糖(推荐),换冷链`）；
 * 2. [SPEC_HINTS] 规格词表（`标准` / `七分糖` / `大杯` …）。
 *
 * 有这两道之后，长度闸门的真实收益已接近零，代价却是**误杀长商品名**：
 * `超大份双人套餐(含可乐薯条鸡翅)` 是 16 字，属于完全正常的商品名，
 * 之前取 14 会把它整行否掉 → `itemCount` 归零、用户看到"商品未识别"。
 *
 * ## 将来若要收紧，请改这里以外的地方
 * 如果发现规格行漏进来了，**优先加强 [SPEC_HINTS] 词表**，
 * 不要回头收紧这个长度；长度是最后一道、也是最容易误伤真商品名的一道。
 */
internal const val MAX_ITEM_LEFT_LEN = 30

/**
 * 竖排文字 / 损坏框的宽高比阈值：`height > 3 × width` 判为竖排或坏框。
 *
 * 3 是踩在真机数据上选的（横排中文行宽高比普遍 < 1，正常块离 3 很远；
 * 实测唯一的命中项是 20×176、宽高比 8.8 的坏框）。
 */
internal const val VERTICAL_ASPECT_RATIO = 3

/**
 * 横向重叠比例阈值：重叠 ≥ 较窄者宽度的 50% 就判为"上下堆叠、不同行"。
 *
 * 为什么是 0.5：真机数据的分布是两极分化的 —— 要合并的并排块横向重叠
 * **全为负**（-140 ~ -956），不该合并的堆叠块横向重叠**是 100%**。
 * 0.5 落在中间的真空地带，两边都有巨大余量，不是靠调参调出来的。
 *
 * 判据对"块高度"完全不敏感，所以不会像高度类判据那样误伤
 * "商品名换行被合成一个高 TextLine"的正常情况。
 */
internal const val HORIZONTAL_OVERLAP_RATIO = 0.5

/**
 * 裸整数价格与"页面金额右界"的最大允许偏差（像素）。
 *
 * 60 是按真机实测选的：`4` 的 right=1269、金额右界=1272（差 3）；
 * 而左对齐的 `x1` right=342、`11:41` right=576，都远超 60。
 * 取 60 而不是 20，是为了容忍不同 DPI 下价格列的小幅抖动。
 */
internal const val MONEY_EDGE_TOLERANCE = 60

/**
 * 店铺名"裁剪掉 UI 装饰后"至少要剩这么多字符，否则认为整行都是装饰（横幅），整条否决。
 *
 * 4 是踩在真机数据上选的：`今日领券` → `今日`(2) 被否决，
 * `蜜雪冰城(秋园雅苑店) h直播中>` → `蜜雪冰城(秋园雅苑店)`(12) 通过。
 */
internal const val MIN_STORE_BODY_LEN = 4

internal const val MAX_QUANTITY = 99

/**
 * 店铺 / 商家标签。
 * 「店铺名称」必须排在「店铺」前面，否则 `店铺名称：麦当劳` 会取到「名称:麦当劳」。
 *
 * `(?<!进)` 是必须的：店铺行右侧的按钮是「⊙ 进商家粉丝群」，含 `商家` 二字，
 * 不加负向断言就会被当成"商家名称"标签，把店名解析成 `粉丝群`。
 */
internal val STORE_LABEL_RE = Regex("(?<!进)(店铺名称|店铺|商家名称|商家|门店)")

/** 精确实付标签（长的排前面，避免「实付款」被「实付」截断）。 */
internal val EXACT_PAID_LABELS: List<String> = listOf(
    "实付款", "实付金额", "实际支付", "支付金额", "应付金额", "已支付", "实付"
)

/** 宽泛合计标签。 */
internal val SUMMARY_LABELS: List<String> = listOf(
    "订单合计", "订单金额", "合计", "总计", "总价"
)

/** 由 [EXACT_PAID_LABELS] 生成的正则（长标签在前，保证「实付款」优先于「实付」命中）。 */
internal val EXACT_PAID_RE = Regex(EXACT_PAID_LABELS.joinToString("|"))

/** 由 [SUMMARY_LABELS] 生成的正则（同上，长标签优先）。 */
internal val SUMMARY_LABEL_RE = Regex(SUMMARY_LABELS.joinToString("|"))

/** 优惠类标签：仅用于 [ParsedOrder.discountTotal]，**绝不**能当实付。 */
internal val DISCOUNT_LABELS: List<String> = listOf(
    "已优惠", "店铺优惠", "共优惠", "优惠", "立减", "红包", "满减"
)

/** 原价类标签：仅用于提示 / 上界校验，不参与取值。 */
internal val ORIGINAL_LABELS: List<String> = listOf(
    "原价", "商品总价", "商品金额", "小计"
)

/** 明确的界面噪声文本（用 equals 精确比较，不要用 contains）。 */
internal val NOISE_TEXTS: List<String> = listOf(
    "美团", "饿了么", "淘宝", "京东", "淘宝闪购",
    "订单详情", "订单已完成", "订单已送达",
    "查看详情", "申请退款", "联系商家", "再来一单", "开发票", "评价赚积分"
)

/**
 * 带标签的元信息行：内部含数字也可能被判为合法店名，必须用 contains 前置否决。
 *
 * 为什么不能写成"含数字且不含 ¥ 就否决"：那会误杀 `7-11便利店`、`3号门炸鸡`
 * 这类合法店名。所以只否决**明确带标签**的订单元信息行。
 */
internal val NOISE_PREFIX: List<String> = listOf(
    "订单号", "订单编号", "下单时间", "支付时间", "配送时间", "预计送达",
    "收货地址", "联系电话", "手机号", "发票抬头", "取餐号", "桌号",
    // 真实截图里的折叠行 / 店铺行右侧的运营按钮
    "支付方式", "期望时间", "配送方式", "送达时间", "收货人", "联系人", "备注",
    "粉丝群", "进商家", "直播中"
)

/** 店铺名里不该出现的词（费用 / 优惠 / 合计 / 操作按钮等）。 */
internal val STORE_EXCLUDE: List<String> = listOf(
    "打包费", "餐盒", "配送费", "配送", "运费", "服务费",
    "优惠", "红包", "满减", "立减", "折扣",
    "合计", "总计", "小计", "实付", "原价", "商品总价",
    "会员", "发票", "备注", "预计", "已减", "待支付",
    "餐具", "联系商家", "全部商品", "商品", "评价", "再来一单",
    // 真机截图：店铺行右侧的运营按钮 / 赠送类文案 / 顶部促销横幅
    "粉丝群", "进商家", "直播", "赠送", "已用", "领券"
)

/** 商品行里不该出现的词（与店铺排除词同源）。 */
internal val ITEM_EXCLUDE: List<String> = STORE_EXCLUDE

/**
 * 规格词总表（对外暴露，便于用例与后续维护对照）。
 * 实际判定见 [SPEC_HINTS_MULTI] 与 [SPEC_SINGLE_TOKEN_RE]。
 */
internal val SPEC_HINTS: List<String> = listOf(
    "标准", "正常冰", "少冰", "去冰", "常温", "热", "七分糖", "五分糖", "三分糖",
    "无糖", "推荐", "换", "加料", "规格", "份量", "口味", "温度", "甜度",
    "大杯", "中杯", "小杯"
)

/** 多字规格词：contains 即可判定，不会误伤商品名。 */
internal val SPEC_HINTS_MULTI: List<String> = listOf(
    "标准", "正常冰", "少冰", "去冰", "常温", "七分糖", "五分糖", "三分糖",
    "无糖", "推荐", "加料", "规格", "份量", "口味", "温度", "甜度",
    "大杯", "中杯", "小杯"
)

/**
 * 单字规格词必须是"独立 token"才算规格，否则 `热干面`（热）、
 * `换` 之外的商品名会被误杀。
 */
internal val SPEC_SINGLE_TOKEN_RE = Regex("""(?:^|[\s,、])(?:热|换)(?:$|[\s,、(（])""")

/** 非金额数字的后缀单位：`200克` / `4.8分` / `1人份` / `500ml` 等。 */
internal val NON_MONEY_SUFFIX_RE = Regex(
    """\d+(?:\.\d+)?\s*(克|kg|KG|g|G|ml|ML|毫升|升|人份|份|寸|英寸|个装|串|片|块|公里|km|分钟|分|星|月售|人|条|%|℃)"""
)

/** 带 `¥` 的金额（可带负号，如「已优惠 -¥3」）。 */
internal val MARKED_AMOUNT_RE = Regex("""¥\s*(-?\d{1,6}(?:\.\d{1,2})?)""")

/** 用于清洗商品名 / 判断"只剩符号"的带 ¥ 金额（不做分组）。 */
internal val MARKED_AMOUNT_ANY_RE = Regex("""¥\s*-?\d{1,6}(?:\.\d{1,2})?""")

/** 裸小数金额：`43.4` / `26.40`；整数不算（整数是数量、序号、日期）。 */
internal val BARE_DECIMAL_RE = Regex("""(?<![\d.])-?\d{1,6}\.\d{1,2}(?![\d])""")

/** 行尾（可跟数量后缀）的 `d.dd`。 */
internal val LINE_END_DECIMAL_RE = Regex("""(?<![\d.])\d{1,6}\.\d{1,2}\s*(?:[x*]\s*\d{1,2})?\s*$""")

/** 标签之后"紧邻的"金额（可带 `¥` / 负号，可带 `元` 后缀）。 */
internal val IMMEDIATE_AMOUNT_RE = Regex("""^\s*[-−]?\s*¥?\s*(\d{1,6}(?:\.\d{1,2})?)""")

/** 清洗用的"任意数字"（金额 / 整数都删）。 */
internal val MONEY_CLEAN_RE = Regex("""\d+(?:\.\d+)?(?:[x]\d{1,2})?""")

/**
 * 括号片段：**捕获**括号内的内容，由 [isSpecFragment] 决定删不删。
 *
 * 保留原有的"括号内不含嵌套括号、长度 ≤14"约束（避免正则回溯炸掉）。
 */
internal val PAREN_FRAGMENT_RE = Regex("""[（(]([^（()）]{0,14})[)）]""")

/**
 * 括号内"数字 + 单位"形态：`200克` / `1人份` / `1.5L` / `12寸` 等。
 *
 * 允许数字与单位之间有空格（`200 克`）；要求**整体匹配**，不是 contains ——
 * `含2块饼干` 这种商品名说明不能被当规格删掉。
 */
internal val PAREN_NUMBER_UNIT_RE = Regex(
    """^\s*\d+(?:\.\d+)?\s*(?:克|kg|KG|g|G|ml|ML|毫升|升|人份|份|寸|英寸|个装|串|片|块)\s*$"""
)

/** 行首活动标签：`[折]` / `【券】` / `[赠]`（美团列表页的商品前缀）。 */
internal val PRODUCT_TAG_RE = Regex("""[\[【]\s*(?:折|券|赠|特|秒|新品|满减|限时|活动)\s*[\]】]""")

/**
 * 裸的单字活动角标（真机上 `折` 会被单独切成一块）。
 *
 * 必须是"行首 + 自成 token"：前后都要有边界，这样
 * `折 三拼霸霸奶茶` 会剥离，而 `折扣商品` / `新品奶茶` / `特价套餐` 不会。
 */
internal val LEADING_ACTIVITY_TAG_RE =
    Regex("""^[\s·•*\-|、]*[折券新赠特惠](?=$|[\s,、(（)）\]】])""")

/**
 * 行尾的裸整数（1~3 位），且前面必须是空格或汉字。
 *
 * `雪王小宝杯 4` → 命中 `4`；
 * `C684` 的 `684` 前面是字母 `C` → 不命中；
 * `x1` 的 `1` 前面是 `x` → 不命中；
 * `11:41` 的 `41` 前面是 `:` → 不命中；
 * `182****0814` 的 `0814` 前面是 `*` → 不命中。
 */
internal val TRAILING_BARE_INTEGER_RE = Regex("""(?<=[\s\u4E00-\u9FFF])(\d{1,3})$""")

/** 数量后缀：`x2` / `×1` / `*3`（normalize 后 × 已是 x）。 */
internal val QUANTITY_RE = Regex("""[xX×*]\s*(\d{1,2})(?!\d)""")

/** 整行只有一个数量标记（`x1`）——绝不是商品名 / 店名。 */
internal val QUANTITY_ONLY_RE = Regex("""^[xX×*]\s*\d{1,2}$""")

/** 打码的个人信息（`刘**(先生)` / `182****0814`）——不是店名。 */
internal val MASKED_INFO_RE = Regex("""\*{2,}""")

/** 行首序号 / 项目符号。 */
internal val LEADING_INDEX_RE = Regex("""^[\s·•*\-|、]+|^\s*\d{1,2}\s*[.、)）]\s*""")

/** 语义行清理用的行首符号（不删 `[` / `【`，活动标签交给 [PRODUCT_TAG_RE]）。 */
internal val LEADING_SYMBOL_RE = Regex("""^[\s·•*\-|、]+""")

/** 店名尾部的箭头 / 分隔符 / 项目符号。 */
internal val TAIL_SYMBOL_RE = Regex("""[\s>›≫»«|·•*—^~⌃]+$""")

/**
 * 店名尾部的 UI 词表。**长的排前面**，否则 `进商家` 会抢在 `进商家粉丝群` 之前命中。
 */
internal val STORE_TAIL_WORDS: List<String> = listOf(
    "进商家粉丝群", "商家粉丝群", "正在直播", "直播中", "进店逛逛", "查看全部",
    "粉丝群", "进商家", "直播", "进店", "关注", "已关注", "收藏",
    "领券", "抢购", "展开", "收起", "更多"
)

/** 紧跟在汉字 / 右括号之后的短拉丁标签（如直播间水印 `lu`）。 */
internal val TRAILING_LATIN_TAG_RE = Regex("""(?<=[)\]）】\u4E00-\u9FFF])\s*[A-Za-z]{1,4}$""")

/**
 * 由 [DISCOUNT_LABELS] 生成的"优惠整段"正则（标签 + 自带金额，可带负号与 `元`）。
 *
 * 标签按长度倒序，保证 `已优惠` 优先于 `优惠` 命中，不会只咬掉半截。
 */
internal val DISCOUNT_SEGMENT_RE = Regex(
    "(" + DISCOUNT_LABELS.sortedByDescending { it.length }.joinToString("|") + ")" +
        """\s*[-−]?\s*¥?\s*\d{1,6}(?:\.\d{1,2})?\s*(?:元)?"""
)

/** `jd` 作为独立单词出现（避免误命中普通单词里的 jd）。 */
internal val JD_WORD_RE = Regex("""(?i)(?<![a-z])jd(?![a-z])""")

/** 是否 CJK 汉字（中文店名的主体）。 */
internal fun isCjk(ch: Char): Boolean =
    ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF'

/**
 * 全角 → 半角，并折叠空白。
 *
 * 处理：`（）`→`()`、`￥`→`¥`、`×`→`x`、`＊`→`*`、`．`→`.`、
 * 全角数字/字母 → 半角、全角空格 → 半角空格。
 */
fun normalize(text: String): String {
    if (text.isEmpty()) return ""
    val sb = StringBuilder(text.length)
    for (ch in text) {
        val converted = when (ch) {
            '（' -> '('
            '）' -> ')'
            '￥' -> '¥'
            '×' -> 'x'
            '＊' -> '*'
            '．' -> '.'
            '：' -> ':'
            '，' -> ','
            '　' -> ' '
            '\n', '\r', '\t' -> ' '
            else -> {
                if (ch in '！'..'～') {
                    // 全角 ASCII 区（U+FF01..U+FF5E）→ 半角
                    (ch.code - 0xFEE0).toChar()
                } else {
                    ch
                }
            }
        }
        sb.append(converted)
    }
    return sb.toString().replace(Regex("""\s+"""), " ").trim()
}

/**
 * **整个解析的地基**：把"非金额数字"（带单位的量词）替换成空格。
 *
 * 例：`(200克)x1` → `( )x1`；`4.8分` → ` `；`1人份` → ` `。
 * 不先做这一步，商品名里会残留一堆假数字，金额也会被误读。
 */
fun stripNonMoneyNumbers(text: String): String =
    NON_MONEY_SUFFIX_RE.replace(text, " ")

/**
 * 剥掉文本里的「优惠标签 + 它自己的金额」整段。
 *
 * `合计 已优惠8.5元 ¥12.3` → `合计   ¥12.3`
 * `实付 ¥26.4 已优惠 ¥21.9` → `实付 ¥26.4`
 */
fun stripDiscountSegments(text: String): String =
    if (text.isEmpty()) text else DISCOUNT_SEGMENT_RE.replace(text, " ")

/**
 * 抽取文本中的金额（按出现位置从左到右，值取绝对值 —— 前置 `-` 号剥掉）。
 *
 * 优先取带 `¥` 前缀的数字；若一个都没有，再退回"裸小数"（`d.dd`）：
 * 整数一律不当金额，避免把数量 / 序号 / 日期算成钱。
 */
fun amountsIn(text: String): List<Double> {
    if (text.isBlank()) return emptyList()
    val marked = MARKED_AMOUNT_RE.findAll(text)
        .mapNotNull { it.groupValues[1].toDoubleOrNull() }
        .map { kotlin.math.abs(it) }
        .toList()
    if (marked.isNotEmpty()) return marked
    return BARE_DECIMAL_RE.findAll(text)
        .mapNotNull { it.value.toDoubleOrNull() }
        .map { kotlin.math.abs(it) }
        .toList()
}

/**
 * 解析数量：只认 `x1`/`×2`/`*3` 后缀，只在行尾最后 14 字符内找，取最后一个。
 * 永远返回 >= 1。
 */
fun parseQuantity(rawText: String): Int {
    val tail = if (rawText.length <= QUANTITY_TAIL_WINDOW) {
        rawText
    } else {
        rawText.takeLast(QUANTITY_TAIL_WINDOW)
    }
    val match = QUANTITY_RE.findAll(tail).lastOrNull() ?: return 1
    val value = match.groupValues[1].toIntOrNull() ?: return 1
    return value.coerceIn(1, MAX_QUANTITY)
}