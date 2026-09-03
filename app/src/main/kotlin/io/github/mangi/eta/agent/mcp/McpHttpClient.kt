package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

internal class McpHttpClient(
    private val server: McpServerSetting,
    private val bearerToken: String?,
) : AutoCloseable {
    data class Discovery(
        val protocolVersion: String,
        val tools: List<McpToolDefinition>,
        val cacheTtlMs: Long?,
    )

    private data class ToolList(
        val tools: List<McpToolDefinition>,
        val cacheTtlMs: Long?,
    )

    private data class WireResponse(
        val json: JSONObject?,
        val sessionId: String?,
    )

    private val endpoint = validateMcpEndpoint(server.url)
    private val requestIds = AtomicLong(1)
    private val toolHeadersByName = mutableMapOf<String, McpToolHeaders>()
    private val lifecycleLock = Any()
    private var activeCall: Call? = null
    private var closed = false
    @Volatile
    private var negotiatedVersion: String? = null
    @Volatile
    private var legacySessionId: String? = null

    fun discoverTools(): Discovery {
        val requestedMode = server.protocolMode
        if (requestedMode == McpProtocolMode.LEGACY) return discoverLegacy()
        if (requestedMode == McpProtocolMode.LATEST) return discoverLatest()
        return try {
            discoverLatest()
        } catch (failure: McpProtocolCompatibilityException) {
            discoverLegacy()
        }
    }

    fun callTool(tool: McpToolDefinition, arguments: JSONObject): JSONObject {
        val version = negotiatedVersion ?: when (
            server.lastProtocolVersion ?: server.protocolMode
        ) {
            in SUPPORTED_LEGACY_PROTOCOL_VERSIONS -> initializeLegacy()
            else -> McpProtocolMode.LATEST.also { negotiatedVersion = it }
        }
        val parameterHeaders = if (version == McpProtocolMode.LATEST) {
            toolHeadersByName.getOrPut(tool.name) {
                McpToolHeaders.fromSchema(JSONObject(tool.inputSchemaJson))
            }.extract(arguments)
        } else {
            emptyMap()
        }
        fun invoke(sessionId: String?): JSONObject = unwrapResult(
            requireNotNull(
                request(
                    method = "tools/call",
                    name = tool.name,
                    params = JSONObject()
                        .put("name", tool.name)
                        .put("arguments", arguments),
                    protocolVersion = version,
                    sessionId = sessionId,
                    additionalHeaders = parameterHeaders,
                ).json,
            ) { "MCP 工具返回为空" },
        )
        return if (version in SUPPORTED_LEGACY_PROTOCOL_VERSIONS) {
            withLegacySessionRetry(::invoke)
        } else {
            invoke(sessionId = null)
        }
    }

    override fun close() {
        val call: Call?
        val sessionId: String?
        val protocolVersion: String?
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            call = activeCall
            activeCall = null
            sessionId = legacySessionId
            legacySessionId = null
            protocolVersion = negotiatedVersion
        }
        call?.cancel()
        if (sessionId != null && protocolVersion in SUPPORTED_LEGACY_PROTOCOL_VERSIONS) {
            releaseLegacySession(sessionId, requireNotNull(protocolVersion))
        }
    }

    private fun discoverLatest(): Discovery {
        negotiatedVersion = McpProtocolMode.LATEST
        return try {
            val listed = listTools(McpProtocolMode.LATEST, sessionId = null)
            Discovery(McpProtocolMode.LATEST, listed.tools, listed.cacheTtlMs)
        } catch (failure: McpHttpStatusException) {
            if (
                failure.code in setOf(400, 404, 405) &&
                failure.jsonRpcCode !in MODERN_JSON_RPC_ERRORS
            ) {
                throw McpProtocolCompatibilityException(failure)
            }
            throw failure
        } catch (failure: McpJsonRpcException) {
            if (failure.code == -32600) {
                throw McpProtocolCompatibilityException(failure)
            }
            throw failure
        }
    }

    private fun discoverLegacy(): Discovery {
        val listed = withLegacySessionRetry { sessionId ->
            listTools(requireNotNull(negotiatedVersion), sessionId)
        }
        return Discovery(requireNotNull(negotiatedVersion), listed.tools, cacheTtlMs = null)
    }

    private fun <T> withLegacySessionRetry(block: (String?) -> T): T {
        initializeLegacy()
        val sessionId = legacySessionId
        return try {
            block(sessionId)
        } catch (failure: McpHttpStatusException) {
            if (sessionId == null || failure.code != 404) throw failure
            legacySessionId = null
            negotiatedVersion = null
            initializeLegacy()
            block(legacySessionId)
        }
    }

    private fun initializeLegacy(): String {
        val currentVersion = negotiatedVersion
        if (currentVersion in SUPPORTED_LEGACY_PROTOCOL_VERSIONS && legacySessionId != null) {
            return requireNotNull(currentVersion)
        }
        val initialized = request(
            method = "initialize",
            params = JSONObject()
                .put("protocolVersion", McpProtocolMode.LEGACY)
                .put("capabilities", JSONObject())
                .put(
                    "clientInfo",
                    JSONObject().put("name", "Eta").put("version", CLIENT_VERSION),
                ),
            protocolVersion = McpProtocolMode.LEGACY,
            sessionId = null,
        )
        val initializeResult = unwrapResult(
            requireNotNull(initialized.json) { "MCP initialize 返回为空" }
        )
        val protocolVersion = initializeResult.optString("protocolVersion")
        require(protocolVersion in SUPPORTED_LEGACY_PROTOCOL_VERSIONS) {
            "MCP 服务器返回了不支持的协议版本"
        }
        val newSessionId = initialized.sessionId
        val accepted = synchronized(lifecycleLock) {
            if (closed) {
                false
            } else {
                legacySessionId = newSessionId
                true
            }
        }
        if (!accepted) {
            newSessionId?.let { releaseLegacySession(it, protocolVersion) }
            error("MCP 客户端已关闭")
        }
        notification(
            method = "notifications/initialized",
            protocolVersion = protocolVersion,
            sessionId = legacySessionId,
        )
        negotiatedVersion = protocolVersion
        return protocolVersion
    }

    private fun listTools(protocolVersion: String, sessionId: String?): ToolList {
        val tools = mutableListOf<McpToolDefinition>()
        var cursor: String? = null
        var cacheTtlMs: Long? = null
        repeat(MAX_LIST_PAGES) {
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val response = request(
                method = "tools/list",
                params = params,
                protocolVersion = protocolVersion,
                sessionId = sessionId,
            ).json ?: error("MCP tools/list 返回为空")
            val result = unwrapResult(response)
            if (protocolVersion == McpProtocolMode.LATEST) {
                // 旧服务可能忽略现代协议元数据并仍以 200 返回，
                // 此时需要改走 initialize 协商。
                if (!result.has("resultType") || result.isNull("resultType")) {
                    throw McpProtocolCompatibilityException(
                        IOException("MCP tools/list 返回了旧协议结果")
                    )
                }
                require(result.optString("resultType") == "complete") {
                    "MCP tools/list 返回了不支持的结果类型"
                }
                val pageTtl = result.nullableLong("ttlMs")?.coerceAtLeast(0L) ?: 0L
                cacheTtlMs = cacheTtlMs?.let { minOf(it, pageTtl) } ?: pageTtl
            }
            val page = result.optJSONArray("tools") ?: JSONArray()
            for (index in 0 until page.length()) {
                if (tools.size >= MAX_DISCOVERED_TOOLS) break
                page.optJSONObject(index)?.let { tools += parseTool(it) }
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            if (cursor == null || tools.size >= MAX_DISCOVERED_TOOLS) {
                return ToolList(tools, cacheTtlMs)
            }
        }
        return ToolList(tools, cacheTtlMs)
    }

    private fun parseTool(source: JSONObject): McpToolDefinition {
        val name = source.optString("name").trim()
        val schema = source.optJSONObject("inputSchema")
        val annotations = source.optJSONObject("annotations")
        return McpToolDefinition(
            name = name,
            title = source.optString("title").ifBlank {
                annotations?.optString("title").orEmpty()
            }.take(MAX_TITLE_CHARS),
            description = source.optString("description").take(MAX_DESCRIPTION_CHARS),
            inputSchemaJson = (schema ?: JSONObject()).toString(),
            readOnlyHint = annotations?.nullableBoolean("readOnlyHint"),
            destructiveHint = annotations?.nullableBoolean("destructiveHint"),
            idempotentHint = annotations?.nullableBoolean("idempotentHint"),
            openWorldHint = annotations?.nullableBoolean("openWorldHint"),
        )
    }

    private fun request(
        method: String,
        params: JSONObject,
        protocolVersion: String,
        sessionId: String?,
        name: String? = null,
        additionalHeaders: Map<String, String> = emptyMap(),
    ): WireResponse {
        val id = requestIds.getAndIncrement()
        val decoratedParams = JSONObject(params.toString())
        if (protocolVersion == McpProtocolMode.LATEST) {
            decoratedParams.put(
                "_meta",
                (decoratedParams.optJSONObject("_meta") ?: JSONObject())
                    .put("io.modelcontextprotocol/protocolVersion", protocolVersion)
                    .put(
                        "io.modelcontextprotocol/clientInfo",
                        JSONObject().put("name", "Eta").put("version", CLIENT_VERSION),
                    )
                    .put("io.modelcontextprotocol/clientCapabilities", JSONObject()),
            )
        }
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", decoratedParams)
        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/event-stream")
            .header(HEADER_PROTOCOL_VERSION, protocolVersion)
            .header(HEADER_METHOD, method)
            .apply {
                name?.let {
                    header(
                        HEADER_NAME,
                        if (protocolVersion == McpProtocolMode.LATEST) {
                            encodeMcpHeaderValue(it)
                        } else {
                            it
                        },
                    )
                }
                sessionId?.let { header(HEADER_SESSION_ID, it) }
                additionalHeaders.forEach { (headerName, headerValue) ->
                    header(headerName, headerValue)
                }
            }
            .applyAuthorization()
            .build()
        return execute(httpRequest, expectsBody = true, expectedId = id)
    }

    private fun notification(
        method: String,
        protocolVersion: String,
        sessionId: String?,
    ) {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", JSONObject())
        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/event-stream")
            .header(HEADER_PROTOCOL_VERSION, protocolVersion)
            .header(HEADER_METHOD, method)
            .apply { sessionId?.let { header(HEADER_SESSION_ID, it) } }
            .applyAuthorization()
            .build()
        execute(httpRequest, expectsBody = false, expectedId = null)
    }

    private fun execute(
        request: Request,
        expectsBody: Boolean,
        expectedId: Long?,
    ): WireResponse {
        val call = AgentHttpClient.client.newCall(request)
        synchronized(lifecycleLock) {
            check(!closed) { "MCP 客户端已关闭" }
            check(activeCall == null) { "MCP 客户端正在执行其他请求" }
            activeCall = call
        }
        try {
            call.execute().use { response ->
                val sessionId = response.header(HEADER_SESSION_ID)
                if (response.isSuccessful && !expectsBody) return WireResponse(null, sessionId)
                val json = if (response.isSuccessful) {
                    response.readWireJson(expectedId)
                } else {
                    runCatching { response.readWireJson(expectedId) }.getOrNull()
                }
                if (!response.isSuccessful) {
                    throw McpHttpStatusException(
                        code = response.code,
                        safeMessage = response.safeErrorSummary(),
                        jsonRpcCode = json?.optJSONObject("error")?.optInt("code"),
                    )
                }
                if (json == null) throw IOException("MCP 响应正文为空")
                if (expectedId != null && json.opt("id")?.toString() != expectedId.toString()) {
                    throw IOException("MCP 响应 id 不匹配")
                }
                json.optJSONObject("error")?.let { error ->
                    throw McpJsonRpcException(
                        code = error.optInt("code"),
                        safeMessage = error.optString("message").take(MAX_ERROR_CHARS),
                    )
                }
                return WireResponse(json, sessionId)
            }
        } finally {
            synchronized(lifecycleLock) {
                if (activeCall === call) activeCall = null
            }
        }
    }

    private fun releaseLegacySession(sessionId: String, protocolVersion: String) {
        val request = Request.Builder()
            .url(endpoint)
            .delete()
            .header(HEADER_PROTOCOL_VERSION, protocolVersion)
            .header(HEADER_SESSION_ID, sessionId)
            .applyAuthorization()
            .build()
        AgentHttpClient.client.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            }
        )
    }

    private fun Request.Builder.applyAuthorization(): Request.Builder = apply {
        bearerToken?.trim()?.takeIf { it.isNotBlank() }?.let { token ->
            header("Authorization", "Bearer $token")
        }
    }

    private fun unwrapResult(response: JSONObject): JSONObject =
        response.optJSONObject("result") ?: error("MCP 响应缺少 result")

    private fun Response.readWireJson(expectedId: Long?): JSONObject? =
        if (header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)) {
            readSseJson(expectedId)
        } else {
            readBoundedBody().takeIf(String::isNotBlank)?.let(::JSONObject)
        }

    private fun Response.readBoundedBody(): String {
        val bytes = body.source().use { source ->
            val buffer = Buffer()
            while (buffer.size <= MAX_RESPONSE_BYTES) {
                val read = source.read(
                    buffer,
                    minOf(8_192L, MAX_RESPONSE_BYTES + 1L - buffer.size),
                )
                if (read == -1L) break
            }
            buffer.readByteArray()
        }
        require(bytes.size <= MAX_RESPONSE_BYTES) { "MCP 响应超过大小限制" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun Response.safeErrorSummary(): String =
        message.take(MAX_ERROR_CHARS).ifBlank { "HTTP $code" }

    private fun Response.readSseJson(expectedId: Long?): JSONObject? {
        val source = body.source()
        val dataLines = mutableListOf<String>()
        var totalBytes = 0L

        fun consumeEvent(): JSONObject? {
            if (dataLines.isEmpty()) return null
            val json = runCatching { JSONObject(dataLines.joinToString("\n")) }.getOrNull()
            dataLines.clear()
            return json?.takeIf { candidate ->
                expectedId == null || candidate.opt("id")?.toString() == expectedId.toString()
            }
        }

        while (true) {
            val line = source.readBoundedSseLine(MAX_RESPONSE_BYTES - totalBytes) ?: break
            totalBytes += line.toByteArray().size + 1L
            require(totalBytes <= MAX_RESPONSE_BYTES) { "MCP 响应超过大小限制" }
            when {
                line.isEmpty() -> consumeEvent()?.let { return it }
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
            }
        }
        return consumeEvent()
    }

    private fun okio.BufferedSource.readBoundedSseLine(remainingBytes: Long): String? {
        require(remainingBytes > 0L) { "MCP 响应超过大小限制" }
        val newline = indexOf('\n'.code.toByte(), 0L, remainingBytes + 1L)
        if (newline >= 0L) {
            val line = readUtf8(newline)
            skip(1L)
            return line.removeSuffix("\r")
        }
        if (request(remainingBytes + 1L)) throw IOException("MCP 响应超过大小限制")
        if (exhausted()) return null
        val tail = readUtf8().removeSuffix("\r")
        return tail.ifEmpty { null }
    }

    private fun JSONObject.nullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

    private fun JSONObject.nullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val CLIENT_VERSION = "1"
        const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"
        const val HEADER_METHOD = "Mcp-Method"
        const val HEADER_NAME = "Mcp-Name"
        const val HEADER_SESSION_ID = "Mcp-Session-Id"
        const val MAX_RESPONSE_BYTES = 1_048_576L
        const val MAX_ERROR_CHARS = 200
        const val MAX_TITLE_CHARS = 160
        const val MAX_DESCRIPTION_CHARS = 2_000
        const val MAX_DISCOVERED_TOOLS = 128
        const val MAX_LIST_PAGES = 8
        val SUPPORTED_LEGACY_PROTOCOL_VERSIONS = setOf(
            "2024-11-05",
            "2025-03-26",
            "2025-06-18",
            McpProtocolMode.LEGACY,
        )
        val MODERN_JSON_RPC_ERRORS = setOf(-32601, -32020, -32021, -32022)
    }
}

internal class McpHttpStatusException(
    val code: Int,
    safeMessage: String,
    val jsonRpcCode: Int? = null,
) : IOException("MCP 请求失败：HTTP $code ${safeMessage.take(200)}")

internal class McpJsonRpcException(
    val code: Int,
    safeMessage: String,
) : IOException("MCP 协议错误：code=$code ${safeMessage.take(200)}")

private class McpProtocolCompatibilityException(cause: Throwable) : IOException(cause.message, cause)

internal fun validateMcpEndpoint(raw: String): okhttp3.HttpUrl {
    return raw.trim().toHttpUrlOrNull() ?: error("MCP 地址无效")
}
