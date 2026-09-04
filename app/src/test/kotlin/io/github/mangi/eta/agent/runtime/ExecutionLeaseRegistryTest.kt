package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionLeaseRegistryTest {
    @Test
    fun taskAcquiredDuringServiceRestartIsNotDrainedByThePreviousOwner() {
        val registry = ExecutionLeaseRegistry()
        registry.attachOwner(1)
        registry.acquire("old") { error("已完成的任务不应取消") }
        assertFalse(registry.closeOwnerIfIdle(1))
        registry.release("old")
        assertTrue(registry.closeOwnerIfIdle(1))
        var stopped = false
        registry.acquire("next") { stopped = true }
        assertTrue(registry.drainOwner(1).isEmpty())
        assertEquals(1, registry.count())
        registry.attachOwner(2)
        registry.drainOwner(2).forEach { it() }
        assertTrue(stopped)
    }

    @Test
    fun foregroundStartFailurePreservesRootBoundRunsButAnExplicitStopStillCancelsThem() {
        val registry = ExecutionLeaseRegistry()
        val stopped = mutableListOf<String>()
        registry.acquire("root-run", allowBoundFallback = true) { stopped += "root" }
        registry.acquire("user-run") { stopped += "user" }
        registry.drain(startFailed = true).forEach { it() }
        assertEquals(listOf("user"), stopped)
        assertEquals(0, registry.count())
        registry.acquire("root-run", allowBoundFallback = true) { stopped += "root" }
        registry.drain().forEach { it() }
        assertEquals(listOf("user", "root"), stopped)
    }

    @Test
    fun stoppingOnlyConsumesLiveOwnedTasksOnce() {
        val registry = ExecutionLeaseRegistry()
        val stopped = mutableListOf<String>()
        assertTrue(registry.acquire("run:1") { stopped += "run" })
        assertTrue(registry.acquire("user:daemon") { stopped += "daemon" })
        assertTrue(registry.acquire("user:pty") { stopped += "pty" })
        assertFalse(registry.acquire("user:daemon") { error("复用任务不能替换持有者") })
        registry.release("run:1")
        assertEquals(2, registry.count())
        val callbacks = registry.drain()
        assertEquals(0, registry.count())
        assertTrue(registry.drain().isEmpty())
        callbacks.forEach { it() }
        assertEquals(listOf("daemon", "pty"), stopped)
        assertTrue(registry.acquire("run:2") { stopped += "next" })
        registry.release("user:daemon")
        assertEquals(1, registry.count())
    }
}
