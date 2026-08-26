package fuck.andes.data.repository

/**
 * P2-1 自动沉淀规则：从用户消息中保守提取稳定事实。
 *
 * 设计原则：
 * - 只处理明确的第一人称陈述（锚定"我"），且命中稳定特征词（名字/年龄/喜欢/居住/工作/职业/工具）。
 * - 疑问句、祈使句（帮我/请/麻烦）、含临时时间词的句子一律跳过。
 * - 每条消息最多 [MAX_FACTS_PER_MESSAGE] 条，防止一次性请求污染 L1。
 * - 幂等：写入走 L1 content 去重 + L3 键覆盖，重复处理无害。
 *
 * v6.0.1 增强：
 * - 补"负面偏好"词表（烦/厌恶/嫌弃/受不了）：原词表只有 喜欢/讨厌/反感，用户真实表达
 *   "我最烦广告了" 这一类无法被蒸馏。同时把 DISLIKE 拆成"否定喜欢 / 明确负面词"两分支，
 *   修复原版把"我喜欢X"也误当 DISLIKE 匹配的隐藏缺陷。
 * - 降低短句阈值（MIN_SENTENCE_CHARS 6 -> 3）："我讨厌广告"(5字) 等精短偏好不再被过滤。
 * - 新增"决定/进度/结论"蒸馏：命中强锚点（决定/采用/拍板/进度/已完成/发布/上线/落地）
 *   的陈述句，产出 category=project 的 L1 记忆，用于跨会话恢复"上次决定的方案/进度"，
 *   直击"前面忘了"（过程性结论不落记忆）的问题。
 *
 * 本类为纯逻辑，无 Android 依赖，可直接 kotlinc 单测。
 */
internal object MemoryDistillRules {

    internal data class DistilledFact(
        /** 规范化陈述（原句匹配片段，写入 L1 content）。 */
        val content: String,
        /** L1 分类：user / preference / life / work / project。 */
        val category: String,
        /** 非空时同时写入 L3 画像（key 为画像键）。 */
        val profileKey: String? = null,
        /** 画像值（仅在 profileKey 非空时使用，默认取 content）。 */
        val profileValue: String? = null,
    )

    private data class Pattern(
        val regex: Regex,
        val category: String,
        val profileKey: String? = null,
        /** 画像值取第几个捕获组（1 起），null 表示取完整匹配。 */
        val profileGroup: Int? = null,
    )

    // 名字：我的名字/昵称/称呼 是/叫/为 X
    private val NAME = Pattern(
        regex = Regex("""(?:我的(?:名字|昵称|称呼)(?:是|叫|为)|我(?:叫|名字是))\s*[:：]?\s*([\p{L}\p{N}·]{2,20})"""),
        category = "user",
        profileKey = "用户名字",
        profileGroup = 1,
    )
    // 年龄：我(今年)?N岁
    private val AGE = Pattern(Regex("""我(?:今年)?\s*(\d{1,3})\s*岁"""), "user")
    // 不喜欢/讨厌/反感/烦。拆两分支：①否定喜欢(不太/并不/不 + 喜欢) ②明确负面词(讨厌/反感/烦/厌恶/嫌弃/受不了)。
    private val DISLIKE = Pattern(
        Regex(
            """我(?:(?:很|最|超|就)?(?:不太|并不|不)喜欢|(?:很|最|超|就)?(?:讨厌|反感|烦|厌恶|嫌弃|受不了))\s*([^，。！？!?、]{2,60})"""
        ),
        "preference",
    )
    // 喜欢/偏爱/爱
    private val LIKE = Pattern(
        Regex("""我(?:很|最|超|就)?(?:喜欢|偏爱|爱)\s*([^，。！？!?、]{2,60})"""),
        "preference",
    )
    // 居住地
    private val LIVE = Pattern(
        Regex("""我(?:住|居住)(?:在)?\s*([^，。！？!?、]{2,40})"""),
        "life",
    )
    // 工作地点：我在 X 工作/上班/就职
    private val WORK_AT = Pattern(
        Regex("""我在\s*([^，。！？!?、]{2,40})\s*(?:工作|上班|就职)"""),
        "work",
    )
    // 职业身份：我是 X（排除转述/疑问）
    private val IDENTITY = Pattern(
        Regex("""我是\s*(?:一个|一名|一位|做)?\s*([^，。！？!?、]{2,40})"""),
        "user",
    )
    // 从事/负责
    private val OCCUPATION = Pattern(
        Regex("""我(?:从事|在做|负责)\s*([^，。！？!?、]{2,40})"""),
        "work",
    )
    // 使用工具/应用：我(平时)(在)?用 X
    private val TOOL_USE = Pattern(
        Regex("""我(?:平时)?(?:在)?(?:用|使用)\s*([^，。！？!?、]{2,40})"""),
        "preference",
    )

    // 家庭关系：我(有|养)一个?(女儿|儿子|...)
    private val FAMILY = Pattern(
        Regex("""我(?:有|养)\s*一个?\s*(?:女儿|儿子|孩子|弟弟|妹妹|哥哥|姐姐|爷爷|奶奶|爸爸|妈妈)"""),
        "life",
        profileKey = "家庭",
    )
    // 学习/学校：我在/就读于/毕业于 X大学/学校
    private val SCHOOL = Pattern(
        Regex("""我(?:在|就读于|毕业于)\s*([^，。！？!?、]{2,40}(?:大学|学校|学院))"""),
        "life",
    )
    // 常用语言：我会说/会讲/使用 X语
    private val LANGUAGE = Pattern(
        Regex("""我(?:会说|会讲|使用)\s*([^，。！？!?、]{2,20}(?:语|话))"""),
        "preference",
    )
    // 稳定习惯：我习惯/经常/每天 X
    private val HABIT = Pattern(
        Regex("""我(?:习惯|经常|每天)\s*([^，。！？!?、]{2,60})"""),
        "preference",
    )

    // 决定/进度/结论：命中强锚点的陈述句 -> project 类 L1 记忆
    private val DECISION = Pattern(
        Regex("""(?:决定|定下来|敲定|拍板|采用|选型|进度|已完成|发布|上线|落地)\s*[:：]?\s*([^，。！？!?、\n]{2,120})"""),
        category = "project",
    )

    private val TRANSIENT_WORDS = listOf(
        "现在", "今天", "明天", "昨天", "刚才", "马上", "立刻", "待会", "稍后", "正在",
    )
    private val REQUEST_WORDS = listOf(
        "帮我", "请", "麻烦", "能不能", "可以吗", "帮我查", "帮我看看", "帮忙", "求",
    )
    private val IDENTITY_EXCLUDE = listOf(
        "问你", "想说", "想问", "在问", "来问", "不是", "来确认", "来告诉你", "来跟你说",
    )

    private const val MIN_SENTENCE_CHARS = 3
    private const val MAX_FACTS_PER_SENTENCE = 3
    private const val MAX_FACTS_PER_MESSAGE = 6
    private const val MAX_FACT_CHARS = 120

    /** 从一条用户消息中提取稳定事实（保守规则）。 */
    fun extract(message: String): List<DistilledFact> {
        if (message.isBlank()) return emptyList()
        val facts = mutableListOf<DistilledFact>()
        for (sentence in splitSentences(message)) {
            val text = sentence.trim()
            if (!eligible(text)) continue
            facts += matchDecision(text)
            facts += matchName(text)
            facts += matchAge(text)
            facts += matchDislike(text)
            facts += matchLike(text)
            facts += matchLive(text)
            facts += matchWorkAt(text)
            facts += matchIdentity(text)
            facts += matchOccupation(text)
            facts += matchToolUse(text)
            facts += matchFamily(text)
            facts += matchSchool(text)
            facts += matchLanguage(text)
            facts += matchHabit(text)
        }
        return facts
            .distinctBy { it.content to it.category }
            .take(MAX_FACTS_PER_MESSAGE)
    }

    private fun matchDecision(text: String): List<DistilledFact> = DECISION.regex.find(text)?.let {
        listOf(DistilledFact(content = normalize(it.value), category = DECISION.category))
    } ?: emptyList()

    private fun matchName(text: String): List<DistilledFact> = NAME.regex.find(text)?.let {
        listOf(
            DistilledFact(
                content = normalize(it.value),
                category = NAME.category,
                profileKey = NAME.profileKey,
                profileValue = it.groupValues.getOrNull(1)?.take(20),
            )
        )
    } ?: emptyList()

    private fun matchAge(text: String): List<DistilledFact> = AGE.regex.find(text)?.let {
        listOf(DistilledFact(normalize(it.value), AGE.category))
    } ?: emptyList()

    private fun matchDislike(text: String): List<DistilledFact> = DISLIKE.regex.find(text)?.let {
        listOf(DistilledFact(normalize(it.value), DISLIKE.category))
    } ?: emptyList()

    private fun matchLike(text: String): List<DistilledFact> = LIKE.regex.find(text)?.let {
        listOf(DistilledFact(normalize(it.value), LIKE.category))
    } ?: emptyList()

    private fun matchLive(text: String): List<DistilledFact> = LIVE.regex.find(text)?.let {
        listOf(DistilledFact(normalize(it.value), LIVE.category))
    } ?: emptyList()

    private fun matchWorkAt(text: String): List<DistilledFact> = WORK_AT.regex.find(text)?.let {
        listOf(DistilledFact(normalize(it.value), WORK_AT.category))
    } ?: emptyList()

    private fun matchIdentity(text: String): List<DistilledFact> {
        if (IDENTITY_EXCLUDE.any { text.contains(it) }) return emptyList()
        return IDENTITY.regex.find(text)?.let {
            listOf(DistilledFact(normalize(it.value), IDENTITY.category))
        } ?: emptyList()
    }

    private fun matchOccupation(text: String): List<DistilledFact> = OCCUPATION.regex.find(text)?.let {
        listOf(DistilledFact(normalize(it.value), OCCUPATION.category))
    } ?: emptyList()

    private fun matchFamily(text: String): List<DistilledFact> = FAMILY.regex.find(text)?.let {
        listOf(DistilledFact(normalize(it.value), FAMILY.category, profileKey = FAMILY.profileKey))
    } ?: emptyList()

    private fun matchSchool(text: String): List<DistilledFact> = SCHOOL.regex.find(text)?.let {
        listOf(DistilledFact(normalize(it.value), SCHOOL.category))
    } ?: emptyList()

    private fun matchLanguage(text: String): List<DistilledFact> = LANGUAGE.regex.find(text)?.let {
        listOf(DistilledFact(normalize(it.value), LANGUAGE.category))
    } ?: emptyList()

    private fun matchHabit(text: String): List<DistilledFact> = HABIT.regex.find(text)?.let {
        listOf(DistilledFact(normalize(it.value), HABIT.category))
    } ?: emptyList()

    private fun matchToolUse(text: String): List<DistilledFact> = TOOL_USE.regex.find(text)?.let {
        listOf(DistilledFact(normalize(it.value), TOOL_USE.category))
    } ?: emptyList()

    /** 候选句过滤：长度、疑问句、祈使句、临时时间词。 */
    private fun eligible(text: String): Boolean {
        if (text.length < MIN_SENTENCE_CHARS) return false
        if (text.endsWith("？") || text.endsWith("?")) return false
        if (REQUEST_WORDS.any { text.contains(it) }) return false
        if (TRANSIENT_WORDS.any { text.contains(it) }) return false
        return true
    }

    private fun splitSentences(message: String): List<String> =
        message.split(Regex("""[。！？!?\n；;]"""))

    private fun normalize(raw: String): String =
        raw.trim().trimEnd('，', '。', '！', '！', '?', '？', ';', '；').take(MAX_FACT_CHARS)
}
