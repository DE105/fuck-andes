package fuck.andes.agent.model

import fuck.andes.agent.runtime.AgentRunController
import fuck.andes.agent.runtime.AgentTokenUsage
import fuck.andes.data.model.OpenAiEndpointMode
import java.io.BufferedReader
import java.io.InputStreamReader
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object OpenAiChatCompletionsProvider : AgentProviderClient {
    private const val MAX_ERROR_CHARS = 600
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override val id: String = "openai_chat_completions"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
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
        require(config.openAiEndpointMode == OpenAiEndpointMode.CHAT_COMPLETIONS) {
            "Responses API 已预留配置位，但当前运行时仅支持 Chat Completions"
        }
        val url = ProviderUrls.openAiChatCompletionsUrl(config.baseUrl)
        val headers = okhttp3.Headers.Builder()
            .add("Content-Type", "application/json; charset=utf-8")
            .add("Accept", "text/event-stream")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .also { CustomHeaderFilter.mergeInto(it, config.customHeaders) }
            .build()

        val requestBody = buildRequestJson(config, request.messages, request.tools)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url(url)
            .headers(headers)
            .post(requestBody)
            .build()

        val call = AgentHttpClient.client.newCall(httpRequest)
        val binding = runController.register { call.cancel() }

        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)

            call.execute().use { response ->
                val code = response.code
                onEvent(ProviderEvent.ResponseHeaders(code))
                runController.throwIfCancelled()

                if (!response.isSuccessful) {
                    val errorBody = response.body.string()
                    error("模型接口返回 HTTP $code：${errorBody.compactError()}")
                }

                val assistantMessage = readStreamingAssistantMessage(response.body.byteStream(), runController, onEvent)
                onEvent(ProviderEvent.Completed(assistantMessage.optString("finish_reason").ifBlank { null }))
                return ProviderResponse(assistantMessage)
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
        return JSONObject()
            .put("model", config.model)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("messages", messages)
            .put("tools", tools)
            .put("tool_choice", "auto")
            .also { request ->
                ProviderReasoning.applyOpenAiCompatibleRequest(request, config)
                mergeExtraBody(request, config.extraBodyJson)
                RequestBodyMerge.mergeCustomBody(request, config.customBody)
            }
    }

    private fun readStreamingAssistantMessage(
        stream: java.io.InputStream?,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): JSONObject {
        if (stream == null) error("模型接口未返回响应流")
        val content = StringBuilder()
        val reasoningContent = StringBuilder()
        val toolCalls = linkedMapOf<Int, StreamingToolCall>()
        var usage: AgentTokenUsage? = null
        var sawStreamData = false
        var sawDone = false
        var finishReason: String? = null
        var nextContentIndex = 0
        var thinkingContentIndex: Int? = null
        var textContentIndex: Int? = null

        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            while (true) {
                runController.throwIfCancelled()
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                sawStreamData = true
                val payload = line.removePrefix("data:").trim()
                if (payload.isBlank()) continue
                if (payload == "[DONE]") {
                    sawDone = true
                    break
                }
                val chunk = JSONObject(payload)
                parseUsage(chunk)?.let { parsedUsage ->
                    usage = parsedUsage
                    onEvent(ProviderEvent.Usage(parsedUsage))
                }
                val choices = chunk.optJSONArray("choices")
                if (choices == null || choices.length() == 0) continue
                val choice = choices.optJSONObject(0) ?: continue
                val reason = choice.optString("finish_reason")
                if (reason.isNotBlank() && reason != "null") {
                    finishReason = reason
                }
                val delta = choice.optJSONObject("delta") ?: continue
                if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                    val text = delta.optString("reasoning_content")
                    if (text.isNotEmpty()) {
                        val index = thinkingContentIndex ?: nextContentIndex++
                            .also {
                                thinkingContentIndex = it
                                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.THINKING, it))
                            }
                        reasoningContent.append(text)
                        onEvent(
                            ProviderEvent.BlockDelta(
                                kind = AssistantBlockKind.THINKING,
                                index = index,
                                delta = text,
                            )
                        )
                    }
                }
                if (delta.has("content") && !delta.isNull("content")) {
                    val text = delta.optString("content")
                    if (text.isNotEmpty()) {
                        val index = textContentIndex ?: nextContentIndex++
                            .also {
                                textContentIndex = it
                                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, it))
                            }
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
                val deltaToolCalls = delta.optJSONArray("tool_calls") ?: continue
                for (i in 0 until deltaToolCalls.length()) {
                    val item = deltaToolCalls.optJSONObject(i) ?: continue
                    val index = item.optInt("index", i)
                    val call = toolCalls.getOrPut(index) {
                        StreamingToolCall(
                            index = index,
                            contentIndex = nextContentIndex++,
                        ).also { created ->
                            onEvent(
                                ProviderEvent.BlockStart(
                                    kind = AssistantBlockKind.TOOL_CALL,
                                    index = created.contentIndex,
                                )
                            )
                        }
                    }
                    if (item.has("id") && !item.isNull("id")) call.id = item.optString("id")
                    if (item.has("type") && !item.isNull("type")) call.type = item.optString("type").ifBlank { "function" }
                    val function = item.optJSONObject("function")
                    val nameDelta = function?.takeIf { it.has("name") && !it.isNull("name") }?.optString("name").orEmpty()
                    val argsDelta = function?.takeIf { it.has("arguments") && !it.isNull("arguments") }?.optString("arguments").orEmpty()
                    if (nameDelta.isNotEmpty()) call.name.append(nameDelta)
                    if (argsDelta.isNotEmpty()) call.arguments.append(argsDelta)
                    if (argsDelta.isNotEmpty()) {
                        onEvent(
                            ProviderEvent.BlockDelta(
                                kind = AssistantBlockKind.TOOL_CALL,
                                index = call.contentIndex,
                                delta = argsDelta,
                            )
                        )
                    }
                }
            }
        }

        if (!sawStreamData) error("模型接口未返回 SSE data chunk")
        if (!sawDone) error("模型接口 SSE 流未正常结束")

        thinkingContentIndex?.let { index ->
            onEvent(
                ProviderEvent.BlockEnd(
                    kind = AssistantBlockKind.THINKING,
                    index = index,
                    content = reasoningContent.toString(),
                )
            )
        }
        textContentIndex?.let { index ->
            onEvent(
                ProviderEvent.BlockEnd(
                    kind = AssistantBlockKind.TEXT,
                    index = index,
                    content = content.toString(),
                )
            )
        }
        toolCalls.values.sortedBy { it.contentIndex }.forEach { call ->
            onEvent(
                ProviderEvent.BlockEnd(
                    kind = AssistantBlockKind.TOOL_CALL,
                    index = call.contentIndex,
                    blockId = call.id,
                    name = call.name.toString().ifBlank { null },
                    content = call.arguments.toString(),
                )
            )
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", content.toString())
            .put("reasoning_content", reasoningContent.toString())
            .put("finish_reason", finishReason.orEmpty())
            .also { message ->
                usage?.let { message.put("usage", it.toJson()) }
            }
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.values.sortedBy { it.index }.forEachIndexed { position, call ->
                                array.put(call.toJson(position))
                            }
                        }
                    )
                }
            }
    }

    private data class StreamingToolCall(
        val index: Int,
        val contentIndex: Int,
        var id: String? = null,
        var type: String = "function",
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun toJson(position: Int): JSONObject {
            val functionName = name.toString().trim()
            return JSONObject()
                .put("id", id ?: "tool_call_$position")
                .put("type", type.ifBlank { "function" })
                .put(
                    "function",
                    JSONObject()
                        .put("name", functionName)
                        .put("arguments", arguments.toString())
                )
        }
    }

    private fun mergeExtraBody(request: JSONObject, extraBodyJson: String) {
        if (extraBodyJson.isBlank()) return
        val extraBody = JSONObject(extraBodyJson)
        extraBody.keys().forEach { key ->
            request.put(key, extraBody.get(key))
        }
    }

    private fun parseUsage(chunk: JSONObject): AgentTokenUsage? {
        val usage = chunk.optJSONObject("usage") ?: return null
        return AgentTokenUsage(
            contextTokens = usage.firstInt("total_tokens"),
            inputTokens = usage.firstInt("prompt_tokens", "input_tokens"),
            outputTokens = usage.firstInt("completion_tokens", "output_tokens"),
            reasoningTokens = usage.firstNestedInt(
                "completion_tokens_details",
                "output_tokens_details",
                childKey = "reasoning_tokens"
            ),
            cachedTokens = usage.firstNestedInt(
                "prompt_tokens_details",
                childKey = "cached_tokens"
            ) ?: usage.firstInt(
                "cached_tokens",
                "cached_input_tokens",
                "cache_read_input_tokens",
                "prompt_cache_hit_tokens",
            )
        ).takeUnless { it.isEmpty }
    }

    private fun JSONObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val raw = opt(key)
            when (raw) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.firstNestedInt(
        vararg parentKeys: String,
        childKey: String
    ): Int? {
        for (parentKey in parentKeys) {
            val parent = optJSONObject(parentKey) ?: continue
            parent.firstInt(childKey)?.let { return it }
        }
        return null
    }

    private fun AgentTokenUsage.toJson(): JSONObject =
        JSONObject().also { json ->
            contextTokens?.let { json.put("total_tokens", it) }
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
