package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPendingResultRecoveryTest {
    @Test
    fun recoveryDoesNotReplaceFailedAttemptOrRetryNotice() {
        val partial = AgentMessageUi(id = "assistant-retry-run-1-0", content = "半截回答")
        val notice = SystemNoticeMessageUi(
            id = "assistant-retry-run-retry-1",
            code = SystemNoticeCode.ModelRetry,
            detail = "正在重试",
        )
        val state = AgentChatUiState(
            messages = listOf(partial, notice), input = "", isStreaming = true, thinkingEnabled = false,
        )
        for (ok in listOf(true, false)) {
            val recovered = AgentPendingResultRecovery.apply(
                state = state,
                runId = "retry-run",
                result = AgentRuntimeWire.RunResult(
                    runId = "retry-run", ok = ok, content = if (ok) "最终回答" else "",
                    error = if (ok) null else "重试耗尽",
                ),
                supplements = emptyList(),
            )
            assertEquals(listOf(partial, notice), recovered.state.messages.take(2))
            assertEquals("assistant-retry-run-2-result", recovered.state.messages.last().id)
            assertEquals(3, recovered.state.messages.size)
        }
    }

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
        val message = recovered.state.messages.single() as SystemNoticeMessageUi
        assertEquals(SystemNoticeCode.RuntimeFailed, message.code)
        assertEquals("失败原因", message.detail)
    }

    @Test
    fun recoveryDoesNotMergeEarlierTextBlockIntoSameRoundFinalBlock() {
        val state = AgentChatUiState(
            messages = listOf(
                AgentMessageUi(
                    id = "assistant-run-blocks-1-1",
                    content = "我先搜索。",
                    isStreaming = false,
                    renderMarkdown = true,
                ),
                AgentMessageUi(
                    id = "assistant-run-blocks-1-3",
                    content = "这是最终答案。",
                    isStreaming = false,
                    renderMarkdown = true,
                ),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )

        val recovered = AgentPendingResultRecovery.apply(
            state = state,
            runId = "run-blocks",
            result = AgentRuntimeWire.RunResult(
                runId = "run-blocks",
                ok = true,
                content = "我先搜索。这是最终答案。",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "我先搜索。这是最终答案。",
                    ),
                ),
            ),
            supplements = emptyList(),
        )

        assertEquals(
            listOf("我先搜索。", "这是最终答案。"),
            recovered.state.messages.filterIsInstance<AgentMessageUi>().map { it.content },
        )
    }

    @Test
    fun recoveryUsesSemanticNoticeForSuccessfulRunWithoutText() {
        val recovered = AgentPendingResultRecovery.apply(
            state = AgentChatUiState(
                messages = emptyList(),
                input = "",
                isStreaming = true,
                thinkingEnabled = false,
            ),
            runId = "run-empty",
            result = AgentRuntimeWire.RunResult(
                runId = "run-empty",
                ok = true,
                content = "",
            ),
            supplements = emptyList(),
        )

        val message = recovered.state.messages.single() as SystemNoticeMessageUi
        assertEquals(SystemNoticeCode.EmptyResult, message.code)
        assertEquals(null, message.detail)
    }

    @Test
    fun completedOutboxResultKeepsToolTraceAndRemovesInterruptedNotice() {
        val tool = ToolActivityMessageUi(
            id = "run-tool-tool-1-call-1",
            toolName = "run_command",
            status = ToolActivityStatusUi.Success,
            argumentsSummary = "执行命令 · Android · root",
            command = "uptime",
            resultSummary = "完成",
        )
        val recovered = AgentPendingResultRecovery.apply(
            state = AgentChatUiState(
                messages = listOf(
                    tool,
                    SystemNoticeMessageUi(
                        id = "interrupted-run-tool",
                        code = SystemNoticeCode.Interrupted,
                    ),
                ),
                input = "",
                isStreaming = false,
                thinkingEnabled = false,
            ),
            runId = "run-tool",
            result = AgentRuntimeWire.RunResult(
                runId = "run-tool",
                ok = true,
                content = "最终结果",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(role = "assistant", content = "最终结果")
                ),
            ),
            supplements = emptyList(),
        )

        assertFalse(recovered.alreadyApplied)
        assertEquals(tool, recovered.state.messages.first())
        assertTrue(recovered.state.messages.none { it.id == "interrupted-run-tool" })
        assertEquals("最终结果", (recovered.state.messages.last() as AgentMessageUi).content)
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
