package fuck.andes.agent.memory.retrieval

import org.junit.Assert.assertEquals
import org.junit.Test

class VectorSearchTest {

    private val q = floatArrayOf(1f, 0f, 0f)

    @Test
    fun topk按余弦排序() {
        val entries = listOf(
            VectorSearch.Entry("a", floatArrayOf(1f, 0f, 0f)),
            VectorSearch.Entry("b", floatArrayOf(0f, 1f, 0f)),
            VectorSearch.Entry("c", floatArrayOf(0.9f, 0.1f, 0f)),
        )
        val res = VectorSearch.search(q, entries, 2)
        assertEquals("a", res[0].first)
        assertEquals("c", res[1].first)
    }

    @Test
    fun 正交向量余弦为0() {
        assertEquals(0f, VectorSearch.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test
    fun 同向等比向量余弦为1() {
        assertEquals(1f, VectorSearch.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-6f)
    }

    @Test
    fun 维度不匹配返回0() {
        assertEquals(0f, VectorSearch.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f)), 1e-6f)
    }

    @Test
    fun 空列表返回空() {
        assertEquals(0, VectorSearch.search(q, emptyList(), 5).size)
    }
}
