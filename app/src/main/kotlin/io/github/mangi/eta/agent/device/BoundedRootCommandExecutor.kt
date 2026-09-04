package io.github.mangi.eta.agent.device

import io.github.mangi.eta.core.AgentLogger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 只执行 Eta 内部构造的固定 Root 命令。调用方不得把模型参数直接拼成脚本。
 *
 * 输出在读取时即截断，但仍持续排空管道，避免子进程因缓冲区写满而挂起。
 */
internal class BoundedRootCommandExecutor(
    private val logger: AgentLogger,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : AutoCloseable {
    private val activeProcesses = ConcurrentHashMap.newKeySet<Process>()
    private val closed = AtomicBoolean(false)

    fun execute(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    ): Result {
        if (closed.get()) return Result.failed("ROOT_EXECUTOR_CLOSED")
        if (!rootAvailable()) return Result.failed("ROOT_REQUIRED")
        val envelope = RootCommandEnvelope(command)
        val process = runCatching {
            ProcessBuilder("su", "-c", envelope.script)
                .redirectErrorStream(false)
                .start()
        }.getOrElse {
            return Result.failed("ROOT_UNAVAILABLE")
        }
        if (!activeProcesses.add(process) || closed.get()) {
            terminate(process)
            return Result.failed("ROOT_EXECUTOR_CLOSED")
        }

        val ioPool = Executors.newFixedThreadPool(2)
        return try {
            val stdoutFuture = ioPool.submit<BoundedOutput> {
                process.inputStream.use { it.readBounded(maxOutputBytes) }
            }
            val stderrFuture = ioPool.submit<BoundedOutput> {
                process.errorStream.use {
                    it.readBounded(maxOutputBytes.coerceIn(1, MAX_MAX_OUTPUT_BYTES) + envelope.markerBytes)
                }
            }
            val completed = runCatching {
                process.waitFor(timeoutMillis.coerceIn(500L, MAX_TIMEOUT_MS), TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!completed) {
                terminate(process)
            }
            val stdout = runCatching { stdoutFuture.get(IO_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrDefault(BoundedOutput.EMPTY)
            val stderrRead = runCatching { stderrFuture.get(IO_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            val stderr = stderrRead ?: BoundedOutput.EMPTY
            val rootOutput = envelope.inspect(stderr.text)
            if (stderrRead != null && rootOutput.denied(completed)) {
                RootAccess.markDenied()
                logger.warn("Agent root access outcome=denied code=ROOT_REQUIRED")
                return Result.failed("ROOT_REQUIRED")
            }
            Result(
                exitCode = if (completed) runCatching { process.exitValue() }.getOrDefault(-1) else -2,
                stdout = stdout.text,
                stderr = rootOutput.stderr,
                timedOut = !completed,
                truncated = stdout.truncated || stderr.truncated,
            )
        } finally {
            activeProcesses.remove(process)
            terminate(process)
            ioPool.shutdownNow()
        }.also { result ->
            logger.debug {
                "Agent root command outcome=${if (result.ok) "completed" else "failed"} " +
                    "exit=${result.exitCode} timeout=${result.timedOut} " +
                    "output_chars=${result.stdout.length + result.stderr.length} " +
                    "truncated=${result.truncated}"
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeProcesses.toList().forEach(::terminate)
        activeProcesses.clear()
    }

    private fun terminate(process: Process) {
        if (process.isAlive) {
            runCatching { process.destroy() }
            runCatching { process.waitFor(250L, TimeUnit.MILLISECONDS) }
        }
        if (process.isAlive) runCatching { process.destroyForcibly() }
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun InputStream.readBounded(maxBytes: Int): BoundedOutput {
        val limit = maxBytes.coerceIn(1, MAX_MAX_OUTPUT_BYTES)
        val collected = ByteArrayOutputStream(limit.coerceAtMost(32 * 1024))
        val buffer = ByteArray(8 * 1024)
        var truncated = false
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            val remaining = limit - collected.size()
            if (remaining > 0) collected.write(buffer, 0, read.coerceAtMost(remaining))
            if (read > remaining) truncated = true
        }
        return BoundedOutput(
            text = collected.toString(StandardCharsets.UTF_8.name()),
            truncated = truncated,
        )
    }

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val truncated: Boolean,
        val errorCode: String = "",
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut && errorCode.isBlank()

        companion object {
            fun failed(code: String): Result = Result(
                exitCode = -1,
                stdout = "",
                stderr = "",
                timedOut = false,
                truncated = false,
                errorCode = code,
            )
        }
    }

    private data class BoundedOutput(
        val text: String,
        val truncated: Boolean,
    ) {
        companion object {
            val EMPTY = BoundedOutput("", truncated = false)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
        const val MAX_TIMEOUT_MS = 30_000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024
        const val MAX_MAX_OUTPUT_BYTES = 2 * 1024 * 1024
        const val IO_JOIN_TIMEOUT_MS = 2_000L
    }
}
