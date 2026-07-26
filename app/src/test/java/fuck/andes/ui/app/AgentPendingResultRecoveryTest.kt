package fuck.andes.ui.app

import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.runtime.AgentRunMetrics
import fuck.andes.agent.runtime.AgentRuntimeWire
import fuck.andes.agent.runtime.AgentUiHandoffPayload
import fuck.andes.ui.model.AgentChatUiState
import fuck.andes.ui.model.AgentMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPendingResultRecoveryTest {
    @Test
    fun recoveryFinalizesLatestRoundAndAppendsTranscriptExactlyOnce() {
        val transcript = listOf(
            AgentModelClient.ConversationMessage(role = "assistant", content = "最终结果")
        )
        val state = AgentChatUiState(
            messages = listOf(
                AgentMessageUi(
                    id = "assistant-run-1-1",
                    content = "中间结果",
                    isStreaming = false,
                    renderMarkdown = false,
                ),
                AgentMessageUi(
                    id = "assistant-run-1-2",
                    content = "部分",
                    isStreaming = true,
                    renderMarkdown = false,
                ),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        val supplement = AgentUiHandoffPayload.Supplement(
            index = 1,
            text = "补充条件",
            createdAt = 1L,
        )
        val result = AgentRuntimeWire.RunResult(
            runId = "run-1",
            ok = true,
            content = "最终结果",
            transcript = transcript,
            metrics = AgentRunMetrics(
                inputTokens = 240,
                cachedInputTokens = 180,
                outputTokens = 36,
                elapsedMs = 5_400,
            ),
        )

        val recovered = AgentPendingResultRecovery.apply(
            state = state,
            runId = "run-1",
            result = result,
            supplements = listOf(supplement),
        )

        assertFalse(recovered.alreadyApplied)
        assertEquals(
            listOf(
                "assistant-run-1-1",
                "user-run-1-supplement-1",
                "assistant-run-1-2",
            ),
            recovered.state.messages.map { it.id },
        )
        val latest = recovered.state.messages.last() as AgentMessageUi
        assertEquals("最终结果", latest.content)
        assertFalse(latest.isStreaming)
        assertTrue(latest.renderMarkdown)
        assertEquals(240, latest.runMetrics?.inputTokens)
        assertEquals(180, latest.runMetrics?.cachedInputTokens)
        assertEquals(36, latest.runMetrics?.outputTokens)
        assertEquals(5_400L, latest.runMetrics?.elapsedMs)
        assertEquals(transcript, recovered.state.history)
        assertFalse(recovered.state.isStreaming)

        val replay = AgentPendingResultRecovery.apply(
            state = recovered.state,
            runId = "run-1",
            result = result,
            supplements = listOf(supplement),
        )
        assertTrue(replay.alreadyApplied)
        assertEquals(recovered.state, replay.state)
    }

    @Test
    fun recoveryCreatesAssistantWhenStreamingPlaceholderWasNeverPersisted() {
        val state = AgentChatUiState(
            messages = emptyList(),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )

        val recovered = AgentPendingResultRecovery.apply(
            state = state,
            runId = "run-2",
            result = AgentRuntimeWire.RunResult(
                runId = "run-2",
                ok = false,
                content = "",
                error = "失败原因",
            ),
            supplements = emptyList(),
        )

        assertEquals("assistant-run-2-1", recovered.state.messages.single().id)
        val message = recovered.state.messages.single() as AgentMessageUi
        assertEquals("失败原因", message.content)
        assertFalse(message.renderMarkdown)
    }

    @Test
    fun appliedMarkerPreventsOldOutboxReplayAfterLaterTurns() {
        val state = AgentChatUiState(
            messages = listOf(
                AgentMessageUi(id = "assistant-run-1-1", content = "第一轮"),
                AgentMessageUi(id = "assistant-run-2-1", content = "第二轮"),
            ),
            history = listOf(
                AgentModelClient.ConversationMessage(role = "assistant", content = "第一轮"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第二轮"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
            appliedRuntimeRunIds = listOf("run-1", "run-2"),
        )

        val replay = AgentPendingResultRecovery.apply(
            state = state,
            runId = "run-1",
            result = AgentRuntimeWire.RunResult(
                runId = "run-1",
                ok = true,
                content = "第一轮",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(role = "assistant", content = "第一轮")
                ),
            ),
            supplements = emptyList(),
        )

        assertTrue(replay.alreadyApplied)
        assertEquals(state, replay.state)
    }

    @Test
    fun continuationPromptIsAddedToUiAndDurableHistoryOnce() {
        val prompt = AgentUiHandoffPayload.Supplement(
            index = 1,
            text = "继续检查",
            createdAt = 10L,
        )
        val recovered = AgentPendingResultRecovery.apply(
            state = AgentChatUiState(
                messages = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = false,
            ),
            runId = "run-next",
            result = AgentRuntimeWire.RunResult(
                runId = "run-next",
                ok = true,
                content = "检查完成",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(role = "assistant", content = "检查完成")
                ),
            ),
            promptSupplement = prompt,
            supplements = emptyList(),
        )

        assertEquals(
            listOf("user-run-next-supplement-1", "assistant-run-next-1"),
            recovered.state.messages.map { it.id },
        )
        assertEquals(listOf("user", "assistant"), recovered.state.history.map { it.role })
        assertEquals("继续检查", recovered.state.history.first().content)
    }
}
