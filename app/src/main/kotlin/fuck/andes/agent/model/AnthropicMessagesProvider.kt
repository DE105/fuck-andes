package fuck.andes.agent.model

import fuck.andes.agent.runtime.AgentRunController
import fuck.andes.agent.runtime.AgentTokenUsage
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object AnthropicMessagesProvider : AgentProviderClient {
    private const val MAX_ERROR_CHARS = 600
    private const val DEFAULT_MAX_TOKENS = 4096
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override val id: String = "anthropic_messages"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.ANTHROPIC_MESSAGES,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderResponse {
        val config = request.config
        val headers = okhttp3.Headers.Builder()
            .add("Content-Type", "application/json; charset=utf-8")
            .add("Accept", "text/event-stream")
            .add("anthropic-version", config.anthropicVersion)
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("x-api-key", config.apiKey)
                }
                CustomHeaderFilter.mergeInto(this, config.customHeaders)
            }
            .build()
        val httpRequest = Request.Builder()
            .url(ProviderUrls.anthropicMessagesUrl(config.baseUrl))
            .headers(headers)
            .post(
                buildRequestJson(config, request.messages, request.tools)
                    .toString()
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

        val call = AgentHttpClient.client.newCall(httpRequest)
        val binding = runController.register { call.cancel() }
        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            call.execute().use { response ->
                onEvent(ProviderEvent.ResponseHeaders(response.code))
                runController.throwIfCancelled()
                if (!response.isSuccessful) {
                    val errorBody = response.body.string()
                    error("Anthropic 接口返回 HTTP ${response.code}：${errorBody.compactError()}")
                }
                val assistant = readStreamingAssistantMessage(response.body.byteStream(), runController, onEvent)
                onEvent(ProviderEvent.Completed(assistant.optString("finish_reason").ifBlank { null }))
                return ProviderResponse(assistant)
            }
        } catch (throwable: Throwable) {
            runCatching { runController.throwIfCancelled() }
                .getOrElse { interruption -> throw interruption }
            throw throwable
        } finally {
            binding.close()
        }
    }

    private fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray
    ): JSONObject {
        val systemParts = mutableListOf<String>()
        val anthropicMessages = JSONArray()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "system" -> extractText(message.opt("content"))
                    .takeIf { it.isNotBlank() }
                    ?.let(systemParts::add)
                "user" -> anthropicMessages.put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", convertUserContent(message.opt("content")))
                )
                "assistant" -> anthropicMessages.put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", convertAssistantContent(message))
                )
                "tool" -> anthropicMessages.put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "tool_result")
                                    .put("tool_use_id", message.optString("tool_call_id"))
                                    .put("content", message.optString("content"))
                            )
                        )
                )
            }
        }

        return JSONObject()
            .put("model", config.model)
            .put("max_tokens", DEFAULT_MAX_TOKENS)
            .put("stream", true)
            .put("messages", anthropicMessages)
            .also { request ->
                val system = systemParts.joinToString("\n\n").trim()
                if (system.isNotBlank()) request.put("system", system)
                convertTools(tools)?.let { request.put("tools", it) }
                ProviderReasoning.applyAnthropicRequest(request, config)
                RequestBodyMerge.mergeCustomBody(request, config.customBody)
            }
    }

    private fun convertUserContent(content: Any?): JSONArray =
        when (content) {
            is JSONArray -> JSONArray().also { out ->
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    when (item.optString("type")) {
                        "text" -> item.optString("text")
                            .takeIf { it.isNotBlank() }
                            ?.let { out.put(JSONObject().put("type", "text").put("text", it)) }
                        "image_url" -> convertImageBlock(item)?.let(out::put)
                    }
                }
                if (out.length() == 0) out.put(JSONObject().put("type", "text").put("text", ""))
            }
            else -> JSONArray().put(JSONObject().put("type", "text").put("text", extractText(content)))
        }

    private fun convertAssistantContent(message: JSONObject): JSONArray {
        val content = JSONArray()
        extractText(message.opt("content"))
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { content.put(JSONObject().put("type", "text").put("text", it)) }
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (index in 0 until toolCalls.length()) {
                val toolCall = toolCalls.optJSONObject(index) ?: continue
                val function = toolCall.optJSONObject("function") ?: continue
                content.put(
                    JSONObject()
                        .put("type", "tool_use")
                        .put("id", toolCall.optString("id").ifBlank { "tool_call_$index" })
                        .put("name", function.optString("name"))
                        .put("input", parseJsonObject(function.optString("arguments")))
                )
            }
        }
        if (content.length() == 0) {
            content.put(JSONObject().put("type", "text").put("text", ""))
        }
        return content
    }

    private fun convertTools(tools: JSONArray): JSONArray? {
        if (tools.length() == 0) return null
        val converted = JSONArray()
        for (index in 0 until tools.length()) {
            val item = tools.optJSONObject(index) ?: continue
            val function = item.optJSONObject("function") ?: continue
            val name = function.optString("name")
            if (name.isBlank()) continue
            converted.put(
                JSONObject()
                    .put("name", name)
                    .put("description", function.optString("description"))
                    .put("input_schema", function.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
            )
        }
        return converted.takeIf { it.length() > 0 }
    }

    private fun convertImageBlock(item: JSONObject): JSONObject? {
        val url = item.optJSONObject("image_url")?.optString("url").orEmpty()
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma <= 5) return null
        val meta = url.substring(5, comma)
        val mediaType = meta.substringBefore(';').ifBlank { "image/png" }
        val data = url.substring(comma + 1)
        return JSONObject()
            .put("type", "image")
            .put(
                "source",
                JSONObject()
                    .put("type", "base64")
                    .put("media_type", mediaType)
                    .put("data", data)
            )
    }

    private fun readStreamingAssistantMessage(
        stream: InputStream,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): JSONObject {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val blocks = linkedMapOf<Int, AnthropicBlock>()
        var currentEvent = ""
        val data = StringBuilder()
        var sawMessageStop = false
        var finishReason: String? = null
        var usage: AgentTokenUsage? = null

        fun dispatch() {
            val payload = data.toString().trim()
            if (payload.isBlank()) {
                currentEvent = ""
                data.setLength(0)
                return
            }
            val result = processEvent(
                event = currentEvent,
                payload = payload,
                blocks = blocks,
                content = content,
                reasoning = reasoning,
                onEvent = onEvent
            )
            if (result.messageStop) sawMessageStop = true
            result.finishReason?.let { finishReason = it }
            result.usage?.let {
                usage = it
                onEvent(ProviderEvent.Usage(it))
            }
            currentEvent = ""
            data.setLength(0)
        }

        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            while (true) {
                runController.throwIfCancelled()
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> dispatch()
                    line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trim())
                    }
                }
            }
        }
        dispatch()
        if (!sawMessageStop) error("Anthropic SSE 流未正常结束")

        return JSONObject()
            .put("role", "assistant")
            .put("content", content.toString())
            .put("reasoning_content", reasoning.toString())
            .put("finish_reason", finishReason.orEmpty())
            .also { message ->
                usage?.let { message.put("usage", it.toJson()) }
                val toolCalls = blocks.values
                    .filter { it.type == "tool_use" && it.name.isNotBlank() }
                    .sortedBy { it.index }
                if (toolCalls.isNotEmpty()) {
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.forEachIndexed { position, block ->
                                array.put(block.toToolCallJson(position))
                            }
                        }
                    )
                }
            }
    }

    private fun processEvent(
        event: String,
        payload: String,
        blocks: MutableMap<Int, AnthropicBlock>,
        content: StringBuilder,
        reasoning: StringBuilder,
        onEvent: (ProviderEvent) -> Unit
    ): EventResult {
        if (payload == "[DONE]") return EventResult(messageStop = true)
        val json = JSONObject(payload)
        val type = json.optString("type").ifBlank { event }
        return when (type) {
            "message_start" -> EventResult(usage = parseUsage(json.optJSONObject("message")?.optJSONObject("usage")))
            "content_block_start" -> {
                val index = json.optInt("index")
                val block = json.optJSONObject("content_block") ?: JSONObject()
                val item = AnthropicBlock(
                    index = index,
                    type = block.optString("type"),
                    id = block.optString("id"),
                    name = block.optString("name")
                )
                block.optJSONObject("input")
                    ?.takeIf { it.length() > 0 }
                    ?.let { item.arguments.append(it.toString()) }
                blocks[index] = item
                when (item.type) {
                    "text" -> onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, index))
                    "thinking" -> onEvent(ProviderEvent.BlockStart(AssistantBlockKind.THINKING, index))
                    "tool_use" -> onEvent(
                        ProviderEvent.BlockStart(
                            kind = AssistantBlockKind.TOOL_CALL,
                            index = index,
                            blockId = item.id.ifBlank { null },
                            name = item.name.ifBlank { null },
                        )
                    )
                }
                EventResult()
            }
            "content_block_delta" -> {
                val index = json.optInt("index")
                val delta = json.optJSONObject("delta") ?: JSONObject()
                when (delta.optString("type")) {
                    "text_delta" -> {
                        val text = delta.optString("text")
                        if (text.isNotEmpty()) {
                            blocks.getOrPut(index) { AnthropicBlock(index = index, type = "text") }
                                .text
                                .append(text)
                            content.append(text)
                            onEvent(
                                ProviderEvent.BlockDelta(
                                    kind = AssistantBlockKind.TEXT,
                                    index = index,
                                    delta = text,
                                )
                            )
                        }
                    }
                    "thinking_delta" -> {
                        val text = delta.optString("thinking")
                        if (text.isNotEmpty()) {
                            blocks.getOrPut(index) { AnthropicBlock(index = index, type = "thinking") }
                                .thinking
                                .append(text)
                            reasoning.append(text)
                            onEvent(
                                ProviderEvent.BlockDelta(
                                    kind = AssistantBlockKind.THINKING,
                                    index = index,
                                    delta = text,
                                )
                            )
                        }
                    }
                    "input_json_delta" -> {
                        val partial = delta.optString("partial_json")
                        if (partial.isNotEmpty()) {
                            val block = blocks.getOrPut(index) { AnthropicBlock(index = index, type = "tool_use") }
                            block.arguments.append(partial)
                            onEvent(
                                ProviderEvent.BlockDelta(
                                    kind = AssistantBlockKind.TOOL_CALL,
                                    index = index,
                                    delta = partial,
                                )
                            )
                        }
                    }
                }
                EventResult()
            }
            "content_block_stop" -> {
                val index = json.optInt("index")
                val block = blocks[index] ?: return EventResult()
                val kind = when (block.type) {
                    "text" -> AssistantBlockKind.TEXT
                    "thinking" -> AssistantBlockKind.THINKING
                    "tool_use" -> AssistantBlockKind.TOOL_CALL
                    else -> return EventResult()
                }
                onEvent(
                    ProviderEvent.BlockEnd(
                        kind = kind,
                        index = index,
                        blockId = block.id.ifBlank { null },
                        name = block.name.ifBlank { null },
                        content = block.content(),
                    )
                )
                EventResult()
            }
            "message_delta" -> EventResult(
                finishReason = json.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotBlank() },
                usage = parseUsage(json.optJSONObject("usage"))
            )
            "message_stop" -> EventResult(messageStop = true)
            else -> EventResult()
        }
    }

    private data class AnthropicBlock(
        val index: Int,
        var type: String = "",
        var id: String = "",
        var name: String = "",
        val text: StringBuilder = StringBuilder(),
        val thinking: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun content(): String =
            when (type) {
                "text" -> text.toString()
                "thinking" -> thinking.toString()
                else -> arguments.toString()
            }

        fun toToolCallJson(position: Int): JSONObject =
            JSONObject()
                .put("id", id.ifBlank { "tool_call_$position" })
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", name)
                        .put("arguments", arguments.toString().ifBlank { "{}" })
                )
    }

    private data class EventResult(
        val messageStop: Boolean = false,
        val finishReason: String? = null,
        val usage: AgentTokenUsage? = null
    )

    private fun parseUsage(usage: JSONObject?): AgentTokenUsage? {
        usage ?: return null
        val uncachedInputTokens = usage.firstInt("input_tokens")
        val cachedInputTokens = usage.firstInt("cache_read_input_tokens")
        val cacheCreationInputTokens = usage.firstInt("cache_creation_input_tokens")
        return AgentTokenUsage(
            contextTokens = null,
            inputTokens = sumKnown(
                uncachedInputTokens,
                cachedInputTokens,
                cacheCreationInputTokens,
            ),
            outputTokens = usage.firstInt("output_tokens"),
            reasoningTokens = usage.firstInt("thinking_output_tokens"),
            cachedTokens = cachedInputTokens,
        ).takeUnless { it.isEmpty }
    }

    private fun extractText(content: Any?): String =
        when (content) {
            null, JSONObject.NULL -> ""
            is String -> content
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    if (item.optString("type") == "text") {
                        if (isNotEmpty()) append('\n')
                        append(item.optString("text"))
                    }
                }
            }
            else -> content.toString()
        }

    private fun parseJsonObject(raw: String): JSONObject =
        runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(JSONObject())

    private fun JSONObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            when (val raw = opt(key)) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun sumKnown(vararg values: Int?): Int? {
        if (values.all { it == null }) return null
        return values.sumOf { it?.toLong() ?: 0L }
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun AgentTokenUsage.toJson(): JSONObject =
        JSONObject().also { json ->
            inputTokens?.let { json.put("input_tokens", it) }
            outputTokens?.let { json.put("output_tokens", it) }
            reasoningTokens?.let { json.put("reasoning_tokens", it) }
            cachedTokens?.let { json.put("cached_tokens", it) }
        }

    private fun String.compactError(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }
}
