package fuck.andes.agent.mcp

import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import java.io.File
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * 本地 stdio MCP 服务器子进程桥接。
 *
 * 用 [ProcessBuilder] 拉起 `command + args`，通过 kotlinx-io 的
 * [asSource]/[asSink] 把进程 stdin/stdout/stderr 接到 SDK 的
 * [StdioClientTransport]；SDK 内部以 IODispatcher 处理阻塞流。
 */
internal class StdioProcessBridge private constructor(
    val process: Process,
    val transport: StdioClientTransport,
) {
    fun close() {
        runCatching { process.destroy() }
    }

    companion object {
        fun start(
            workingDirectory: File?,
            command: String,
            args: List<String>,
            env: Map<String, String>,
        ): StdioProcessBridge {
            val processBuilder = ProcessBuilder(listOf(command) + args)
            processBuilder.directory(workingDirectory)
            processBuilder.environment().putAll(env)
            val process = processBuilder.start()

            val transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered(),
                error = process.errorStream.asSource().buffered(),
            ) { line ->
                when {
                    line.contains("fatal", ignoreCase = true) ||
                        line.contains("panic", ignoreCase = true) ->
                        StdioClientTransport.StderrSeverity.FATAL

                    line.contains("error", ignoreCase = true) ->
                        StdioClientTransport.StderrSeverity.WARNING

                    line.contains("warn", ignoreCase = true) ->
                        StdioClientTransport.StderrSeverity.INFO

                    else -> StdioClientTransport.StderrSeverity.DEBUG
                }
            }
            return StdioProcessBridge(process, transport)
        }
    }
}
