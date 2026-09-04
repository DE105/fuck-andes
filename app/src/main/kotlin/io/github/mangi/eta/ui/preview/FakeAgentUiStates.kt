package io.github.mangi.eta.ui.preview

import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import io.github.mangi.eta.ui.model.PermissionStatusUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi

internal object FakeAgentUiStates {

    val conversations = ConversationPaneUiState(
        selectedConversationId = "c-001",
        searchQuery = "",
        conversations = listOf(
            ConversationSummaryUi(
                id = "c-001",
                title = "让 Eta 操作手机",
                preview = "准备接管屏幕与工具，等待下一步任务",
                timeLabel = "现在",
                mode = ConversationModeUi.PhoneAgent,
                isPinned = true,
                isActiveRun = true,
            ),
            ConversationSummaryUi(
                id = "c-002",
                title = "今天的安排和提醒",
                preview = "查询天气、同步日程，并设置出门提醒",
                timeLabel = "10:41",
                mode = ConversationModeUi.Chat,
                isPinned = true,
            ),
            ConversationSummaryUi(
                id = "c-003",
                title = "打开网易云音乐播放每日推荐",
                preview = "已完成 4 个工具调用，用时 8 秒",
                timeLabel = "10:23",
                mode = ConversationModeUi.PhoneAgent,
            ),
            ConversationSummaryUi(
                id = "c-004",
                title = "分析当前页面截图",
                preview = "总结页面结构，并指出按钮层级问题",
                timeLabel = "昨天",
                mode = ConversationModeUi.Chat,
            ),
            ConversationSummaryUi(
                id = "c-005",
                title = "检查 Alpine 终端环境",
                preview = "列出可用命令、Python/Node 版本和后台 job",
                timeLabel = "周五",
                mode = ConversationModeUi.Terminal,
            ),
            ConversationSummaryUi(
                id = "c-006",
                title = "每晚整理下载目录",
                preview = "定时扫描文件并归类图片、安装包和文档",
                timeLabel = "5-18",
                mode = ConversationModeUi.Automation,
            ),
        ),
    )

    val chatHome: AgentChatHomeUiState = AgentChatHomeUiState(
        messages = emptyList(),
        input = "",
        isStreaming = false,
        thinkingEnabled = false,
    )

    val chat = AgentChatUiState(
        messages = listOf(
            UserMessageUi(
                id = "m-01",
                content = "打开设置里的电池优化",
            ),
            AgentMessageUi(
                id = "m-02",
                content = "当前手机屏幕显示的是**主界面**，关键信息如下：\n\n| 项目 | 内容 |\n| --- | --- |\n| 时间 | 09:55 |\n| 网络 | 5G + Wi-Fi |\n| 电量 | 68% |",
                usage = TokenUsageUi(
                    contextTokens = 17100,
                    inputTokens = 9000,
                    outputTokens = 111,
                    reasoningTokens = 8200,
                ),
            ),
            ThinkingMessageUi(
                id = "thinking-01",
                content = "用户希望我查看当前屏幕，需要先调用 observe_screen 获取屏幕结构，再总结可见信息。",
                isStreaming = false,
                elapsedSeconds = 24,
                collapsed = true,
            ),
            ToolActivityMessageUi(
                id = "tools-01",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{\"include_screenshot\":true}",
                resultSummary = "ok=true, chars=1820, images=1",
                imageCount = 1,
            ),
        ),
        input = "",
        isStreaming = false,
        thinkingEnabled = true,
    )

    val permissionHealth = PermissionHealthUiState(
        items = listOf(
            PermissionHealthItemUi(
                id = "accessibility",
                title = "无障碍服务",
                summary = "已启用，Agent 可操作 UI",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "overlay",
                title = "悬浮窗权限",
                summary = "已授权，可显示运行浮窗",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "notification",
                title = "通知权限",
                summary = "用于后台任务完成提醒",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "location",
                title = "位置权限",
                summary = "仅在 Agent 调用工具时读取",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "root",
                title = "Root 权限",
                summary = "未获得，部分终端命令受限",
                status = PermissionStatusUi.Warning,
                primaryActionLabel = "检查",
            ),
            PermissionHealthItemUi(
                id = "shizuku",
                title = "Shizuku",
                summary = "未配置，ADB 级能力不可用",
                status = PermissionStatusUi.Disabled,
                primaryActionLabel = "配置",
            ),
            PermissionHealthItemUi(
                id = "xposed",
                title = "Hook / Xposed",
                summary = "框架未激活，系统增强能力不可用",
                status = PermissionStatusUi.Missing,
                primaryActionLabel = "查看",
            ),
            PermissionHealthItemUi(
                id = "background",
                title = "后台保活",
                summary = "电池优化未关闭，长任务可能被中断",
                status = PermissionStatusUi.Warning,
                primaryActionLabel = "设置",
            ),
        ),
    )

    val tools = AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = "屏幕操作",
                tools = listOf(
                    ToolItemUi("observe_screen", "观察屏幕", "读取界面节点，必要时附原图"),
                    ToolItemUi("tap_element", "点击", "点击指定坐标或元素"),
                    ToolItemUi("long_press", "长按", "长按指定元素"),
                    ToolItemUi("swipe", "滑动", "滑动、滚动、返回等手势"),
                ),
            ),
            ToolGroupUi(
                id = "input",
                title = "输入与按键",
                tools = listOf(
                    ToolItemUi("input_text", "输入文字", "在焦点处输入文本"),
                    ToolItemUi("paste_text", "粘贴文本", "使用剪贴板写入文本"),
                    ToolItemUi("wait_for_text", "等待文本", "等待界面出现指定文字"),
                ),
            ),
            ToolGroupUi(
                id = "web",
                title = "网页浏览",
                tools = listOf(
                    ToolItemUi("browser_use", "Agent 浏览器", "离屏浏览并保持可接管会话"),
                    ToolItemUi("browser_read", "阅读网页", "提取渲染后的网页正文"),
                    ToolItemUi("browser_interact", "网页交互", "查找、点击和输入元素"),
                    ToolItemUi("browser_screenshot", "页面截图", "把网页视口交给视觉模型"),
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = "App 与 URI",
                tools = listOf(
                    ToolItemUi("get_current_context", "时间与位置", "读取系统时间与最近位置"),
                    ToolItemUi("launch_app", "打开 App", "通过包名启动应用"),
                    ToolItemUi("open_uri", "用应用打开", "显式交给外部应用处理"),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = "终端与文件",
                tools = listOf(
                    ToolItemUi("terminal", "终端命令", "Android/Alpine/Debian 环境执行 shell"),
                ),
            ),
        ),
    )
}
