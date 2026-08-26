package fuck.andes.agent.memory.embedding

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VectorCodecTest {

    @Test
    fun roundtrip() {
        val v = floatArrayOf(0.1f, -0.2f, 0.5f, 1.0f, -0.9f)
        val b = VectorCodec.encode(v)
        val out = VectorCodec.decode(b)
        assertArrayEquals(v, out, 1e-6f)
    }

    @Test
    fun 空向量() {
        val b = VectorCodec.encode(FloatArray(0))
        assertArrayEquals(FloatArray(0), VectorCodec.decode(b), 1e-6f)
    }

    @Test
    fun roundtrip512() {
        val v = FloatArray(512) { (it % 17) * 0.03f }
        val out = VectorCodec.decode(VectorCodec.encode(v))
        assertArrayEquals(v, out, 1e-4f)
    }
}
