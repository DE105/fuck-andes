package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.ConsoleSessionController
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.TerminalScreenBuffer
import io.github.mangi.eta.agent.terminal.isLinux
import io.github.mangi.eta.agent.terminal.ptySupported
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 控制台一帧画面：行对象身份稳定，渲染层按 Line.id 复用、按 Line.version 重排。 */
@Immutable
internal data class ConsoleFrame(
    val lines: List<TerminalScreenBuffer.Line> = emptyList(),
    val screenRows: Int = 0,
    val cursorRow: Int = 0,
    val cursorCol: Int = 0,
    val cursorVisible: Boolean = true,
)

@Immutable
internal data class ConsoleSessionUi(
    val id: String,
    val environment: TerminalEnvironment,
    val exited: Boolean = false,
)

@Immutable
internal data class ConsoleUiState(
    val sessions: List<ConsoleSessionUi> = emptyList(),
    val activeSessionId: String? = null,
    /** 环境 tab 的选择；与当前会话环境一致，无会话时表示新建会话的目标环境。 */
    val environment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val linuxEnvironment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val connected: Boolean = false,
    val exited: Boolean = false,
    /** null = 探测中；false 时入口回退到块式终端。 */
    val ptySupported: Boolean? = null,
    val failMessage: String? = null,
    /** 当前会话的画面；每个会话的屏幕缓冲区独立保存。 */
    val frame: ConsoleFrame = ConsoleFrame(),
)

/**
 * 控制台页面的 App 级状态所有者：持有多个 PTY 会话与各自独立的屏幕缓冲区，
 * 字节流在 IO 线程喂入 [TerminalScreenBuffer]，按节流节奏向 UI 发布当前会话的帧。
 * 离开页面会话仍存活；普通会话由前台执行服务持有，用户停止后回收。
 */
internal class ConsoleStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val FLUSH_INTERVAL_MS = 50L
        const val SCROLLBACK_LINES = 500
    }

    private val appContext = context.applicationContext
    private val sessionLeases = ConcurrentHashMap<String, TerminalSessionLease>()
    private val controller = ConsoleSessionController(
        logger = AndroidAgentLogger,
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
            }
        },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
    private val bufferLock = Any()
    private val initialLinuxEnvironment =
        LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment

    /** 每个会话独立的屏幕缓冲区；写入只能在 IO 线程持锁进行，UI 线程持锁读快照。 */
    private val buffers = mutableMapOf<String, TerminalScreenBuffer>()
    private val sessionSizes = mutableMapOf<String, Pair<Int, Int>>()

    private val _uiState = MutableStateFlow(
        ConsoleUiState(
            environment = if (isReady(initialLinuxEnvironment)) {
                initialLinuxEnvironment
            } else {
                TerminalEnvironment.ANDROID
            },
            linuxEnvironment = initialLinuxEnvironment,
        )
    )
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    private var lastFlushMs = 0L
    private var flushScheduled = false
    private var lastCols = 0
    private var lastRows = 0

    /** 探测 PTY 前提；结果缓存进状态，不支持时 UI 提供回退入口。 */
    fun probePtySupport() {
        scope.launch {
            val supported = withContext(Dispatchers.IO) { ptySupported(ShellProcessSupervisor()) }
            _uiState.update { it.copy(ptySupported = supported) }
        }
    }

    fun refreshLinuxEnvironment() {
        val selected = LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment
        _uiState.update { state ->
            state.copy(
                linuxEnvironment = selected,
                environment = if (state.environment.isLinux) selected else state.environment,
            )
        }
    }

    /**
     * 确保当前有存活会话；网格尺寸变化重入时，当前会话存活则不重建。
     * [environment] 仅用于没有可复用会话时的新建。
     */
    fun open(environment: TerminalEnvironment, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        lastCols = cols
        lastRows = rows
        val state = _uiState.value
        val activeId = state.activeSessionId
        if (activeId != null && controller.sessionAlive(activeId)) return
        if (activeId == null) {
            val reusable = state.sessions.lastOrNull { !it.exited && it.environment == environment }
            if (reusable != null && controller.sessionAlive(reusable.id)) {
                switchSession(reusable.id)
                return
            }
        }
        createSession(environment, cols, rows)
    }

    /** 以当前环境与最近一次网格尺寸新建会话。 */
    fun newSession() {
        if (lastCols <= 0 || lastRows <= 0) return
        createSession(_uiState.value.environment, lastCols, lastRows)
    }

    fun switchSession(sessionId: String) {
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        _uiState.update { state ->
            state.copy(
                activeSessionId = sessionId,
                environment = session.environment,
                connected = !session.exited,
                exited = session.exited,
                failMessage = null,
            )
        }
        flushFrame(sessionId)
    }

    /** 关闭指定会话；关闭当前会话时切换到剩余最近的会话，没有则回到空态。 */
    fun closeSession(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            controller.closeSession(sessionId)
            sessionLeases.remove(sessionId)?.release()
        }
        synchronized(bufferLock) { buffers.remove(sessionId) }
        sessionSizes.remove(sessionId)
        _uiState.update { state ->
            val sessions = state.sessions.filterNot { it.id == sessionId }
            if (state.activeSessionId != sessionId) {
                state.copy(sessions = sessions)
            } else {
                val next = sessions.lastOrNull()
                state.copy(
                    sessions = sessions,
                    activeSessionId = next?.id,
                    environment = next?.environment ?: state.environment,
                    connected = next != null && !next.exited,
                    exited = next?.exited == true,
                    failMessage = null,
                    frame = ConsoleFrame(),
                )
            }
        }
        _uiState.value.activeSessionId?.let { flushFrame(it) }
    }

    /** 重启指定会话：终止后按原环境与原网格尺寸重开。 */
    fun restartSession(sessionId: String) {
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        val size = sessionSizes[sessionId] ?: (lastCols to lastRows)
        if (size.first <= 0 || size.second <= 0) return
        scope.launch(Dispatchers.IO) {
            controller.closeSession(sessionId)
            sessionLeases.remove(sessionId)?.release()
        }
        synchronized(bufferLock) { buffers.remove(sessionId) }
        sessionSizes.remove(sessionId)
        _uiState.update { state ->
            state.copy(sessions = state.sessions.filterNot { it.id == sessionId })
        }
        createSession(session.environment, size.first, size.second)
    }

    /** 切换环境 tab：只改目标环境；有该环境的存活会话则切过去，否则由网格回调新建。 */
    fun switchEnvironment(environment: TerminalEnvironment) {
        val state = _uiState.value
        if (state.environment == environment && state.activeSessionId != null) return
        val reusable = state.sessions.lastOrNull { !it.exited && it.environment == environment }
        if (reusable != null) {
            switchSession(reusable.id)
        } else {
            _uiState.update {
                it.copy(
                    environment = environment,
                    activeSessionId = null,
                    connected = false,
                    exited = false,
                    failMessage = null,
                    frame = ConsoleFrame(),
                )
            }
        }
    }

    /** 当前会话断开后以同一环境与网格尺寸重连。 */
    fun reconnect() {
        val activeId = _uiState.value.activeSessionId ?: return
        restartSession(activeId)
    }

    fun write(text: String) {
        val sessionId = _uiState.value.activeSessionId ?: return
        scope.launch(Dispatchers.IO) { controller.write(sessionId, text) }
    }

    fun close() {
        controller.close()
        sessionLeases.values.forEach { it.release() }
        sessionLeases.clear()
    }

    private fun createSession(environment: TerminalEnvironment, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        lastCols = cols
        lastRows = rows
        _uiState.update { it.copy(connected = false, exited = false, failMessage = null) }
        scope.launch(Dispatchers.IO) {
            val rootfs = environment.linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(appContext, it).absolutePath }
            val identity = TerminalRuntime.defaultIdentity(environment, rootfs)
            val lease = if (identity == "user") {
                TerminalSessionLease.acquire(appContext) { sessionId -> scope.launch { closeSession(sessionId) } } ?: run {
                    _uiState.update { it.copy(failMessage = appContext.getString(R.string.capability_background_failed)) }
                    return@launch
                }
            } else null
            val result = try {
                controller.open(
                    environment = environment,
                    identity = identity,
                    cols = cols,
                    rows = rows,
                    onOutput = ::onOutput,
                    onExit = { sessionId ->
                        lease?.release()
                        onExit(sessionId)
                    },
                )
            } catch (failure: Throwable) {
                lease?.release()
                throw failure
            }
            when (result) {
                is ConsoleSessionController.OpenResult.Ready -> {
                    if (lease != null) {
                        sessionLeases[result.sessionId] = lease
                        if (!lease.attach(result.sessionId) || !controller.sessionAlive(result.sessionId)) {
                            controller.closeSession(result.sessionId)
                            sessionLeases.remove(result.sessionId)?.release()
                            _uiState.update { it.copy(failMessage = appContext.getString(R.string.terminal_session_closed)) }
                            return@launch
                        }
                    }
                    synchronized(bufferLock) {
                        buffers[result.sessionId] = TerminalScreenBuffer(cols, rows, SCROLLBACK_LINES)
                    }
                    sessionSizes[result.sessionId] = cols to rows
                    _uiState.update { state ->
                        state.copy(
                            sessions = state.sessions + ConsoleSessionUi(result.sessionId, environment),
                            activeSessionId = result.sessionId,
                            environment = environment,
                            connected = true,
                            exited = false,
                            frame = ConsoleFrame(),
                        )
                    }
                }
                is ConsoleSessionController.OpenResult.Failed -> {
                    lease?.release()
                    _uiState.update { it.copy(connected = false, failMessage = result.message) }
                }
            }
        }
    }

    private fun onOutput(sessionId: String, chunk: ByteArray) {
        synchronized(bufferLock) {
            buffers[sessionId]?.process(String(chunk, Charsets.UTF_8))
        }
        if (sessionId != _uiState.value.activeSessionId) return
        val now = System.currentTimeMillis()
        if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
            flushFrame(sessionId)
        } else {
            scheduleFlush(sessionId)
        }
    }

    private fun scheduleFlush(sessionId: String) {
        if (flushScheduled) return
        flushScheduled = true
        scope.launch {
            delay(FLUSH_INTERVAL_MS)
            flushScheduled = false
            flushFrame(sessionId)
        }
    }

    private fun flushFrame(sessionId: String) {
        val frame = synchronized(bufferLock) {
            val current = buffers[sessionId] ?: return
            ConsoleFrame(
                lines = current.lines(),
                screenRows = current.rows,
                cursorRow = current.cursorRow,
                cursorCol = current.cursorCol,
                cursorVisible = current.cursorVisible,
            )
        }
        if (sessionId != _uiState.value.activeSessionId) return
        lastFlushMs = System.currentTimeMillis()
        _uiState.update { it.copy(frame = frame) }
    }

    private fun onExit(sessionId: String) {
        sessionLeases.remove(sessionId)?.release()
        _uiState.update { state ->
            val sessions = state.sessions.map {
                if (it.id == sessionId) it.copy(exited = true) else it
            }
            if (state.activeSessionId == sessionId) {
                state.copy(sessions = sessions, connected = false, exited = true)
            } else {
                state.copy(sessions = sessions)
            }
        }
        flushFrame(sessionId)
    }

    private fun isReady(environment: TerminalEnvironment): Boolean =
        environment.linuxDistribution?.let { distribution ->
            LinuxEnvironmentPaths.rootfsReady(
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath,
            )
        } ?: false
}
