package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunMessageProjectorTest {
    @Test
    fun retryKeepsFailedAttemptSeparateAndReplayClearsItsNotice() {
        val projector = AgentRunMessageProjector { 1_000L }
        val partial = projector.appendTextDelta("retry-run", 2, 0, "半截", emptyList())
        val event = AgentEvent.ModelRetryScheduled(2, 1, 3, 2_000, "MODEL_TIMEOUT")
        val retrying = projector.scheduleModelRetry("retry-run", event, partial)
        assertEquals(-1, AgentRunMessageProjector.resultTargetIndex("retry-run", retrying))
        assertEquals("assistant-retry-run-3-result", AgentRunMessageProjector.resultFallbackId("retry-run", retrying))
        val repeated = projector.scheduleModelRetry("retry-run", event, retrying)
        assertEquals(retrying, repeated)
        val resumed = projector.appendTextDelta("retry-run", 3, 0, "完整回答", retrying)
        assertEquals(listOf("半截", "完整回答"), resumed.filterIsInstance<AgentMessageUi>().map { it.content })
        assertFalse(resumed.filterIsInstance<AgentMessageUi>().first().isStreaming)
        assertEquals(SystemNoticeCode.ModelRetry, resumed.filterIsInstance<SystemNoticeMessageUi>().single().code)
        assertTrue(projector.resetForReplay("retry-run", resumed).isEmpty())
    }

    @Test
    fun runCompletionFinalizesUnclosedBlocksAndUnknownToolWithoutChangingOtherRuns() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-final"
        val knownTools = listOf(
            ToolActivityMessageUi(
                id = "$runId-tool-1-call-success",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
                resultSummary = "完成",
            ),
            ToolActivityMessageUi(
                id = "$runId-tool-1-call-failed",
                toolName = "run_command",
                status = ToolActivityStatusUi.Failed,
                argumentsSummary = "{}",
                resultSummary = "执行失败",
            ),
        )
        val otherRunMessages = listOf(
            AgentMessageUi(id = "assistant-run-other-1-0", content = "仍在输出", isStreaming = true),
            ThinkingMessageUi(id = "run-other-thinking-1-0", content = "仍在思考", isStreaming = true),
            ToolActivityMessageUi(
                id = "run-other-tool-1-call-1",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Running,
                argumentsSummary = "{}",
            ),
        )
        val unfinishedTool = ToolActivityMessageUi(
            id = "$runId-tool-1-call-unknown",
            toolName = "run_command",
            status = ToolActivityStatusUi.Running,
            argumentsSummary = "{}",
        )
        val messages = otherRunMessages + knownTools + listOf(
            AgentMessageUi(id = "assistant-$runId", content = "", isStreaming = true),
            AgentMessageUi(id = "assistant-$runId-1-0", content = "回答\n\n", isStreaming = true),
            ThinkingMessageUi(id = "$runId-thinking-1-0", content = "思考", isStreaming = true),
            unfinishedTool,
        )

        val finalized = projector.finalizeRun(runId, messages)

        assertEquals(otherRunMessages + knownTools, finalized.take(otherRunMessages.size + knownTools.size))
        val assistantMessages = finalized.filterIsInstance<AgentMessageUi>()
            .filter { it.id.startsWith("assistant-$runId") }
        assertTrue(assistantMessages.all { !it.isStreaming && it.renderMarkdown })
        assertEquals("回答", assistantMessages.last().content)
        val thinking = finalized.filterIsInstance<ThinkingMessageUi>().single { it.id.startsWith(runId) }
        assertFalse(thinking.isStreaming)
        assertTrue(thinking.collapsed)
        assertEquals(unfinishedTool.copy(status = ToolActivityStatusUi.Unknown), finalized.last())
    }

    @Test
    fun replayResetRemovesOnlyRebuildableTraceAndExplicitlyReplayedSupplements() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-replay"
        val user = UserMessageUi(
            id = "user-$runId",
            content = "分析截图",
            images = listOf("image-preview"),
        )
        val legacySupplement = UserMessageUi(id = "user-$runId-supplement-1", content = "原始补充")
        val replayedSupplement = UserMessageUi(id = "user-$runId-supplement-2", content = "继续检查")
        val otherRunMessages = listOf(
            UserMessageUi(id = "user-other-run", content = "之前的问题"),
            AgentMessageUi(id = "assistant-other-run-1-0", content = "之前的回答"),
            ThinkingMessageUi(id = "other-run-thinking-1-0", content = "之前的思考", isStreaming = false),
            ToolActivityMessageUi(
                id = "other-run-tool-1-call-1",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
            ),
            UserMessageUi(id = "user-other-run-supplement-2", content = "之前的补充"),
        )
        val messages = otherRunMessages + listOf(
            user,
            AgentMessageUi(id = "assistant-$runId", content = "", isStreaming = true),
            AgentMessageUi(id = "assistant-$runId-1-0", content = "半截回答", isStreaming = true),
            ThinkingMessageUi(id = "$runId-thinking-1-fallback", content = "半截思考", isStreaming = true),
            ToolActivityMessageUi(
                id = "$runId-tool-1-call-1",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Unknown,
                argumentsSummary = "{}",
            ),
            legacySupplement,
            replayedSupplement,
            SystemNoticeMessageUi(id = "assistant-$runId-2", code = SystemNoticeCode.RuntimeFailed),
            SystemNoticeMessageUi(id = "interrupted-$runId", code = SystemNoticeCode.Interrupted),
        )

        val reset = projector.resetForReplay(runId, messages, replaySupplementIndexes = setOf(2))

        assertEquals(otherRunMessages + listOf(user, legacySupplement), reset)
        assertEquals(reset, projector.resetForReplay(runId, reset, replaySupplementIndexes = setOf(2)))
    }

    @Test
    fun repeatedReplayRebuildsTextReasoningAndToolsWithoutAccumulatingContent() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-repeat"
        val user = UserMessageUi(id = "user-$runId", content = "看屏幕")
        val toolStart = AgentEvent.ToolStarted(
            round = 1,
            toolCallId = "call-1",
            name = "observe_screen",
            argsPreview = "{}",
        )
        val toolEnd = AgentEvent.ToolFinished(
            round = 1,
            toolCallId = "call-1",
            name = "observe_screen",
            resultSummary = "完成",
            imageCount = 0,
            imageBytes = 0,
            success = true,
        )
        var messages: List<AgentChatMessageUi> = listOf(user)

        repeat(3) {
            messages = projector.resetForReplay(runId, messages)
            messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "先检查", messages)
            messages = projector.finalizeThinking(runId, messages)
            messages = projector.startTool(runId, toolStart, messages)
            messages = projector.finishTool(runId, toolEnd, messages)
            messages = projector.appendTextDelta(runId, round = 2, index = 0, delta = "检查", messages)
            messages = projector.appendTextDelta(runId, round = 2, index = 0, delta = "完成", messages)
            messages = projector.finalizeText(runId, messages)

            assertEquals(4, messages.size)
            assertEquals(user, messages.first())
            assertEquals("先检查", messages.filterIsInstance<ThinkingMessageUi>().single().content)
            assertEquals("检查完成", messages.filterIsInstance<AgentMessageUi>().single().content)
            assertEquals(ToolActivityStatusUi.Success, messages.filterIsInstance<ToolActivityMessageUi>().single().status)
        }
    }

    @Test
    fun replayResetClearsOnlyThatRunsThinkingClockAndKeepsUnreplayedUserInputs() {
        var now = 1_000L
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { now })
        val supplement = UserMessageUi(id = "user-run-reset-supplement-1", content = "保留这条补充")
        var messages: List<AgentChatMessageUi> = listOf(supplement)
        messages = projector.appendReasoningDelta("run-reset", round = 1, index = 0, delta = "先前思考", messages)
        messages = projector.appendReasoningDelta("run-other", round = 1, index = 0, delta = "其他思考", messages)
        now = 9_000L

        messages = projector.resetForReplay("run-reset", messages)
        messages = projector.appendReasoningDelta("run-reset", round = 1, index = 0, delta = "恢复思考", messages)
        now = 11_000L
        messages = projector.finalizeThinking("run-reset", messages)
        messages = projector.finalizeThinking("run-other", messages)

        assertTrue(messages.contains(supplement))
        assertEquals(2, messages.filterIsInstance<ThinkingMessageUi>().single { it.id.startsWith("run-reset-") }.elapsedSeconds)
        assertEquals(10, messages.filterIsInstance<ThinkingMessageUi>().single { it.id.startsWith("run-other-") }.elapsedSeconds)
    }

    @Test
    fun projectsReasoningAndToolsByRoundAndToolCallId() {
        var now = 1_000L
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { now })
        val runId = "run-1"
        var messages: List<AgentChatMessageUi> = listOf(UserMessageUi(id = "user-$runId", content = "看屏幕"))

        messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "先观察", messages)
        now = 4_000L
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_observe_1",
                name = "observe_screen",
                argsPreview = "{}",
            ),
            projector.finalizeThinkingRound(runId, round = 1, messages)
        )
        messages = projector.finishTool(
            runId,
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call_observe_1",
                name = "observe_screen",
                resultSummary = "ok=true, chars=10",
                imageCount = 1,
                imageBytes = 200,
            ),
            messages
        )

        now = 5_000L
        messages = projector.appendReasoningDelta(runId, round = 2, index = 0, delta = "再确认", messages)
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "call_observe_2",
                name = "run_command",
                argsPreview = "执行命令 · Android · root",
                command = "pm list packages | head",
            ),
            projector.finalizeThinkingRound(runId, round = 2, messages)
        )

        assertEquals(
            listOf(
                "user-$runId",
                "$runId-thinking-1-0",
                "$runId-tool-1-call_observe_1",
                "$runId-thinking-2-0",
                "$runId-tool-2-call_observe_2",
            ),
            messages.map { it.id }
        )

        val firstThinking = messages[1] as ThinkingMessageUi
        assertFalse(firstThinking.isStreaming)
        assertEquals(3, firstThinking.elapsedSeconds)

        val firstTool = messages[2] as ToolActivityMessageUi
        assertEquals(ToolActivityStatusUi.Success, firstTool.status)
        assertEquals(1, firstTool.imageCount)

        val secondTool = messages[4] as ToolActivityMessageUi
        assertEquals(ToolActivityStatusUi.Running, secondTool.status)
        assertEquals("执行命令 · Android · root", secondTool.argumentsSummary)
        assertEquals("pm list packages | head", secondTool.command)
    }

    @Test
    fun keepsAssistantTextSeparatedByRound() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-text"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "分析一下"),
            AgentMessageUi(id = "assistant-$runId-1", content = "第一轮", isStreaming = false),
        )

        messages = projector.appendReasoningDelta(runId, round = 2, index = 0, delta = "继续推理", messages)
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "call_2",
                name = "observe_screen",
                argsPreview = "{}",
            ),
            projector.finalizeThinkingRound(runId, round = 2, messages)
        )
        messages = projector.appendTextDelta(runId, round = 2, index = 1, delta = "第二轮", messages)
        messages = projector.appendTextDelta(runId, round = 2, index = 1, delta = "回答", messages)

        assertEquals(
            listOf(
                "user-$runId",
                "assistant-$runId-1",
                "$runId-thinking-2-0",
                "$runId-tool-2-call_2",
                "assistant-$runId-2-1",
            ),
            messages.map { it.id }
        )
        val roundTwoAssistant = messages.last() as AgentMessageUi
        assertEquals("第二轮回答", roundTwoAssistant.content)
        assertTrue(roundTwoAssistant.isStreaming)
    }

    @Test
    fun keepsFallbackToolCallIdsDistinctAcrossRounds() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-fallback"
        var messages: List<AgentChatMessageUi> = listOf(UserMessageUi(id = "user-$runId", content = "操作手机"))

        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "tool_call_0",
                name = "search_apps",
                argsPreview = """{"query":"相机"}""",
            ),
            messages
        )
        messages = projector.finishTool(
            runId,
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "tool_call_0",
                name = "search_apps",
                resultSummary = "ok=true",
                imageCount = 0,
                imageBytes = 0,
            ),
            messages
        )
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "tool_call_0",
                name = "observe_screen",
                argsPreview = """{"include_screenshot":true}""",
            ),
            messages
        )

        val tools = messages.filterIsInstance<ToolActivityMessageUi>()
        assertEquals(2, tools.size)
        assertEquals("search_apps", tools[0].toolName)
        assertEquals(ToolActivityStatusUi.Success, tools[0].status)
        assertEquals("observe_screen", tools[1].toolName)
        assertEquals(ToolActivityStatusUi.Running, tools[1].status)
    }

    @Test
    fun toolActivityFollowsAssistantTextStreamedInSameRound() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-order"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "搜一下")
        )

        messages = projector.appendTextDelta(runId, round = 1, index = 0, delta = "先查找应用", messages)
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_1",
                name = "search_apps",
                argsPreview = "{}",
            ),
            messages
        )

        assertEquals(
            listOf(
                "user-$runId",
                "assistant-$runId-1-0",
                "$runId-tool-1-call_1",
            ),
            messages.map { it.id }
        )
    }

    @Test
    fun finalizingTextTrimsTrailingWhitespace() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-trim"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "你好")
        )

        messages = projector.appendTextDelta(runId, round = 1, index = 0, delta = "回答。\n\n", messages)
        messages = projector.finalizeTextRound(runId, round = 1, messages)

        val assistant = messages.last() as AgentMessageUi
        assertEquals("回答。", assistant.content)
        assertFalse(assistant.isStreaming)
    }

    @Test
    fun interruptedToolBecomesUnknownInsteadOfFailed() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-interrupted"
        val running = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call-1",
                name = "run_command",
                argsPreview = "执行命令",
            ),
            listOf(UserMessageUi(id = "user-$runId", content = "重启设备")),
        )

        val interrupted = projector.interruptRunningTools("任务中断", running)
        val tool = interrupted.filterIsInstance<ToolActivityMessageUi>().single()

        assertEquals(ToolActivityStatusUi.Unknown, tool.status)
        assertEquals("任务中断", tool.resultSummary)
    }

    @Test
    fun keepsInterleavedReasoningTextAndHostedToolInEventOrder() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-interleaved"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "搜索最新消息"),
        )

        messages = projector.appendReasoningDelta(
            runId,
            round = 1,
            index = 0,
            delta = "先判断需要搜索",
            messages,
        )
        messages = projector.appendTextDelta(
            runId,
            round = 1,
            index = 1,
            delta = "我先查一下。",
            messages,
        )
        messages = projector.startHostedTool(
            runId,
            AgentEvent.HostedToolStarted(round = 1, toolCallId = "ws_1", name = "网页搜索"),
            projector.finalizeTextRound(runId, round = 1, messages),
        )
        messages = projector.finishHostedTool(
            runId,
            AgentEvent.HostedToolFinished(
                round = 1,
                toolCallId = "ws_1",
                name = "网页搜索",
                success = true,
            ),
            messages,
        )
        messages = projector.appendReasoningDelta(
            runId,
            round = 1,
            index = 2,
            delta = "整理搜索结果",
            messages,
        )
        messages = projector.appendTextDelta(
            runId,
            round = 1,
            index = 3,
            delta = "这是最终答案。",
            messages,
        )

        assertEquals(
            listOf(
                "user-$runId",
                "$runId-thinking-1-0",
                "assistant-$runId-1-1",
                "$runId-tool-1-ws_1",
                "$runId-thinking-1-2",
                "assistant-$runId-1-3",
            ),
            messages.map { it.id },
        )
        assertFalse((messages[1] as ThinkingMessageUi).isStreaming)
        assertFalse((messages[2] as AgentMessageUi).isStreaming)
        assertEquals(ToolActivityStatusUi.Success, (messages[3] as ToolActivityMessageUi).status)
        assertFalse((messages[4] as ThinkingMessageUi).isStreaming)
        assertTrue((messages[5] as AgentMessageUi).isStreaming)
    }
}
