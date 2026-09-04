package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DetachedTaskSupervisorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val supervisors = mutableListOf<DetachedTaskSupervisor>()

    private fun newSupervisor(): DetachedTaskSupervisor {
        val supervisor = DetachedTaskSupervisor(
            logger = NoopLogger,
            recordsFile = File(temporaryFolder.root, "terminal-daemons.json"),
            daemonDir = File(temporaryFolder.root, "daemon").absolutePath,
        )
        supervisors += supervisor
        return supervisor
    }

    @After
    fun tearDown() {
        // 兜底清理测试残留的 detached 进程（正常路径 stop 已经杀掉）。
        supervisors.flatMap { it.list() }.forEach { status ->
            ProcessHandle.of(status.task.pid).ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun deniedForegroundLeaseNeverStartsProcess() {
        val marker = File(temporaryFolder.root, "must-not-exist")
        val supervisor = DetachedTaskSupervisor(NoopLogger, File(temporaryFolder.root, "records"),
            daemonDir = File(temporaryFolder.root, "daemon").path,
            acquireUserLease = { _, _ -> false })
        val result = supervisor.start("touch ${shellQuote(marker.path)}", temporaryFolder.root.path, "user", TerminalEnvironment.ANDROID)
        assertEquals("BACKGROUND_START_NOT_ALLOWED", (result as DaemonStartResult.Failed).code)
        assertFalse(marker.exists())
        assertTrue(supervisor.list().isEmpty())
    }

    @Test
    fun stopDuringLeaseAcquisitionNeverStartsProcess() {
        val marker = File(temporaryFolder.root, "must-not-exist")
        var released = false
        val supervisor = DetachedTaskSupervisor(NoopLogger, File(temporaryFolder.root, "records"),
            daemonDir = File(temporaryFolder.root, "daemon").path,
            acquireUserLease = { _, stop -> stop(); true },
            releaseUserLease = { released = true })
        val result = supervisor.start("touch ${shellQuote(marker.path)}", temporaryFolder.root.path, "user", TerminalEnvironment.ANDROID)
        assertEquals("TASK_CANCELLED", (result as DaemonStartResult.Failed).code)
        assertFalse(marker.exists())
        assertTrue(released)
    }

    @Test
    fun rootStartWithoutGrantIsRejectedBeforeLaunchingShell() {
        val supervisor = DetachedTaskSupervisor(NoopLogger, File(temporaryFolder.root, "records"), rootAvailable = { false })
        val result = supervisor.start("id", "/", "root", TerminalEnvironment.ANDROID)
        assertEquals("ROOT_REQUIRED", (result as DaemonStartResult.Failed).code)
    }

    @Test
    fun startedTaskKeepsRunningAfterLauncherExits() {
        val supervisor = newSupervisor()
        val result = supervisor.start(
            command = "sleep 30",
            cwd = temporaryFolder.root.absolutePath,
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
        )
        assertTrue("$result", result is DaemonStartResult.Started)
        val task = (result as DaemonStartResult.Started).task

        // start 返回后宿主进程仍被托管；任务不依赖前台命令会话。
        assertTrue(ProcessHandle.of(task.pid).map { it.isAlive }.orElse(false))

        val statuses = supervisor.list()
        assertEquals(1, statuses.size)
        assertTrue(statuses.single().running)

        assertTrue(supervisor.stop(task.id))
        assertTrue(supervisor.list().isEmpty())
    }

    @Test
    fun logsRemainReadableAfterTaskExits() {
        val supervisor = newSupervisor()
        val result = supervisor.start(
            command = "for i in 1 2 3; do echo line\$i; done",
            cwd = temporaryFolder.root.absolutePath,
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
        )
        assertTrue("$result", result is DaemonStartResult.Started)
        val task = (result as DaemonStartResult.Started).task

        assertTrue("task should exit shortly", awaitExited(supervisor, task.id))

        val logs = supervisor.readLogs(task.id)
        assertTrue(logs.ok)
        assertTrue(logs.text.contains("line1"))
        assertTrue(logs.text.contains("line3"))

        // 已退出任务保留记录供查看日志，stop 负责清理。
        assertTrue(supervisor.stop(task.id))
        assertTrue(supervisor.list().isEmpty())
        assertFalse(supervisor.readLogs(task.id).ok)
    }

    @Test
    fun runningTaskIsAdoptedByNewSupervisorInstance() {
        val first = newSupervisor()
        val started = first.start(
            command = "sleep 30",
            cwd = temporaryFolder.root.absolutePath,
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
        )
        assertTrue("$started", started is DaemonStartResult.Started)
        val task = (started as DaemonStartResult.Started).task

        // 模拟 App 重启：新实例加载同一份记录文件，仍应认领存活进程并能停止它。
        val second = newSupervisor()
        val statuses = second.list()
        assertEquals(1, statuses.size)
        assertEquals(task.id, statuses.single().task.id)
        assertTrue(statuses.single().running)
        assertTrue(second.stop(task.id))
    }

    @Test
    fun rejectsStartBeyondCapacity() {
        val supervisor = newSupervisor()
        val started = mutableListOf<DetachedTask>()
        try {
            repeat(DetachedTaskSupervisor.MAX_TASKS) {
                val result = supervisor.start(
                    command = "sleep 30",
                    cwd = temporaryFolder.root.absolutePath,
                    identity = "user",
                    environment = TerminalEnvironment.ANDROID,
                )
                assertTrue("$result", result is DaemonStartResult.Started)
                started += (result as DaemonStartResult.Started).task
            }
            val overflow = supervisor.start(
                command = "sleep 30",
                cwd = temporaryFolder.root.absolutePath,
                identity = "user",
                environment = TerminalEnvironment.ANDROID,
            )
            assertTrue("$overflow", overflow is DaemonStartResult.Failed)
            assertEquals("MAX_TASKS_REACHED", (overflow as DaemonStartResult.Failed).code)
        } finally {
            started.forEach { supervisor.stop(it.id) }
        }
    }

    @Test
    fun stopCleansRecordOfDeadTask() {
        val supervisor = newSupervisor()
        writeRecord(pid = 4_000_000_000L, token = "any-token")
        assertEquals(1, supervisor.list().size)
        assertFalse(supervisor.list().single().running)
        assertTrue(supervisor.stop("dm_stale01"))
        assertTrue(supervisor.list().isEmpty())
    }

    @Test
    fun stopWithMismatchedTokenDoesNotSignalProcess() {
        // PID 复用防护依赖 /proc/<pid>/environ 的 token 校验，无 /proc 的宿主上没有可校验通道。
        assumeTrue("/proc is required for token verification", File("/proc").isDirectory)
        val supervisor = newSupervisor()
        val self = ProcessHandle.current()
        writeRecord(pid = self.pid(), token = "stale-token")

        assertTrue(supervisor.stop("dm_stale01"))
        assertTrue("own process must survive the mismatched-token stop", self.isAlive)
        assertTrue(supervisor.list().isEmpty())
    }

    @Test
    fun linuxTaskPathsTranslateToHostDaemonDir() {
        val supervisor = newSupervisor()
        val linuxTask = DetachedTask(
            id = "dm_linux01",
            pid = 1234,
            token = "token",
            command = "kimi web",
            cwd = "/workspace",
            identity = "root",
            environment = TerminalEnvironment.DEBIAN,
            logPath = "/workspace/daemon/dm_linux01.log",
            startedAt = 0L,
        )
        val androidTask = linuxTask.copy(
            environment = TerminalEnvironment.ANDROID,
            logPath = "/data/local/tmp/eta/daemon/dm_android01.log",
        )

        assertEquals(
            "/data/local/tmp/eta/daemon/dm_linux01.log",
            supervisor.hostDaemonPath(linuxTask, linuxTask.logPath),
        )
        assertEquals(
            "/data/local/tmp/eta/daemon/dm_linux01.pid",
            supervisor.hostDaemonPath(linuxTask, linuxTask.logPath.removeSuffix(".log") + ".pid"),
        )
        // Android 任务的路径原样保留。
        assertEquals(
            androidTask.logPath,
            supervisor.hostDaemonPath(androidTask, androidTask.logPath),
        )
    }

    private fun writeRecord(pid: Long, token: String) {
        val records = JSONArray().put(
            JSONObject()
                .put("id", "dm_stale01")
                .put("pid", pid)
                .put("token", token)
                .put("command", "fake")
                .put("cwd", temporaryFolder.root.absolutePath)
                .put("identity", "user")
                .put("environment", TerminalEnvironment.ANDROID.wireName)
                .put("log_path", File(temporaryFolder.root, "daemon/dm_stale01.log").absolutePath)
                .put("started_at", System.currentTimeMillis())
        )
        File(temporaryFolder.root, "terminal-daemons.json").writeText(records.toString())
    }

    private fun awaitExited(
        supervisor: DetachedTaskSupervisor,
        id: String,
        timeoutMs: Long = 5_000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = supervisor.list().firstOrNull { it.task.id == id } ?: return true
            if (!status.running) return true
            Thread.sleep(150)
        }
        return false
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
