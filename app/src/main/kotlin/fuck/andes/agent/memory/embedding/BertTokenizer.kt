package fuck.andes.agent.memory.embedding

/**
 * 本地中文 Bert 分词器（BertPreTokenizer + WordPiece）。
 * bge-small-zh-v1.5 使用的标准 tokenizer，纯 JVM 逻辑，可本地单测。
 * tokenizer.json 配置：clean_text=true, handle_chinese_chars=true, lowercase=false。
 */
internal class BertTokenizer(private val vocab: Map<String, Int>) {

    data class TokenizedInput(val inputIds: IntArray, val attentionMask: IntArray)

    companion object {
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val UNK = "[UNK]"
        const val PAD = "[PAD]"
        const val MASK = "[MASK]"
        const val DEFAULT_MAX_LEN = 128

        fun fromVocab(lines: List<String>): BertTokenizer {
            val map = HashMap<String, Int>(lines.size * 2)
            lines.forEachIndexed { idx, raw ->
                val t = raw.trim()
                if (t.isNotEmpty()) map[t] = idx
            }
            return BertTokenizer(map)
        }
    }

    fun encode(text: String, maxLen: Int = DEFAULT_MAX_LEN): TokenizedInput {
        val limit = maxLen.coerceAtLeast(2)
        val ids = mutableListOf(vocab[CLS] ?: 101)
        val tokens = tokenize(text)
        for (t in tokens) {
            if (ids.size >= limit - 1) break
            ids += (vocab[t] ?: vocab[UNK] ?: 100)
        }
        ids += (vocab[SEP] ?: 102)
        val inputIds = IntArray(limit)
        val mask = IntArray(limit)
        ids.forEachIndexed { i, v -> if (i < limit) { inputIds[i] = v; mask[i] = 1 } }
        return TokenizedInput(inputIds, mask)
    }

    fun tokenize(text: String): List<String> {
        val cleaned = cleanText(text)
        val chineseSpaced = handleChineseChars(cleaned)
        val words = whitespaceTokenize(chineseSpaced)
        val out = mutableListOf<String>()
        for (w in words) out += wordPiece(w)
        return out
    }

    private fun cleanText(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c == '\u0000' -> sb.append(' ')
                c == '\uFFFD' -> sb.append(' ')
                isControl(c) -> sb.append(' ')
                Character.isWhitespace(c) -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun handleChineseChars(s: String): String {
        val sb = StringBuilder(s.length * 2)
        for (c in s) {
            if (isChineseChar(c)) sb.append(' ').append(c).append(' ')
            else sb.append(c)
        }
        return sb.toString()
    }

    private fun whitespaceTokenize(s: String): List<String> =
        s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun wordPiece(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        if (vocab.containsKey(token)) return listOf(token)
        val out = mutableListOf<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var cur: String? = null
            while (start < end) {
                val sub = token.substring(start, end)
                val piece = if (start > 0) "##$sub" else sub
                if (vocab.containsKey(piece)) { cur = piece; break }
                end--
            }
            if (cur == null) return listOf(UNK)
            out += cur
            start += if (cur.startsWith("##")) cur.length - 2 else cur.length
        }
        return out
    }

    private fun isControl(c: Char): Boolean {
        if (c == '\t' || c == '\n' || c == '\r') return false
        val code = c.code
        return code < 0x20 || (code in 0x7F..0xA0)
    }

    private fun isChineseChar(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF) || (code in 0xF900..0xFAFF)
    }
}
