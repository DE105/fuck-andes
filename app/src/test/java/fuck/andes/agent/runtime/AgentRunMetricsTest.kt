package fuck.andes.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRunMetricsTest {
    @Test
    fun sumsCompleteOpenAiCompatibleUsageAcrossRounds() {
        val accumulator = AgentRunMetricsAccumulator()
        accumulator.accept(AgentEvent.RoundStarted(round = 1, messageCount = 2))
        accumulator.accept(
            AgentEvent.UsageReceived(
                round = 1,
                usage = AgentTokenUsage(inputTokens = 100, outputTokens = 20, cachedTokens = 60),
            )
        )
        accumulator.accept(AgentEvent.RoundStarted(round = 2, messageCount = 4))
        accumulator.accept(
            AgentEvent.UsageReceived(
                round = 2,
                usage = AgentTokenUsage(inputTokens = 150, outputTokens = 30, cachedTokens = 90),
            )
        )

        assertEquals(
            AgentRunMetrics(
                inputTokens = 250,
                cachedInputTokens = 150,
                outputTokens = 50,
                elapsedMs = 3_000,
            ),
            accumulator.snapshot(elapsedMs = 3_000),
        )
    }

    @Test
    fun mergesSnapshotsWithinRoundAndSumsAcrossRounds() {
        val accumulator = AgentRunMetricsAccumulator()
        accumulator.accept(AgentEvent.RoundStarted(round = 1, messageCount = 2))
        accumulator.accept(
            AgentEvent.UsageReceived(
                round = 1,
                usage = AgentTokenUsage(inputTokens = 100, cachedTokens = 60),
            )
        )
        accumulator.accept(
            AgentEvent.UsageReceived(
                round = 1,
                usage = AgentTokenUsage(inputTokens = 100, outputTokens = 20),
            )
        )
        accumulator.accept(AgentEvent.RoundStarted(round = 2, messageCount = 4))
        accumulator.accept(
            AgentEvent.UsageReceived(
                round = 2,
                usage = AgentTokenUsage(inputTokens = 150, outputTokens = 30, cachedTokens = 90),
            )
        )

        assertEquals(
            AgentRunMetrics(
                inputTokens = 250,
                cachedInputTokens = 150,
                outputTokens = 50,
                elapsedMs = 4_200,
            ),
            accumulator.snapshot(elapsedMs = 4_200),
        )
    }

    @Test
    fun incompleteFieldInAnyRoundRemainsUnknown() {
        val accumulator = AgentRunMetricsAccumulator()
        accumulator.accept(AgentEvent.RoundStarted(round = 1, messageCount = 2))
        accumulator.accept(
            AgentEvent.UsageReceived(
                round = 1,
                usage = AgentTokenUsage(inputTokens = 100, outputTokens = 20, cachedTokens = 60),
            )
        )
        accumulator.accept(AgentEvent.RoundStarted(round = 2, messageCount = 4))
        accumulator.accept(
            AgentEvent.UsageReceived(
                round = 2,
                usage = AgentTokenUsage(inputTokens = 150, outputTokens = 30),
            )
        )

        assertEquals(
            AgentRunMetrics(
                inputTokens = 250,
                cachedInputTokens = null,
                outputTokens = 50,
                elapsedMs = null,
            ),
            accumulator.snapshot(elapsedMs = null),
        )
    }
}
