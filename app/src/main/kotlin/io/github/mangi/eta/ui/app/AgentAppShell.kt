package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.AdaptiveTopAppBar
import io.github.mangi.eta.ui.components.ConversationSidePaneScaffold
import io.github.mangi.eta.ui.components.MiuixBackButton
import io.github.mangi.eta.ui.components.TopBarBackdrop
import io.github.mangi.eta.ui.components.captureForTopBar
import io.github.mangi.eta.ui.components.rememberTopBarBackdrop
import io.github.mangi.eta.ui.components.topBarContainerColor
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.window.WindowListPopup
import com.composables.icons.lucide.R as LucideR

/**
 * Agent App 统一壳层。
 *
 * - 负责全局 Scaffold、状态栏/横向安全边距、顶层工具栏。
 * - 首页工具栏只保留历史入口与溢出菜单（新建对话、终端、浏览器），保持聊天舞台干净。
 * - 非首页子路由统一提供返回按钮与标题，避免每个页面各自像独立设置页。
 * - Settings 由标准二级页骨架自己提供 TopAppBar，壳层在此路由不重复绘制。
 */
@Composable
fun AgentAppShell(
    currentRoute: AppRoute?,
    isCurrentRoute: Boolean,
    conversationPaneState: ConversationPaneUiState?,
    isConversationPaneOpen: Boolean,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onDismissConversationPane: () -> Unit,
    onSearchConversations: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    val pageContent: @Composable () -> Unit = {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            ),
            topBar = {
                if (currentRoute !is AppRoute.Settings) {
                    TopBarBackdrop(backdrop) {
                        AgentTopBar(
                            route = currentRoute,
                            scrollBehavior = scrollBehavior,
                            color = topBarColor,
                            onBack = onBack,
                            onOpenConversationPane = onOpenConversationPane,
                            onNewConversation = onNewConversation,
                            onOpenTerminal = onOpenTerminal,
                            onLaunchKimiWeb = onLaunchKimiWeb,
                            kimiWebLabel = kimiWebLabel,
                            canStopKimiWeb = canStopKimiWeb,
                            onStopKimiWeb = onStopKimiWeb,
                            onRefreshKimiWeb = onRefreshKimiWeb,
                            onOpenBrowser = onOpenBrowser,
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .captureForTopBar(backdrop)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            ) {
                content(padding)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (conversationPaneState != null && currentRoute is AppRoute.Home) {
            ConversationSidePaneScaffold(
                state = conversationPaneState,
                visible = isConversationPaneOpen,
                backHandlerEnabled = isCurrentRoute,
                onOpen = onOpenConversationPane,
                onDismiss = onDismissConversationPane,
                onSearchChange = onSearchConversations,
                onConversationSelected = onSelectConversation,
                onConversationRename = onConversationRename,
                onConversationDelete = onConversationDelete,
                onOpenSettings = onOpenSettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenTools = onOpenTools,
                onOpenSkills = onOpenSkills,
                onOpenPermissions = onOpenPermissions,
            ) {
                pageContent()
            }
        } else {
            pageContent()
        }
    }
}

@Composable
private fun AgentTopBar(
    route: AppRoute?,
    scrollBehavior: ScrollBehavior,
    color: Color,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    val isHome = route is AppRoute.Home
    val navigationIcon: @Composable () -> Unit = {
        if (isHome) {
            IconButton(onClick = onOpenConversationPane) {
                Icon(
                    painter = painterResource(LucideR.drawable.lucide_ic_menu),
                    contentDescription = stringResource(R.string.action_conversation_history),
                )
            }
        } else {
            MiuixBackButton(onClick = onBack)
        }
    }
    val actions: @Composable RowScope.() -> Unit = {
        if (isHome) {
            TopBarOverflowMenu(
                onNewConversation = onNewConversation,
                onOpenTerminal = onOpenTerminal,
                onLaunchKimiWeb = onLaunchKimiWeb,
                kimiWebLabel = kimiWebLabel,
                canStopKimiWeb = canStopKimiWeb,
                onStopKimiWeb = onStopKimiWeb,
                onRefreshKimiWeb = onRefreshKimiWeb,
                onOpenBrowser = onOpenBrowser,
            )
        }
    }

    if (isHome) {
        // 首页聊天舞台保持紧凑；二级内容页统一使用可折叠大标题。
        SmallTopAppBar(
            title = titleForRoute(route),
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    } else {
        AdaptiveTopAppBar(
            title = titleForRoute(route),
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    }
}

private val TopBarMenuIconSize = 20.dp

/**
 * 首页顶栏溢出菜单。WindowListPopup 以父布局为锚点，因此与触发按钮包在同一个 Box 中，
 * 弹层从按钮下方右对齐展开。
 */
@Composable
private fun TopBarOverflowMenu(
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { onRefreshKimiWeb(); showMenu = true }) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_ellipsis_vertical),
                contentDescription = stringResource(R.string.action_more),
            )
        }
        WindowListPopup(
            show = showMenu,
            alignment = PopupPositionProvider.Align.End,
            enableWindowDim = false,
            onDismissRequest = { showMenu = false },
        ) {
            val newConversationText = stringResource(R.string.action_new_conversation)
            val openTerminalText = stringResource(R.string.action_open_terminal)
            val launchKimiWebText = kimiWebLabel
            val stopKimiWebText = stringResource(R.string.capability_kimi_stop)
            val openBrowserText = stringResource(R.string.action_open_browser)
            val menuItems = remember(
                newConversationText,
                openTerminalText,
                launchKimiWebText,
                openBrowserText,
                stopKimiWebText,
                canStopKimiWeb,
            ) {
                listOf(
                    DropdownItem(
                        text = newConversationText,
                        icon = { modifier ->
                            Icon(
                                painter = painterResource(LucideR.drawable.lucide_ic_message_circle_plus),
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                    DropdownItem(
                        text = openTerminalText,
                        icon = { modifier ->
                            Icon(
                                painter = painterResource(LucideR.drawable.lucide_ic_square_terminal),
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                    DropdownItem(
                        text = launchKimiWebText,
                        icon = { modifier ->
                            Icon(
                                painter = painterResource(R.drawable.ic_kimi_code),
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                    DropdownItem(
                        text = openBrowserText,
                        icon = { modifier ->
                            Icon(
                                painter = painterResource(LucideR.drawable.lucide_ic_globe),
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                ) + if (canStopKimiWeb) listOf(DropdownItem(text = stopKimiWebText)) else emptyList()
            }
            ListPopupColumn {
                menuItems.forEachIndexed { index, item ->
                    DropdownImpl(
                        item = item,
                        optionSize = menuItems.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            showMenu = false
                            when (index) {
                                0 -> onNewConversation()
                                1 -> onOpenTerminal()
                                2 -> onLaunchKimiWeb()
                                3 -> onOpenBrowser()
                                4 -> onStopKimiWeb()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun titleForRoute(route: AppRoute?): String = when (route) {
    is AppRoute.Home -> ""
    is AppRoute.Chat -> stringResource(R.string.route_chat)
    is AppRoute.Browser -> stringResource(R.string.route_browser)
    is AppRoute.Terminal -> stringResource(R.string.route_terminal)
    is AppRoute.Tools -> stringResource(R.string.route_tools)
    is AppRoute.Skills -> stringResource(R.string.route_skills)
    is AppRoute.Permissions -> stringResource(R.string.route_permissions)
    is AppRoute.SystemEnhance -> stringResource(R.string.route_system_enhancements)
    is AppRoute.Settings -> stringResource(R.string.route_settings)
    is AppRoute.AppearanceSettings -> stringResource(R.string.appearance_title)
    is AppRoute.DataBackup -> stringResource(R.string.data_backup_title)
    is AppRoute.Memory -> stringResource(R.string.route_memory)
    is AppRoute.LinuxEnvironment -> stringResource(R.string.route_linux_environment)
    is AppRoute.Workspace -> stringResource(R.string.capability_workspace)
    is AppRoute.SharedFolders -> stringResource(R.string.route_shared_folders)
    is AppRoute.LinuxFiles -> stringResource(R.string.route_linux_files)
    is AppRoute.ModelProviders -> stringResource(R.string.route_model_providers)
    is AppRoute.McpServers -> stringResource(R.string.route_mcp_servers)
    is AppRoute.McpServerDetail -> stringResource(R.string.route_mcp_server_detail)
    is AppRoute.ModelProviderDetail -> stringResource(R.string.route_provider_details)
    is AppRoute.ModelProviderNew -> stringResource(R.string.route_new_provider)
    null -> stringResource(R.string.app_name)
}
