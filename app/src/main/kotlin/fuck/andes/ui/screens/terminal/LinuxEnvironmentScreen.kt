package fuck.andes.ui.screens.terminal
import fuck.andes.R
import androidx.compose.ui.res.stringResource

import android.content.Context
import android.icu.text.ListFormatter
import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fuck.andes.agent.terminal.AlpineEnvironmentInstaller
import fuck.andes.agent.terminal.AlpineEnvironmentHealth
import fuck.andes.agent.terminal.AlpineApkAnalysisInstaller
import fuck.andes.agent.terminal.AlpineEnvironmentState
import fuck.andes.agent.terminal.AlpineEnvironmentStatus
import fuck.andes.agent.terminal.AlpineInstallProgress
import fuck.andes.agent.terminal.AlpineInstallResult
import fuck.andes.agent.terminal.AlpineInstallStage
import fuck.andes.agent.terminal.ApkAnalysisInstallProgress
import fuck.andes.agent.terminal.ApkAnalysisInstallResult
import fuck.andes.agent.terminal.ApkAnalysisInstallStage
import fuck.andes.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun LinuxEnvironmentScreen(
    context: Context,
    onBack: () -> Unit,
) {
    val installer = remember(context.applicationContext) {
        AlpineEnvironmentInstaller(context.applicationContext)
    }
    val apkAnalysisInstaller = remember(context.applicationContext) {
        AlpineApkAnalysisInstaller(context.applicationContext)
    }
    val coroutineScope = rememberCoroutineScope()
    var status by remember { mutableStateOf(installer.status()) }
    var installing by remember { mutableStateOf(false) }
    var checkingHealth by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<AlpineInstallProgress?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var health by remember { mutableStateOf<AlpineEnvironmentHealth?>(null) }
    var apkAnalysisReady by remember { mutableStateOf(apkAnalysisInstaller.isReady()) }
    var apkAnalysisProgress by remember { mutableStateOf<ApkAnalysisInstallProgress?>(null) }
    var apkAnalysisResultMessage by remember { mutableStateOf<String?>(null) }

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_linux_tool_environment_314d22),
        onBack = onBack,
    ) {
        item(key = "status-title") { SmallTitle(stringResource(R.string.ui_environmental_status_5b32a1)) }
        item(key = "status-card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = status.title(context),
                    summary = progress?.summary(context) ?: status.summary(context),
                    endActions = {
                        TextButton(
                            text = when {
                                installing -> context.getString(R.string.linux_installing)
                                status.state == AlpineEnvironmentState.READY -> context.getString(R.string.linux_reinstall_tools)
                                status.state == AlpineEnvironmentState.BASE_READY && status.version != null -> context.getString(R.string.linux_upgrade_tools)
                                status.state == AlpineEnvironmentState.BASE_READY -> context.getString(R.string.linux_continue_installation)
                                else -> context.getString(R.string.linux_download_install)
                            },
                            enabled = !installing,
                            onClick = {
                                if (installing) return@TextButton
                                installing = true
                                resultMessage = null
                                coroutineScope.launch {
                                    val forceToolInstall = status.state == AlpineEnvironmentState.READY
                                    val result = installer.install(forceToolInstall = forceToolInstall) { update ->
                                        withContext(Dispatchers.Main.immediate) {
                                            progress = update
                                        }
                                    }
                                    status = installer.status()
                                    apkAnalysisReady = apkAnalysisInstaller.isReady()
                                    health = null
                                    progress = null
                                    installing = false
                                    resultMessage = result.toMessage(context)
                                }
                            },
                        )
                    },
                )
                if (installing && progress != null) {
                    InstallLinearProgress(
                        progress = progress?.progressFraction(),
                    )
                }
            }
        }

        if (status.state == AlpineEnvironmentState.READY) {
            item(key = "health-title") { SmallTitle(stringResource(R.string.ui_environmental_inspection_d58123)) }
            item(key = "health-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(
                        title = health?.title(context) ?: context.getString(R.string.linux_not_checked),
                        summary = health?.summary(context) ?: context.getString(R.string.linux_health_summary),
                        endActions = {
                            val repairNeeded = health?.healthy == false
                            TextButton(
                                text = when {
                                    installing -> context.getString(R.string.linux_busy)
                                    checkingHealth -> context.getString(R.string.linux_checking)
                                    repairNeeded -> context.getString(R.string.linux_repair)
                                    else -> context.getString(R.string.linux_check)
                                },
                                enabled = !checkingHealth && !installing,
                                onClick = {
                                    if (checkingHealth || installing) return@TextButton
                                    if (repairNeeded) {
                                        installing = true
                                        health = null
                                        resultMessage = null
                                        coroutineScope.launch {
                                            val result = installer.install(forceToolInstall = true) { update ->
                                                withContext(Dispatchers.Main.immediate) {
                                                    progress = update
                                                }
                                            }
                                            status = installer.status()
                                            progress = null
                                            installing = false
                                            resultMessage = result.toMessage(context)
                                        }
                                    } else {
                                        checkingHealth = true
                                        coroutineScope.launch {
                                            health = installer.inspectHealth()
                                            checkingHealth = false
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }

        if (status.state == AlpineEnvironmentState.READY) {
            item(key = "optional-tools-title") { SmallTitle(stringResource(R.string.ui_optional_tools_3097d6)) }
            item(key = "apk-analysis-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(
                        title = stringResource(R.string.ui_apk_analysis_95ad17),
                        summary = apkAnalysisProgress?.summary(context) ?: if (apkAnalysisReady) {
                            context.getString(R.string.linux_apk_tools_ready)
                        } else {
                            context.getString(R.string.linux_apk_tools_summary)
                        },
                        endActions = {
                            TextButton(
                                text = when {
                                    apkAnalysisReady -> context.getString(R.string.linux_installed)
                                    installing -> context.getString(R.string.linux_installing)
                                    else -> context.getString(R.string.linux_install)
                                },
                                enabled = !installing && !apkAnalysisReady,
                                onClick = {
                                    if (installing || apkAnalysisReady) return@TextButton
                                    installing = true
                                    apkAnalysisResultMessage = null
                                    coroutineScope.launch {
                                        val result = apkAnalysisInstaller.install { update ->
                                            withContext(Dispatchers.Main.immediate) {
                                                apkAnalysisProgress = update
                                            }
                                        }
                                        apkAnalysisReady = apkAnalysisInstaller.isReady()
                                        apkAnalysisProgress = null
                                        installing = false
                                        apkAnalysisResultMessage = result.toMessage(context)
                                    }
                                },
                            )
                        },
                    )
                    if (installing && apkAnalysisProgress != null) {
                        InstallLinearProgress(
                            progress = apkAnalysisProgress?.progressFraction(),
                        )
                    }
                }
            }
        }

        apkAnalysisResultMessage?.let { message ->
            item(key = "apk-analysis-result-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(title = message)
                }
            }
        }

        resultMessage?.let { message ->
            item(key = "result-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(title = message)
                }
            }
        }

        item(key = "details-title") { SmallTitle(stringResource(R.string.ui_illustrate_26670d)) }
        item(key = "details-card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = stringResource(R.string.ui_separate_from_android_root_shell_d2e22f),
                    summary = stringResource(R.string.ui_system_application_log_and_magisk_operations_still_u_69659b),
                )
                BasicComponent(
                    title = stringResource(R.string.ui_agent_basic_tools_98276b),
                    summary = stringResource(R.string.ui_pre_installed_rg_fd_git_ssh_curl_rsync_diff_patch_jq_c492d9),
                )
                BasicComponent(
                    title = stringResource(R.string.ui_python_tools_b811ed),
                    summary = stringResource(R.string.ui_python_pip_venv_pipx_uv_and_ruff_are_pre_installed_p_1aa31e),
                )
                BasicComponent(
                    title = stringResource(R.string.ui_scale_on_demand_fdd11e),
                    summary = stringResource(R.string.ui_node_js_compiler_tmux_vim_and_network_packet_capture_05dcca),
                )
                BasicComponent(
                    title = stringResource(R.string.ui_stable_workspace_86d734),
                    summary = stringResource(R.string.ui_linux_defaults_to_workspace_and_continues_to_be_comp_052e9b),
                )
                BasicComponent(
                    title = stringResource(R.string.ui_permission_boundaries_b11a0c),
                    summary = stringResource(R.string.ui_the_environment_runs_through_root_chroot_and_uses_a__83aefe),
                )
            }
        }
    }
}

private fun AlpineEnvironmentStatus.title(context: Context): String = when (state) {
    AlpineEnvironmentState.NOT_INSTALLED -> context.getString(R.string.linux_not_installed)
    AlpineEnvironmentState.BASE_READY -> context.getString(R.string.linux_base_ready)
    AlpineEnvironmentState.READY -> context.getString(R.string.linux_alpine_ready, version.orEmpty()).trim()
}

private fun AlpineEnvironmentStatus.summary(context: Context): String = when (state) {
    AlpineEnvironmentState.NOT_INSTALLED -> context.getString(R.string.linux_requirements)
    AlpineEnvironmentState.BASE_READY -> if (version == null) {
        context.getString(R.string.linux_tools_incomplete)
    } else {
        context.getString(R.string.linux_tools_upgrade_summary)
    }
    AlpineEnvironmentState.READY -> context.getString(R.string.linux_agent_ready_summary)
}

private fun AlpineEnvironmentHealth.title(context: Context): String = when {
    healthy -> context.getString(R.string.linux_health_ok)
    missingTools.isNotEmpty() -> context.resources.getQuantityString(
        R.plurals.linux_missing_core_commands,
        missingTools.size,
        missingTools.size,
    )
    !workspaceReady -> context.getString(R.string.linux_workspace_error)
    else -> context.getString(R.string.linux_health_needs_check)
}

private fun AlpineEnvironmentHealth.summary(context: Context): String {
    val details = buildList {
        if (missingTools.isNotEmpty()) {
            add(context.getString(R.string.linux_missing_tools, ListFormatter.getInstance().format(missingTools)))
        }
        add(context.getString(if (workspaceReady) R.string.linux_workspace_available else R.string.linux_workspace_unavailable))
        add(context.getString(if (sharedStorageReady) R.string.linux_sdcard_available else R.string.linux_sdcard_unavailable))
        add(context.getString(R.string.linux_space_remaining, availableBytes.toReadableSize(context)))
    }
    return ListFormatter.getInstance().format(details)
}

private fun Long.toReadableSize(context: Context): String = Formatter.formatShortFileSize(context, this)

@Composable
private fun InstallLinearProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
        height = 4.dp,
    )
}

private fun ApkAnalysisInstallProgress.progressFraction(): Float? = when (stage) {
    ApkAnalysisInstallStage.DOWNLOADING ->
        if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
    ApkAnalysisInstallStage.COMPLETE -> 1f
    else -> null
}

private fun AlpineInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    when (stage) {
        AlpineInstallStage.DOWNLOADING -> {
            if (totalBytes > 0L) {
                val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
                return context.getString(R.string.linux_progress_percent, stageName, percent)
            }
        }
        AlpineInstallStage.INSTALLING_TOOLS -> {
            if (totalPackages > 0) {
                return context.getString(
                    R.string.linux_tools_package_progress,
                    stageName,
                    currentPackage,
                    totalPackages,
                )
            }
        }
        else -> Unit
    }
    return stageName
}

private fun ApkAnalysisInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != ApkAnalysisInstallStage.DOWNLOADING || totalBytes <= 0L) {
        return stageName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    val name = when (artifactName) {
        "jadx" -> "JADX"
        "apktool" -> "Apktool"
        "smali" -> "smali"
        "baksmali" -> "baksmali"
        else -> context.getString(R.string.linux_tool)
    }
    return context.getString(R.string.linux_tool_progress_percent, stageName, name, percent)
}

private fun AlpineInstallResult.toMessage(context: Context): String = when (this) {
    AlpineInstallResult.AlreadyReady -> context.getString(R.string.linux_already_ready)
    is AlpineInstallResult.Installed -> context.getString(R.string.linux_install_complete, version)
    is AlpineInstallResult.UnsupportedAbi -> context.getString(R.string.linux_unsupported_abi, abi)
    AlpineInstallResult.RootUnavailable -> context.getString(R.string.linux_root_unavailable)
    AlpineInstallResult.BusyBoxUnavailable -> context.getString(R.string.linux_busybox_unavailable)
    AlpineInstallResult.EnvironmentUnavailable -> context.getString(R.string.linux_environment_unavailable)
    is AlpineInstallResult.Failed -> context.getString(R.string.linux_stage_failed, stage.displayName(context))
}

private fun ApkAnalysisInstallResult.toMessage(context: Context): String = when (this) {
    ApkAnalysisInstallResult.AlreadyReady -> context.getString(R.string.linux_apk_analysis_ready)
    ApkAnalysisInstallResult.EnvironmentNotReady -> context.getString(R.string.linux_base_required)
    is ApkAnalysisInstallResult.InsufficientSpace ->
        context.getString(
            R.string.linux_insufficient_space,
            requiredBytes.toReadableSize(context),
            availableBytes.toReadableSize(context),
        )
    ApkAnalysisInstallResult.Installed -> context.getString(R.string.linux_apk_analysis_installed)
    is ApkAnalysisInstallResult.Failed -> context.getString(R.string.linux_apk_stage_failed, stage.displayName(context))
}

private fun AlpineInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        AlpineInstallStage.CHECKING -> R.string.linux_stage_checking
        AlpineInstallStage.DOWNLOADING -> R.string.linux_stage_downloading
        AlpineInstallStage.EXTRACTING -> R.string.linux_stage_extracting
        AlpineInstallStage.UPDATING_INDEX -> R.string.linux_stage_updating_index
        AlpineInstallStage.INSTALLING_TOOLS -> R.string.linux_stage_installing_tools
        AlpineInstallStage.COMPLETE -> R.string.linux_stage_complete
    },
)

private fun ApkAnalysisInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        ApkAnalysisInstallStage.CHECKING -> R.string.linux_apk_stage_checking
        ApkAnalysisInstallStage.DOWNLOADING -> R.string.linux_apk_stage_downloading
        ApkAnalysisInstallStage.PREPARING -> R.string.linux_apk_stage_preparing
        ApkAnalysisInstallStage.INSTALLING_JAVA -> R.string.linux_apk_stage_installing_java
        ApkAnalysisInstallStage.ACTIVATING -> R.string.linux_apk_stage_activating
        ApkAnalysisInstallStage.VERIFYING -> R.string.linux_apk_stage_verifying
        ApkAnalysisInstallStage.COMPLETE -> R.string.linux_apk_stage_complete
    },
)
