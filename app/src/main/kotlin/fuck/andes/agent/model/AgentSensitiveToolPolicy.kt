package fuck.andes.agent.model

/** 标记原始参数或结果不得进入持久会话的工具。 */
internal object AgentSensitiveToolPolicy {
    fun isSensitive(toolName: String): Boolean =
        toolName.startsWith("mcp_") || toolName in sensitiveTools

    private val sensitiveTools = setOf(
        "get_setting",
        "wifi_credentials",
        "recent_notifications",
        "search_notification_history",
        "recent_app_activity",
        "app_usage_summary",
        "get_current_location",
        "get_device_environment",
        "list_alarms",
        "list_active_timers",
        "search_clipboard_history",
        "get_health_summary",
        "read_sms_code",
        "get_logcat",
        "search_media",
        "search_audio",
        "search_recordings",
        "search_files",
        "search_calendar_events",
        "search_contacts",
        "search_call_history",
        "search_messages",
        "search_downloads",
        "search_coloros_notes",
        "search_coloros_recordings",
        "search_recording_summaries",
        "search_coloros_memories",
        "search_saved_places",
        "search_personal_orders",
        "search_qq_chat_images",
        "search_wechat_chat_images",
        "read_image",
        "set_setting",
        "memory_get",
        "memory_write",
        "memory_compact",
        "memory_atom_write",
        "memory_atom_search",
        "memory_atom_delete",
        "memory_scenario_save",
        "memory_scenario_read",
        "memory_profile_update",
        "memory_profile_delete",
        "memory_conversation_search",
    )
}
