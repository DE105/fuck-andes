package fuck.andes.agent.memory.retrieval

/**
 * 向量检索纯逻辑：余弦相似度 + top-k 排序。
 * 纯 JVM 逻辑，可本地单测。查询/候选向量均由 bge 归一化后存储。
 */
internal object VectorSearch {

    data class Entry(val key: String, val vector: FloatArray)

    fun search(query: FloatArray, entries: List<Entry>, k: Int): List<Pair<String, Float>> =
        entries
            .map { it.key to cosine(query, it.vector) }
            .sortedByDescending { it.second }
            .take(k.coerceAtLeast(0))

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        val denom = kotlin.math.sqrt(na * nb)
        return if (denom == 0f) 0f else dot / denom
    }
}
