package fuck.andes.agent.tool

/**
 * P2-2 MEMORY.md 自动整理器（纯规则、安全版）。
 *
 * 只做无损整理，绝不删除有内容行：
 * - 同章节内完全重复的行去重（保留首次出现）；
 * - 连续空行合并为单个；
 * - 行尾空白清理；
 * - 超过 [LONG_SECTION_LINES] 行的章节在报告中标记 long（仅提示，不自动裁剪）。
 *
 * 本类为纯逻辑，无 Android 依赖，可直接单元测试。
 */
internal object MemoryCompactor {

    internal data class SectionReport(
        val heading: String,
        val linesBefore: Int,
        val linesAfter: Int,
        val removed: Int,
        val long: Boolean,
    )

    internal data class CompactResult(
        val content: String,
        val changed: Boolean,
        val totalRemoved: Int,
        val sections: List<SectionReport>,
        val bytesBefore: Int = 0,
        val bytesAfter: Int = 0,
    )

    private const val LONG_SECTION_LINES = 80

    private val HEADING = Regex("""^#{1,2}\s+.+$""")

    /** 整理全文；[sectionFilter] 非空时只处理标题包含该文本的章节，其余原样保留。 */
    fun compact(content: String, sectionFilter: String?): CompactResult {
        val lines = content.split("\n")
        val headingIndexes = lines.mapIndexedNotNull { index, line ->
            if (HEADING.matches(line.trimEnd())) index else null
        }
        val ranges = if (headingIndexes.isEmpty()) {
            listOf(0 to lines.size)
        } else {
            headingIndexes.mapIndexed { i, start ->
                val end = if (i + 1 < headingIndexes.size) headingIndexes[i + 1] else lines.size
                start to end
            }
        }

        val output = mutableListOf<String>()
        val reports = mutableListOf<SectionReport>()
        var totalRemoved = 0

        for ((start, end) in ranges) {
            val heading = if (headingIndexes.isEmpty()) "(文件头)" else lines[start].trimEnd()
            val sectionLines = lines.subList(start, end)
            val processed = if (sectionFilter == null || heading.contains(sectionFilter)) {
                compactSection(sectionLines)
            } else {
                sectionLines.toMutableList()
            }
            val removed = sectionLines.size - processed.size
            totalRemoved += removed
            reports += SectionReport(
                heading = heading,
                linesBefore = sectionLines.size,
                linesAfter = processed.size,
                removed = removed,
                long = sectionLines.size > LONG_SECTION_LINES,
            )
            output += processed
        }

        val compacted = output.joinToString("\n")
        return CompactResult(
            content = compacted,
            changed = compacted != content,
            totalRemoved = totalRemoved,
            sections = reports,
            bytesBefore = content.length,
            bytesAfter = compacted.length,
        )
    }

    private fun compactSection(lines: List<String>): MutableList<String> {
        val seen = HashSet<String>()
        val result = mutableListOf<String>()
        var previousBlank = false
        for (line in lines) {
            val cleaned = line.trimEnd()
            if (cleaned.isBlank()) {
                // 连续空行合并：只保留一个
                if (!previousBlank && result.isNotEmpty()) {
                    result += ""
                    previousBlank = true
                }
                continue
            }
            previousBlank = false
            // 章节内完全重复行去重（标题行自身不参与，因为标题是章节头）
            if (!seen.add(cleaned)) continue
            result += cleaned
        }
        return result
    }
}
