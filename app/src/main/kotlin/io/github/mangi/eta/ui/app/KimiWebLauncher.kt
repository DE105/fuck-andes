package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class KimiWebRuntimeStatus(
    val taskId: String? = null,
    val running: Boolean = false,
    val url: String? = null,
    val code: String? = null,
)

internal sealed interface KimiWebLaunchResult {
    data class Opened(val url: String) : KimiWebLaunchResult
    data class Failed(val code: String) : KimiWebLaunchResult
}

/** 取得启动期前台引用，解析带 token 的本机地址后交给系统浏览器。 */
internal class KimiWebLauncher(
    private val context: Context,
    private val daemonSupervisor: DetachedTaskSupervisor,
) {
    private val session = KimiWebSession(
        tasks = object : KimiWebSession.Tasks {
            override fun list() = daemonSupervisor.list()
            override fun start(environment: TerminalEnvironment, identity: String) = daemonSupervisor.start(
                command = KimiWebSession.COMMAND, cwd = "/workspace", identity = identity, environment = environment,
            )
            override fun logs(id: String) = daemonSupervisor.readLogs(id)
            override fun stop(id: String) { daemonSupervisor.stop(id) }
        },
        openUrl = { url ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: android.content.ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        },
    )

    suspend fun launch(environment: TerminalEnvironment): KimiWebLaunchResult = launchMutex.withLock {
        withContext(Dispatchers.IO) {
            val distribution = environment.linuxDistribution ?: return@withContext KimiWebLaunchResult.Failed("INVALID_ENVIRONMENT")
            val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
            if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) return@withContext KimiWebLaunchResult.Failed("LINUX_ENVIRONMENT_NOT_READY")
            val identity = TerminalRuntime.defaultIdentity(environment, rootfs.absolutePath)
            val job = currentCoroutineContext().job
            if (identity == "user" && !AgentExecutionService.acquire(context, LAUNCH_ID) { job.cancel() }) {
                return@withContext KimiWebLaunchResult.Failed("BACKGROUND_START_NOT_ALLOWED")
            }
            try {
                currentCoroutineContext().ensureActive()
                session.launch(environment, identity, LinuxEnvironmentPaths.backendOf(rootfs.absolutePath))
            } finally {
                if (identity == "user") AgentExecutionService.release(LAUNCH_ID)
            }
        }
    }

    suspend fun status(environment: TerminalEnvironment): KimiWebRuntimeStatus = withContext(Dispatchers.IO) {
        val distribution = environment.linuxDistribution ?: return@withContext KimiWebRuntimeStatus(code = "INVALID_ENVIRONMENT")
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.path)) return@withContext KimiWebRuntimeStatus(code = "LINUX_ENVIRONMENT_NOT_READY")
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs.path)
        if (identity == "root" && !TerminalRuntime.rootAvailable) return@withContext KimiWebRuntimeStatus(code = "ROOT_REQUIRED")
        val matches = daemonSupervisor.list().filter {
            it.task.environment == environment && it.task.identity == identity &&
                it.task.backend == LinuxEnvironmentPaths.backendOf(rootfs.path) &&
                it.task.command.trim() in setOf(KimiWebSession.COMMAND, "kimi web")
        }
        val task = matches.lastOrNull { it.running } ?: matches.lastOrNull()
            ?: return@withContext KimiWebRuntimeStatus()
        if (!task.running) return@withContext KimiWebRuntimeStatus(taskId = task.task.id, code = "KIMI_EXITED")
        val logs = daemonSupervisor.readLogs(task.task.id)
        KimiWebRuntimeStatus(task.task.id, true, if (logs.ok) KimiWebSession.addressFromLogs(logs.text) else null, if (logs.ok) null else logs.code)
    }

    suspend fun stop(environment: TerminalEnvironment): Boolean = launchMutex.withLock {
        withContext(Dispatchers.IO) {
            val status = status(environment)
            status.taskId?.let(daemonSupervisor::stop) ?: false
        }
    }

    private companion object {
        val launchMutex = Mutex()
        const val LAUNCH_ID = "kimi-launch"
    }
}
