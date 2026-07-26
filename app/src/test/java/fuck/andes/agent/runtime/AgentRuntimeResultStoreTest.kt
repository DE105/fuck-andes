package fuck.andes.agent.runtime

import fuck.andes.data.db.FuckAndesDatabase
import android.content.Context
import fuck.andes.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRuntimeResultStoreTest {
    @Test
    fun emptyLegacyTranscriptFallsBackToSuccessfulAssistantContent() {
        val runId = "legacy-${System.nanoTime()}"
        AgentRuntimeResultStore.add(
            context,
            AgentRuntimeWire.CompletedRun(
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = runId,
                    source = "agent_ui",
                    payload = "conversation-1",
                ),
                result = AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = true,
                    content = "旧版本结果",
                ),
                createdAt = System.currentTimeMillis(),
            ),
        )

        val restored = AgentRuntimeResultStore.list(context).single { it.result.runId == runId }
        assertEquals(listOf("assistant"), restored.result.transcript.map { it.role })
        assertEquals("旧版本结果", restored.result.transcript.single().content)
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FuckAndesDatabase.closeForTests()
        context.deleteDatabase("fuck_andes.db")
    }

    @Test
    fun acknowledgementBeforeCompletionPreventsLateResultWriteBack() {
        val runId = "ack-before-add-${System.nanoTime()}"
        val completedRun = AgentRuntimeWire.CompletedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = "breeno",
                payload = "{}",
            ),
            result = AgentRuntimeWire.RunResult(
                runId = runId,
                ok = true,
                content = "obsolete result",
            ),
            createdAt = System.currentTimeMillis(),
        )

        AgentRuntimeResultStore.remove(context, runId)

        assertFalse(AgentRuntimeResultStore.add(context, completedRun))
        assertTrue(AgentRuntimeResultStore.list(context).none { it.result.runId == runId })
    }

    @Test
    fun saveAndLoadPreservesTranscript() {
        val runId = "transcript-${System.nanoTime()}"
        val transcript = listOf(
            AgentModelClient.ConversationMessage(
                role = "assistant",
                toolCallsJson = "[{\"id\":\"call-1\"}]",
            ),
            AgentModelClient.ConversationMessage(
                role = "tool",
                toolCallId = "call-1",
                content = "{\"ok\":true}",
            ),
            AgentModelClient.ConversationMessage(role = "assistant", content = "完成"),
        )
        val completedRun = AgentRuntimeWire.CompletedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = "breeno",
                payload = "{}",
            ),
            result = AgentRuntimeWire.RunResult(
                runId = runId,
                ok = true,
                content = "完成",
                transcript = transcript,
                metrics = AgentRunMetrics(
                    inputTokens = 240,
                    cachedInputTokens = 180,
                    outputTokens = 36,
                    elapsedMs = 5_400,
                ),
            ),
            createdAt = System.currentTimeMillis(),
        )

        assertTrue(AgentRuntimeResultStore.add(context, completedRun))

        val restored = AgentRuntimeResultStore.list(context).single { it.result.runId == runId }
        assertEquals(transcript.map { it.role }, restored.result.transcript.map { it.role })
        assertEquals("call-1", restored.result.transcript[1].toolCallId)
        assertEquals("完成", restored.result.transcript.last().content)
        assertEquals(completedRun.result.metrics, restored.result.metrics)
    }
}
