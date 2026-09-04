package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject

internal enum class RootRequirement { NONE, PARTIAL, REQUIRED }
internal enum class LsposedRequirement { NONE, OPTIONAL, REQUIRED }

internal enum class ToolSystemAccess { NONE, NOTIFICATIONS, USAGE, LOCATION }

internal data class LocalToolRequirement(
    val rootRequirement: RootRequirement,
    val lsposedRequirement: LsposedRequirement = LsposedRequirement.NONE,
    val accessibility: Boolean = false,
    val systemAccess: ToolSystemAccess = ToolSystemAccess.NONE,
    val colorOs: Boolean = false,
)

/** 展示、模型目录与执行边界共同使用的本地工具能力合同。未登记的工具不能发布。 */
internal object AgentToolRequirements {
    private val definitions = buildMap {
        fun register(root: RootRequirement, vararg names: String) {
            names.forEach { name ->
                check(put(name, LocalToolRequirement(root)) == null) { "Duplicate tool: $name" }
            }
        }
        register(
            RootRequirement.NONE,
            "get_current_context", "search_apps", "launch_app", "open_uri", "browser_use",
            "observe_screen", "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
            "replace_text", "clear_text", "set_clipboard", "get_clipboard", "paste_text",
            "wait", "wait_for_text", "wait_for_package", "open_system_panel",
            "set_alarm", "set_timer", "device_status", "media_control", "set_volume",
            "search_notification_history", "recent_app_activity", "app_usage_summary",
            "get_current_location", "get_device_environment", "memory_get", "memory_write",
            "skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
            "skills_inspect_github", "skills_install_from_github",
        )
        register(
            RootRequirement.PARTIAL,
            "press_key", "network_info", "get_setting", "recent_notifications",
            "search_personal_orders", "terminal", "run_command", "read_file",
            "write_file", "list_directory", "read_image",
        )
        register(
            RootRequirement.REQUIRED,
            "top_memory_apps", "top_storage_apps", "wifi_credentials", "read_sms_code",
            "get_logcat", "set_setting", "set_device_state", "app_state_control",
            "list_alarms", "list_active_timers", "get_health_summary", "search_clipboard_history",
            "search_media", "search_audio", "search_recordings", "search_files",
            "search_calendar_events", "search_contacts", "search_call_history", "search_messages",
            "search_downloads", "search_coloros_notes", "search_coloros_recordings",
            "search_recording_summaries", "search_coloros_memories", "search_saved_places",
            "search_qq_chat_images", "search_wechat_chat_images",
        )
        listOf(
            "observe_screen", "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
            "replace_text", "clear_text", "paste_text", "press_key", "open_system_panel",
            "wait_for_text", "wait_for_package",
        ).forEach { name -> put(name, getValue(name).copy(accessibility = true)) }
        mapOf(
            "recent_notifications" to ToolSystemAccess.NOTIFICATIONS,
            "search_notification_history" to ToolSystemAccess.NOTIFICATIONS,
            "search_personal_orders" to ToolSystemAccess.NOTIFICATIONS,
            "recent_app_activity" to ToolSystemAccess.USAGE,
            "app_usage_summary" to ToolSystemAccess.USAGE,
            "get_current_location" to ToolSystemAccess.LOCATION,
        ).forEach { (name, access) -> put(name, getValue(name).copy(systemAccess = access)) }
        listOf(
            "search_coloros_notes", "search_coloros_recordings", "search_recording_summaries",
            "search_coloros_memories", "search_saved_places",
        ).forEach { name -> put(name, getValue(name).copy(colorOs = true)) }
        // 系统记忆优先使用 Hook 桥接，框架失联时仍有独立的 Root 快照来源。
        listOf("search_coloros_memories", "search_saved_places", "search_personal_orders").forEach { name ->
            put(name, getValue(name).copy(lsposedRequirement = LsposedRequirement.OPTIONAL))
        }
    }

    val toolNames: Set<String> get() = definitions.keys

    fun find(name: String): LocalToolRequirement? = definitions[name]

    fun rootRequirement(name: String): RootRequirement =
        requireNotNull(find(name)) { "Missing tool requirements: $name" }.rootRequirement

    fun requiresAccessibility(name: String): Boolean = find(name)?.accessibility == true

    fun rootDenied(name: String, arguments: JSONObject, rootAvailable: Boolean): Boolean {
        if (rootAvailable) return false
        if (rootRequirement(name) == RootRequirement.REQUIRED) return true
        return when (name) {
            "terminal" -> arguments.optString("identity").equals("root", ignoreCase = true)
            "press_key" -> arguments.optString("button").equals("PASTE", ignoreCase = true)
            else -> false
        }
    }

    /** 复制后收窄，不能修改下一轮或另一个 run 共用的原始 Schema。 */
    fun project(tools: JSONArray, rootAvailable: Boolean): JSONArray = JSONArray().also { result ->
        for (index in 0 until tools.length()) {
            val original = tools.getJSONObject(index)
            val name = original.getJSONObject("function").getString("name")
            val requirement = rootRequirement(name)
            if (!rootAvailable && requirement == RootRequirement.REQUIRED) continue
            val tool = JSONObject(original.toString())
            if (!rootAvailable) projectUnprivileged(tool.getJSONObject("function"))
            result.put(tool)
        }
    }

    private fun projectUnprivileged(function: JSONObject) {
        val properties = function.getJSONObject("parameters").optJSONObject("properties")
        when (function.getString("name")) {
            "terminal" -> {
                function.put("description", "在当前设备管理普通 Android Shell 或用户选择的 Linux 环境。" +
                    "以 App UID 执行，支持会话、异步任务和后台服务；Linux 内的模拟身份不提供 Android 系统特权。" +
                    "使用 open_and_exec 执行单次命令，open/exec 复用会话，daemon_start/list/logs/stop 管理后台服务。")
                properties?.getJSONObject("identity")
                    ?.put("enum", JSONArray().put("user"))
                    ?.put("description", "宿主执行身份；当前仅支持 user，默认 user。")
                properties?.getJSONObject("environment")?.put("description",
                    "android 使用普通 Android Shell；linux 使用用户选择的发行版和免 Root 后端。默认 android。")
                properties?.getJSONObject("cwd")?.put("description",
                    "工作目录。Android 默认使用 Eta 私有工作区，Linux 默认 /workspace。")
            }
            "run_command" -> {
                function.put("description",
                    "通过普通 Android Shell 执行单次非交互命令，以 App UID 运行；只能访问当前应用有权访问的资源。")
                properties?.getJSONObject("cwd")?.put("description", "工作目录，默认使用 Eta 私有工作区。")
            }
            "list_directory" -> {
                function.put("description", "列出当前应用有权访问的目录，默认使用 Eta 私有工作区。")
                properties?.optJSONObject("path")?.apply {
                    put("description", "目录路径；未提供时使用 Eta 私有工作区。")
                    remove("default")
                }
            }
            "read_image" -> properties?.getJSONObject("path")?.put("description",
                "当前应用有权读取的绝对图片路径、file URI 或已授权的 content URI。")
            "press_key" -> properties?.getJSONObject("button")?.let { button ->
                val values = button.getJSONArray("enum")
                button.put("enum", JSONArray().also { allowed ->
                    for (i in 0 until values.length()) {
                        val value = values.getString(i)
                        if (!value.equals("PASTE", ignoreCase = true)) allowed.put(value)
                    }
                })
                button.put("description", "无障碍支持的系统按键；粘贴文本请使用 paste_text。")
            }
            "search_personal_orders" -> function.put("description",
                "从用户已授权保存的通知历史检索外卖、购物、快递、票券和出行订单。")
        }
    }
}
