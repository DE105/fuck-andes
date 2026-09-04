package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentInstaller
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentState
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentStatus
import io.github.mangi.eta.agent.terminal.AlpineInstallProgress
import io.github.mangi.eta.agent.terminal.AlpineInstallResult
import io.github.mangi.eta.agent.terminal.AlpineInstallStage
import io.github.mangi.eta.agent.terminal.ApkAnalysisInstallProgress
import io.github.mangi.eta.agent.terminal.ApkAnalysisInstallResult
import io.github.mangi.eta.agent.terminal.ApkAnalysisInstallStage
import io.github.mangi.eta.agent.terminal.DebianEnvironmentInstaller
import io.github.mangi.eta.agent.terminal.DebianEnvironmentState
import io.github.mangi.eta.agent.terminal.DebianEnvironmentStatus
import io.github.mangi.eta.agent.terminal.DebianInstallProgress
import io.github.mangi.eta.agent.terminal.DebianInstallResult
import io.github.mangi.eta.agent.terminal.DebianInstallStage
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxApkAnalysisInstaller
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.LinuxPackageProfile
import io.github.mangi.eta.agent.terminal.LinuxPackageProfileInstaller
import io.github.mangi.eta.agent.terminal.LinuxPackageProfiles
import io.github.mangi.eta.agent.terminal.PackageProfileInstallProgress
import io.github.mangi.eta.agent.terminal.PackageProfileInstallResult
import io.github.mangi.eta.agent.terminal.PackageProfileInstallStage
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.ui.app.KimiWebLaunchResult
import io.github.mangi.eta.ui.app.KimiWebLauncher
import io.github.mangi.eta.ui.app.launchForegroundExecution
import io.github.mangi.eta.ui.app.message
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.app.rememberExecutionNotificationRequest
import io.github.mangi.eta.ui.components.IconTintGreen
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import com.composables.icons.lucide.R as LucideR

private enum class InstallTarget {
    BASE,
    TOOLS,
    APK_ANALYSIS,
    PYTHON,
    NODE,
    SSH,
    KIMI,
}

private data class PackageProfileUi(
    val target: InstallTarget,
    val profile: LinuxPackageProfile,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    @param:StringRes val readyRes: Int,
    @param:StringRes val debianSummaryRes: Int = summaryRes,
    @param:StringRes val debianReadyRes: Int = readyRes,
)

private val packageProfileUis = listOf(
    PackageProfileUi(
        target = InstallTarget.PYTHON,
        profile = LinuxPackageProfiles.PYTHON,
        titleRes = R.string.linux_python_tools,
        summaryRes = R.string.linux_python_tools_summary,
        readyRes = R.string.linux_python_tools_ready,
        debianSummaryRes = R.string.linux_python_tools_summary_debian,
        debianReadyRes = R.string.linux_python_tools_ready_debian,
    ),
    PackageProfileUi(
        target = InstallTarget.NODE,
        profile = LinuxPackageProfiles.NODE,
        titleRes = R.string.linux_node_tools,
        summaryRes = R.string.linux_node_tools_summary,
        readyRes = R.string.linux_node_tools_ready,
    ),
    PackageProfileUi(
        target = InstallTarget.SSH,
        profile = LinuxPackageProfiles.SSH,
        titleRes = R.string.linux_ssh_tools,
        summaryRes = R.string.linux_ssh_tools_summary,
        readyRes = R.string.linux_ssh_tools_ready,
    ),
    PackageProfileUi(
        target = InstallTarget.KIMI,
        profile = LinuxPackageProfiles.KIMI,
        titleRes = R.string.linux_kimi_tools,
        summaryRes = R.string.linux_kimi_tools_summary,
        readyRes = R.string.linux_kimi_tools_ready,
    ),
)

@Composable
internal fun LinuxEnvironmentScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val appContext = context.applicationContext
    val capabilities = rememberDeviceCapabilities()
    val requestExecutionNotifications = rememberExecutionNotificationRequest()
    val coroutineScope = rememberCoroutineScope()
    val selectionFlow = remember(appContext) {
        LinuxEnvironmentSettingsRepository.selectedFlow(appContext)
    }
    val selectedDistribution by selectionFlow.collectAsState(
        initial = LinuxEnvironmentSettingsRepository.current(appContext),
    )
    val backendFlow = remember(appContext, selectedDistribution, capabilities.root.isGranted) {
        LinuxEnvironmentSettingsRepository.backendFlow(appContext, selectedDistribution)
    }
    val backend by backendFlow.collectAsState(
        initial = LinuxEnvironmentSettingsRepository.backend(appContext, selectedDistribution),
    )
    val requiresRoot = backend == LinuxExecutionBackend.CHROOT && !capabilities.root.isGranted
    val installer = remember(appContext, backend) { AlpineEnvironmentInstaller(appContext) }
    val debianInstaller = remember(appContext, backend) { DebianEnvironmentInstaller(appContext) }
    val apkAnalysisInstaller = remember(appContext, selectedDistribution, backend) {
        LinuxApkAnalysisInstaller(appContext, selectedDistribution)
    }
    val profileInstallers = remember(appContext, selectedDistribution, backend) {
        packageProfileUis.associate { profileUi ->
            profileUi.target to LinuxPackageProfileInstaller(
                context = appContext,
                distribution = selectedDistribution,
                profile = profileUi.profile,
            )
        }
    }
    var status by remember(installer) { mutableStateOf(installer.status()) }
    var debianStatus by remember(debianInstaller) { mutableStateOf(debianInstaller.status()) }
    var busyTarget by remember { mutableStateOf<InstallTarget?>(null) }
    var progress by remember { mutableStateOf<AlpineInstallProgress?>(null) }
    var debianProgress by remember { mutableStateOf<DebianInstallProgress?>(null) }
    var profileProgressSummary by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var profileReady by remember(selectedDistribution, backend) {
        mutableStateOf(packageProfileUis.associate { it.target to profileInstallers.getValue(it.target).isReady() })
    }
    var apkAnalysisReady by remember(selectedDistribution, backend) {
        mutableStateOf(apkAnalysisInstaller.isReady())
    }
    var apkAnalysisProgress by remember { mutableStateOf<ApkAnalysisInstallProgress?>(null) }
    var kimiWebLaunching by remember { mutableStateOf(false) }
    var kimiWebRunning by remember(selectedDistribution, backend) { mutableStateOf(false) }
    val kimiWebLauncher = remember(appContext) {
        KimiWebLauncher(
            context = appContext,
            daemonSupervisor = DetachedTaskSupervisor(
                logger = AndroidAgentLogger,
                recordsFile = DetachedTaskSupervisor.defaultRecordsFile(appContext),
                linuxRootfsPathProvider = { environment ->
                    environment.linuxDistribution?.let { distribution ->
                        LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
                    }
                },
                linuxSharedMountsProvider = { SharedFolderMounts.current() },
            ),
        )
    }
    LaunchedEffect(selectedDistribution, backend, kimiWebLaunching) {
        if (!kimiWebLaunching) {
            kimiWebRunning = kimiWebLauncher.status(selectedDistribution.terminalEnvironment).running
        }
    }
    val selectedBaseReady = when (selectedDistribution) {
        LinuxDistribution.ALPINE -> status.state != AlpineEnvironmentState.NOT_INSTALLED
        LinuxDistribution.DEBIAN -> debianStatus.state != DebianEnvironmentState.NOT_INSTALLED
    }
    val selectedToolsReady = when (selectedDistribution) {
        LinuxDistribution.ALPINE -> status.state == AlpineEnvironmentState.READY
        LinuxDistribution.DEBIAN -> debianStatus.state == DebianEnvironmentState.READY
    }

    fun launchInstallation(block: suspend () -> Unit) {
        val operation: suspend () -> Unit = {
            try {
                block()
            } finally {
                progress = null
                debianProgress = null
                profileProgressSummary = null
                apkAnalysisProgress = null
                busyTarget = null
            }
        }
        if (backend == LinuxExecutionBackend.PROOT) {
            requestExecutionNotifications()
            coroutineScope.launchForegroundExecution(
                context = appContext,
                onUnavailable = {
                    busyTarget = null
                    resultMessage = context.getString(R.string.capability_background_failed)
                },
                block = operation,
            )
        } else {
            coroutineScope.launch { operation() }
        }
    }

    fun installBase() {
        if (busyTarget != null || requiresRoot) return
        busyTarget = InstallTarget.BASE
        resultMessage = null
        launchInstallation {
            resultMessage = when (selectedDistribution) {
                LinuxDistribution.ALPINE -> installer.installBase { update ->
                    withContext(Dispatchers.Main.immediate) { progress = update }
                }.toMessage(context)
                LinuxDistribution.DEBIAN -> debianInstaller.installBase { update ->
                    withContext(Dispatchers.Main.immediate) { debianProgress = update }
                }.toMessage(context)
            }
            status = installer.status()
            debianStatus = debianInstaller.status()
            progress = null
            debianProgress = null
            busyTarget = null
        }
    }

    fun installTools() {
        if (busyTarget != null || requiresRoot) return
        busyTarget = InstallTarget.TOOLS
        resultMessage = null
        launchInstallation {
            resultMessage = when (selectedDistribution) {
                LinuxDistribution.ALPINE -> installer.installTools { update ->
                    withContext(Dispatchers.Main.immediate) { progress = update }
                }.toMessage(context)
                LinuxDistribution.DEBIAN -> debianInstaller.installTools { update ->
                    withContext(Dispatchers.Main.immediate) { debianProgress = update }
                }.toMessage(context)
            }
            status = installer.status()
            debianStatus = debianInstaller.status()
            profileReady = packageProfileUis.associate {
                it.target to profileInstallers.getValue(it.target).isReady()
            }
            apkAnalysisReady = apkAnalysisInstaller.isReady()
            progress = null
            debianProgress = null
            busyTarget = null
        }
    }

    /** Kimi 就绪后按钮变为启动 Web UI：守护任务常驻 kimi web，解析地址后拉起浏览器。 */
    fun launchKimiWeb() {
        if (kimiWebLaunching || requiresRoot) return
        requestExecutionNotifications()
        kimiWebLaunching = true
        resultMessage = null
        coroutineScope.launch {
            val result = kimiWebLauncher.launch(selectedDistribution.terminalEnvironment)
            kimiWebLaunching = false
            if (result is KimiWebLaunchResult.Failed) {
                resultMessage = result.message(context)
            }
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_linux_tool_environment_314d22),
        onBack = onBack,
    ) {
        item(key = "distribution-title") {
            SmallTitle(stringResource(R.string.linux_distribution_title))
        }
        item(key = "distribution-card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                RadioButtonPreference(
                    title = stringResource(R.string.linux_distribution_alpine),
                    summary = stringResource(R.string.linux_distribution_alpine_summary),
                    selected = selectedDistribution == LinuxDistribution.ALPINE,
                    enabled = busyTarget == null && !kimiWebLaunching,
                    onClick = {
                        resultMessage = null
                        coroutineScope.launch {
                            LinuxEnvironmentSettingsRepository.select(LinuxDistribution.ALPINE)
                        }
                    },
                )
                RadioButtonPreference(
                    title = stringResource(R.string.linux_distribution_debian),
                    summary = stringResource(R.string.linux_distribution_debian_summary),
                    selected = selectedDistribution == LinuxDistribution.DEBIAN,
                    enabled = busyTarget == null && !kimiWebLaunching,
                    onClick = {
                        resultMessage = null
                        coroutineScope.launch {
                            LinuxEnvironmentSettingsRepository.select(LinuxDistribution.DEBIAN)
                        }
                    },
                )
            }
        }

        item(key = "backend-title") { SmallTitle(stringResource(R.string.capability_linux_backend)) }
        item(key = "backend") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                RadioButtonPreference(
                    title = stringResource(R.string.capability_linux_proot),
                    summary = stringResource(R.string.capability_linux_proot_summary),
                    selected = backend == LinuxExecutionBackend.PROOT,
                    enabled = busyTarget == null && !kimiWebLaunching,
                    onClick = { coroutineScope.launch {
                        LinuxEnvironmentSettingsRepository.selectBackend(selectedDistribution, LinuxExecutionBackend.PROOT)
                    } },
                )
                if (capabilities.root.isGranted || backend == LinuxExecutionBackend.CHROOT) {
                    RadioButtonPreference(
                        title = stringResource(R.string.capability_linux_chroot),
                        summary = stringResource(R.string.capability_linux_chroot_summary),
                        selected = backend == LinuxExecutionBackend.CHROOT,
                        enabled = busyTarget == null && !kimiWebLaunching && capabilities.root.isGranted,
                        onClick = { coroutineScope.launch {
                            LinuxEnvironmentSettingsRepository.selectBackend(selectedDistribution, LinuxExecutionBackend.CHROOT)
                        } },
                    )
                }
                if (requiresRoot) {
                    ArrowPreference(
                        title = stringResource(R.string.capability_enhancements),
                        summary = stringResource(R.string.capability_linux_root_lost),
                        onClick = { onNavigate(AppRoute.SystemEnhance) },
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.capability_workspace),
                    summary = stringResource(R.string.capability_workspace_summary),
                    onClick = { onNavigate(AppRoute.Workspace) },
                )
            }
        }

        item(key = "status-title") { SmallTitle(stringResource(R.string.ui_environmental_status_5b32a1)) }
        item(key = "status-card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = when (selectedDistribution) {
                        LinuxDistribution.ALPINE -> status.title(context)
                        LinuxDistribution.DEBIAN -> debianStatus.title(context)
                    },
                    summary = when (selectedDistribution) {
                        LinuxDistribution.ALPINE -> progress?.summary(context) ?: status.summary(context)
                        LinuxDistribution.DEBIAN -> debianProgress?.summary(context) ?: debianStatus.summary(context)
                    },
                    endActions = {
                        TextButton(
                            text = when {
                                busyTarget == InstallTarget.BASE || busyTarget == InstallTarget.TOOLS ->
                                    context.getString(R.string.linux_installing)
                                !selectedBaseReady -> context.getString(R.string.linux_install_base)
                                !selectedToolsReady -> context.getString(R.string.linux_install_base_tools)
                                else -> context.getString(R.string.linux_ready)
                            },
                            enabled = busyTarget == null && !requiresRoot && !selectedToolsReady,
                            onClick = {
                                if (selectedBaseReady) installTools() else installBase()
                            },
                        )
                    },
                )
            }
        }

        if (selectedBaseReady) {
            item(key = "shared-folders-title") { SmallTitle(stringResource(R.string.shared_folders_title)) }
            item(key = "shared-folders-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.shared_folders_entry_title),
                        summary = stringResource(R.string.shared_folders_entry_summary),
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_folder_open,
                                tint = IconTintGreen,
                            )
                        },
                        onClick = { onNavigate(AppRoute.SharedFolders) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.linux_files_entry_title),
                        summary = stringResource(R.string.linux_files_entry_summary),
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_file_text,
                                tint = IconTintGreen,
                            )
                        },
                        onClick = { onNavigate(AppRoute.LinuxFiles(selectedDistribution.wireName)) },
                    )
                }
            }
        }

        if (selectedToolsReady) {
            item(key = "optional-tools-title") { SmallTitle(stringResource(R.string.ui_optional_tools_3097d6)) }
            item(key = "optional-tools-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    packageProfileUis.forEach { profileUi ->
                        val ready = profileReady[profileUi.target] == true
                        val isKimi = profileUi.target == InstallTarget.KIMI
                        val summaryRes = if (selectedDistribution == LinuxDistribution.DEBIAN) {
                            profileUi.debianSummaryRes
                        } else {
                            profileUi.summaryRes
                        }
                        val readyRes = if (selectedDistribution == LinuxDistribution.DEBIAN) {
                            profileUi.debianReadyRes
                        } else {
                            profileUi.readyRes
                        }
                        BasicComponent(
                            title = stringResource(profileUi.titleRes),
                            summary = if (busyTarget == profileUi.target) {
                                profileProgressSummary ?: stringResource(summaryRes)
                            } else if (ready) {
                                stringResource(readyRes)
                            } else {
                                stringResource(summaryRes)
                            },
                            endActions = {
                                if (isKimi && kimiWebRunning) {
                                    TextButton(
                                        text = stringResource(R.string.action_stop),
                                        enabled = !kimiWebLaunching && !requiresRoot,
                                        onClick = {
                                            coroutineScope.launch {
                                                val stopped = kimiWebLauncher.stop(selectedDistribution.terminalEnvironment)
                                                kimiWebRunning = !stopped
                                            }
                                        },
                                    )
                                }
                                TextButton(
                                    text = when {
                                        isKimi && ready -> stringResource(
                                            if (kimiWebLaunching) {
                                                R.string.linux_kimi_web_starting
                                            } else if (kimiWebRunning) {
                                                R.string.action_open
                                            } else {
                                                R.string.linux_kimi_web_launch
                                            },
                                        )
                                        ready -> stringResource(R.string.linux_installed)
                                        busyTarget == profileUi.target -> stringResource(R.string.linux_installing)
                                        else -> stringResource(R.string.linux_install)
                                    },
                                    enabled = !requiresRoot && if (isKimi && ready) {
                                        !kimiWebLaunching && busyTarget == null
                                    } else {
                                        busyTarget == null && !ready
                                    },
                                    onClick = {
                                        if (isKimi && ready) {
                                            launchKimiWeb()
                                            return@TextButton
                                        }
                                        if (busyTarget != null || ready) return@TextButton
                                        busyTarget = profileUi.target
                                        resultMessage = null
                                        val profileTitle = context.getString(profileUi.titleRes)
                                        launchInstallation {
                                            val profileInstaller = profileInstallers.getValue(profileUi.target)
                                            val result = profileInstaller.install { update ->
                                                withContext(Dispatchers.Main.immediate) {
                                                    profileProgressSummary = update.summary(context, profileTitle)
                                                }
                                            }
                                            profileReady = profileReady +
                                                (profileUi.target to profileInstaller.isReady())
                                            profileProgressSummary = null
                                            busyTarget = null
                                            resultMessage = result.toMessage(context, profileTitle)
                                        }
                                    },
                                )
                            },
                        )
                    }
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
                                    busyTarget == InstallTarget.APK_ANALYSIS -> context.getString(R.string.linux_installing)
                                    else -> context.getString(R.string.linux_install)
                                },
                                enabled = busyTarget == null && !requiresRoot && !apkAnalysisReady,
                                onClick = {
                                    if (busyTarget != null || apkAnalysisReady) return@TextButton
                                    busyTarget = InstallTarget.APK_ANALYSIS
                                    resultMessage = null
                                    launchInstallation {
                                        val result = apkAnalysisInstaller.install { update ->
                                            withContext(Dispatchers.Main.immediate) {
                                                apkAnalysisProgress = update
                                            }
                                        }
                                        apkAnalysisReady = apkAnalysisInstaller.isReady()
                                        apkAnalysisProgress = null
                                        busyTarget = null
                                        resultMessage = result.toMessage(context)
                                    }
                                },
                            )
                        },
                    )
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
    }
}

private fun AlpineEnvironmentStatus.title(context: Context): String = when (state) {
    AlpineEnvironmentState.NOT_INSTALLED -> context.getString(R.string.linux_not_installed)
    AlpineEnvironmentState.BASE_READY -> context.getString(R.string.linux_base_ready)
    AlpineEnvironmentState.READY -> context.getString(R.string.linux_alpine_ready, version.orEmpty()).trim()
}

private fun AlpineEnvironmentStatus.summary(context: Context): String = when (state) {
    AlpineEnvironmentState.NOT_INSTALLED -> context.getString(R.string.linux_requirements)
    AlpineEnvironmentState.BASE_READY -> context.getString(R.string.linux_tools_incomplete)
    AlpineEnvironmentState.READY -> context.getString(R.string.linux_agent_ready_summary)
}

private fun DebianEnvironmentStatus.title(context: Context): String = when (state) {
    DebianEnvironmentState.NOT_INSTALLED -> context.getString(R.string.linux_debian_not_installed)
    DebianEnvironmentState.BASE_READY -> context.getString(R.string.linux_debian_base_ready)
    DebianEnvironmentState.READY -> context.getString(R.string.linux_debian_ready, version.orEmpty()).trim()
}

private fun DebianEnvironmentStatus.summary(context: Context): String = when (state) {
    DebianEnvironmentState.NOT_INSTALLED -> context.getString(R.string.linux_debian_requirements)
    DebianEnvironmentState.BASE_READY -> context.getString(R.string.linux_debian_tools_incomplete)
    DebianEnvironmentState.READY -> context.getString(R.string.linux_debian_agent_ready_summary)
}

private fun Long.toReadableSize(context: Context): String = Formatter.formatShortFileSize(context, this)

private fun AlpineInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != AlpineInstallStage.DOWNLOADING || totalBytes <= 0L) {
        return stageName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    return context.getString(R.string.linux_progress_percent, stageName, percent)
}

private fun DebianInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != DebianInstallStage.DOWNLOADING || totalBytes <= 0L) return stageName
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    return context.getString(R.string.linux_progress_percent, stageName, percent)
}

private fun PackageProfileInstallProgress.summary(context: Context, profileTitle: String): String =
    when (stage) {
        PackageProfileInstallStage.CHECKING -> context.getString(R.string.linux_profile_stage_checking)
        PackageProfileInstallStage.DOWNLOADING -> if (totalBytes > 0L) {
            context.getString(
                R.string.linux_profile_stage_downloading_percent,
                profileTitle,
                (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L),
            )
        } else {
            context.getString(R.string.linux_profile_stage_downloading, profileTitle)
        }
        PackageProfileInstallStage.INSTALLING ->
            context.getString(R.string.linux_profile_stage_installing, profileTitle)
        PackageProfileInstallStage.COMPLETE -> context.getString(R.string.linux_profile_stage_complete)
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
    is AlpineInstallResult.BaseInstalled -> context.getString(R.string.linux_base_install_complete, version)
    is AlpineInstallResult.ToolsInstalled -> context.getString(R.string.linux_install_complete, version)
    AlpineInstallResult.BaseNotInstalled -> context.getString(R.string.linux_base_required)
    is AlpineInstallResult.UnsupportedAbi -> context.getString(R.string.linux_unsupported_abi, abi)
    AlpineInstallResult.RootUnavailable -> context.getString(R.string.linux_root_unavailable)
    AlpineInstallResult.BusyBoxUnavailable -> context.getString(R.string.linux_busybox_unavailable)
    AlpineInstallResult.EnvironmentUnavailable -> context.getString(R.string.linux_environment_unavailable)
    is AlpineInstallResult.Failed -> message ?: context.getString(R.string.linux_stage_failed, stage.displayName(context))
}

private fun DebianInstallResult.toMessage(context: Context): String = when (this) {
    DebianInstallResult.AlreadyReady -> context.getString(R.string.linux_debian_already_ready)
    is DebianInstallResult.BaseInstalled -> context.getString(R.string.linux_debian_base_install_complete, version)
    is DebianInstallResult.ToolsInstalled -> context.getString(R.string.linux_debian_install_complete, version)
    DebianInstallResult.BaseNotInstalled -> context.getString(R.string.linux_base_required)
    is DebianInstallResult.UnsupportedAbi -> context.getString(R.string.linux_unsupported_abi, abi)
    DebianInstallResult.RootUnavailable -> context.getString(R.string.linux_root_unavailable)
    DebianInstallResult.BusyBoxUnavailable -> context.getString(R.string.linux_busybox_unavailable)
    DebianInstallResult.EnvironmentUnavailable -> context.getString(R.string.linux_environment_unavailable)
    is DebianInstallResult.Failed -> message ?: context.getString(R.string.linux_stage_failed, stage.displayName(context))
}

private fun PackageProfileInstallResult.toMessage(
    context: Context,
    profileTitle: String,
): String = when (this) {
    PackageProfileInstallResult.AlreadyReady ->
        context.getString(R.string.linux_profile_already_ready, profileTitle)
    PackageProfileInstallResult.EnvironmentNotReady -> context.getString(R.string.linux_base_required)
    is PackageProfileInstallResult.DependencyMissing -> {
        val dependencyTitle = packageProfileUis
            .firstOrNull { it.profile.id == profileId }
            ?.let { context.getString(it.titleRes) }
            ?: profileId
        context.getString(R.string.linux_profile_dependency_missing, dependencyTitle)
    }
    PackageProfileInstallResult.Installed ->
        context.getString(R.string.linux_profile_installed, profileTitle)
    is PackageProfileInstallResult.Failed -> context.getString(
        R.string.linux_profile_stage_failed,
        PackageProfileInstallProgress(stage).summary(context, profileTitle),
    )
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
        AlpineInstallStage.INSTALLING_TOOLS -> R.string.linux_stage_installing_tools
        AlpineInstallStage.COMPLETE -> R.string.linux_stage_complete
    },
)

private fun DebianInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        DebianInstallStage.CHECKING -> R.string.linux_stage_checking
        DebianInstallStage.DOWNLOADING -> R.string.linux_stage_downloading
        DebianInstallStage.EXTRACTING -> R.string.linux_stage_extracting
        DebianInstallStage.INSTALLING_TOOLS -> R.string.linux_stage_installing_tools
        DebianInstallStage.COMPLETE -> R.string.linux_stage_complete
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

@Composable
private fun TintedIcon(icon: Int, tint: Color) {
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(32.dp)
            .background(tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Color.White,
        )
    }
}
