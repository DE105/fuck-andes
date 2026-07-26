package fuck.andes.agent.runtime

import fuck.andes.data.db.FuckAndesDatabase
import android.content.Context
import fuck.andes.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRunArchiveStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FuckAndesDatabase.closeForTests()
        context.deleteDatabase("fuck_andes.db")
    }

    @Test
    fun saveAndLoadPreservesHandoffEventsAndResult() {
        val createdAt = System.currentTimeMillis()
        val archivedRun = AgentRunArchiveStore.ArchivedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = "run-1",
                source = "external_test",
                payload = AgentExternalArchivePayload(
                    userText = "查一下系统状态",
                    conversationKey = "session-1",
                    title = "外部入口",
                ).toJson(),
            ),
            events = listOf(
                AgentEvent.AssistantBlockDelta(
                    round = 1,
                    kind = AgentEvent.AssistantBlockKind.THINKING,
                    index = 0,
                    deltaChars = 2,
                    delta = "先",
                ),
                AgentEvent.AssistantBlockDelta(
                    round = 1,
                    kind = AgentEvent.AssistantBlockKind.THINKING,
                    index = 0,
                    deltaChars = 2,
                    delta = "看",
                ),
                AgentEvent.ToolStarted(
                    round = 1,
                    toolCallId = "call-1",
                    name = "run_command",
                    argsPreview = """{"cmd":"uptime"}""",
                ),
                AgentEvent.ToolFinished(
                    round = 1,
                    toolCallId = "call-1",
                    name = "run_command",
                    resultSummary = "ok=true, chars=42",
                    imageCount = 0,
                    imageBytes = 0,
                ),
                AgentEvent.RunFinished(
                    round = 1,
                    contentChars = 12,
                ),
            ),
            result = AgentRuntimeWire.RunResult(
                runId = "run-1",
                ok = true,
                content = "系统状态正常",
                reasoningContent = "先看系统状态",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "系统状态正常",
                    )
                ),
                metrics = AgentRunMetrics(
                    inputTokens = 400,
                    cachedInputTokens = 250,
                    outputTokens = 80,
                    elapsedMs = 8_750,
                ),
            ),
            createdAt = createdAt,
        )

        AgentRunArchiveStore.add(context, archivedRun)

        val restored = AgentRunArchiveStore.list(context).single()

        assertEquals(archivedRun.handoff, restored.handoff)
        assertEquals(archivedRun.result, restored.result)
        assertEquals(archivedRun.createdAt, restored.createdAt)
        assertEquals(
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                deltaChars = 4,
                delta = "先看",
            ),
            restored.events.first()
        )
        assertEquals(4, restored.events.size)
    }

    @Test
    fun removeDeletesByRunIdOrHandoffId() {
        val createdAt = System.currentTimeMillis()
        AgentRunArchiveStore.add(
            context,
            AgentRunArchiveStore.ArchivedRun(
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = "handoff-1",
                    source = "external_test",
                    payload = AgentExternalArchivePayload(
                        userText = "查一下系统状态",
                        conversationKey = "session-1",
                        title = "外部入口",
                    ).toJson(),
                ),
                events = emptyList(),
                result = AgentRuntimeWire.RunResult(
                    runId = "run-1",
                    ok = true,
                    content = "done",
                ),
                createdAt = createdAt,
            )
        )

        AgentRunArchiveStore.remove(context, "run-1")

        assertEquals(emptyList<AgentRunArchiveStore.ArchivedRun>(), AgentRunArchiveStore.list(context))
    }
}
