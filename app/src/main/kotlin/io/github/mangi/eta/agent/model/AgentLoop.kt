package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Agent run 的纯编排循环。
 *
 * 一次 assistant 响应及其完整工具批次构成一个 turn；
 * steering 只在 turn 结束后注入，不能用取消网络或关闭工具资源来模拟。循环不设置本地轮次上限，
 * 由模型自然结束、取消或错误终止。
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val toolsForRound: (() -> JSONArray)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
) {
    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private var toolCallValidator = AgentToolCallValidator(tools)
    private val accumulatedReasoning = StringBuilder()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private var pendingToolImageMessage: JSONObject? = null

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> = sensitiveToolCallIds.toSet()

    fun run(): Result {
        var round = 1

        while (true) {
            runController.throwIfCancelled()
            appendPendingSteeringMessage()

            val roundTools = toolsForRound?.invoke() ?: tools
            toolCallValidator = AgentToolCallValidator(roundTools)
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            val completedRound = try {
                modelRetry.complete(
                    initialRound = round,
                    request = ProviderRequest(config, messages, roundTools),
                    provider = provider,
                    controller = runController,
                    onEvent = onEvent,
                    onProviderEvent = { attemptRound, providerEvent ->
                        if (providerEvent is ProviderEvent.BlockDelta &&
                            providerEvent.kind == AssistantBlockKind.THINKING
                        ) {
                            accumulatedReasoning.append(providerEvent.delta)
                        }
                        providerEvent.toAgentEvent(attemptRound)?.let(onEvent)
                    },
                    discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                )
            } finally {
                // 同一回合的重试仍需原始观察；整个回合结束后才移除截图。
                discardPendingToolImageMessage()
            }
            round = completedRound.round
            val providerResponse = completedRound.response

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            messages.put(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                )
            )
            onEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    reasoningContent = assistantReasoning,
                    toolNames = toolCalls.map { it.name },
                )
            )

            if (toolCalls.isNotEmpty()) {
                val outcomes = when (providerResponse.stopReason) {
                    AssistantStopReason.TOOL_USE ->
                        toolCalls.map { call -> executeTool(round, call) }
                    AssistantStopReason.OUTPUT_LIMIT ->
                        toolCalls.map { call ->
                            rejectedToolOutcome(
                                round = round,
                                toolCall = call,
                                code = "TRUNCATED_TOOL_CALL",
                                message = "模型输出达到长度上限，工具参数可能不完整；本次调用未执行，请重新提交完整参数。",
                            )
                        }
                    else ->
                        toolCalls.map { call ->
                            rejectedToolOutcome(
                                round = round,
                                toolCall = call,
                                code = "UNEXPECTED_TOOL_CALL",
                                message = "模型在 ${providerResponse.stopReason.name} 终止状态下返回了工具调用；" +
                                    "本批调用未执行，请重新规划。",
                            )
                        }
                }
                appendToolOutcomes(round, outcomes)
                round += 1
                continue
            }

            // assistant 已自然结束时再检查 steering。这样补充消息不会丢掉刚完成的回答。
            if (appendPendingSteeringOrSeal()) {
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                error("模型接口第 $round 轮返回为空${finishReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }

            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
                content = content,
                reasoningContent = reasoningSnapshot(),
                sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
            )
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringMessage() ?: return false
        messages.put(AgentConversationCodec.userTextMessage(steeringPrompt(supplement)))
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringOrSeal() ?: return false
        messages.put(AgentConversationCodec.userTextMessage(steeringPrompt(supplement)))
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        "用户补充指令：$supplement\n\n请基于当前任务上下文继续执行，不要从头重复已经完成或已经验证过的操作。"

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        toolCallValidator.validate(toolCall)?.let { validationError ->
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "INVALID_TOOL_ARGUMENTS",
                message = validationError,
            )
        }
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )

        val result = try {
            toolExecutor.execute(toolCall)
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
            sensitiveToolCallIds += toolCall.id
        }

        runController.throwIfCancelled()
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        onEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = traceFormatter.isSuccessResult(result),
            )
        )
    }

    private fun appendToolOutcomes(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // Provider 要求同一 assistant 批次的全部 tool result 连续出现；图片观察统一放在批次之后。
        outcomes.forEach { outcome ->
            messages.put(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result))
        }

        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) return

        // 工具截图是瞬时观察，不是会话资产。下一次推理消费后立即删除。
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "Latest observation image(s) returned by tool(s): $toolNames.",
            images = images,
        ).also(messages::put)

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            )
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            )
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

}
