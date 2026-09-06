package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.core.toSafeLogToken

internal sealed interface AgentEvent {
    fun toLogLine(): String

    enum class AssistantBlockKind {
        TEXT,
        THINKING,
        TOOL_CALL,
    }

    data class RunStarted(
        val initialImages: Int,
        val initialImageBytes: Int,
        val toolCount: Int,
        val terminalTools: Boolean
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_started images=$initialImages, image_bytes=$initialImageBytes, tools=$toolCount, terminal=$terminalTools"
    }

    data class RoundStarted(
        val round: Int,
        val messageCount: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "round_started round=$round, messages=$messageCount"
    }

    data class ModelRetryScheduled(
        val round: Int,
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Int,
        val reasonCode: String,
    ) : AgentEvent {
        val displayMessage: String
            get() = "模型请求暂时中断，${delayMs / 1000} 秒后重试（$attempt/$maxAttempts）；此前工具结果已保留。"

        override fun toLogLine(): String =
            "model_retry_scheduled round=$round, attempt=$attempt, delay_ms=$delayMs, code=${reasonCode.toSafeLogToken()}"
    }

    data class ProviderRequestStarted(
        val round: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_request_started round=$round"
    }

    data class ProviderResponseStarted(
        val round: Int,
        val httpCode: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_response_started round=$round, http_code=$httpCode"
    }

    data class AssistantBlockStart(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_start round=$round, kind=$kind, index=$index, " +
                "name=${name.toSafeLogToken()}"
    }

    data class AssistantBlockDelta(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val deltaChars: Int,
        val delta: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_delta round=$round, kind=$kind, index=$index, chars=$deltaChars"
    }

    data class AssistantBlockEnd(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
        val contentChars: Int,
        val replacementContent: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_end round=$round, kind=$kind, index=$index, " +
                "name=${name.toSafeLogToken()}, chars=$contentChars"
    }

    data class AssistantReceived(
        val round: Int,
        val contentChars: Int,
        val reasoningContent: String,
        val toolNames: List<String>
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_received round=$round, content_chars=$contentChars, " +
                "reasoning_chars=${reasoningContent.length}, tool_count=${toolNames.size}, " +
                "tools=${toolNames.take(MAX_LOGGED_TOOL_NAMES).map { it.toSafeLogToken() }}"
    }

    data class UsageReceived(
        val round: Int,
        val usage: AgentTokenUsage
    ) : AgentEvent {
        override fun toLogLine(): String =
            "usage_received round=$round, ctx=${usage.contextTokens}, in=${usage.inputTokens}, out=${usage.outputTokens}, reasoning=${usage.reasoningTokens}, cache=${usage.cachedTokens}"
    }

    data class UserSupplementReceived(
        val index: Int,
        val text: String
    ) : AgentEvent {
        override fun toLogLine(): String =
            "user_supplement_received index=$index, chars=${text.length}"
    }

    data class ToolStarted(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val argsPreview: String,
        val command: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_started round=$round, name=${name.toSafeLogToken()}, " +
                "args_chars=${argsPreview.length}, command_chars=${command?.length ?: 0}"
    }

    data class ToolFinished(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val resultSummary: String,
        val imageCount: Int,
        val imageBytes: Int,
        /** 可选：旧版本 Runtime 不发送，消费端缺省时回退到摘要文本判断。 */
        val success: Boolean? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_finished round=$round, name=${name.toSafeLogToken()}, " +
                "${resultSummary.toSafeResultLogFields()}, images=$imageCount, image_bytes=$imageBytes"
    }

    data class HostedToolStarted(
        val round: Int,
        val toolCallId: String,
        val name: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "hosted_tool_started round=$round, name=${name.toSafeLogToken()}"
    }

    data class HostedToolFinished(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val success: Boolean,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "hosted_tool_finished round=$round, name=${name.toSafeLogToken()}, success=$success"
    }

    data class ToolImagesAttached(
        val round: Int,
        val toolName: String,
        val imageCount: Int,
        val imageBytes: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_images_attached round=$round, name=${toolName.toSafeLogToken()}, " +
                "images=$imageCount, image_bytes=$imageBytes"
    }

    data class RunFinished(
        val round: Int,
        val contentChars: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_finished round=$round, content_chars=$contentChars"
    }

    data class RunFailed(
        val reason: String
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_failed reason_chars=${reason.length}"
    }
}

private const val MAX_LOGGED_TOOL_NAMES = 8
private const val RESULT_CODE_MARKER = "code="

/** 摘要字段分隔符：旧格式用逗号，人文化摘要用间隔号。 */
private val RESULT_FIELD_SEPARATORS = listOf(", ", " · ")

private fun String.toSafeResultLogFields(): String = buildString {
    append("summary_chars=").append(this@toSafeResultLogFields.length)
    extractResultCode()?.let { resultCode ->
        append(", code=").append(resultCode.toSafeLogToken())
    }
}

private fun String.extractResultCode(): String? {
    val fieldStart = when {
        startsWith(RESULT_CODE_MARKER) -> 0
        else -> RESULT_FIELD_SEPARATORS
            .mapNotNull { separator ->
                indexOf(separator + RESULT_CODE_MARKER)
                    .takeIf { it >= 0 }
                    ?.plus(separator.length)
            }
            .minOrNull() ?: return null
    }
    val valueStart = fieldStart + RESULT_CODE_MARKER.length
    val valueEnd = RESULT_FIELD_SEPARATORS
        .mapNotNull { separator ->
            indexOf(separator, startIndex = valueStart).takeIf { it >= 0 }
        }
        .minOrNull() ?: length
    return substring(valueStart, valueEnd)
}
