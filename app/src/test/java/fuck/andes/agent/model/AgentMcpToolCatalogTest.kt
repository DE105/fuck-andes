package fuck.andes.agent.model

import fuck.andes.agent.mcp.McpToolDescriptor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentMcpToolCatalogTest {

    private fun descriptor(
        serverId: String = "filesystem_ab12",
        serverName: String = "filesystem",
        toolName: String = "read_file",
        description: String = "读取文件",
    ) = McpToolDescriptor(
        serverId = serverId,
        serverName = serverName,
        toolName = toolName,
        description = description,
        inputSchema = JSONObject(
            """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
        ),
    )

    @Test
    fun toolNameEncodesServerAndTool() {
        val encoded = AgentMcpToolCatalog.toolName(descriptor())
        assertEquals("mcp__filesystem_ab12__read_file", encoded)
    }

    @Test
    fun parseToolNameRoundTrips() {
        val encoded = AgentMcpToolCatalog.toolName(descriptor())
        assertEquals("filesystem_ab12" to "read_file", AgentMcpToolCatalog.parseToolName(encoded))
    }

    @Test
    fun parseToolNameKeepsDashesAndDotsInToolName() {
        assertEquals(
            "server_x1" to "my-tool.with.dots",
            AgentMcpToolCatalog.parseToolName("mcp__server_x1__my-tool.with.dots"),
        )
    }

    @Test
    fun parseToolNameRejectsNonMcpNames() {
        assertNull(AgentMcpToolCatalog.parseToolName("terminal"))
        assertNull(AgentMcpToolCatalog.parseToolName("mcp__onlyServer"))
        assertNull(AgentMcpToolCatalog.parseToolName(""))
    }

    @Test
    fun appendToEmitsFunctionSchema() {
        val tools = org.json.JSONArray()
        AgentMcpToolCatalog.appendTo(tools, listOf(descriptor()))

        assertEquals(1, tools.length())
        val function = tools.getJSONObject(0)
        assertEquals("function", function.getString("type"))
        val fn = function.getJSONObject("function")
        assertEquals("mcp__filesystem_ab12__read_file", fn.getString("name"))
        assertTrue(fn.getString("description").contains("读取文件"))
        assertTrue(fn.getString("description").contains("filesystem"))
        assertEquals("object", fn.getJSONObject("parameters").getString("type"))
    }

    @Test
    fun appendToFallsBackToGeneratedDescription() {
        val tools = org.json.JSONArray()
        AgentMcpToolCatalog.appendTo(
            tools,
            listOf(descriptor(description = "")),
        )
        val description = tools.getJSONObject(0)
            .getJSONObject("function")
            .getString("description")
        assertTrue(description.contains("MCP 服务器 filesystem"))
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
