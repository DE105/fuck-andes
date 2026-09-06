package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelClientLoopTest {
    @Test
    fun eachRoundUsesOneCapabilitySnapshotForDeclarationValidationAndPrompt() {
        var root = true
        var captures = 0
        val executed = mutableListOf<String>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                assertTrue(request.tools.toString().contains("set_setting"))
                assertTrue(request.messages.toString().contains("相关应用私有文件与数据库"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("first", "get_current_context", "{}")))
            },
            { request, _ ->
                assertFalse(request.tools.toString().contains("set_setting"))
                assertFalse(request.messages.toString().contains("相关应用私有文件与数据库"))
                assertTrue(request.messages.toString().contains("identity=user"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("stale", "terminal", "{\"action\":\"open\",\"identity\":\"root\"}"),
                ))
            },
            { request, _ ->
                assertTrue(request.messages.toString().contains("INVALID_TOOL_ARGUMENTS"))
                assistant(content = "完成", finishReason = "stop")
            },
        ))
        AgentModelClient.complete(
            config = modelConfig().copy(terminalTools = true, deviceSensitiveActionTools = true),
            prompt = "开始",
            provider = provider,
            capabilitiesProvider = {
                captures++
                AgentToolCapabilities(rootAvailable = root)
            },
            toolExecutor = AgentModelClient.ToolExecutor {
                executed += it.id
                root = false
                AgentModelClient.ToolResult("{\"ok\":true}")
            },
        )
        assertEquals(listOf("first"), executed)
        assertEquals(4, captures)
    }

    @Test
    fun textOnlyRunReturnsIncrementalTranscript() {
        val provider = ScriptedProvider(
            assistant(content = "完成", finishReason = "stop")
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "当前问题",
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "旧问题"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "旧回答"),
            ),
            toolExecutor = AgentModelClient.ToolExecutor { error("不应调用工具") },
            provider = provider,
        )

        assertEquals("完成", result.content)
        assertEquals(listOf("assistant"), result.transcript.map { it.role })
        assertEquals("完成", result.transcript.single().content)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun toolBatchFeedsResultsBackInSourceOrder() {
        val provider = ScriptedProvider(
            assistant(
                content = "先执行",
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "get_current_context", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
                reasoning = "需要两个结果",
            ),
            assistant(content = "已完成", finishReason = "stop"),
        )
        val executed = mutableListOf<String>()

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                AgentModelClient.ToolResult(
                    JSONObject()
                        .put("ok", true)
                        .put("call", call.id)
                        .toString()
                )
            },
            provider = provider,
        )

        assertEquals(listOf("call-1", "call-2"), executed)
        assertEquals("需要两个结果", result.reasoningContent)
        assertEquals(
            listOf("assistant", "tool", "tool", "assistant"),
            result.transcript.map { it.role },
        )
        assertEquals(
            listOf("assistant", "tool", "tool"),
            provider.requests[1].roleSuffix(3),
        )
        assertEquals("call-1", provider.requests[1].getJSONObjectFromEnd(2).getString("tool_call_id"))
        assertEquals("call-2", provider.requests[1].getJSONObjectFromEnd(1).getString("tool_call_id"))
    }

    @Test
    fun steeringWaitsForWholeToolBatchWithoutCancellingResources() {
        val controller = AgentRunController()
        val cancelledResources = AtomicInteger(0)
        controller.register { cancelledResources.incrementAndGet() }
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "get_current_context", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
            ),
            assistant(content = "已按补充完成", finishReason = "stop"),
        )
        val executed = mutableListOf<String>()

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                if (call.id == "call-1") controller.steer("改用第二种方案")
                AgentModelClient.ToolResult(JSONObject().put("ok", true).toString())
            },
            provider = provider,
            runController = controller,
        )

        assertEquals(listOf("call-1", "call-2"), executed)
        assertEquals(0, cancelledResources.get())
        assertFalse(controller.hasPendingSteering)
        assertEquals("已按补充完成", result.content)
        assertEquals(
            listOf("assistant", "tool", "tool", "user"),
            provider.requests[1].roleSuffix(4),
        )
        assertTrue(
            provider.requests[1]
                .getJSONObjectFromEnd(1)
                .getString("content")
                .contains("改用第二种方案")
        )
    }

    @Test
    fun steeringAfterTextResponsePreservesThatAssistantTurn() {
        val controller = AgentRunController()
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    controller.steer("再补充一项")
                    assistant(content = "第一段回答", finishReason = "stop")
                },
                { _, _ -> assistant(content = "最终回答", finishReason = "stop") },
            )
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { error("不应调用工具") },
            provider = provider,
            runController = controller,
        )

        assertEquals("最终回答", result.content)
        assertEquals(
            listOf("assistant", "user", "assistant"),
            result.transcript.map { it.role },
        )
        assertEquals("第一段回答", result.transcript.first().content)
    }

    @Test
    fun truncatedToolCallIsReportedWithoutExecution() {
        listOf("length", "max_tokens").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "terminal", "{\"command\":\"rm -")),
                ),
                assistant(content = "已重新规划", finishReason = "stop"),
            )
            var executed = false
            val events = mutableListOf<AgentEvent>()

            val result = AgentModelClient.complete(
                config = modelConfig(),
                prompt = "执行任务",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executed = true
                    AgentModelClient.ToolResult("unexpected")
                },
                provider = provider,
                onEvent = events::add,
            )

            assertFalse(executed)
            assertEquals("已重新规划", result.content)
            val toolResult = provider.requests[1].getJSONObjectFromEnd(1)
            assertEquals("tool", toolResult.getString("role"))
            assertTrue(toolResult.getString("content").contains("TRUNCATED_TOOL_CALL"))
            val toolStarted = events.filterIsInstance<AgentEvent.ToolStarted>().single()
            assertFalse(toolStarted.argsPreview.contains("rm -"))
        }
    }

    @Test
    fun malformedAndDuplicateToolCallsReceiveStableTerminalResults() {
        val malformedCalls = JSONArray()
            .put(toolCall("duplicate", "get_current_context", "{}"))
            .put(toolCall("duplicate", "get_current_context", "{}"))
            .put("not-an-object")
        val firstResponse = assistant(content = "", finishReason = "tool_calls")
            .put("tool_calls", malformedCalls)
        val provider = ScriptedProvider(
            firstResponse,
            assistant(content = "recovered", finishReason = "stop"),
        )
        val executed = mutableListOf<Pair<String, String>>()

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id to call.name
                AgentModelClient.ToolResult(JSONObject().put("ok", false).toString())
            },
            provider = provider,
        )

        assertEquals(
            listOf(
                "duplicate" to "get_current_context",
                "duplicate_1" to "get_current_context",
            ),
            executed,
        )
        val secondRequest = provider.requests[1]
        assertEquals(
            listOf("duplicate", "duplicate_1", "tool_call_2"),
            secondRequest
                .getJSONObject(secondRequest.length() - 4)
                .getJSONArray("tool_calls")
                .let { calls -> (0 until calls.length()).map { calls.getJSONObject(it).getString("id") } },
        )
        assertEquals(
            listOf("duplicate", "duplicate_1", "tool_call_2"),
            (3 downTo 1).map { offset ->
                secondRequest.getJSONObjectFromEnd(offset).getString("tool_call_id")
            },
        )
    }

    @Test
    fun imageObservationsFollowEveryToolResultInTheBatch() {
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "observe_screen", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
            ),
            assistant(content = "看到了", finishReason = "stop"),
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "观察",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = if (call.id == "call-1") {
                        listOf(
                            AgentModelClient.ModelImage(
                                reference = "data:image/png;base64,AA==",
                                mimeType = "image/png",
                                bytes = 1,
                            )
                        )
                    } else {
                        emptyList()
                    },
                )
            },
            provider = provider,
        )

        assertEquals(
            listOf("assistant", "tool", "tool", "user"),
            provider.requests[1].roleSuffix(4),
        )
        assertFalse(result.transcript.any { it.contentJson.contains("base64") })
        assertFalse(result.transcript.any { it.contentJson.contains("未写入持久会话") })
    }

    @Test
    fun toolScreenshotIsConsumedByExactlyOneModelRequest() {
        val screenshot = "data:image/png;base64,c2NyZWVu"
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe", "observe_screen", "{}")),
            ),
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("tap", "tap", "{\"x\":10,\"y\":20}")),
            ),
            assistant(content = "完成", finishReason = "stop"),
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "观察后点击",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = if (call.name == "observe_screen") {
                        listOf(
                            AgentModelClient.ModelImage(
                                reference = screenshot,
                                mimeType = "image/png",
                                bytes = 6,
                                source = "screen",
                            )
                        )
                    } else {
                        emptyList()
                    },
                )
            },
            provider = provider,
        )

        assertFalse(provider.requests[0].toString().contains(screenshot))
        assertTrue(provider.requests[1].toString().contains(screenshot))
        assertFalse(provider.requests[2].toString().contains(screenshot))
        assertFalse(result.transcript.any { it.contentJson.contains(screenshot) })
    }

    @Test
    fun newerToolImageReplacesThePreviousTransientObservation() {
        val firstImage = "data:image/png;base64,Zmlyc3Q="
        val secondImage = "data:image/png;base64,c2Vjb25k"
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe-1", "observe_screen", "{}")),
            ),
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe-2", "observe_screen", "{}")),
            ),
            assistant(content = "完成", finishReason = "stop"),
        )
        var observationIndex = 0

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "连续观察",
            toolExecutor = AgentModelClient.ToolExecutor {
                val reference = if (observationIndex++ == 0) firstImage else secondImage
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = listOf(
                        AgentModelClient.ModelImage(
                            reference = reference,
                            mimeType = "image/png",
                            bytes = 6,
                            source = "screen",
                        )
                    ),
                )
            },
            provider = provider,
        )

        assertTrue(provider.requests[1].toString().contains(firstImage))
        assertFalse(provider.requests[2].toString().contains(firstImage))
        assertTrue(provider.requests[2].toString().contains(secondImage))
    }

    @Test
    fun missingRequiredArgumentsNeverReachDeviceExecutor() {
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("call-1", "tap", "{}")),
            ),
            assistant(content = "已修正", finishReason = "stop"),
        )
        var executed = false

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "点击",
            toolExecutor = AgentModelClient.ToolExecutor {
                executed = true
                AgentModelClient.ToolResult("unexpected")
            },
            provider = provider,
        )

        assertFalse(executed)
        assertTrue(
            provider.requests[1]
                .getJSONObjectFromEnd(1)
                .getString("content")
                .contains("INVALID_TOOL_ARGUMENTS")
        )
    }

    @Test
    fun contradictoryStopReasonNeverExecutesToolCalls() {
        listOf("stop", "content_filter", "refusal").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "tap", "{\"x\":1,\"y\":2}")),
                ),
                assistant(content = "已安全结束", finishReason = "stop"),
            )
            var executed = false

            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "开始",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executed = true
                    AgentModelClient.ToolResult("unexpected")
                },
                provider = provider,
            )

            assertFalse(executed)
            assertTrue(
                provider.requests[1]
                    .getJSONObjectFromEnd(1)
                    .getString("content")
                    .contains("UNEXPECTED_TOOL_CALL")
            )
        }
    }

    @Test
    fun normalToolStopAliasesExecuteValidatedCalls() {
        listOf("tool_calls", "tool_use").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "get_current_context", "{}")),
                ),
                assistant(content = "完成", finishReason = "stop"),
            )
            var executions = 0

            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "开始",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executions += 1
                    AgentModelClient.ToolResult("{\"ok\":true}")
                },
                provider = provider,
            )

            assertEquals(1, executions)
        }
    }

    @Test
    fun providerFailureCarriesCompletedToolTranscriptForSafeRecovery() {
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    assistant(
                        finishReason = "tool_calls",
                        reasoning = "先检查状态",
                        toolCalls = listOf(
                            toolCall("call-1", "get_current_context", "{}")
                        ),
                    )
                },
                { _, _ -> error("provider disconnected") },
            )
        )

        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "开始",
                toolExecutor = AgentModelClient.ToolExecutor {
                    AgentModelClient.ToolResult("{\"ok\":true}")
                },
                provider = provider,
            )
        }

        assertEquals(listOf("assistant", "tool"), failure.transcript.map { it.role })
        assertEquals("先检查状态", failure.reasoningContent)
    }

    @Test
    fun loopContinuesPastFormerLocalLimitsUntilProviderFinishes() {
        val toolRounds = 257
        val responses = List<(ProviderRequest, AgentRunController) -> JSONObject>(toolRounds) { index ->
            { _, _ ->
                assistant(
                    finishReason = "tool_calls",
                    toolCalls = listOf(toolCall("call-$index", "get_current_context", "{}")),
                )
            }
        } + listOf<(ProviderRequest, AgentRunController) -> JSONObject>(
            { _, _ -> assistant(content = "完成", finishReason = "stop") }
        )
        val provider = ScriptedProvider(responses)
        var executions = 0
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("开始"))

        val result = AgentLoop(
            config = modelConfig(),
            messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                executions += 1
                AgentModelClient.ToolResult(JSONObject().put("ok", true).toString())
            },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
        ).run()

        assertEquals("完成", result.content)
        assertEquals(toolRounds, executions)
        assertEquals(toolRounds + 1, provider.requests.size)
    }

    @Test
    fun retryPreservesToolResultsAndImagesWithoutReplayingToolsOrFailedReasoning() {
        val requests = mutableListOf<String>()
        val events = mutableListOf<AgentEvent>()
        var executions = 0
        val provider = object : AgentProviderClient by ScriptedProvider(emptyList()) {
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                requests += request.messages.toString()
                onEvent(ProviderEvent.RequestStarted)
                return when (requests.size) {
                    1 -> ProviderResponse(assistant(
                        finishReason = "tool_calls",
                        reasoning = "先观察",
                        toolCalls = listOf(toolCall("observe-1", "get_current_context", "{}")),
                    ))
                    2 -> {
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "失败的思考"))
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 1, "半截回答"))
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, 2, "半截参数"))
                        throw java.net.SocketTimeoutException("timeout")
                    }
                    else -> ProviderResponse(assistant(content = "完成", finishReason = "stop", reasoning = "观察成功"))
                }
            }
        }
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("开始"))
        val loop = AgentLoop(
            config = modelConfig(), messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                executions++
                AgentModelClient.ToolResult(
                    content = "观察结果",
                    images = listOf(AgentModelClient.ModelImage("data:image/png;base64,dGVzdA==", "image/png", 4)),
                )
            },
            runController = AgentRunController(), traceFormatter = AgentTraceFormatter(),
            onEvent = events::add, modelRetry = AgentModelRetry { _, _ -> },
        )
        val result = loop.run()
        assertEquals(1, executions)
        assertEquals(3, requests.size)
        assertEquals(requests[1], requests[2])
        assertTrue(requests[2].contains("data:image/png"))
        assertFalse(messages.toString().contains("data:image/png"))
        assertFalse(messages.toString().contains("半截"))
        assertEquals("先观察观察成功", result.reasoningContent)
        assertEquals(listOf(1, 2, 3), events.filterIsInstance<AgentEvent.RoundStarted>().map { it.round })
        assertEquals(2, events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single().round)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolStarted>().size)
    }

    private class ScriptedProvider(
        private val responses: List<(ProviderRequest, AgentRunController) -> JSONObject>,
    ) : AgentProviderClient {
        constructor(vararg responses: JSONObject) : this(
            responses.map { response -> { _, _ -> response } }
        )

        override val id: String = "scripted"
        override val capabilities: ProviderCapabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )

        val requests = mutableListOf<JSONArray>()
        private var index = 0

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            requests += JSONArray(request.messages.toString())
            val response = responses.getOrNull(index)
                ?: error("缺少第 ${index + 1} 个 scripted response")
            index += 1
            return ProviderResponse(response(request, runController))
        }
    }

    private fun modelConfig(): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
            browserTools = false,
        )

    private fun assistant(
        content: String = "",
        finishReason: String,
        toolCalls: List<JSONObject> = emptyList(),
        reasoning: String = "",
    ): JSONObject =
        JSONObject()
            .put("role", "assistant")
            .put("content", content)
            .put("reasoning_content", reasoning)
            .put("finish_reason", finishReason)
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put("tool_calls", JSONArray(toolCalls))
                }
            }

    private fun toolCall(
        id: String,
        name: String,
        arguments: String,
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("arguments", arguments),
            )

    private fun JSONArray.roleSuffix(count: Int): List<String> =
        ((length() - count) until length()).map { index ->
            getJSONObject(index).getString("role")
        }

    private fun JSONArray.getJSONObjectFromEnd(offset: Int): JSONObject =
        getJSONObject(length() - offset)
}
