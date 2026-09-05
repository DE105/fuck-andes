package io.github.mangi.eta.ui.screens.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AdsClick
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WebAsset
import androidx.compose.ui.graphics.vector.ImageVector

internal fun iconForTool(toolId: String): ImageVector = when (toolId) {
    "observe", "observe_screen" -> Icons.Rounded.DocumentScanner
    "click", "tap_element" -> Icons.Rounded.AdsClick
    "tap_area" -> Icons.Rounded.MyLocation
    "long_press" -> Icons.Rounded.TouchApp
    "swipe" -> Icons.Rounded.OpenWith
    "scroll" -> Icons.Rounded.SwapVert
    "clipboard", "paste_text" -> Icons.Rounded.ContentPasteGo
    "input_text" -> Icons.Rounded.Keyboard
    "replace_text" -> Icons.Rounded.FindReplace
    "clear_text" -> Icons.AutoMirrored.Rounded.Backspace
    "wait_text", "wait_for_text" -> Icons.Rounded.Schedule
    "search_apps" -> Icons.AutoMirrored.Rounded.ManageSearch
    "get_current_context" -> Icons.Rounded.LocationOn
    "open_app", "launch_app" -> Icons.Rounded.Apps
    "open_uri" -> Icons.AutoMirrored.Rounded.OpenInNew
    "browser_use" -> Icons.Rounded.Language
    "browser_read" -> Icons.AutoMirrored.Rounded.MenuBook
    "browser_interact" -> Icons.Rounded.AdsClick
    "browser_screenshot" -> Icons.Rounded.ScreenshotMonitor
    "memory_get", "memory_write" -> Icons.Rounded.Psychology
    "press_key" -> Icons.Rounded.Smartphone
    "open_system_panel" -> Icons.Rounded.WebAsset
    "set_alarm", "set_timer" -> Icons.Rounded.Schedule
    "device_status", "network_info", "set_device_state" -> Icons.Rounded.Smartphone
    "media_control" -> Icons.Rounded.PlayArrow
    "set_volume" -> Icons.Rounded.Settings
    "top_memory_apps", "top_storage_apps" -> Icons.Rounded.Layers
    "read_sms_code" -> Icons.Rounded.Key
    "recent_notifications", "search_notification_history" -> Icons.Rounded.Notifications
    "recent_app_activity", "app_usage_summary" -> Icons.Rounded.Layers
    "get_current_location", "search_saved_places" -> Icons.Rounded.LocationOn
    "get_device_environment" -> Icons.Rounded.Smartphone
    "list_alarms", "list_active_timers" -> Icons.Rounded.Schedule
    "search_clipboard_history" -> Icons.Rounded.ContentPasteGo
    "get_health_summary" -> Icons.Rounded.Insights
    "wifi_credentials" -> Icons.Rounded.Lock
    "get_setting", "set_setting" -> Icons.Rounded.Settings
    "app_state_control" -> Icons.Rounded.GppMaybe
    "get_logcat" -> Icons.Rounded.Description
    "search_media" -> Icons.Rounded.ScreenshotMonitor
    "search_audio" -> Icons.Rounded.PlayArrow
    "search_recordings", "search_coloros_recordings" -> Icons.Rounded.Mic
    "search_files", "search_downloads" -> Icons.Rounded.FolderOpen
    "search_calendar_events" -> Icons.Rounded.Schedule
    "search_contacts", "search_call_history" -> Icons.Rounded.Smartphone
    "search_messages" -> Icons.Rounded.ChatBubble
    "search_coloros_notes" -> Icons.Rounded.EditNote
    "search_recording_summaries", "search_coloros_memories" -> Icons.Rounded.Psychology
    "search_personal_orders" -> Icons.AutoMirrored.Rounded.ManageSearch
    "search_qq_chat_images", "search_wechat_chat_images" -> Icons.Rounded.ScreenshotMonitor
    "read_image" -> Icons.Rounded.ScreenshotMonitor
    "terminal", "terminal_job", "run_command" -> Icons.Rounded.Terminal
    "read_file" -> Icons.Rounded.Description
    "write_file" -> Icons.Rounded.EditNote
    "list_directory" -> Icons.Rounded.FolderOpen
    else -> Icons.Rounded.Settings
}
