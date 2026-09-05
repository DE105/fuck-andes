package io.github.mangi.eta.agent.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeSessionTest {
    @Test
    fun replayBoundaryPrecedesConcurrentLiveEventsAndTerminalResult() {
        val deliveries = Collections.synchronizedList(mutableListOf<String>())
        val replayBoundaryReached = CountDownLatch(1)
        val releaseReplayBoundary = CountDownLatch(1)
        val producerStarted = CountDownLatch(1)
        val session = AgentRuntimeSession(runId = "run-boundary")
        session.emit(AgentEvent.RoundStarted(round = 1, messageCount = 1))
        val attached = AtomicBoolean(false)
        val attachThread = Thread {
            attached.set(
                session.attach(
                    eventSink = { event -> deliveries += "round-${(event as AgentEvent.RoundStarted).round}" },
                    resultSink = { deliveries += "result" },
                    onReplayComplete = {
                        replayBoundaryReached.countDown()
                        assertTrue(releaseReplayBoundary.await(2, TimeUnit.SECONDS))
                        deliveries += "replay-complete"
                    },
                )
            )
        }.apply { isDaemon = true }
        val producerThread = Thread {
            producerStarted.countDown()
            session.emit(AgentEvent.RoundStarted(round = 2, messageCount = 2))
            session.complete(
                AgentRuntimeWire.RunResult(runId = "run-boundary", ok = true, content = "完成")
            )
        }.apply { isDaemon = true }

        attachThread.start()
        try {
            assertTrue(replayBoundaryReached.await(1, TimeUnit.SECONDS))
            producerThread.start()
            assertTrue(producerStarted.await(1, TimeUnit.SECONDS))
            assertEquals(listOf("round-1"), deliveries.toList())
        } finally {
            releaseReplayBoundary.countDown()
            attachThread.join(2_000)
            producerThread.join(2_000)
        }

        assertTrue(attached.get())
        assertEquals(listOf("round-1", "replay-complete", "round-2", "result"), deliveries)
    }

    @Test
    fun emptyReplayStillAcknowledgesBeforeLaterEvents() {
        val deliveries = mutableListOf<String>()
        val session = AgentRuntimeSession(runId = "run-empty")

        assertTrue(
            session.attach(
                eventSink = { deliveries += "event" },
                resultSink = { deliveries += "result" },
                onReplayComplete = { deliveries += "replay-complete" },
            )
        )
        session.emit(AgentEvent.RoundStarted(round = 1, messageCount = 1))

        assertEquals(listOf("replay-complete", "event"), deliveries)
    }

    @Test
    fun terminalSessionRejectsAttachWithoutAcknowledgingReplay() {
        val deliveries = mutableListOf<String>()
        val session = AgentRuntimeSession(runId = "run-terminal")
        session.complete(
            AgentRuntimeWire.RunResult(runId = "run-terminal", ok = true, content = "完成")
        )

        assertFalse(
            session.attach(
                eventSink = { deliveries += "event" },
                resultSink = { deliveries += "result" },
                onReplayComplete = { deliveries += "replay-complete" },
            )
        )
        assertTrue(deliveries.isEmpty())
    }

    @Test
    fun replacementSubscriberReceivesSafeReplayThenLiveEventsAndResult() {
        val firstEvents = mutableListOf<AgentEvent>()
        val firstResults = mutableListOf<AgentRuntimeWire.RunResult>()
        val replacementEvents = mutableListOf<AgentEvent>()
        val replacementResults = mutableListOf<AgentRuntimeWire.RunResult>()
        val session = AgentRuntimeSession(
            runId = "run-attach",
            eventSink = firstEvents::add,
            resultSink = firstResults::add,
        )
        val visibleDelta = AgentEvent.AssistantBlockDelta(
            round = 1,
            kind = AgentEvent.AssistantBlockKind.TEXT,
            index = 0,
            deltaChars = 2,
            delta = "你好",
        )
        val privateToolDelta = AgentEvent.AssistantBlockDelta(
            round = 1,
            kind = AgentEvent.AssistantBlockKind.TOOL_CALL,
            index = 1,
            deltaChars = 16,
            delta = "{\"token\":\"secret\"}",
        )
        session.emit(visibleDelta)
        session.emit(privateToolDelta)

        assertTrue(session.attach(replacementEvents::add, replacementResults::add))
        assertEquals(listOf(visibleDelta), replacementEvents)

        val liveEvent = AgentEvent.ToolFinished(
            round = 1,
            toolCallId = "call-1",
            name = "run_command",
            resultSummary = "完成",
            imageCount = 0,
            imageBytes = 0,
            success = true,
        )
        session.emit(liveEvent)
        val result = AgentRuntimeWire.RunResult(
            runId = "run-attach",
            ok = true,
            content = "完成",
        )
        session.complete(result)

        assertEquals(listOf(visibleDelta, privateToolDelta, liveEvent), firstEvents)
        assertEquals(listOf(visibleDelta, liveEvent), replacementEvents)
        assertEquals(listOf(result), firstResults)
        assertEquals(listOf(result), replacementResults)
        assertFalse(session.attach({}, {}))
    }

    @Test
    fun concurrentCompletionAndCancellationPublishExactlyOneTerminalResult() {
        repeat(100) { iteration ->
            val results = Collections.synchronizedList(mutableListOf<AgentRuntimeWire.RunResult>())
            val session = AgentRuntimeSession(
                runId = "run-$iteration",
                resultSink = results::add,
            )
            val start = CountDownLatch(1)
            val finished = CountDownLatch(2)
            val completed = AtomicBoolean(false)
            val cancelled = AtomicBoolean(false)
            val completeThread = Thread {
                start.await()
                completed.set(
                    session.complete(
                        AgentRuntimeWire.RunResult(
                            runId = "run-$iteration",
                            ok = true,
                            content = "done",
                        )
                    )
                )
                finished.countDown()
            }.apply { isDaemon = true }
            val cancelThread = Thread {
                start.await()
                cancelled.set(session.cancel("stopped"))
                finished.countDown()
            }.apply { isDaemon = true }

            completeThread.start()
            cancelThread.start()
            start.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            completeThread.join(2_000)
            cancelThread.join(2_000)

            assertTrue(completed.get() xor cancelled.get())
            assertEquals(1, results.size)
            assertTrue(session.isTerminal)
            if (!results.single().ok) assertTrue(session.controller.isCancelled)
        }
    }

    @Test
    fun terminalResultIsDeliveredExactlyOnceAndStopsLaterEvents() {
        val events = mutableListOf<AgentEvent>()
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        val session = AgentRuntimeSession(
            runId = "run-1",
            eventSink = events::add,
            resultSink = results::add,
        )

        assertTrue(session.emit(AgentEvent.RoundStarted(round = 1, messageCount = 1)))
        assertTrue(
            session.complete(
                AgentRuntimeWire.RunResult(
                    runId = "run-1",
                    ok = true,
                    content = "done",
                )
            )
        )
        assertFalse(session.cancel("late cancel"))
        assertFalse(session.steer("late steer"))
        assertFalse(session.emit(AgentEvent.RoundStarted(round = 2, messageCount = 2)))

        assertEquals(1, events.size)
        assertEquals(1, results.size)
        assertEquals("done", results.single().content)
    }

    @Test
    fun cancellationCancelsControllerBeforePublishingTerminalResult() {
        val observedCancelled = mutableListOf<Boolean>()
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession(
            runId = "run-1",
            resultSink = { result ->
                observedCancelled += session.controller.isCancelled
                results += result
            },
        )

        assertTrue(session.cancel("stopped"))
        assertTrue(session.controller.isCancelled)
        assertEquals("stopped", results.single().error)
        assertTrue(observedCancelled.single())
    }

    @Test
    fun completionClaimOwnsPersistenceAndRejectsConcurrentCancellation() {
        val persistenceStarted = CountDownLatch(1)
        val allowPersistence = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<AgentRuntimeWire.RunResult>())
        val session = AgentRuntimeSession(runId = "run-1", resultSink = results::add)
        val completionReturned = AtomicBoolean(false)
        val worker = Thread {
            completionReturned.set(
                session.complete(
                    result = AgentRuntimeWire.RunResult(
                        runId = "run-1",
                        ok = true,
                        content = "done",
                    ),
                    beforePublish = {
                        persistenceStarted.countDown()
                        allowPersistence.await(2, TimeUnit.SECONDS)
                    },
                )
            )
        }.apply { isDaemon = true }

        worker.start()
        try {
            assertTrue(persistenceStarted.await(1, TimeUnit.SECONDS))
            assertFalse(session.cancel("too late"))
        } finally {
            allowPersistence.countDown()
            worker.join(2_000)
        }

        assertTrue(completionReturned.get())
        assertEquals(1, results.size)
        assertTrue(results.single().ok)
    }

    @Test
    fun commitCallbackFailureCannotLeaveSessionWithoutTerminalResult() {
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        val session = AgentRuntimeSession(runId = "run-1", resultSink = results::add)

        assertThrows(IllegalStateException::class.java) {
            session.complete(
                result = AgentRuntimeWire.RunResult(
                    runId = "run-1",
                    ok = false,
                    content = "",
                    error = "persistence failed",
                ),
                beforePublish = { error("disk full") },
            )
        }

        assertTrue(session.isTerminal)
        assertEquals(1, results.size)
        assertFalse(session.cancel("late"))
    }
}
