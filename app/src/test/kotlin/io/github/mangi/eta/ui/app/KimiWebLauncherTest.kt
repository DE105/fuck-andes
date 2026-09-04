package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.terminal.DaemonLogsResult
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.DetachedTaskStatus
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiWebLauncherTest {
    @Test fun reusesMatchingDaemonWithoutStartingAnother() = runBlocking {
        val tasks = FakeTasks(existing = true)
        val result = session(tasks).launch(ENV, "user", BACKEND)
        assertTrue(result is KimiWebLaunchResult.Opened)
        assertEquals(0, tasks.starts)
        assertTrue(tasks.stops.isEmpty())
    }

    @Test fun browserFailureDoesNotStopReusedDaemon() = runBlocking {
        val tasks = FakeTasks(existing = true)
        val result = session(tasks, open = false).launch(ENV, "user", BACKEND)
        assertEquals(KimiWebLaunchResult.Failed("BROWSER_UNAVAILABLE"), result)
        assertTrue(tasks.stops.isEmpty())
    }

    @Test fun exitedNewDaemonFailsImmediatelyAndIsCleanedUp() = runBlocking {
        val tasks = FakeTasks(existing = false, running = false)
        val result = session(tasks).launch(ENV, "user", BACKEND)
        assertEquals(KimiWebLaunchResult.Failed("KIMI_EXITED"), result)
        assertEquals(listOf("new"), tasks.stops)
    }

    @Test fun timeoutCleansOnlyNewDaemon() = runBlocking {
        val tasks = FakeTasks(existing = false, output = "still loading")
        val result = session(tasks).launch(ENV, "user", BACKEND)
        assertEquals(KimiWebLaunchResult.Failed("URL_TIMEOUT"), result)
        assertEquals(listOf("new"), tasks.stops)
    }

    @Test fun cancellationCleansNewDaemonAndPreservesReusedDaemon() = runBlocking {
        for (existing in listOf(false, true)) {
            val tasks = FakeTasks(existing = existing, output = "loading")
            val read = CompletableDeferred<Unit>()
            tasks.onLogs = { read.complete(Unit) }
            val job = launch { KimiWebSession(tasks, { true }, waitIntervalMs = 60_000).launch(ENV, "user", BACKEND) }
            read.await()
            job.cancelAndJoin()
            assertEquals(if (existing) emptyList<String>() else listOf("new"), tasks.stops)
        }
    }

    private fun session(tasks: FakeTasks, open: Boolean = true) = KimiWebSession(tasks, { open }, waitAttempts = 1, waitIntervalMs = 0)

    private class FakeTasks(existing: Boolean, private val running: Boolean = true, private val output: String = "http://127.0.0.1:5494/#token=abc_123") : KimiWebSession.Tasks {
        private var task: DetachedTask? = if (existing) task("old") else null
        var starts = 0
        val stops = mutableListOf<String>()
        var onLogs: () -> Unit = {}
        override fun list() = task?.let { listOf(DetachedTaskStatus(it, running)) }.orEmpty()
        override fun start(environment: TerminalEnvironment, identity: String): DaemonStartResult {
            starts++
            return DaemonStartResult.Started(task("new").also { task = it })
        }
        override fun logs(id: String): DaemonLogsResult { onLogs(); return DaemonLogsResult(ok = true, text = output) }
        override fun stop(id: String) { stops += id }
        companion object {
            private fun task(id: String) = DetachedTask(id, 10, "owner", KimiWebSession.COMMAND, "/workspace", "user", ENV, "/log", 0, BACKEND)
        }
    }

    companion object {
        private val ENV = TerminalEnvironment.DEBIAN
        private val BACKEND = LinuxExecutionBackend.PROOT
    }
}
