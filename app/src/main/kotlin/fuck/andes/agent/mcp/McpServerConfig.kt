package fuck.andes.agent.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP 服务器传输类型。 */
@Serializable
internal enum class McpTransport {
    @SerialName("http")
    HTTP,

    @SerialName("sse")
    SSE,

    @SerialName("stdio")
    STDIO,
}

/**
 * 单个 MCP 服务器配置。
 *
 * - HTTP/SSE：使用 [url] 与 [headers]/[bearerToken]；
 * - STDIO：使用 [command] + [args] + [env] 在应用进程内拉起子进程（如 `npx`、`uvx`）。
 */
@Serializable
internal data class McpServerConfig(
    val id: String,
    val name: String,
    val transport: McpTransport,
    val url: String = "",
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val bearerToken: String = "",
    val enabled: Boolean = true,
    val timeoutSeconds: Long = 60,
) {
    val effectiveTimeoutMs: Long
        get() = timeoutSeconds.coerceIn(5L, 600L) * 1_000L

    /** 传输端点是否具备连接前提；空配置视为未完成，不参与发现。 */
    fun connectable(): Boolean = when (transport) {
        McpTransport.HTTP, McpTransport.SSE -> url.isNotBlank()
        McpTransport.STDIO -> command.isNotBlank()
    }
}
