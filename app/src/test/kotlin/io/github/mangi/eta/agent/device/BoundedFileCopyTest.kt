package io.github.mangi.eta.agent.device

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedFileCopyTest {
    @Test
    fun acceptsExactLimitAndRejectsExcessWithoutWritingPastLimit() {
        val content = ByteArray(16_384) { (it % 127).toByte() }
        val output = ByteArrayOutputStream()
        BoundedFileCopy.copy(ByteArrayInputStream(content), output, content.size.toLong())
        assertArrayEquals(content, output.toByteArray())

        val bounded = ByteArrayOutputStream()
        try {
            BoundedFileCopy.copy(ByteArrayInputStream(content), bounded, 8_193L)
            throw AssertionError("超限文件必须被拒绝")
        } catch (_: BoundedFileCopy.TooLargeException) {
            assertTrue(bounded.size() <= 8_193)
        }
    }
}
