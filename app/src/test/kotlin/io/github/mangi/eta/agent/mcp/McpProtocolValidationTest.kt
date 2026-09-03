package io.github.mangi.eta.agent.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class McpProtocolValidationTest {
    @Test
    fun modernDiscoveryUsesStatelessEnvelopeAndRoutingHeaders() {
        val requestBody = AtomicReference<String>()
        val methodHeader = AtomicReference<String>()
        val server = localServer { exchange ->
            requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            methodHeader.set(exchange.requestHeaders.getFirst("Mcp-Method"))
            exchange.respond(
                200,
                """{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","ttlMs":30000,"tools":[{"name":"search","inputSchema":{"type":"object"}}]}}""",
            )
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val discovery = McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }

            assertEquals(McpProtocolMode.LATEST, discovery.protocolVersion)
            assertEquals(30_000L, discovery.cacheTtlMs)
            assertEquals(listOf("search"), discovery.tools.map { it.name })
            assertEquals("tools/list", methodHeader.get())
            val body = JSONObject(requestBody.get())
            assertEquals("tools/list", body.getString("method"))
            assertEquals(
                McpProtocolMode.LATEST,
                body.getJSONObject("params").getJSONObject("_meta")
                    .getString("io.modelcontextprotocol/protocolVersion"),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun autoModeFallsBackToLegacySessionHandshake() {
        val requestIndex = AtomicInteger()
        val requestBodies = CopyOnWriteArrayList<String>()
        val deleteReceived = CountDownLatch(1)
        val server = localServer { exchange ->
            if (exchange.requestMethod == "DELETE") {
                deleteReceived.countDown()
                exchange.respond(200, "")
                return@localServer
            }
            requestBodies += exchange.requestBody.bufferedReader().use { it.readText() }
            when (requestIndex.getAndIncrement()) {
                0 -> exchange.respond(400, "bad protocol")
                1 -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""",
                    mapOf("Mcp-Session-Id" to "session-1"),
                )
                2 -> exchange.respond(202, "")
                3 -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"legacy","inputSchema":{"type":"object"}}]}}""",
                )
                else -> exchange.respond(200, "")
            }
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val discovery = McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }

            assertEquals(McpProtocolMode.LEGACY, discovery.protocolVersion)
            assertEquals(listOf("legacy"), discovery.tools.map { it.name })
            assertEquals(null, discovery.cacheTtlMs)
            assertEquals(4, requestIndex.get())
            assertNull(JSONObject(requestBodies[1]).getJSONObject("params").optJSONObject("_meta"))
            assertNull(JSONObject(requestBodies[3]).getJSONObject("params").optJSONObject("_meta"))
            check(deleteReceived.await(2, TimeUnit.SECONDS)) { "legacy session was not released" }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun autoModeFallsBackWhenModernToolListReturnsLegacyResultShape() {
        val requestIndex = AtomicInteger()
        val methods = CopyOnWriteArrayList<String>()
        val versions = CopyOnWriteArrayList<String>()
        val server = localServer { exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            methods += body.getString("method")
            versions += exchange.requestHeaders.getFirst("MCP-Protocol-Version")
            when (requestIndex.getAndIncrement()) {
                0 -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"ignored-modern","inputSchema":{"type":"object"}}]}}""",
                )
                1 -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""",
                )
                2 -> exchange.respond(202, "")
                3 -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"legacy","inputSchema":{"type":"object"}}]}}""",
                )
            }
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val discovery = McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }

            assertEquals("2024-11-05", discovery.protocolVersion)
            assertEquals(listOf("legacy"), discovery.tools.map { it.name })
            assertEquals(
                listOf("tools/list", "initialize", "notifications/initialized", "tools/list"),
                methods,
            )
            assertEquals(
                listOf(
                    McpProtocolMode.LATEST,
                    McpProtocolMode.LEGACY,
                    "2024-11-05",
                    "2024-11-05",
                ),
                versions,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun legacyHandshakeUsesOlderVersionSelectedByServer() {
        val requestIndex = AtomicInteger()
        val subsequentVersions = CopyOnWriteArrayList<String>()
        val server = localServer { exchange ->
            if (exchange.requestMethod == "DELETE") {
                exchange.respond(200, "")
                return@localServer
            }
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            when (requestIndex.getAndIncrement()) {
                0 -> exchange.respond(400, "legacy")
                1 -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":${body.getLong("id")},"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""",
                    mapOf("Mcp-Session-Id" to "session-1"),
                )
                2 -> {
                    subsequentVersions += exchange.requestHeaders.getFirst("MCP-Protocol-Version")
                    exchange.respond(202, "")
                }
                3 -> {
                    subsequentVersions += exchange.requestHeaders.getFirst("MCP-Protocol-Version")
                    exchange.respond(
                        200,
                        """{"jsonrpc":"2.0","id":${body.getLong("id")},"result":{"tools":[{"name":"legacy","inputSchema":{"type":"object"}}]}}""",
                    )
                }
            }
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val discovery = McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }

            assertEquals("2024-11-05", discovery.protocolVersion)
            assertEquals(listOf("2024-11-05", "2024-11-05"), subsequentVersions)
            assertEquals(listOf("legacy"), discovery.tools.map { it.name })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun modernJsonRpcHeaderErrorDoesNotFallBackToLegacy() {
        val requestCount = AtomicInteger()
        val server = localServer { exchange ->
            requestCount.incrementAndGet()
            exchange.respond(
                400,
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32020,"message":"Header mismatch"}}""",
            )
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val failure = runCatching {
                McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals(1, requestCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun sseParserCombinesDataLinesAndReturnsAtFinalEvent() {
        val eventSent = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val server = localServer { exchange ->
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                output.write(
                    """data: {"jsonrpc":"2.0",
data: "id":1,"result":{"resultType":"complete","ttlMs":1000,"tools":[]}}

""".toByteArray()
                )
                output.flush()
                eventSent.countDown()
                releaseServer.await(5, TimeUnit.SECONDS)
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val result = executor.submit<McpHttpClient.Discovery> {
                McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }
            }
            check(eventSent.await(2, TimeUnit.SECONDS)) { "SSE event was not sent" }
            val discovery = result.get(2, TimeUnit.SECONDS)

            assertEquals(emptyList<String>(), discovery.tools.map { it.name })
        } finally {
            releaseServer.countDown()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun legacyToolCallReinitializesOnceAfterSession404() {
        val initializeCount = AtomicInteger()
        val toolCallCount = AtomicInteger()
        val server = localServer { exchange ->
            if (exchange.requestMethod == "DELETE") {
                exchange.respond(200, "")
                return@localServer
            }
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            when (body.getString("method")) {
                "tools/list" -> if (exchange.requestHeaders.getFirst("MCP-Protocol-Version") == McpProtocolMode.LATEST) {
                    exchange.respond(400, "legacy")
                } else {
                    exchange.respond(
                        200,
                        """{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"legacy","inputSchema":{"type":"object"}}]}}""",
                    )
                }
                "initialize" -> {
                    val index = initializeCount.incrementAndGet()
                    exchange.respond(
                        200,
                        """{"jsonrpc":"2.0","id":${if (index == 1) 2 else 5},"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""",
                        mapOf("Mcp-Session-Id" to "session-$index"),
                    )
                }
                "notifications/initialized" -> exchange.respond(202, "")
                "tools/call" -> if (toolCallCount.incrementAndGet() == 1) {
                    exchange.respond(404, "expired")
                } else {
                    exchange.respond(
                        200,
                        """{"jsonrpc":"2.0","id":6,"result":{"content":[{"type":"text","text":"ok"}]}}""",
                    )
                }
            }
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            McpHttpClient(configured, bearerToken = null).use { client ->
                val tool = client.discoverTools().tools.single()
                val result = client.callTool(tool, JSONObject())

                assertEquals("ok", result.getJSONArray("content").getJSONObject(0).getString("text"))
            }
            assertEquals(2, initializeCount.get())
            assertEquals(2, toolCallCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun legacyToolListReinitializesOnceAfterSession404() {
        val initializeCount = AtomicInteger()
        val legacyListCount = AtomicInteger()
        val server = localServer { exchange ->
            if (exchange.requestMethod == "DELETE") {
                exchange.respond(200, "")
                return@localServer
            }
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            when (body.getString("method")) {
                "tools/list" -> if (exchange.requestHeaders.getFirst("MCP-Protocol-Version") == McpProtocolMode.LATEST) {
                    exchange.respond(400, "legacy")
                } else if (legacyListCount.incrementAndGet() == 1) {
                    exchange.respond(404, "expired")
                } else {
                    exchange.respond(
                        200,
                        """{"jsonrpc":"2.0","id":${body.getLong("id")},"result":{"tools":[{"name":"legacy","inputSchema":{"type":"object"}}]}}""",
                    )
                }
                "initialize" -> {
                    val index = initializeCount.incrementAndGet()
                    exchange.respond(
                        200,
                        """{"jsonrpc":"2.0","id":${body.getLong("id")},"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""",
                        mapOf("Mcp-Session-Id" to "session-$index"),
                    )
                }
                "notifications/initialized" -> exchange.respond(202, "")
            }
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val discovery = McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }

            assertEquals(listOf("legacy"), discovery.tools.map { it.name })
            assertEquals(2, initializeCount.get())
            assertEquals(2, legacyListCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun closeCancelsActiveRequestWithoutWaitingForNetworkTimeout() {
        val requestStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val server = localServer { exchange ->
            requestStarted.countDown()
            releaseServer.await(5, TimeUnit.SECONDS)
            runCatching { exchange.respond(200, "{}") }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val client = McpHttpClient(configured, bearerToken = null)
            val result = executor.submit<Throwable?> {
                runCatching { client.discoverTools() }.exceptionOrNull()
            }
            check(requestStarted.await(2, TimeUnit.SECONDS)) { "request did not start" }

            val startedAt = System.nanoTime()
            client.close()
            val closeElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            releaseServer.countDown()

            assertNotNull(result.get(2, TimeUnit.SECONDS))
            check(closeElapsedMs < 500L) { "close blocked for ${closeElapsedMs}ms" }
        } finally {
            releaseServer.countDown()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun endpointAcceptsUserConfiguredHttpAndHttpsUrls() {
        assertEquals("https", validateMcpEndpoint("https://example.com/mcp").scheme)
        assertEquals("http", validateMcpEndpoint("http://127.0.0.1:8080/mcp").scheme)
        assertEquals("http", validateMcpEndpoint("http://192.168.1.2:8080/mcp").scheme)
        assertEquals("user", validateMcpEndpoint("https://user@example.com/mcp").username)
        assertNotNull(runCatching { validateMcpEndpoint("not a url") }.exceptionOrNull())
    }

    @Test
    fun modernDiscoveryKeepsSchemaKeywordsWithoutAvailabilityGate() {
        val schema = JSONObject(
            """{"type":"object","anyOf":[{"required":["query"]},{"required":["filter"]}],"properties":{"query":{"${'$'}ref":"#/${'$'}defs/query"}},"${'$'}defs":{"query":{"type":"string"}}}"""
        )
        val server = localServer { exchange ->
            exchange.respond(
                200,
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put(
                        "result",
                        JSONObject()
                            .put("resultType", "complete")
                            .put("ttlMs", 1_000)
                            .put(
                                "tools",
                                org.json.JSONArray().put(
                                    JSONObject()
                                        .put("name", "search notes / advanced")
                                        .put("inputSchema", schema)
                                )
                            )
                    )
                    .toString(),
            )
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val tool = McpHttpClient(configured, bearerToken = null).use {
                it.discoverTools().tools.single()
            }

            assertEquals("search notes / advanced", tool.name)
            assertEquals(schema.toString(), tool.inputSchemaJson)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun modernToolCallMirrorsAnnotatedParameters() {
        val nameHeader = AtomicReference<String>()
        val regionHeader = AtomicReference<String>()
        val pageHeader = AtomicReference<String>()
        val server = localServer { exchange ->
            nameHeader.set(exchange.requestHeaders.getFirst("Mcp-Name"))
            regionHeader.set(exchange.requestHeaders.getFirst("Mcp-Param-Region"))
            pageHeader.set(exchange.requestHeaders.getFirst("Mcp-Param-Page"))
            exchange.respond(
                200,
                """{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","content":[]}}""",
            )
        }
        try {
            val configured = McpServerSetting(
                id = "test",
                name = "Test",
                url = server.url(),
                lastProtocolVersion = McpProtocolMode.LATEST,
            )
            val tool = McpToolDefinition(
                name = "执行任务",
                inputSchemaJson = """{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"},"page":{"type":"integer","x-mcp-header":"Page"}}}""",
            )

            McpHttpClient(configured, bearerToken = null).use { client ->
                client.callTool(
                    tool,
                    JSONObject().put("region", "华东").put("page", 42),
                )
            }

            assertEquals(encodeMcpHeaderValue("执行任务"), nameHeader.get())
            assertEquals(encodeMcpHeaderValue("华东"), regionHeader.get())
            assertEquals("42", pageHeader.get())
        } finally {
            server.stop(0)
        }
    }

    private fun localServer(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp", handler)
            start()
        }

    private fun HttpServer.url(): String = "http://127.0.0.1:${address.port}/mcp"

    private fun HttpExchange.respond(
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        headers.forEach(responseHeaders::set)
        if (body.isNotEmpty()) responseHeaders.set("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
