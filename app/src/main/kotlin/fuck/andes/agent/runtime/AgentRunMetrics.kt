package fuck.andes.agent.runtime

internal data class AgentRunMetrics(
    val inputTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val outputTokens: Int? = null,
    val elapsedMs: Long? = null,
) {
    val isEmpty: Boolean
        get() = inputTokens == null &&
            cachedInputTokens == null &&
            outputTokens == null &&
            elapsedMs == null
}

internal class AgentRunMetricsAccumulator {
    private val startedRounds = linkedSetOf<Int>()
    private val usageByRound = mutableMapOf<Int, AgentTokenUsage>()

    fun accept(event: AgentEvent) {
        when (event) {
            is AgentEvent.RoundStarted -> startedRounds += event.round
            is AgentEvent.UsageReceived -> {
                startedRounds += event.round
                usageByRound[event.round] = usageByRound[event.round].mergeSnapshot(event.usage)
            }
            else -> Unit
        }
    }

    fun snapshot(elapsedMs: Long?): AgentRunMetrics? {
        val rounds = startedRounds.map(usageByRound::get)
        val metrics = AgentRunMetrics(
            inputTokens = rounds.sumComplete { it.inputTokens },
            cachedInputTokens = rounds.sumComplete { it.cachedTokens },
            outputTokens = rounds.sumComplete { it.outputTokens },
            elapsedMs = elapsedMs,
        )
        return metrics.takeUnless { it.isEmpty }
    }
}

private fun AgentTokenUsage?.mergeSnapshot(other: AgentTokenUsage): AgentTokenUsage =
    AgentTokenUsage(
        contextTokens = maxKnown(this?.contextTokens, other.contextTokens),
        inputTokens = maxKnown(this?.inputTokens, other.inputTokens),
        outputTokens = maxKnown(this?.outputTokens, other.outputTokens),
        reasoningTokens = maxKnown(this?.reasoningTokens, other.reasoningTokens),
        cachedTokens = maxKnown(this?.cachedTokens, other.cachedTokens),
    )

private fun maxKnown(first: Int?, second: Int?): Int? = when {
    first == null -> second
    second == null -> first
    else -> maxOf(first, second)
}

private inline fun List<AgentTokenUsage?>.sumComplete(
    selector: (AgentTokenUsage) -> Int?,
): Int? {
    if (isEmpty()) return null
    var total = 0L
    for (usage in this) {
        val value = usage?.let(selector) ?: return null
        total += value
    }
    return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
