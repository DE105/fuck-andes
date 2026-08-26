package fuck.andes.data.repository

import android.content.Context
import fuck.andes.agent.memory.embedding.BgeEmbeddingEngine
import fuck.andes.agent.memory.embedding.VectorCodec
import fuck.andes.agent.memory.retrieval.VectorSearch
import fuck.andes.data.db.FuckAndesDatabase
import fuck.andes.data.db.MemoryEmbeddingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 语义记忆索引器：写记忆时用 bge 算向量存入 memory_embeddings；检索时按 query 向量 top-k 召回，返回记忆内容。
 * 引擎为进程级单例（首次加载模型，之后复用），所有 IO 在 Dispatchers.IO。
 * 任何 embedding 失败均静默降级（不影响记忆写入/关键词检索），保证健壮性。
 */
internal class SemanticMemoryIndexer(private val context: Context) {

    data class SemanticHit(val refId: String, val content: String, val score: Float)

    private val appContext = context.applicationContext
    private val dao by lazy { FuckAndesDatabase.get(appContext).memoryEmbeddingDao() }
    private val layerDao by lazy { FuckAndesDatabase.get(appContext).memoryLayerDao() }
    private val engine by lazy { BgeEmbeddingEngine.getInstance(appContext) }

    suspend fun index(refType: String, refId: String, content: String) {
        if (content.isBlank()) return
        val vec = runCatching { withContext(Dispatchers.IO) { engine.embed(content) } }
            .getOrNull() ?: return
        if (vec.isEmpty()) return
        dao.upsert(
            MemoryEmbeddingEntity(
                id = "$refType:$refId",
                refType = refType,
                refId = refId,
                vector = VectorCodec.encode(vec),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun remove(refType: String, refId: String) {
        dao.deleteByRef(refType, refId)
    }

    /** 按 query 语义召回 top-k 记忆，返回内容 + 相似度；失败/空返回空列表。 */
    suspend fun search(query: String, k: Int = 5): List<SemanticHit> {
        if (query.isBlank()) return emptyList()
        val qv = runCatching { withContext(Dispatchers.IO) { engine.embed(query) } }
            .getOrNull() ?: return emptyList()
        if (qv.isEmpty()) return emptyList()
        val entries = dao.all().map { VectorSearch.Entry(it.refId, VectorCodec.decode(it.vector)) }
        return VectorSearch.search(qv, entries, k).mapNotNull { (refId, score) ->
            val content = layerDao.atomById(refId)?.content
                ?: layerDao.scenarioById(refId)?.content
                ?: return@mapNotNull null
            SemanticHit(refId, content, score)
        }
    }

    /** 方案 B+：蒸馏升格语义关卡使用的向量化接口。推理失败返回空数组（由调用方降级不入 MEMORY.md）。 */
    suspend fun embed(content: String): FloatArray {
        if (content.isBlank()) return FloatArray(0)
        return runCatching { withContext(Dispatchers.IO) { engine.embed(content) } }
            .getOrNull() ?: FloatArray(0)
    }
}
