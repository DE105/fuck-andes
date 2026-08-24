package fuck.andes.agent.terminal

import android.content.Context
import fuck.andes.R
import fuck.andes.core.AndroidAgentLogger
import fuck.andes.core.safeLogType
import fuck.andes.data.datastore.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class AlpineInstallSession(
    val running: Boolean = false,
    val progress: AlpineInstallProgress? = null,
    val resultMessage: String? = null,
)

/**
 * 全局 Linux 工具环境安装协调器。
 *
 * 安装任务挂在独立于页面的 [CoroutineScope] 上，用户退出 Linux 工具环境页面时
 * 安装/升级不会被取消；重新进入页面通过 [session] 观察到进行中的进度或最终结果。
 */
internal object AlpineEnvironmentController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startMutex = Mutex()

    private val _session = MutableStateFlow(AlpineInstallSession())
    val session = _session.asStateFlow()

    fun startInstall(context: Context, forceToolInstall: Boolean) {
        if (_session.value.running) return
        scope.launch {
            startMutex.withLock {
                if (_session.value.running) return@withLock
                _session.value = AlpineInstallSession(running = true)
                runCatching {
                    val settings = SettingsDataStore.settings()
                    val installer = AlpineEnvironmentInstaller(
                        context = context.applicationContext,
                        mirror = settings.alpineMirror,
                        customMirrorUrl = settings.customAlpineMirrorUrl,
                    )
                    installer.install(forceToolInstall = forceToolInstall) { progress ->
                        _session.value = AlpineInstallSession(running = true, progress = progress)
                    }
                }.onSuccess { result ->
                    _session.value = AlpineInstallSession(
                        running = false,
                        resultMessage = result.toMessage(context.applicationContext),
                    )
                }.onFailure { throwable ->
                    AndroidAgentLogger.warn(
                        "Alpine environment action=install outcome=unexpected " +
                            "errorType=${throwable.safeLogType()}",
                    )
                    _session.value = AlpineInstallSession(
                        running = false,
                        resultMessage = context.applicationContext.getString(
                            R.string.linux_install_unexpected_error,
                        ),
                    )
                }
            }
        }
    }
}

internal fun AlpineInstallResult.toMessage(context: Context): String = when (this) {
    AlpineInstallResult.AlreadyReady -> context.getString(R.string.linux_already_ready)
    is AlpineInstallResult.Installed -> context.getString(R.string.linux_install_complete, version)
    is AlpineInstallResult.UnsupportedAbi -> context.getString(R.string.linux_unsupported_abi, abi)
    AlpineInstallResult.RootUnavailable -> context.getString(R.string.linux_root_unavailable)
    AlpineInstallResult.BusyBoxUnavailable -> context.getString(R.string.linux_busybox_unavailable)
    AlpineInstallResult.EnvironmentUnavailable -> context.getString(R.string.linux_environment_unavailable)
    is AlpineInstallResult.Failed -> context.getString(R.string.linux_stage_failed, stage.displayName(context))
}
