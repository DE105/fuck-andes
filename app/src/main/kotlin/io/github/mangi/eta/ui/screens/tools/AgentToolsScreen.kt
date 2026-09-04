package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.actualToolName
import io.github.mangi.eta.ui.model.projectToolGroups
import io.github.mangi.eta.ui.model.toolCardAction
import io.github.mangi.eta.ui.model.toolCardRequirement
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import com.composables.icons.lucide.R as LucideR

private object ToolsMetrics {
    val GridHorizontalPadding = 20.dp
    val GridGap = 12.dp
    val CardMinHeight = 136.dp
    val CardInsidePadding = 16.dp
    val IconContainerSize = 40.dp
    val IconSize = 20.dp
    val IconTitleGap = 12.dp
    val TitleSummaryGap = 2.dp
}

@Composable
fun AgentToolsScreen(
    state: AgentToolsUiState,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val capabilities = rememberDeviceCapabilities()
    var showAll by rememberSaveable { mutableStateOf(false) }
    val currentListState = rememberLazyListState()
    val allListState = rememberLazyListState()
    val groups = projectToolGroups(state.groups, showAll, capabilities.root.isGranted, capabilities.tools.colorOs)
    MiuixScaffoldPage(
        title = stringResource(R.string.ui_tool_ability_9f0f80),
        onBack = { onAction(AgentToolsAction.NavigateBack) },
        modifier = modifier,
        listState = if (showAll) allListState else currentListState,
    ) {
        item(key = "capability-view") {
            TabRow(
                tabs = listOf(stringResource(R.string.capability_current_device), stringResource(R.string.capability_all)),
                selectedTabIndex = if (showAll) 1 else 0,
                onTabSelected = { showAll = it == 1 },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        item(key = "capability-discovery") {
            Card(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.capability_enhancements),
                    summary = stringResource(R.string.capability_enhancements_summary),
                    onClick = { onAction(AgentToolsAction.OpenEnhancements) },
                )
            }
        }
        groups.forEach { group ->
            item(key = "${group.id}-title") {
                SmallTitle(group.title)
            }
            items(
                items = group.tools.chunked(2),
                key = { row -> "${group.id}-${row.joinToString(separator = "-") { it.id }}" },
            ) { row ->
                ToolGridRow(
                    tools = row,
                    rootGranted = capabilities.root.isGranted,
                    capabilities = capabilities.tools,
                    onToolClick = { tool ->
                        toolCardAction(tool.id, capabilities.tools)?.let(onAction)
                    },
                )
            }
        }
    }
}

@Composable
private fun ToolGridRow(
    tools: List<ToolItemUi>,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onToolClick: (ToolItemUi) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ToolsMetrics.GridHorizontalPadding)
            .padding(bottom = ToolsMetrics.GridGap),
    ) {
        val useSingleColumn = maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.3f
        if (useSingleColumn) {
            Column(verticalArrangement = Arrangement.spacedBy(ToolsMetrics.GridGap)) {
                tools.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        rootGranted = rootGranted,
                        capabilities = capabilities,
                        onClick = if (toolCardAction(tool.id, capabilities) != null) {
                            { onToolClick(tool) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.GridGap),
            ) {
                tools.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        rootGranted = rootGranted,
                        capabilities = capabilities,
                        onClick = if (toolCardAction(tool.id, capabilities) != null) {
                            { onToolClick(tool) }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                if (tools.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ToolCard(
    tool: ToolItemUi,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .heightIn(min = ToolsMetrics.CardMinHeight),
        cornerRadius = CardDefaults.CornerRadius,
        insideMargin = PaddingValues(ToolsMetrics.CardInsidePadding),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
        ),
        pressFeedbackType = if (onClick != null) PressFeedbackType.Sink else PressFeedbackType.None,
        showIndication = onClick != null,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .size(ToolsMetrics.IconContainerSize)
                .background(colorForTool(tool.id), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconForTool(tool.id)),
                contentDescription = null,
                modifier = Modifier.size(ToolsMetrics.IconSize),
                tint = Color.White,
            )
        }
        Spacer(modifier = Modifier.height(ToolsMetrics.IconTitleGap))
        Text(
            text = tool.title,
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(ToolsMetrics.TitleSummaryGap))
        Text(
            text = if (!rootGranted && tool.id == "terminal") {
                stringResource(R.string.capability_terminal_ordinary_summary)
            } else { tool.summary },
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        val requirement = toolCardRequirement(tool.id)
        val requirementText = when {
            !rootGranted && requirement.rootRequirement == RootRequirement.REQUIRED -> stringResource(R.string.capability_root_required)
            !capabilities.accessibilityAvailable && requirement.accessibility -> stringResource(R.string.capability_accessibility_required)
            capabilities.unavailableCode(actualToolName(tool.id)) == "NOTIFICATION_ACCESS_REQUIRED" -> stringResource(R.string.capability_notification_access_required)
            capabilities.unavailableCode(actualToolName(tool.id)) == "APP_USAGE_ACCESS_REQUIRED" -> stringResource(R.string.capability_usage_access_required)
            capabilities.unavailableCode(actualToolName(tool.id)) == "LOCATION_PERMISSION_REQUIRED" -> stringResource(R.string.capability_location_access_required)
            requirement.colorOs -> stringResource(R.string.capability_coloros_required)
            !rootGranted && requirement.rootRequirement == RootRequirement.PARTIAL -> stringResource(R.string.capability_root_partial)
            else -> null
        }
        if (requirementText != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = requirementText, style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions)
        }
    }
}

private fun colorForTool(toolId: String): Color = when (toolId) {
    // 与设置页保持一致：圆形图标底色统一使用 ColorOS 设置主色，不使用 variant/截图取样色。
    // 屏幕与控件
    "observe", "observe_screen",
    "click", "tap_element",
    "tap_area", "long_press",
    "swipe", "scroll" -> Color(0xFFFF7700)

    // 文本与剪贴板
    "clipboard", "paste_text",
    "input_text", "replace_text",
    "clear_text", "wait_text",
    "wait_for_text" -> Color(0xFF0066FF)

    // 应用与系统
    "search_apps", "open_app",
    "get_current_context", "launch_app", "open_uri",
    "press_key", "open_system_panel" -> Color(0xFF00BD13)

    // 设备直达与敏感设备能力
    "set_alarm", "set_timer", "device_status", "network_info",
    "media_control", "set_volume", "top_memory_apps", "top_storage_apps" -> Color(0xFF00BD13)
    "read_sms_code", "recent_notifications", "wifi_credentials",
    "search_notification_history", "recent_app_activity", "app_usage_summary",
    "get_current_location", "get_device_environment", "list_alarms", "list_active_timers",
    "search_clipboard_history", "get_health_summary",
    "get_setting", "set_setting", "set_device_state", "app_state_control",
    "get_logcat" -> Color(0xFFFF7700)

    // 个人数据直达
    "search_media", "search_audio", "search_recordings", "search_files",
    "search_calendar_events", "search_contacts", "search_call_history",
    "search_messages", "search_downloads", "search_coloros_notes",
    "search_coloros_recordings", "search_recording_summaries", "search_qq_chat_images",
    "search_coloros_memories", "search_saved_places", "search_personal_orders",
    "search_wechat_chat_images" -> Color(0xFFFF7700)

    // 文件视觉
    "read_image" -> Color(0xFF0066FF)

    // Agent 浏览器
    "browser_use", "browser_read",
    "browser_interact", "browser_screenshot" -> Color(0xFF0066FF)

    // 记忆
    "memory_get", "memory_write" -> Color(0xFF0066FF)

    // 终端与文件
    "terminal", "terminal_job",
    "run_command", "read_file",
    "write_file", "list_directory" -> Color(0xFFFFB200)

    else -> Color(0xFF0066FF)
}

private fun iconForTool(toolId: String): Int = when (toolId) {
    "observe", "observe_screen" -> LucideR.drawable.lucide_ic_scan_text
    "click", "tap_element" -> LucideR.drawable.lucide_ic_mouse_pointer_click
    "tap_area" -> LucideR.drawable.lucide_ic_locate_fixed
    "long_press" -> LucideR.drawable.lucide_ic_hand
    "swipe" -> LucideR.drawable.lucide_ic_move
    "scroll" -> LucideR.drawable.lucide_ic_scroll
    "clipboard", "paste_text" -> LucideR.drawable.lucide_ic_clipboard_paste
    "input_text" -> LucideR.drawable.lucide_ic_keyboard
    "replace_text" -> LucideR.drawable.lucide_ic_replace
    "clear_text" -> LucideR.drawable.lucide_ic_eraser
    "wait_text", "wait_for_text" -> LucideR.drawable.lucide_ic_clock
    "search_apps" -> LucideR.drawable.lucide_ic_package_search
    "get_current_context" -> LucideR.drawable.lucide_ic_map_pin
    "open_app", "launch_app" -> LucideR.drawable.lucide_ic_app_window
    "open_uri" -> LucideR.drawable.lucide_ic_external_link
    "browser_use" -> LucideR.drawable.lucide_ic_globe
    "browser_read" -> LucideR.drawable.lucide_ic_book_open_text
    "browser_interact" -> LucideR.drawable.lucide_ic_mouse_pointer_click
    "browser_screenshot" -> LucideR.drawable.lucide_ic_scan_eye
    "memory_get", "memory_write" -> LucideR.drawable.lucide_ic_brain
    "press_key" -> LucideR.drawable.lucide_ic_smartphone
    "open_system_panel" -> LucideR.drawable.lucide_ic_panel_top_open
    "set_alarm", "set_timer" -> LucideR.drawable.lucide_ic_clock
    "device_status", "network_info", "set_device_state" -> LucideR.drawable.lucide_ic_smartphone
    "media_control" -> LucideR.drawable.lucide_ic_play
    "set_volume" -> LucideR.drawable.lucide_ic_settings
    "top_memory_apps", "top_storage_apps" -> LucideR.drawable.lucide_ic_layers
    "read_sms_code" -> LucideR.drawable.lucide_ic_key
    "recent_notifications", "search_notification_history" -> LucideR.drawable.lucide_ic_bell
    "recent_app_activity", "app_usage_summary" -> LucideR.drawable.lucide_ic_layers
    "get_current_location", "search_saved_places" -> LucideR.drawable.lucide_ic_map_pin
    "get_device_environment" -> LucideR.drawable.lucide_ic_smartphone
    "list_alarms", "list_active_timers" -> LucideR.drawable.lucide_ic_clock
    "search_clipboard_history" -> LucideR.drawable.lucide_ic_clipboard_paste
    "get_health_summary" -> LucideR.drawable.lucide_ic_activity
    "wifi_credentials" -> LucideR.drawable.lucide_ic_lock
    "get_setting", "set_setting" -> LucideR.drawable.lucide_ic_settings
    "app_state_control" -> LucideR.drawable.lucide_ic_shield_alert
    "get_logcat" -> LucideR.drawable.lucide_ic_file_text
    "search_media" -> LucideR.drawable.lucide_ic_scan_eye
    "search_audio" -> LucideR.drawable.lucide_ic_play
    "search_recordings", "search_coloros_recordings" -> LucideR.drawable.lucide_ic_mic
    "search_files", "search_downloads" -> LucideR.drawable.lucide_ic_folder_open
    "search_calendar_events" -> LucideR.drawable.lucide_ic_clock
    "search_contacts", "search_call_history" -> LucideR.drawable.lucide_ic_smartphone
    "search_messages" -> LucideR.drawable.lucide_ic_message_square
    "search_coloros_notes" -> LucideR.drawable.lucide_ic_file_pen
    "search_recording_summaries", "search_coloros_memories" -> LucideR.drawable.lucide_ic_brain
    "search_personal_orders" -> LucideR.drawable.lucide_ic_package_search
    "search_qq_chat_images", "search_wechat_chat_images" -> LucideR.drawable.lucide_ic_scan_eye
    "read_image" -> LucideR.drawable.lucide_ic_scan_eye
    "terminal", "terminal_job", "run_command" -> LucideR.drawable.lucide_ic_square_terminal
    "read_file" -> LucideR.drawable.lucide_ic_file_text
    "write_file" -> LucideR.drawable.lucide_ic_file_pen
    "list_directory" -> LucideR.drawable.lucide_ic_folder_open
    else -> LucideR.drawable.lucide_ic_settings
}
