package fuck.andes.ui
import fuck.andes.R
import androidx.compose.ui.res.stringResource

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import com.composables.icons.lucide.R as LucideR
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fuck.andes.FuckAndesApp
import fuck.andes.agent.accessibility.AccessibilityProtectionClient
import fuck.andes.agent.accessibility.AgentAccessibilityService
import fuck.andes.agent.voice.EtaVoiceInteractionService
import fuck.andes.config.PowerAssistantTarget
import fuck.andes.config.Prefs
import fuck.andes.data.repository.ProviderRepository
import fuck.andes.data.repository.RuntimeConfigRepository
import fuck.andes.systemizer.GoogleAppSystemizerInstaller
import fuck.andes.ui.components.MiuixDialogActions
import fuck.andes.ui.components.MiuixScaffoldPage
import fuck.andes.ui.navigation.AppRoute
import fuck.andes.systemizer.RootManager
import fuck.andes.systemizer.SystemizerInstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

// ── ColorOS / COUI 主色（ColorOS 16.1 Settings.apk: coui_color_*） ────────────────
// 约定：设置页圆形图标/按钮底色只使用 ColorOS 设置主色。
// 不要用 coui_color_*_variant、截图平均取样色或 Material/iOS 近似色替代，否则实心圆底会发灰或偏色。
private val ColorOSOrangeRed = Color(0xFFFF7700)
private val ColorOSRoyalBlue = Color(0xFF0066FF)
private val ColorOSVividGreen = Color(0xFF00BD13)
private val ColorOSAmberYellow = Color(0xFFFFB200)
private val ColorOSLightBlue = Color(0xFF0066FF)
private val ColorOSRed = Color(0xFFEB3B2F)
private val ColorOSPurple = Color(0xFF0066FF)
private val ColorOSSlateGray = Color(0xFF0066FF)
private val ColorOSOrange = Color(0xFFFF7700)

/**
 * 模块配置界面。
 *
 * 开关默认值由 [Prefs.Keys.BOOLEAN_DEFAULTS] 统一定义。Eta Runtime 自己消费的开关写入
 * App 本地配置；仅 Hook 消费的开关通过 RemotePreferences 提交到 LSPosed。
 */
@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var showSystemizerDialog by remember { mutableStateOf(false) }
    var installingSystemizer by remember { mutableStateOf(false) }

    // 悬浮窗权限状态：授权后从系统设置返回时（ON_RESUME）刷新。
    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    var accessibilityGranted by remember {
        mutableStateOf(isAgentAccessibilityEnabled(context))
    }
    var accessibilityProtectionEnabled by remember {
        mutableStateOf(AccessibilityProtectionClient.isEnabled(context))
    }
    var accessibilityProtectionPending by remember { mutableStateOf(false) }
    var etaAssistantActive by remember { mutableStateOf(isEtaAssistantActive(context)) }
    val openAssistantSettings: () -> Unit = {
        val failed = runCatching {
            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isFailure
        if (failed) {
            Toast.makeText(context, context.getString(R.string.settings_open_assistant_failed), Toast.LENGTH_SHORT).show()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = android.provider.Settings.canDrawOverlays(context)
                accessibilityGranted = isAgentAccessibilityEnabled(context)
                accessibilityProtectionEnabled =
                    AccessibilityProtectionClient.isEnabled(context)
                etaAssistantActive = isEtaAssistantActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Provider / Model 选中状态展示
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsState(initial = null)
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsState(initial = null)
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val providerSummary = selectedProvider?.let { provider ->
        "${provider.name} / ${selectedModel?.displayName ?: stringResource(R.string.settings_model_not_selected)}"
    } ?: stringResource(R.string.settings_not_configured)

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时保持 null，UI 禁止修改。
    var prefs by remember { mutableStateOf(Prefs.remotePreferencesForUi(FuckAndesApp.serviceInstance)) }
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    var powerAssistantTarget by remember(prefs) {
        mutableStateOf(Prefs.powerAssistantTarget(prefs))
    }
    DisposableEffect(prefs) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            if (key == Prefs.Keys.POWER_KEY_ASSISTANT_TARGET ||
                key == Prefs.Keys.POWER_KEY_TAKEOVER
            ) {
                powerAssistantTarget = Prefs.powerAssistantTarget(changedPrefs)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(Unit) {
        val listener = object : FuckAndesApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                prefs = Prefs.remotePreferencesForUi(service)
                Prefs.reconcileAgentPreferences(service)
                coroutineScope.launch {
                    RuntimeConfigRepository.ensureDefaults(service)
                }
            }
        }
        FuckAndesApp.addServiceStateListener(listener, notifyImmediately = true)
        onDispose { FuckAndesApp.removeServiceStateListener(listener) }
    }
    val powerAssistantTargets = PowerAssistantTarget.entries
    val powerAssistantItems = powerAssistantTargets.map { target ->
        DropdownItem(text = target.displayName(context))
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_set_up_7debf9),
        onBack = onBack,
    ) {
            // ── LSPosed 未连接提示 ──────────────────────────────────────
            if (prefs == null) {
                item(key = "service_warning") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        BasicComponent(
                            title = stringResource(R.string.ui_lsposed_service_is_not_connected_44734d),
                            summary = stringResource(R.string.ui_agent_and_local_tools_can_still_be_used_but_the_syst_b14479),
                        )
                    }
                }
            }

            // ── Agent ──────────────────────────────────────────────────
            item(key = "section_agent") {
                SmallTitle("Agent")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.ui_model_provider_e8c7f5),
                        summary = providerSummary,
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_cpu,
                                tint = ColorOSPurple,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ModelProviders) },
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                        key = Prefs.Keys.AGENT_THINKING_ENABLED,
                        icon = LucideR.drawable.lucide_ic_brain_circuit,
                        iconTint = ColorOSRoyalBlue,
                    )
                    PrefDivider()
                    ArrowPreference(
                        title = stringResource(R.string.ui_memory_b55ff5),
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_notebook_tabs,
                                tint = ColorOSOrange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Memory) },
                    )
                }
            }

            // ── 工具 ───────────────────────────────────────────────────
            item(key = "section_tools") {
                SmallTitle(stringResource(R.string.ui_tool_a72ef1))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_web_browsing_tools_8b6b03),
                        key = Prefs.Keys.AGENT_BROWSER_TOOLS,
                        icon = LucideR.drawable.lucide_ic_globe,
                        iconTint = ColorOSVividGreen,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_device_direct_tools_e2d595),
                        key = Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                        icon = LucideR.drawable.lucide_ic_smartphone,
                        iconTint = ColorOSVividGreen,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_allow_reading_of_sensitive_device_information_feaec0),
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                        icon = LucideR.drawable.lucide_ic_eye,
                        iconTint = ColorOSAmberYellow,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_allow_sensitive_device_operation_3d42ea),
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                        icon = LucideR.drawable.lucide_ic_shield_alert,
                        iconTint = ColorOSAmberYellow,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_terminal_file_tools_18bb43),
                        key = Prefs.Keys.AGENT_TERMINAL_TOOLS,
                        icon = LucideR.drawable.lucide_ic_file_terminal,
                        iconTint = ColorOSAmberYellow,
                    )
                    PrefDivider()
                    ArrowPreference(
                        title = stringResource(R.string.ui_linux_tool_environment_314d22),
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_container,
                                tint = ColorOSVividGreen,
                            )
                        },
                        onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                    )
                }
            }

            // ── 系统助手接管 ──────────────────────────────────────────────
            item(key = "section_assistant_takeover") {
                SmallTitle(stringResource(R.string.ui_system_assistant_takes_over_f46043))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.ui_eta_system_assistant_003e9b),
                        summary = stringResource(
                            if (etaAssistantActive) {
                                R.string.settings_default_assistant_active
                            } else {
                                R.string.settings_select_default_assistant
                            },
                        ),
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_bot,
                                tint = ColorOSRoyalBlue,
                            )
                        },
                        onClick = openAssistantSettings,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "双击电源键打开钱包（仅 ColorOS）",
                        key = Prefs.Keys.POWER_KEY_DOUBLE_PRESS_WALLET,
                        icon = LucideR.drawable.lucide_ic_power,
                        iconTint = ColorOSRoyalBlue,
                    )
                    PrefDivider()
                    var walletTarget by remember(prefs) {
                        mutableStateOf(prefs?.getString(Prefs.Keys.POWER_KEY_WALLET_TARGET, Prefs.Keys.WALLET_TARGET_GOOGLE) ?: Prefs.Keys.WALLET_TARGET_GOOGLE)
                    }
                    ArrowPreference(
                        title = "双击打开的钱包目标",
                        summary = if (walletTarget == Prefs.Keys.WALLET_TARGET_COLOROS) "一加 / ColorOS 钱包" else "谷歌钱包 (Google Wallet)",
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_power,
                                tint = ColorOSRoyalBlue,
                            )
                        },
                        onClick = {
                            val nextTarget = if (walletTarget == Prefs.Keys.WALLET_TARGET_GOOGLE) Prefs.Keys.WALLET_TARGET_COLOROS else Prefs.Keys.WALLET_TARGET_GOOGLE
                            val targetPrefs = prefs ?: return@ArrowPreference
                            if (putStringSync(targetPrefs, Prefs.Keys.POWER_KEY_WALLET_TARGET, nextTarget)) {
                                walletTarget = nextTarget
                                val label = if (nextTarget == Prefs.Keys.WALLET_TARGET_COLOROS) "一加 / ColorOS 钱包" else "谷歌钱包"
                                Toast.makeText(context.applicationContext, "已切换为：$label", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    PrefDivider()
                    WindowSpinnerPreference(
                        title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
                        summary = powerAssistantTarget.displayName(context),
                        items = powerAssistantItems,
                        selectedIndex = powerAssistantTargets.indexOf(powerAssistantTarget),
                        onSelectedIndexChange = { index ->
                            val target = powerAssistantTargets.getOrNull(index)
                                ?: return@WindowSpinnerPreference
                            val targetPrefs = prefs ?: return@WindowSpinnerPreference
                            if (putStringSync(
                                    prefs = targetPrefs,
                                    key = Prefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                                    value = target.persistedValue,
                                )
                            ) {
                                powerAssistantTarget = target
                            } else {
                                Toast.makeText(
                                    context.applicationContext,
                                    context.getString(R.string.settings_write_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        dialogButtonString = stringResource(R.string.action_cancel),
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_power,
                                tint = ColorOSOrangeRed,
                            )
                        },
                        enabled = prefs != null,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_automatically_set_default_assistant_f86963),
                        summary = stringResource(R.string.ui_valid_only_for_gemini_and_eta_d5b63d),
                        key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                        icon = LucideR.drawable.lucide_ic_settings_2,
                        iconTint = ColorOSVividGreen,
                    )
                }
            }

            // ── 厂商助手兼容入口 ──────────────────────────────────────────
            item(key = "section_oem_assistant_compatibility") {
                SmallTitle(stringResource(R.string.ui_xiaobu_xiaoai_compatible_entrance_ae918a))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_enable_vendor_assistant_custom_models_c8e465),
                        key = Prefs.Keys.AGENT_CUSTOM_MODEL,
                        icon = LucideR.drawable.lucide_ic_cpu,
                        iconTint = ColorOSOrangeRed,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_only_take_over_with_agent_prefix_d17556),
                        key = Prefs.Keys.AGENT_REQUIRE_PREFIX,
                        icon = LucideR.drawable.lucide_ic_message_square_code,
                        iconTint = ColorOSAmberYellow,
                    )
                }
            }

            // ── Gemini ─────────────────────────────────────────────────
            item(key = "section_gemini") {
                SmallTitle("Gemini")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_maintain_hey_google_detection_after_screen_rest_9d6877),
                        key = Prefs.Keys.HOTWORD_SELF_HEAL,
                        icon = LucideR.drawable.lucide_ic_ear,
                        iconTint = ColorOSAmberYellow,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_lock_screen_evokes_automatic_voice_input_1cde18),
                        key = Prefs.Keys.LOCKSCREEN_VOICE_COMMAND,
                        icon = LucideR.drawable.lucide_ic_lock,
                        iconTint = ColorOSRed,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_bright_screen_evokes_automatic_voice_input_4358fe),
                        key = Prefs.Keys.SCREEN_ON_VOICE_COMMAND,
                        icon = LucideR.drawable.lucide_ic_mic,
                        iconTint = ColorOSLightBlue,
                    )
                    PrefDivider()
                    ArrowPreference(
                        title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_package_check,
                                tint = ColorOSVividGreen,
                            )
                        },
                        enabled = !installingSystemizer,
                        holdDownState = showSystemizerDialog,
                        onClick = {
                            if (!installingSystemizer) {
                                showSystemizerDialog = true
                            }
                        },
                    )
                }
            }

            // ── 一圈即搜 ────────────────────────────────────────────────
            item(key = "section_circle_to_search") {
                SmallTitle(stringResource(R.string.ui_search_in_one_turn_179584))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_long_press_on_the_gesture_bar_triggers_a_circle_to_s_b80117),
                        key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                        icon = LucideR.drawable.lucide_ic_panel_bottom,
                        iconTint = ColorOSRoyalBlue,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_long_press_with_two_fingers_to_trigger_a_circle_sear_ab597a),
                        key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                        icon = LucideR.drawable.lucide_ic_hand,
                        iconTint = ColorOSLightBlue,
                    )
                }
            }

            // ── 通用 ────────────────────────────────────────────────────
            item(key = "section_general") {
                SmallTitle(stringResource(R.string.settings_general))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.appearance_title),
                        summary = stringResource(R.string.appearance_summary),
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_palette,
                                tint = ColorOSRoyalBlue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.AppearanceSettings) },
                    )
                }
            }

            // ── 权限 ────────────────────────────────────────────────────
            item(key = "section_permissions") {
                SmallTitle(stringResource(R.string.ui_permissions_560165))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.ui_floating_window_permissions_076b77),
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_layers,
                                tint = ColorOSOrangeRed,
                            )
                        },
                        endActions = {
                            Text(
                                text = stringResource(
                                    if (overlayGranted) R.string.status_authorized else R.string.status_unauthorized,
                                ),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = if (overlayGranted) {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                } else {
                                    ColorOSOrangeRed
                                },
                            )
                        },
                        onClick = {
                            if (!overlayGranted) {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            }
                        },
                    )
                    PrefDivider()
                    ArrowPreference(
                        title = stringResource(R.string.ui_accessibility_enhancement_tools_8fd257),
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_accessibility,
                                tint = ColorOSRoyalBlue,
                            )
                        },
                        endActions = {
                            val enabled = accessibilityGranted || AgentAccessibilityService.isAvailable()
                            Text(
                                text = stringResource(
                                    if (enabled) R.string.status_enabled else R.string.status_disabled,
                                ),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = if (enabled) {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                } else {
                                    ColorOSRoyalBlue
                                },
                            )
                        },
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                )
                            }
                        },
                    )
                    PrefDivider()
                    SwitchPreference(
                        title = stringResource(R.string.ui_enforce_accessibility_55e838),
                        checked = accessibilityProtectionEnabled,
                        onCheckedChange = { enabled ->
                            if (accessibilityProtectionPending) {
                                return@SwitchPreference
                            }
                            accessibilityProtectionPending = true
                            AccessibilityProtectionClient.setEnabled(
                                context = context,
                                enabled = enabled,
                            ) { result ->
                                accessibilityProtectionPending = false
                                accessibilityProtectionEnabled = result.enabled
                                accessibilityGranted = isAgentAccessibilityEnabled(context)
                                val failureMessage = when (result.status) {
                                    AccessibilityProtectionClient.ControlStatus.APPLIED -> null
                                    AccessibilityProtectionClient.ControlStatus.UNAVAILABLE ->
                                        context.getString(R.string.accessibility_protection_unavailable)
                                    AccessibilityProtectionClient.ControlStatus.REJECTED ->
                                        context.getString(R.string.accessibility_protection_rejected)
                                }
                                if (failureMessage != null) {
                                    Toast.makeText(
                                        context.applicationContext,
                                        failureMessage,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_shield_check,
                                tint = ColorOSVividGreen,
                            )
                        },
                        enabled = !accessibilityProtectionPending,
                    )
                }
            }

            // ── 关于 ────────────────────────────────────────────────────
            item(key = "section_about") {
                SmallTitle(stringResource(R.string.ui_about_bed172))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.ui_source_code_740296),
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_github,
                                tint = ColorOSPurple,
                            )
                        },
                        endActions = {
                            Text(
                                text = "GitHub",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/Mangi-11/Eta"),
                            )
                            context.startActivity(intent)
                        },
                    )
                }
            }
        }

        SystemizerConfirmDialog(
            show = showSystemizerDialog,
            installing = installingSystemizer,
            onDismissRequest = {
                if (!installingSystemizer) {
                    showSystemizerDialog = false
                }
            },
            onConfirm = {
                if (installingSystemizer) return@SystemizerConfirmDialog
                showSystemizerDialog = false
                installingSystemizer = true
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        GoogleAppSystemizerInstaller(context.applicationContext).install()
                    }
                    installingSystemizer = false
                    Toast.makeText(
                        context.applicationContext,
                        result.toToastMessage(context),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
}

// ── 带色彩的圆形图标（ColorOS 风格：圆形背景 + 纯白图标） ────────────────────────────────

@Composable
private fun TintedIcon(
    icon: Int,
    tint: Color,
) {
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

// ── Card 内分隔线 ───────────────────────────────────────────────────────────

@Composable
private fun PrefDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(
            // 对齐 BasicComponent 内文字起始位置：
            // insideMargin(16) + 图标 padding end(12) + 圆形宽度(32) = 60dp
            start = 60.dp,
        ),
    )
}

// ── 系统化确认对话框 ─────────────────────────────────────────────────────────

@Composable
private fun SystemizerConfirmDialog(
    show: Boolean,
    installing: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
        summary = stringResource(R.string.ui_system_applications_have_voice_wake_up_permissions_f_0190f2),
        onDismissRequest = onDismissRequest,
    ) {
        MiuixDialogActions(
            confirmText = if (installing) {
                stringResource(R.string.status_processing)
            } else {
                stringResource(R.string.action_confirm)
            },
            cancelEnabled = !installing,
            confirmEnabled = !installing,
            onCancel = onDismissRequest,
            onConfirm = onConfirm,
        )
    }
}

// ── 带图标的布尔开关 ─────────────────────────────────────────────────────────

/**
 * 单个布尔开关：状态随 [prefs]/[key] 变化重读，切换时同步写入。
 *
 * 配置来源由调用方按能力边界传入。Hook 开关仍可能因 LSPosed 未连接而禁用；Agent
 * Runtime 开关始终使用 App 本地配置。
 */
@Composable
private fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: Int,
    iconTint: Color,
) {
    val enabled = prefs != null
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: default)
    }
    DisposableEffect(prefs, key) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == key) {
                checked = changedPrefs.getBoolean(key, default)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = { value ->
            // 同步提交；RemotePreferences.commit() 失败（binder 提交失败）时回滚 UI 状态，
            // 避免 UI 显示已切换而 hook 进程实际未收到。
            val targetPrefs = prefs ?: return@SwitchPreference
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
                if (key in Prefs.Keys.LOCAL_AGENT_KEYS) {
                    Prefs.reconcileAgentPreferences(FuckAndesApp.serviceInstance)
                }
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        startAction = {
            TintedIcon(icon = icon, tint = iconTint)
        },
        enabled = enabled,
    )
}

/**
 * 同步写入布尔值。RemotePreferences 的 [commit] 先更新本进程 map 再同步等待 binder 提交，
 * 失败（binder RemoteException）返回 false 但本进程 map 已被改写——此时 hook 进程收不到新值。
 * 返回是否提交成功，供调用方决定是否更新 UI。
 */
private fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean
): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)

private fun putStringSync(
    prefs: SharedPreferences,
    key: String,
    value: String
): Boolean =
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

private fun PowerAssistantTarget.displayName(context: Context): String =
    when (this) {
        PowerAssistantTarget.OEM -> context.getString(R.string.power_assistant_system_default)
        PowerAssistantTarget.GEMINI -> "Gemini"
        PowerAssistantTarget.ETA -> "Eta"
    }

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java
    ).flattenToString()
    val enabledServices = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun isEtaAssistantActive(context: Context): Boolean =
    VoiceInteractionService.isActiveService(
        context,
        ComponentName(context, EtaVoiceInteractionService::class.java),
    )

private fun SystemizerInstallResult.toToastMessage(context: Context): String =
    when (this) {
        SystemizerInstallResult.AlreadySystemized -> context.getString(R.string.systemizer_already_system)
        SystemizerInstallResult.GoogleAppMissing -> context.getString(R.string.systemizer_google_missing)
        SystemizerInstallResult.UnsupportedRootManager -> context.getString(R.string.systemizer_root_manager_missing)
        SystemizerInstallResult.KernelSuMetamoduleMissing -> context.getString(R.string.systemizer_metamodule_missing)
        is SystemizerInstallResult.RootPermissionUnavailable -> when (rootManager) {
            RootManager.KERNEL_SU -> context.getString(R.string.systemizer_grant_kernelsu)
            RootManager.MAGISK -> context.getString(R.string.systemizer_grant_magisk)
            RootManager.UNSUPPORTED -> context.getString(R.string.systemizer_root_denied)
        }
        is SystemizerInstallResult.InstalledRebootRequired -> context.getString(R.string.systemizer_installed)
        is SystemizerInstallResult.Failed -> commandOutput
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.let { "$message：$it" }
            ?: message
    }
