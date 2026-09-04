package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionStopQueueTest {
    @Test
    fun destroyingServiceBeforeDispatchStillRunsEveryAcceptedStopCallback() {
        val executor = Executors.newSingleThreadExecutor()
        val unblock = CountDownLatch(1)
        executor.execute { unblock.await() }
        val stopped = mutableListOf<String>()
        val failures = mutableListOf<Exception>()
        val queue = ExecutionStopQueue(executor, failures::add)
        try {
            queue.submit(listOf(
                { stopped += "first" },
                { throw IllegalStateException("单个回收失败") },
                { stopped += "second" },
            ))
            queue.close(listOf({ stopped += "destroy" }))
        } finally {
            unblock.countDown()
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf("first", "second", "destroy"), stopped)
        assertEquals(1, failures.size)
    }
}
