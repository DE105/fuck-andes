package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.DetachedTaskStatus
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.UserTerminalController
import io.github.mangi.eta.agent.terminal.isLinux
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.ui.components.ansiPlainText
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
internal data class TerminalBlockUi(
    val id: Long,
    val isSystem: Boolean = false,
    val command: String = "",
    val cwdAtStart: String = "",
    val output: String = "",
    val exitCode: Int? = null,
    val running: Boolean = false,
    val truncated: Boolean = false,
)

@Immutable
internal data class DaemonTaskUi(
    val id: String,
    val command: String,
    val environment: TerminalEnvironment,
    val identity: String,
    val running: Boolean,
    val startedAt: Long,
)

@Immutable
internal data class TerminalSessionUi(
    val id: String,
    val environment: TerminalEnvironment,
    val cwd: String,
    val running: Boolean = false,
    val alive: Boolean = true,
)

@Immutable
internal data class UserTerminalUiState(
    val sessions: List<TerminalSessionUi> = emptyList(),
    val activeSessionId: String? = null,
    /** 当前会话的命令块；每个会话的块列表独立保存。 */
    val blocks: List<TerminalBlockUi> = emptyList(),
    /** 环境 tab 的选择；与当前会话环境一致，无会话时表示新建会话的目标环境。 */
    val environment: TerminalEnvironment = TerminalEnvironment.ANDROID,
    val cwd: String = "",
    val running: Boolean = false,
    val linuxReady: Boolean = false,
    val linuxEnvironment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val daemonTasks: List<DaemonTaskUi> = emptyList(),
    val failMessage: String? = null,
)

/**
 * 用户手动终端的 App 级状态所有者：把 [UserTerminalController] 的多会话线程模型映射为 Compose 状态。
 * 普通会话由前台执行服务持有，离开终端页、旋转屏幕不会中断；App 进程死亡则会话随之结束。
 */
internal class UserTerminalStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val MAX_BLOCKS = 200
        const val MAX_BLOCK_OUTPUT_CHARS = 64_000
        const val FLUSH_INTERVAL_MS = 120L
    }

    private val appContext = context.applicationContext
    private val sessionLeases = ConcurrentHashMap<String, TerminalSessionLease>()
    private val controller = UserTerminalController(
        logger = AndroidAgentLogger,
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
            }
        },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
        onSessionExit = { sessionId ->
            sessionLeases.remove(sessionId)?.release()
            scope.launch { updateSessionEntry(sessionId) { it.copy(alive = false) } }
        },
    )
    private val daemonSupervisor = DetachedTaskSupervisor(
        logger = AndroidAgentLogger,
        recordsFile = DetachedTaskSupervisor.defaultRecordsFile(appContext),
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
            }
        },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
    private val initialLinuxEnvironment =
        LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment

    private val _uiState = MutableStateFlow(
        UserTerminalUiState(
            environment = if (isReady(initialLinuxEnvironment)) {
                initialLinuxEnvironment
            } else {
                TerminalEnvironment.ANDROID
            },
            linuxEnvironment = initialLinuxEnvironment,
            linuxReady = isReady(initialLinuxEnvironment),
        )
    )
    val uiState: StateFlow<UserTerminalUiState> = _uiState.asStateFlow()

    private var blockId = 0L

    /** 每个会话独立的块列表与流式输出缓冲；只有当前会话投影进 uiState.blocks。 */
    private val sessionBlocks = mutableMapOf<String, List<TerminalBlockUi>>()
    private val sessionBuffers = mutableMapOf<String, SessionOutput>()

    fun refreshLinuxReady() {
        val selected = LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment
        val ready = isReady(selected)
        _uiState.update {
            it.copy(
                linuxEnvironment = selected,
                environment = if (it.environment.isLinux) selected else it.environment,
                linuxReady = ready,
            )
        }
    }

    fun refreshDaemonTasks() {
        scope.launch {
            val statuses = withContext(Dispatchers.IO) { daemonSupervisor.list() }
            _uiState.update { state ->
                state.copy(daemonTasks = statuses.map(::toDaemonTaskUi))
            }
        }
    }

    fun stopDaemonTask(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) { daemonSupervisor.stop(id) }
            refreshDaemonTasks()
        }
    }

    /** 读取守护任务日志尾部；ANSI 序列剥离为纯文本，失败时返回可展示的原因文本。 */
    suspend fun daemonLogs(id: String): String = withContext(Dispatchers.IO) {
        val result = daemonSupervisor.readLogs(id)
        when {
            result.ok && result.text.isBlank() -> appContext.getString(R.string.terminal_daemon_logs_empty)
            result.ok -> ansiPlainText(result.text.trimEnd())
            else -> result.message.ifBlank { appContext.getString(R.string.terminal_daemon_logs_empty) }
        }
    }

    /** 以当前环境新建会话并切换过去。 */
    fun newSession() {
        val environment = _uiState.value.environment
        scope.launch {
            openSessionInternal(environment)
        }
    }

    /** 切换当前会话；块列表、cwd 与运行态随之换绑。 */
    fun switchSession(sessionId: String) {
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        _uiState.update { state ->
            state.copy(
                activeSessionId = sessionId,
                blocks = sessionBlocks[sessionId].orEmpty(),
                environment = session.environment,
                cwd = session.cwd,
                running = session.running,
            )
        }
    }

    /** 关闭指定会话；关闭当前会话时切换到剩余最近的会话，没有则回到空态。 */
    fun closeSession(sessionId: String) {
        scope.launch {
            withContext(Dispatchers.IO) { controller.stopSession(sessionId) }
            sessionLeases.remove(sessionId)?.release()
            sessionBlocks.remove(sessionId)
            sessionBuffers.remove(sessionId)
            _uiState.update { state ->
                val sessions = state.sessions.filterNot { it.id == sessionId }
                if (state.activeSessionId != sessionId) {
                    state.copy(sessions = sessions)
                } else {
                    val next = sessions.lastOrNull()
                    state.copy(
                        sessions = sessions,
                        activeSessionId = next?.id,
                        blocks = next?.let { sessionBlocks[it.id].orEmpty() }.orEmpty(),
                        cwd = next?.cwd ?: "",
                        running = next?.running == true,
                        environment = next?.environment ?: state.environment,
                    )
                }
            }
        }
    }

    /** 重启指定会话：终止后按原环境与 cwd 重开，命令块保留并追加提示。 */
    fun restartSession(sessionId: String) {
        scope.launch {
            val entry = _uiState.value.sessions.find { it.id == sessionId } ?: return@launch
            withContext(Dispatchers.IO) { controller.stopSession(sessionId) }
            sessionLeases.remove(sessionId)?.release()
            val opened = withContext(Dispatchers.IO) {
                openLeasedSession(entry.environment, entry.cwd)
            }
            when (opened) {
                is UserTerminalController.OpenResult.Failed -> {
                    updateSessionEntry(sessionId) { it.copy(alive = false, running = false) }
                    appendSystemBlock(sessionId, opened.message)
                }
                is UserTerminalController.OpenResult.Ready -> {
                    replaceSession(sessionId, opened)
                    appendSystemBlock(
                        opened.sessionId,
                        appContext.getString(R.string.terminal_session_restarted),
                    )
                }
            }
        }
    }

    fun send(rawCommand: String) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return
        if (command.length > 16_000) return
        val state = _uiState.value
        if (state.running) return
        val environment = state.environment
        scope.launch {
            val sessionId = ensureSession(state, environment) ?: return@launch
            val cwdAtStart = _uiState.value.sessions.find { it.id == sessionId }?.cwd.orEmpty()
            val id = ++blockId
            appendBlock(
                sessionId,
                TerminalBlockUi(
                    id = id,
                    command = command,
                    cwdAtStart = cwdAtStart,
                    running = true,
                ),
            )
            updateSessionEntry(sessionId) { it.copy(running = true) }
            sessionBuffers[sessionId] = SessionOutput()
            val result = withContext(Dispatchers.IO) {
                controller.exec(sessionId, command) { text, _ -> onOutputDelta(sessionId, id, text) }
            }
            flushOutput(sessionId, id)
            finalizeBlock(sessionId, id, result)
        }
    }

    /** 运行中向当前会话 stdin 发送一行输入，并以暗色回显到当前块（管道没有 tty 回显）。 */
    fun sendInput(rawInput: String) {
        val text = rawInput.trim()
        if (text.isEmpty()) return
        val sessionId = _uiState.value.activeSessionId ?: return
        if (!_uiState.value.running) return
        scope.launch(Dispatchers.IO) { controller.writeInput(sessionId, text + "\n") }
        updateSessionBlocks(sessionId) { blocks ->
            blocks.map { block ->
                if (block.running) {
                    block.copy(output = block.output + "\u001B[2m" + text + "\u001B[0m\n")
                } else {
                    block
                }
            }
        }
    }

    /** 终止当前会话及其运行中的命令。 */
    fun stop() {
        val sessionId = _uiState.value.activeSessionId ?: return
        if (!_uiState.value.running) return
        scope.launch(Dispatchers.IO) { controller.stopSession(sessionId) }
    }

    /** 切换环境 tab：只改目标环境；有该环境的存活会话则切过去，否则进入空态待新建。 */
    fun switchEnvironment(environment: TerminalEnvironment) {
        val state = _uiState.value
        if (state.environment == environment && state.activeSessionId != null) return
        val reusable = state.sessions.lastOrNull { it.environment == environment && it.alive }
        if (reusable != null) {
            switchSession(reusable.id)
        } else {
            _uiState.update {
                it.copy(
                    environment = environment,
                    linuxReady = isReady(environment),
                    activeSessionId = null,
                    blocks = emptyList(),
                    cwd = "",
                    running = false,
                )
            }
        }
    }

    fun close() {
        controller.close()
        sessionLeases.values.forEach { it.release() }
        sessionLeases.clear()
    }

    private fun openLeasedSession(environment: TerminalEnvironment, cwd: String? = null): UserTerminalController.OpenResult {
        val rootfs = environment.linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(appContext, it).absolutePath }
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs)
        val lease = if (identity == "user") {
            TerminalSessionLease.acquire(appContext) { sessionId -> scope.launch { closeSession(sessionId) } }
                ?: return UserTerminalController.OpenResult.Failed("BACKGROUND_START_NOT_ALLOWED", appContext.getString(R.string.capability_background_failed))
        } else null
        val result = try {
            controller.openSession(environment, cwd = cwd, identity = identity)
        } catch (failure: Throwable) {
            lease?.release()
            throw failure
        }
        if (result is UserTerminalController.OpenResult.Ready && lease != null) {
            sessionLeases[result.sessionId] = lease
            if (!lease.attach(result.sessionId) || !controller.sessionAlive(result.sessionId)) {
                controller.stopSession(result.sessionId)
                sessionLeases.remove(result.sessionId)?.release()
                return UserTerminalController.OpenResult.Failed("TERMINAL_CLOSED", appContext.getString(R.string.terminal_session_closed))
            }
        } else if (result is UserTerminalController.OpenResult.Failed) {
            lease?.release()
        }
        return result
    }

    /**
     * 确保有一个可用于执行的会话：当前存活会话直接复用；已退出的当前会话重开并提示；
     * 没有当前会话时优先复用同环境存活会话，否则新建。返回可用的 sessionId。
     */
    private suspend fun ensureSession(
        state: UserTerminalUiState,
        environment: TerminalEnvironment,
    ): String? {
        val activeId = state.activeSessionId
        val active = state.sessions.find { it.id == activeId }
        if (active != null && active.environment == environment) {
            val alive = withContext(Dispatchers.IO) { controller.sessionAlive(active.id) }
            if (alive) return active.id
            // 已退出：原环境原 cwd 重开，块保留。
            val reopened = withContext(Dispatchers.IO) {
                openLeasedSession(active.environment, active.cwd)
            }
            return when (reopened) {
                is UserTerminalController.OpenResult.Failed -> {
                    updateSessionEntry(active.id) { it.copy(alive = false) }
                    appendSystemBlock(active.id, reopened.message)
                    null
                }
                is UserTerminalController.OpenResult.Ready -> {
                    replaceSession(active.id, reopened)
                    appendSystemBlock(
                        reopened.sessionId,
                        appContext.getString(R.string.terminal_session_restarted),
                    )
                    reopened.sessionId
                }
            }
        }
        val reusable = state.sessions.lastOrNull { it.environment == environment && it.alive }
        if (reusable != null) {
            switchSession(reusable.id)
            return reusable.id
        }
        return openSessionInternal(environment)
    }

    /** 新建会话并设为当前；失败时在当前块列表追加原因。返回新 sessionId。 */
    private suspend fun openSessionInternal(environment: TerminalEnvironment): String? {
        _uiState.update { it.copy(failMessage = null) }
        val opened = withContext(Dispatchers.IO) { openLeasedSession(environment) }
        return when (opened) {
            is UserTerminalController.OpenResult.Failed -> {
                val activeId = _uiState.value.activeSessionId
                if (activeId != null) appendSystemBlock(activeId, opened.message)
                else _uiState.update { it.copy(failMessage = opened.message) }
                null
            }
            is UserTerminalController.OpenResult.Ready -> {
                val session = TerminalSessionUi(
                    id = opened.sessionId,
                    environment = opened.environment,
                    cwd = opened.cwd,
                )
                sessionBlocks[opened.sessionId] = emptyList()
                _uiState.update { state ->
                    state.copy(
                        sessions = state.sessions + session,
                        activeSessionId = opened.sessionId,
                        blocks = emptyList(),
                        environment = opened.environment,
                        cwd = opened.cwd,
                        running = false,
                    )
                }
                opened.sessionId
            }
        }
    }

    /** 重启/重开场景：新 sessionId 接替旧会话的位置，命令块随 id 迁移。 */
    private fun replaceSession(oldId: String, opened: UserTerminalController.OpenResult.Ready) {
        sessionLeases.remove(oldId)?.release()
        val inheritedBlocks = sessionBlocks.remove(oldId).orEmpty()
        sessionBlocks[opened.sessionId] = inheritedBlocks
        sessionBuffers.remove(oldId)
        _uiState.update { state ->
            val sessions = state.sessions.map { session ->
                if (session.id == oldId) {
                    TerminalSessionUi(
                        id = opened.sessionId,
                        environment = opened.environment,
                        cwd = opened.cwd,
                    )
                } else {
                    session
                }
            }
            if (state.activeSessionId == oldId) {
                state.copy(
                    sessions = sessions,
                    activeSessionId = opened.sessionId,
                    blocks = inheritedBlocks,
                    cwd = opened.cwd,
                    running = false,
                )
            } else {
                state.copy(sessions = sessions)
            }
        }
    }

    /** 输出保留原始 ANSI 序列，渲染层解析为颜色与样式；运行日志/复制场景由渲染层剥离。 */
    private fun onOutputDelta(sessionId: String, blockId: Long, text: String) {
        if (text.isEmpty()) return
        val buffer = sessionBuffers[sessionId] ?: return
        synchronized(buffer.lock) { buffer.text.append(text) }
        val now = System.currentTimeMillis()
        if (now - buffer.lastFlushMs >= FLUSH_INTERVAL_MS) {
            flushOutput(sessionId, blockId)
        }
    }

    private fun flushOutput(sessionId: String, blockId: Long) {
        val buffer = sessionBuffers[sessionId] ?: return
        val chunk = synchronized(buffer.lock) {
            if (buffer.text.isEmpty()) null else buffer.text.toString().also { buffer.text.setLength(0) }
        } ?: return
        buffer.lastFlushMs = System.currentTimeMillis()
        updateSessionBlocks(sessionId) { blocks ->
            blocks.map { block ->
                if (block.id != blockId) {
                    block
                } else {
                    val combined = block.output + chunk
                    if (combined.length > MAX_BLOCK_OUTPUT_CHARS) {
                        block.copy(
                            output = combined.take(MAX_BLOCK_OUTPUT_CHARS),
                            truncated = true,
                        )
                    } else {
                        block.copy(output = combined)
                    }
                }
            }
        }
    }

    private fun finalizeBlock(sessionId: String, blockId: Long, result: UserTerminalController.ExecResult) {
        updateSessionBlocks(sessionId) { blocks ->
            blocks.map { block ->
                if (block.id == blockId) {
                    block.copy(running = false, exitCode = result.exitCode)
                } else {
                    block
                }
            }
        }
        updateSessionEntry(sessionId) {
            it.copy(running = false, cwd = result.cwd, alive = !result.sessionClosed)
        }
        if (result.sessionClosed) {
            sessionLeases.remove(sessionId)?.release()
            appendSystemBlock(
                sessionId,
                appContext.getString(
                    if (result.interrupted) R.string.terminal_interrupted else R.string.terminal_session_closed
                ),
            )
        }
    }

    private fun appendBlock(sessionId: String, block: TerminalBlockUi) {
        updateSessionBlocks(sessionId) { blocks -> (blocks + block).takeLast(MAX_BLOCKS) }
    }

    private fun appendSystemBlock(sessionId: String, message: String) {
        appendBlock(
            sessionId,
            TerminalBlockUi(
                id = ++blockId,
                isSystem = true,
                output = message,
            ),
        )
    }

    private fun updateSessionBlocks(sessionId: String, transform: (List<TerminalBlockUi>) -> List<TerminalBlockUi>) {
        val updated = transform(sessionBlocks[sessionId].orEmpty())
        sessionBlocks[sessionId] = updated
        _uiState.update { state ->
            if (state.activeSessionId == sessionId) state.copy(blocks = updated) else state
        }
    }

    private fun updateSessionEntry(sessionId: String, transform: (TerminalSessionUi) -> TerminalSessionUi) {
        _uiState.update { state ->
            val sessions = state.sessions.map { if (it.id == sessionId) transform(it) else it }
            if (state.activeSessionId == sessionId) {
                val active = sessions.firstOrNull { it.id == sessionId }
                state.copy(
                    sessions = sessions,
                    running = active?.running == true,
                    cwd = active?.cwd ?: state.cwd,
                )
            } else {
                state.copy(sessions = sessions)
            }
        }
    }

    private fun isReady(environment: TerminalEnvironment): Boolean =
        environment.linuxDistribution?.let { distribution ->
            LinuxEnvironmentPaths.rootfsReady(
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath,
            )
        } ?: false

    private fun toDaemonTaskUi(status: DetachedTaskStatus) = DaemonTaskUi(
        id = status.task.id,
        command = status.task.command,
        environment = status.task.environment,
        identity = status.task.identity,
        running = status.running,
        startedAt = status.task.startedAt,
    )

    private class SessionOutput {
        val lock = Any()
        val text = StringBuilder()
        var lastFlushMs = 0L
    }
}

internal val TerminalEnvironment.displayName: String
    get() = when (this) {
        TerminalEnvironment.ANDROID -> "Android"
        TerminalEnvironment.ALPINE -> "Alpine"
        TerminalEnvironment.DEBIAN -> "Debian"
    }
