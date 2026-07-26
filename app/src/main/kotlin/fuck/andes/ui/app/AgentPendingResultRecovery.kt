package fuck.andes.ui.app

import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.runtime.AgentRuntimeWire
import fuck.andes.agent.runtime.AgentUiHandoffPayload
import fuck.andes.ui.model.AgentChatHomeUiState
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentMessageUi
import fuck.andes.ui.model.UserMessageUi
import fuck.andes.ui.model.toRunMetricsUi

/** 将 Runtime outbox 的结果幂等折叠回 App 会话。 */
internal object AgentPendingResultRecovery {
    data class Outcome(
        val state: AgentChatHomeUiState,
        val alreadyApplied: Boolean,
    )

    fun apply(
        state: AgentChatHomeUiState,
        runId: String,
        result: AgentRuntimeWire.RunResult,
        promptSupplement: AgentUiHandoffPayload.Supplement? = null,
        supplements: List<AgentUiHandoffPayload.Supplement>,
    ): Outcome {
        val content = if (result.ok) {
            result.content.ifBlank { "已完成。" }
        } else {
            result.error ?: "Agent Runtime 调用失败"
        }
        val history = AgentRuntimeHistoryReducer.apply(
            state = state,
            runId = runId,
            additions = listOfNotNull(
                promptSupplement?.let { supplement ->
                    AgentModelClient.buildUserHistoryMessage(
                        text = supplement.text,
                        images = emptyList(),
                    )
                }
            ) + result.transcript,
        )
        if (history.alreadyApplied) return Outcome(state, alreadyApplied = true)

        val messagesWithResult = state.messages.toMutableList().also { messages ->
            val assistantIndex = messages.indexOfLast { it.isAssistantForRun(runId) }
            val completedMessage = AgentMessageUi(
                id = "assistant-$runId-1",
                content = content,
                isStreaming = false,
                renderMarkdown = result.ok,
                runMetrics = result.metrics?.toRunMetricsUi(),
            )
            if (assistantIndex >= 0) {
                messages[assistantIndex] = (messages[assistantIndex] as AgentMessageUi).copy(
                    content = content,
                    isStreaming = false,
                    renderMarkdown = result.ok,
                    runMetrics = result.metrics?.toRunMetricsUi(),
                )
            } else {
                messages += completedMessage
            }
        }
        return Outcome(
            state = state.copy(
                messages = mergeSupplements(
                    runId = runId,
                    supplements = listOfNotNull(promptSupplement) + supplements,
                    messages = messagesWithResult,
                    beforeLatestAssistant = true,
                ),
                history = history.state.history,
                appliedRuntimeRunIds = history.state.appliedRuntimeRunIds,
                isStreaming = false,
            ),
            alreadyApplied = false,
        )
    }

    fun mergeSupplements(
        runId: String,
        supplements: List<AgentUiHandoffPayload.Supplement>,
        messages: List<AgentChatMessageUi>,
        beforeLatestAssistant: Boolean = false,
    ): List<AgentChatMessageUi> {
        var updated = messages
        supplements.sortedBy { it.index }.forEach { supplement ->
            val id = supplementMessageId(runId, supplement.index)
            if (updated.any { it.id == id }) return@forEach
            val userMessage = UserMessageUi(id = id, content = supplement.text)
            val assistantIndex = updated.indexOfLast {
                it is AgentMessageUi &&
                    it.isAssistantForRun(runId) &&
                    (beforeLatestAssistant || it.isStreaming)
            }
            updated = if (assistantIndex >= 0) {
                updated.toMutableList().also { it.add(assistantIndex, userMessage) }
            } else {
                updated + userMessage
            }
        }
        return updated
    }

    private fun AgentChatMessageUi.isAssistantForRun(runId: String): Boolean =
        this is AgentMessageUi &&
            (id == "assistant-$runId" || id.startsWith(assistantMessagePrefix(runId)))

    private fun assistantMessagePrefix(runId: String): String = "assistant-$runId-"

    private fun supplementMessageId(runId: String, index: Int): String =
        "user-$runId-supplement-$index"

}
