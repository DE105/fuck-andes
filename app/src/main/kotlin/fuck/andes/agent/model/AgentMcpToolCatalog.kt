package fuck.andes.agent.model

import android.util.Log
import fuck.andes.agent.mcp.McpClientManager
import fuck.andes.agent.mcp.McpConfigStore
import fuck.andes.agent.mcp.McpToolDescriptor
import fuck.andes.config.Prefs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * MCP 工具目录：把已发现的外部 MCP 服务器工具并入 Agent 的 function-calling 列表。
 *
 * 工具名编码为 `mcp__{serverId}__{toolName}` 以避免与内置工具冲突；调用时按
 * 同一格式解析回服务器与工具名。
 */
internal object AgentMcpToolCatalog {
    const val MCP_TOOL_PREFIX = "mcp__"

    private const val TAG = "AgentMcpToolCatalog"

    /**
     * 同步获取可用 MCP 工具（Agent complete 在后台线程调用）。
     * 优先返回已缓存结果，避免每次运行都重新连接全部服务器。
     */
    fun discoverBlocking(): List<McpToolDescriptor> {
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_MCP_TOOLS)) return emptyList()
        val cached = McpClientManager.cachedTools()
        if (cached.isNotEmpty()) return cached
        return runCatching {
            runBlocking { McpClientManager.discoverAll(McpConfigStore.servers()) }
        }.getOrElse { throwable ->
            Log.w(TAG, "MCP 工具发现失败: ${throwable.message}")
            emptyList()
        }
    }

    fun appendTo(tools: JSONArray, descriptors: List<McpToolDescriptor>) {
        descriptors.forEach { descriptor ->
            val description = buildString {
                if (descriptor.description.isNotBlank()) {
                    append(descriptor.description)
                } else {
                    append("通过 MCP 服务器 ${descriptor.serverName} 提供的工具 ${descriptor.toolName}")
                }
                append(" [来源: MCP 服务器 ${descriptor.serverName}]")
            }
            tools.put(
                AgentToolSchema.function(
                    name = toolName(descriptor),
                    description = description,
                    parameters = descriptor.inputSchema,
                ),
            )
        }
    }

    fun toolName(descriptor: McpToolDescriptor): String =
        "$MCP_TOOL_PREFIX${descriptor.serverId}__${descriptor.toolName}"

    /** 解析 `mcp__{serverId}__{toolName}` 为 (serverId, toolName)；非 MCP 工具返回 null。 */
    fun parseToolName(name: String): Pair<String, String>? {
        if (!name.startsWith(MCP_TOOL_PREFIX)) return null
        val parts = name.removePrefix(MCP_TOOL_PREFIX).split("__", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return parts[0] to parts[1]
    }
}
