package fuck.andes.agent.memory.embedding

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 512 维 float 向量 ↔ BLOB 字节序列化（小端）。纯 JVM 逻辑，可本地单测。 */
internal object VectorCodec {

    fun encode(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return buf.array()
    }

    fun decode(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val arr = FloatArray(buf.remaining() / 4)
        for (i in arr.indices) arr[i] = buf.getFloat()
        return arr
    }
}
