package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import kotlin.concurrent.thread

/**
 * 控制台会话控制器：PTY 字节流原样透传，无状态行协议、无输出截断。
 *
 * 与 [UserTerminalController] 的差别在于交互模型：控制台面向全屏 TUI 与交互式 CLI，
 * 输入直接写 stdin（含方向键、Ctrl 组合键的转义字节），输出由调用方喂给
 * [TerminalScreenBuffer] 维护屏幕网格。多个控制台会话并存，按 sessionId 路由。
 */
internal class ConsoleSessionController(
    private val logger: AgentLogger,
    private val linuxRootfsPath: String? = null,
    private val linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val processSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(),
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
) : AutoCloseable {

    private companion object {
        const val DEFAULT_ANDROID_CWD = "/data/local/tmp/eta"
        const val MAX_SESSIONS = 6
    }

    sealed interface OpenResult {
        data class Ready(val sessionId: String) : OpenResult
        data class Failed(val code: String, val message: String) : OpenResult
    }

    data class SessionInfo(
        val id: String,
        val environment: TerminalEnvironment,
        val alive: Boolean,
    )

    private val sessionLock = Any()
    private val sessions = LinkedHashMap<String, PtySession>()
    private var nextSessionNumber = 0

    fun listSessions(): List<SessionInfo> = synchronized(sessionLock) {
        sessions.map { (id, session) ->
            SessionInfo(
                id = id,
                environment = session.environment,
                alive = !session.closed && session.process.isAlive,
            )
        }
    }

    fun sessionAlive(sessionId: String): Boolean = synchronized(sessionLock) {
        sessions[sessionId]?.let { !it.closed && it.process.isAlive } == true
    }

    fun open(
        environment: TerminalEnvironment,
        cols: Int,
        rows: Int,
        onOutput: (sessionId: String, chunk: ByteArray) -> Unit,
        onExit: (sessionId: String) -> Unit,
        identity: String? = null,
    ): OpenResult {
        synchronized(sessionLock) {
            pruneDeadSessionsLocked()
            if (sessions.size >= MAX_SESSIONS) {
                return OpenResult.Failed("SESSION_LIMIT_REACHED", "会话数量已达上限")
            }
            val environmentRootfsPath = rootfsPath(environment)
            val identity = identity ?: if (environment.isLinux) TerminalRuntime.defaultIdentity(environment, environmentRootfsPath) else if (rootAvailable()) "root" else "user"
            if (identity !in setOf("root", "user")) return OpenResult.Failed("INVALID_ARGUMENT", "执行身份无效")
            if (environment.isLinux && identity == "user" && LinuxEnvironmentPaths.backendOf(environmentRootfsPath) != LinuxExecutionBackend.PROOT) return OpenResult.Failed("LINUX_ENVIRONMENT_REQUIRES_ROOT", "所选 Linux 环境需要 Root")
            if (environment.isLinux && identity == "root" && LinuxEnvironmentPaths.backendOf(environmentRootfsPath) == LinuxExecutionBackend.PROOT) return OpenResult.Failed("INVALID_IDENTITY", "免 Root Linux 使用普通应用身份")
            if (identity == "root" && !rootAvailable()) return OpenResult.Failed("ROOT_REQUIRED", "Root 授权不可用")
            if (environment.isLinux &&
                !LinuxEnvironmentPaths.rootfsReady(environmentRootfsPath)
            ) {
                return OpenResult.Failed("LINUX_ENVIRONMENT_NOT_READY", "Linux 工具环境尚未安装")
            }
            val process = processSupervisor.startShellProcess(
                identity = identity,
                command = null,
                mergeStderr = true,
                environment = environment,
                linuxRootfsPath = environmentRootfsPath,
                linuxSharedMounts = if (environment.isLinux) {
                    linuxSharedMountsProvider()
                } else {
                    emptyList()
                },
                pty = true,
                ptyCols = cols,
                ptyRows = rows,
            ) ?: return OpenResult.Failed("PROCESS_START_FAILED", "无法启动控制台进程，请检查所选环境和终端组件")

            val sessionId = "c${++nextSessionNumber}"
            val newSession = PtySession(environment, process, identity)
            sessions[sessionId] = newSession
            newSession.readerThread = thread(name = "console-pty-reader", isDaemon = true) {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                try {
                    while (true) {
                        val read = process.inputStream.read(buffer)
                        if (read < 0) break
                        onOutput(sessionId, buffer.copyOf(read))
                    }
                } catch (_: Exception) {
                    // 进程死亡或关闭时读端断开，交给 waiter 统一上报退出。
                }
            }
            newSession.waiterThread = thread(name = "console-pty-waiter", isDaemon = true) {
                runCatching { process.waitFor() }
                newSession.closed = true
                processSupervisor.retireExitedProcess(process)
                onExit(sessionId)
            }
            // 落到环境默认工作目录；clear 清掉这条引导命令本身的回显。
            // 用户工具的安装器常把 PATH 写进 profile；控制台 shell 不是 login shell，这里显式补齐。
            val defaultCwd = if (environment.isLinux) "/workspace" else TerminalRuntime.workspace(identity)
            val bootstrap = "mkdir -p ${shellQuote(defaultCwd)}; " +
                "[ -f /etc/profile ] && . /etc/profile; " +
                "[ -f \"\$HOME/.profile\" ] && . \"\$HOME/.profile\"; " +
                "cd ${shellQuote(defaultCwd)} && clear\n"
            runCatching {
                process.outputStream.write(bootstrap.toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }
            logger.info("Console action=open outcome=succeeded environment=${environment.wireName} cols=$cols rows=$rows")
            return OpenResult.Ready(sessionId)
        }
    }

    /** 向指定控制台会话写入输入字节（键盘文本、方向键/功能键转义序列）。会话不可用时静默丢弃。 */
    fun write(sessionId: String, bytes: ByteArray) {
        val current = synchronized(sessionLock) { sessions[sessionId] } ?: return
        if (current.closed || !current.process.isAlive) return
        if (current.identity == "root" && !rootAvailable()) {
            closeSession(sessionId)
            return
        }
        runCatching {
            synchronized(current.stdinLock) {
                current.process.outputStream.write(bytes)
                current.process.outputStream.flush()
            }
        }
    }

    fun write(sessionId: String, text: String) = write(sessionId, text.toByteArray(Charsets.UTF_8))

    /** 关闭单个会话；只回收该进程树，不影响其它会话，也不触发 supervisor 全局关闭。 */
    fun closeSession(sessionId: String) {
        synchronized(sessionLock) {
            closeSessionLocked(sessionId)
        }
    }

    override fun close() {
        processSupervisor.beginClosing()
        synchronized(sessionLock) {
            sessions.keys.toList().forEach { closeSessionLocked(it) }
        }
    }

    private fun closeSessionLocked(sessionId: String) {
        val current = sessions.remove(sessionId) ?: return
        current.closed = true
        runCatching { current.process.outputStream.close() }
        processSupervisor.terminateAndReap(current.process)
        runCatching { current.readerThread.join(500) }
        runCatching { current.waiterThread.join(500) }
        processSupervisor.unregisterProcess(current.process)
    }

    /** 回收已退出的会话槽位，避免死会话占用并发上限。 */
    private fun pruneDeadSessionsLocked() {
        val deadIds = sessions.filterValues { it.closed || !it.process.isAlive }.keys.toList()
        deadIds.forEach { closeSessionLocked(it) }
    }

    private fun rootfsPath(environment: TerminalEnvironment): String? =
        linuxRootfsPathProvider?.invoke(environment) ?: linuxRootfsPath

    private class PtySession(
        val environment: TerminalEnvironment,
        val process: Process,
        val identity: String,
    ) {
        val stdinLock = Any()

        @Volatile
        var closed: Boolean = false

        lateinit var readerThread: Thread
        lateinit var waiterThread: Thread
    }
}
