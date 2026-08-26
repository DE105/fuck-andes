package fuck.andes.agent.memory.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context

/**
 * 本地 bge-small-zh-v1.5 语义 embedding 引擎（ONNX Runtime 推理）。
 * 从 assets 加载 int8 模型 + vocab，把文本编码为 512 维归一化向量。
 *
 * 进程级单例：首次加载 24MB 模型，之后复用，避免每次推理重新读 assets / 建 session。
 * 依赖 Android Context + onnxruntime 原生库，由真机验证效果；分词/检索纯逻辑在 BertTokenizer / VectorSearch。
 */
internal class BgeEmbeddingEngine private constructor(context: Context) : AutoCloseable {

    companion object {
        @Volatile
        private var instance: BgeEmbeddingEngine? = null

        fun getInstance(context: Context): BgeEmbeddingEngine =
            instance ?: synchronized(this) {
                instance ?: BgeEmbeddingEngine(context).also { instance = it }
            }

        const val MAX_LEN = 128
        private const val MODEL_RES = "models/bge-small-zh/model_int8.onnx"
        private const val VOCAB_RES = "models/bge-small-zh/vocab.txt"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val tokenizer: BertTokenizer
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open(MODEL_RES).use { it.readBytes() }
        val vocab = context.assets.open(VOCAB_RES).bufferedReader().use { it.readLines() }
        tokenizer = BertTokenizer.fromVocab(vocab)
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        session = env.createSession(modelBytes, opts)
    }

    /** 文本 → 512 维归一化向量；推理异常向上抛（由调用方降级为关键词检索）。 */
    fun embed(text: String): FloatArray {
        val clean = text.trim()
        if (clean.isEmpty()) return FloatArray(0)
        return try {
            val enc = tokenizer.encode(clean, MAX_LEN)
            val seq = enc.inputIds.size
            val ids = Array(1) { enc.inputIds.map { it.toLong() }.toLongArray() }
            val mask = Array(1) { enc.attentionMask.map { it.toLong() }.toLongArray() }
            val tokenType = Array(1) { LongArray(seq) }
            val inputs = HashMap<String, OnnxTensor>()
            inputs["input_ids"] = OnnxTensor.createTensor(env, ids)
            inputs["attention_mask"] = OnnxTensor.createTensor(env, mask)
            inputs["token_type_ids"] = OnnxTensor.createTensor(env, tokenType)
            val result = try {
                session.run(inputs)
            } finally {
                inputs.values.forEach { it.close() }
            }
            try {
                normalize(extractVector(result))
            } finally {
                result.close()
            }
        } catch (e: OrtException) {
            throw IllegalStateException("bge embedding 推理失败: ${e.message}", e)
        }
    }

    /** 取 rank3 输出（last_hidden_state）的 [CLS]（首 token hidden）；否则降级为全零。 */
    private fun extractVector(result: OrtSession.Result): FloatArray {
        val it = result.iterator()
        while (it.hasNext()) {
            val value = it.next().value
            if (value !is OnnxTensor) continue
            val shape = value.info.shape
            if (shape.size == 3) {
                val hidden = shape[2].toInt()
                val flat = FloatArray(value.floatBuffer.remaining())
                value.floatBuffer.get(flat)
                return if (hidden > 0 && flat.size >= hidden) {
                    flat.copyOfRange(0, hidden)
                } else flat
            }
        }
        return FloatArray(0)
    }

    private fun normalize(v: FloatArray): FloatArray {
        if (v.isEmpty()) return v
        var norm = 0f
        for (x in v) norm += x * x
        if (norm == 0f) return v
        val n = kotlin.math.sqrt(norm)
        return FloatArray(v.size) { v[it] / n }
    }

    override fun close() {
        session.close()
        env.close()
    }
}
