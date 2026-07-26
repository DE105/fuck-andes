package fuck.andes.agent.runtime

import fuck.andes.core.AgentLogger
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRunTimingTest {
    @Test
    fun measuresFromFirstAssistantOutputUntilRunFinishes() {
        var now = 100L
        val timing = AgentRunTiming(NoOpLogger) { now }

        now = 500L
        timing.accept(
            AgentEvent.AssistantBlockStart(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
            )
        )
        now = 1_500L
        timing.accept(
            AgentEvent.AssistantBlockStart(
                round = 2,
                kind = AgentEvent.AssistantBlockKind.TEXT,
                index = 0,
            )
        )
        now = 4_700L
        timing.accept(AgentEvent.RunFinished(round = 2, contentChars = 20))
        now = 8_000L

        assertEquals(4_200L, timing.assistantOutputElapsedMs())
    }

    @Test
    fun returnsNullBeforeAssistantProducesOutput() {
        var now = 100L
        val timing = AgentRunTiming(NoOpLogger) { now }
        now = 600L
        timing.accept(AgentEvent.RunFinished(round = 1, contentChars = 0))

        assertEquals(null, timing.assistantOutputElapsedMs())
    }

    @Test
    fun freezesElapsedTimeWhenRunFails() {
        var now = 100L
        val timing = AgentRunTiming(NoOpLogger) { now }

        now = 500L
        timing.accept(
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.TEXT,
                index = 0,
                deltaChars = 1,
                delta = "x",
            )
        )
        now = 2_000L
        timing.accept(AgentEvent.RunFailed("failed"))
        now = 8_000L

        assertEquals(1_500L, timing.assistantOutputElapsedMs())
    }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
