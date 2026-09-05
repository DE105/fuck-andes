package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeAttachDeliveryTest {
    @Test
    fun historyIsPublishedOnceBeforeLiveEventsAndResult() {
        val deliveries = mutableListOf<String>()
        val replayBatches = mutableListOf<List<AgentEvent>>()
        val delivery = AgentRuntimeAttachDelivery(
            onReplay = { events ->
                replayBatches += events
                deliveries += "replay"
            },
            onEvent = { deliveries += "event-${(it as AgentEvent.RoundStarted).round}" },
            onAttachResponse = { deliveries += "attached-$it" },
            onResult = { deliveries += "result" },
        )
        val history = listOf(round(1), round(2))
        history.forEach(delivery::event)
        assertTrue(deliveries.isEmpty())

        delivery.attachResponse(true)
        delivery.event(round(3))
        delivery.result(result())
        delivery.attachResponse(true)
        delivery.event(round(4))
        delivery.result(result())

        assertEquals(listOf(history), replayBatches)
        assertEquals(listOf("replay", "attached-true", "event-3", "result"), deliveries)
    }

    @Test
    fun successfulAttachPublishesAnEmptyHistorySnapshot() {
        val replayBatches = mutableListOf<List<AgentEvent>>()
        val responses = mutableListOf<Boolean>()
        val delivery = AgentRuntimeAttachDelivery(
            onReplay = replayBatches::add,
            onEvent = { error("没有实时事件") },
            onAttachResponse = responses::add,
            onResult = { error("没有终态") },
        )

        delivery.attachResponse(true)
        delivery.attachResponse(true)

        assertEquals(listOf(emptyList<AgentEvent>()), replayBatches)
        assertEquals(listOf(true), responses)
    }

    @Test
    fun rejectedAttachDiscardsHistoryAndClosesDelivery() {
        val responses = mutableListOf<Boolean>()
        val delivery = AgentRuntimeAttachDelivery(
            onReplay = { error("拒绝订阅不能发布历史") },
            onEvent = { error("拒绝订阅不能发布实时事件") },
            onAttachResponse = responses::add,
            onResult = { error("拒绝订阅不能发布终态") },
        )
        delivery.event(round(1))

        delivery.attachResponse(false)
        delivery.attachResponse(false)
        delivery.attachResponse(true)
        delivery.event(round(2))
        delivery.result(result())

        assertEquals(listOf(false), responses)
    }

    @Test
    fun legacyResultBeforeAttachResponseFlushesHistoryBeforeCompletion() {
        val deliveries = mutableListOf<String>()
        val replayBatches = mutableListOf<List<AgentEvent>>()
        val delivery = AgentRuntimeAttachDelivery(
            onReplay = {
                replayBatches += it
                deliveries += "replay"
            },
            onEvent = { error("所有事件均在终态之前回放") },
            onAttachResponse = { error("终态后的确认不能再次开放订阅") },
            onResult = { deliveries += "result" },
        )
        delivery.event(round(1))

        delivery.result(result())
        delivery.attachResponse(true)
        delivery.result(result())

        assertEquals(listOf(listOf(round(1))), replayBatches)
        assertEquals(listOf("replay", "result"), deliveries)
    }

    @Test
    fun legacyEventOnlyCallbackReceivesEveryEventInOrder() {
        val deliveries = mutableListOf<String>()
        val delivery = AgentRuntimeAttachDelivery(
            onEvent = { deliveries += "event-${(it as AgentEvent.RoundStarted).round}" },
            onAttachResponse = { deliveries += "attached-$it" },
            onResult = { deliveries += "result" },
        )
        delivery.event(round(1))
        delivery.event(round(2))
        delivery.attachResponse(true)
        delivery.event(round(3))
        delivery.result(result())

        assertEquals(
            listOf("event-1", "event-2", "attached-true", "event-3", "result"),
            deliveries,
        )
    }

    @Test
    fun legacyEventOnlyCallbackAlsoHandlesResultBeforeAttachResponse() {
        val deliveries = mutableListOf<String>()
        val delivery = AgentRuntimeAttachDelivery(
            onEvent = { deliveries += "event" },
            onAttachResponse = { error("结果到达前没有确认") },
            onResult = { deliveries += "result" },
        )
        delivery.event(round(1))

        delivery.result(result())
        delivery.attachResponse(true)

        assertEquals(listOf("event", "result"), deliveries)
    }

    private fun round(index: Int) = AgentEvent.RoundStarted(round = index, messageCount = index)

    private fun result() = AgentRuntimeWire.RunResult(
        runId = "run-attach",
        ok = true,
        content = "完成",
    )
}
