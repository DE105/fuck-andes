package fuck.andes.agent.mcp

import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** 单个 MCP 服务器暴露的工具，用于并入 Agent 工具目录。 */
internal data class McpToolDescriptor(
    val serverId: String,
    val serverName: String,
    val toolName: String,
    val description: String,
    val inputSchema: JSONObject,
)

/** 工具调用结果，供 Agent 回灌。 */
internal data class McpCallResult(
    val text: String,
    val isError: Boolean,
)

/**
 * MCP 客户端连接与工具发现/调用管理。
 *
 * 会话按 [McpServerConfig.id] 复用，连接互斥串行化；工具目录带 TTL 缓存，
 * 配置变更或连接失败时失效。
 */
internal object McpClientManager {
    private const val TAG = "McpClientManager"
    private const val CACHE_TTL_MS = 30_000L
    private const val CLIENT_NAME = "fuck_andes"
    private const val CLIENT_VERSION = "2.6.7"

    private class McpSession(
        val client: Client,
        val bridge: StdioProcessBridge?,
        val httpClient: HttpClient? = null,
    ) {
        suspend fun close() {
            runCatching { client.close() }
            bridge?.close()
            runCatching { httpClient?.close() }
        }
    }

    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val connectMutex = Mutex()

    @Volatile
    private var toolsCache: List<McpToolDescriptor> = emptyList()

    @Volatile
    private var cacheTimestamp: Long = 0L

    /** 最近一次工具发现结果；未缓存时返回空列表。 */
    fun cachedTools(): List<McpToolDescriptor> = toolsCache

    fun clearCache() {
        toolsCache = emptyList()
        cacheTimestamp = 0L
    }

    /**
     * 对启用中的服务器执行工具发现，结果按 TTL 缓存。
     * 单个服务器失败不影响其余服务器；配置不可达时记为失败并从缓存剔除。
     */
    suspend fun discoverAll(configs: List<McpServerConfig>): List<McpToolDescriptor> {
        val now = System.currentTimeMillis()
        if (now - cacheTimestamp < CACHE_TTL_MS && toolsCache.isNotEmpty()) {
            return toolsCache
        }
        val discovered = configs
            .filter { it.enabled && it.connectable() }
            .flatMap { config ->
                runCatching {
                    withTimeout(config.effectiveTimeoutMs) { discoverServer(config) }
                }.getOrElse { cause ->
                    Log.w(TAG, "发现 ${config.name} 的工具失败: ${cause.message}")
                    emptyList()
                }
            }
        toolsCache = discovered
        cacheTimestamp = System.currentTimeMillis()
        return toolsCache
    }

    suspend fun discoverServer(config: McpServerConfig): List<McpToolDescriptor> {
        val session = sessionFor(config)
        val tools = session.client.listTools().tools
        Log.d(
            TAG,
            "发现 ${config.name} 工具: " +
                tools.joinToString { tool -> "${tool.name} schema=${tool.inputSchema}" },
        )
        return tools.map { tool -> tool.toDescriptor(config) }
    }

    /** 调用某服务器的工具并返回文本化结果。 */
    suspend fun invoke(config: McpServerConfig, toolName: String, arguments: JSONObject): McpCallResult {
        Log.d(TAG, "调用工具 ${config.name}/$toolName 参数: $arguments")
        val argumentsMap = arguments.toArgumentMap()
        Log.d(TAG, "调用工具 ${config.name}/$toolName map: $argumentsMap")
        val session = sessionFor(config)
        val result = withTimeout(config.effectiveTimeoutMs) {
            session.client.callTool(
                name = toolName,
                arguments = argumentsMap,
            )
        }
        return McpCallResult(
            text = renderContent(result.content),
            isError = result.isError == true,
        )
    }

    /** 关闭单个服务器会话（配置删除/禁用时调用）。 */
    suspend fun closeServer(config: McpServerConfig) {
        sessions.remove(config.id)?.close()
        clearCache()
    }

    /** 关闭全部会话（设置变更时调用）。 */
    suspend fun closeAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        clearCache()
    }

    private suspend fun sessionFor(config: McpServerConfig): McpSession {
        sessions[config.id]?.let { return it }
        connectMutex.withLock {
            sessions[config.id]?.let { return it }
            val session = connect(config)
            sessions[config.id] = session
            return session
        }
    }

    private suspend fun connect(config: McpServerConfig): McpSession {
        val clientInfo = Implementation(name = CLIENT_NAME, version = CLIENT_VERSION)
        return when (config.transport) {
            McpTransport.HTTP, McpTransport.SSE -> {
                val httpClient = buildHttpClient(config)
                val transport: Transport = when (config.transport) {
                    McpTransport.HTTP -> StreamableHttpClientTransport(
                        httpClient,
                        config.url,
                    ) {
                        applyAuth(config)
                    }
                    else -> SseClientTransport(
                        httpClient,
                        config.url,
                    ) {
                        applyAuth(config)
                    }
                }
                val sdkClient = Client(clientInfo)
                sdkClient.connect(DebugTransport(transport))
                McpSession(sdkClient, null, httpClient)
            }
            McpTransport.STDIO -> {
                val bridge = StdioProcessBridge.start(
                    workingDirectory = null,
                    command = config.command,
                    args = config.args,
                    env = config.env,
                )
                val sdkClient = Client(clientInfo)
                sdkClient.connect(bridge.transport)
                McpSession(sdkClient, bridge)
            }
        }
    }

    private fun buildHttpClient(config: McpServerConfig): HttpClient =
        HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = config.effectiveTimeoutMs
                connectTimeoutMillis = config.effectiveTimeoutMs
            }
        }

    /** 打印实际发出的 JSON-RPC 消息，用于核对 SDK 序列化后的完整请求体。 */
    private class DebugTransport(
        private val delegate: Transport,
    ) : Transport by delegate {
        override suspend fun send(message: JSONRPCMessage) {
            runCatching {
                Log.d(TAG, "OUTGOING ${message.method}: ${McpJson.encodeToString(message)}")
            }
            delegate.send(message)
        }
    }

    private fun HttpRequestBuilder.applyAuth(config: McpServerConfig) {
        if (config.bearerToken.isNotBlank()) {
            bearerAuth(config.bearerToken)
        }
        config.headers.forEach { (name, value) -> header(name, value) }
    }

    private fun Tool.toDescriptor(config: McpServerConfig): McpToolDescriptor = McpToolDescriptor(
        serverId = config.id,
        serverName = config.name,
        toolName = name,
        description = description ?: "",
        inputSchema = JSONObject().apply {
            put("type", "object")
            inputSchema.properties?.let { put("properties", JSONObject(it.toString())) }
            inputSchema.required?.let { put("required", JSONArray(it)) }
        },
    )

    private fun JSONObject.toArgumentMap(): Map<String, Any?> = buildMap {
        keys().forEach { key -> put(key, normalizeValue(get(key))) }
    }

    private fun normalizeValue(value: Any?): Any? = when (value) {
        is JSONObject -> value.toArgumentMap()
        is JSONArray -> (0 until value.length()).map { normalizeValue(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    private fun renderContent(content: List<ContentBlock>): String = buildString {
        content.forEach { block ->
            when (block) {
                is TextContent -> appendLine(block.text)
                is ImageContent -> appendLine("[图片内容: mime=${block.mimeType}]")
                is AudioContent -> appendLine("[音频内容: mime=${block.mimeType}]")
                else -> appendLine("[内容块: ${block.type}]")
            }
        }
    }.trim()
}
