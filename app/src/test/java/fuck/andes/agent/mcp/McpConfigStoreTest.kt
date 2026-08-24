package fuck.andes.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConfigStoreTest {

    @Test
    fun parseImportJson_httpAndStdioServers() {
        val raw = """
            {
              "mcpServers": {
                "filesystem": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
                  "env": { "LANG": "en_US.UTF-8" }
                },
                "remote": {
                  "url": "https://example.com/mcp",
                  "bearerToken": "abc123",
                  "headers": { "X-Custom": "yes" }
                }
              }
            }
        """.trimIndent()

        val servers = McpConfigStore.parseImportJson(raw)

        assertEquals(2, servers.size)
        val filesystem = servers.first { it.name == "filesystem" }
        assertEquals(McpTransport.STDIO, filesystem.transport)
        assertEquals("npx", filesystem.command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-filesystem", "/tmp"), filesystem.args)
        assertEquals("en_US.UTF-8", filesystem.env["LANG"])
        assertTrue(filesystem.connectable())

        val remote = servers.first { it.name == "remote" }
        assertEquals(McpTransport.HTTP, remote.transport)
        assertEquals("https://example.com/mcp", remote.url)
        assertEquals("abc123", remote.bearerToken)
        assertEquals("yes", remote.headers["X-Custom"])
        assertTrue(remote.connectable())
    }

    @Test
    fun parseImportJson_missingUrlAndCommandThrows() {
        val raw = """{"mcpServers": {"broken": {}}}"""
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            McpConfigStore.parseImportJson(raw)
        }
    }

    @Test
    fun parseImportJson_missingMcpServersThrows() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            McpConfigStore.parseImportJson("""{"foo": {}}""")
        }
    }

    @Test
    fun exportImportRoundTripPreservesHttpServer() {
        val config = McpServerConfig(
            id = "remote_a1b2",
            name = "remote",
            transport = McpTransport.HTTP,
            url = "https://example.com/mcp",
            bearerToken = "secret",
            headers = mapOf("X-Custom" to "yes"),
            enabled = false,
            timeoutSeconds = 120,
        )

        val exported = McpConfigStore.exportServersJson(listOf(config))
        assertTrue(exported.contains("\"mcpServers\""))
        assertTrue(exported.contains("https://example.com/mcp"))

        val imported = McpConfigStore.parseImportJson(exported).single()
        assertEquals(config.name, imported.name)
        assertEquals(McpTransport.HTTP, imported.transport)
        assertEquals(config.url, imported.url)
        assertEquals(config.bearerToken, imported.bearerToken)
        assertEquals(config.headers, imported.headers)
        assertFalse(imported.enabled)
        assertEquals(120L, imported.timeoutSeconds)
    }

    @Test
    fun newIdGeneratesStableSlugWithHashSuffix() {
        val first = McpConfigStore.newId("My Files Server!")
        val second = McpConfigStore.newId("My Files Server!")
        assertEquals(first, second)
        assertTrue(first.startsWith("my_files_server_"))
        assertTrue(first.matches(Regex("^[a-z0-9._-]+$")))

        val blank = McpConfigStore.newId("!!!")
        assertTrue(blank.startsWith("server_"))
    }

    @Test
    fun serverConfigConnectableRules() {
        assertTrue(
            McpServerConfig(
                id = "a", name = "a", transport = McpTransport.HTTP, url = "https://x",
            ).connectable(),
        )
        assertFalse(
            McpServerConfig(
                id = "b", name = "b", transport = McpTransport.SSE, url = "",
            ).connectable(),
        )
        assertTrue(
            McpServerConfig(
                id = "c", name = "c", transport = McpTransport.STDIO, command = "npx",
            ).connectable(),
        )
        assertFalse(
            McpServerConfig(
                id = "d", name = "d", transport = McpTransport.STDIO, command = "",
            ).connectable(),
        )
    }
}
