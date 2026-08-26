package fuck.andes.agent.model

/**
 * 长任务结论自动沉淀（纯逻辑，可 kotlinc 单测，无 Android 依赖）。
 *
 * 现状：长任务 / 项目型对话完成后，其"关键结论 / 决定 / 交付成果"若不主动写入持久记忆，
 * 会在稍后会话中丢失。本类在会话结束（result.ok）时，从最终交付内容里**保守**提取
 * 值得沉淀为 L2 场景记忆的结论性信息；无明确交付则返回 null，避免污染记忆。
 *
 * 保守原则（延续 GORK 风格）：
 *  - 只处理非空的最终交付内容；
 *  - 只提取"结论性 / 决定性"语句（命中关键词）；
 *  - 每条截断到合理长度；
 *  - 需要场景名（由调用方提供会话标题），同名覆盖。
 */
internal object ConversationDistiller {

    data class DistillResult(val scenarioName: String, val content: String)

    // 结论 / 决定性 / 交付性关键词
    private val DECISION_KEYWORDS = listOf(
        "结论", "决定", "采用", "方案", "选择", "确定", "最终", "完成", "已实现",
        "根因", "修复", "解决", "版本", "发布", "项目", "重构", "交付", "实现",
        "安装", "上线", "部署", "设计", "架构", "定稿", "通过", "验证",
    )

    /**
     * 尝试从最终交付内容中提取可沉淀的 L2 场景记忆。
     *
     * @param scenarioName 场景名（如会话标题或项目名），同名覆盖。
     * @param deliverable 本轮最终交付内容（assistant 的最终答复）。
     * @param maxLines 最多提取的结论行数。
     * @param maxLineChars 每行最长字符数（超出截断）。
     * @return null 表示无可沉淀的明确结论。
     */
    fun tryDistill(
        scenarioName: String,
        deliverable: String,
        maxLines: Int = 8,
        maxLineChars: Int = 160,
    ): DistillResult? {
        val trimmed = deliverable.trim()
        if (trimmed.isBlank()) return null
        val conclusionLines = trimmed.lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() && isConclusionLine(it) }
            .take(maxLines)
            .map { it.take(maxLineChars) }
        if (conclusionLines.isEmpty()) return null
        return DistillResult(
            scenarioName = scenarioName.trim().take(200),
            content = conclusionLines.joinToString("\n"),
        )
    }

    private fun isConclusionLine(line: String): Boolean =
        DECISION_KEYWORDS.any { line.contains(it, ignoreCase = true) }
}
