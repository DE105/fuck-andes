package io.github.mangi.eta.agent.device

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 流式复制授权文件，不信任文档提供方声明的文件大小。 */
internal object BoundedFileCopy {
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long) {
        require(maxBytes >= 0L)
        val buffer = ByteArray(8 * 1024)
        var copied = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val read = input.read(buffer)
            if (read < 0) return
            if (copied + read > maxBytes) throw TooLargeException()
            output.write(buffer, 0, read)
            copied += read
        }
    }

    class TooLargeException : IOException("文件超过导入大小限制")
}
