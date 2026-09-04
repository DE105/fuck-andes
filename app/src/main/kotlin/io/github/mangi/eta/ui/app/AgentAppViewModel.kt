package io.github.mangi.eta.ui.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxPackageProfiles
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.linuxPackageProfileReady
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity 级状态所有者；配置变更只重建 UI，不替换正在运行的 Agent 会话。 */
internal class AgentAppViewModel(application: Application) : AndroidViewModel(application) {
    val state = AgentAppState(
        context = application,
        scope = viewModelScope,
    )
    private val terminalHost = TerminalSessionHost.get(application)
    val terminalStore = terminalHost.terminal
    val consoleStore = terminalHost.console
    val kimiWebLauncher = KimiWebLauncher(
        context = application,
        daemonSupervisor = DetachedTaskSupervisor(
            logger = AndroidAgentLogger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(application),
            linuxRootfsPathProvider = { environment ->
                environment.linuxDistribution?.let { distribution ->
                    LinuxEnvironmentPaths.rootfsDir(application, distribution).absolutePath
                }
            },
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
        ),
    )

    var kimiWebState by mutableStateOf(KimiWebUiState())
        private set
    private var kimiWebJob: Job? = null

    fun refreshKimiWeb() {
        if (kimiWebJob?.isActive == true) return
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                val distribution = LinuxEnvironmentSettingsRepository.current(getApplication())
                val rootfs = LinuxEnvironmentPaths.rootfsDir(getApplication(), distribution)
                if (!linuxPackageProfileReady(rootfs, LinuxPackageProfiles.KIMI)) {
                    KimiWebUiState(KimiWebPhase.NOT_INSTALLED)
                } else {
                    val runtime = kimiWebLauncher.status(distribution.terminalEnvironment)
                    when {
                        runtime.running -> KimiWebUiState(KimiWebPhase.RUNNING)
                        runtime.code != null -> KimiWebUiState(KimiWebPhase.FAILED, runtime.code)
                        kimiWebState.phase == KimiWebPhase.FAILED -> kimiWebState
                        else -> KimiWebUiState(KimiWebPhase.READY)
                    }
                }
            }
            if (kimiWebJob?.isActive != true) kimiWebState = status
        }
    }

    fun launchKimiWeb(onFinished: (KimiWebLaunchResult) -> Unit) {
        if (kimiWebJob?.isActive == true) return
        kimiWebState = KimiWebUiState(KimiWebPhase.STARTING)
        kimiWebJob = viewModelScope.launch {
            val distribution = LinuxEnvironmentSettingsRepository.current(getApplication())
            val result = try {
                kimiWebLauncher.launch(distribution.terminalEnvironment)
            } finally {
                if (kimiWebState.phase == KimiWebPhase.STARTING) kimiWebState = KimiWebUiState(KimiWebPhase.READY)
            }
            kimiWebState = when (result) {
                is KimiWebLaunchResult.Opened -> KimiWebUiState(KimiWebPhase.RUNNING)
                is KimiWebLaunchResult.Failed -> KimiWebUiState(KimiWebPhase.FAILED, result.code)
            }
            onFinished(result)
        }
    }

    fun stopKimiWeb() {
        val preparation = kimiWebJob
        preparation?.cancel()
        kimiWebJob = viewModelScope.launch {
            preparation?.join()
            val distribution = LinuxEnvironmentSettingsRepository.current(getApplication())
            val status = kimiWebLauncher.status(distribution.terminalEnvironment)
            val stopped = if (status.taskId == null) status.code == null
                else kimiWebLauncher.stop(distribution.terminalEnvironment)
            kimiWebState = if (stopped) KimiWebUiState(KimiWebPhase.READY)
                else KimiWebUiState(KimiWebPhase.FAILED, status.code ?: "STOP_FAILED")
        }
    }
}
