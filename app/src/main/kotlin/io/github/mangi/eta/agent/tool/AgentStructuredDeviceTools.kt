package io.github.mangi.eta.agent.tool

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.agent.device.AgentNotificationHistoryService
import io.github.mangi.eta.data.repository.NotificationHistoryRepository
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** 常用系统动作的结构化实现；所有 Root 脚本都由本类固定生成。 */
internal class AgentStructuredDeviceTools(
    private val context: Context,
    private val logger: AgentLogger,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    private val colorOs: () -> Boolean = { AgentToolCapabilities.isColorOsDevice() },
) {
    private val personalDataTools = AgentPersonalDataTools(root)
    private val colorOsMemoryTools = AgentColorOsMemoryTools(context, root)
    private val personalContextTools = AgentPersonalContextTools(context)
    private val privateDatabaseTools = AgentPrivateDatabaseTools(context, root)
    private val notificationHistory by lazy { NotificationHistoryRepository(context) }

    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult? =
        personalDataTools.execute(name, args)
            ?: personalContextTools.execute(name, args)
            ?: privateDatabaseTools.execute(name, args)
            ?: when (name) {
            "search_coloros_memories" -> colorOsMemoryTools.search(args)
            "search_saved_places" -> colorOsMemoryTools.searchSavedPlaces(args)
            "search_personal_orders" -> searchPersonalOrders(args)
            "set_alarm" -> text(setAlarm(args))
            "set_timer" -> text(setTimer(args))
            "device_status" -> text(deviceStatus())
            "network_info" -> text(networkInfo())
            "top_memory_apps" -> text(topMemoryApps(args))
            "top_storage_apps" -> text(topStorageApps(args))
            "media_control" -> text(mediaControl(args))
            "set_volume" -> text(setVolume(args))
            "get_setting" -> sensitive(getSetting(args))
            "wifi_credentials" -> sensitive(wifiCredentials(args))
            "recent_notifications" -> sensitive(recentNotifications(args))
            "read_sms_code" -> sensitive(readSmsCode(args))
            "get_logcat" -> sensitive(getLogcat(args))
            "set_setting" -> text(setSetting(args))
            "set_device_state" -> text(setDeviceState(args))
            "app_state_control" -> text(appStateControl(args))
            else -> null
        }

    private fun searchPersonalOrders(args: JSONObject): AgentModelClient.ToolResult {
        val limit = args.optInt("limit", 10).coerceIn(1, 30)
        val query = args.optString("query").trim()
        val memoryResult = if (!rootAvailable()) {
            JSONObject()
                .put("ok", false)
                .put("code", "ROOT_REQUIRED")
                .put("message", "系统记忆来源需要设备 Root 权限；仍可查询已授权的通知历史")
        } else if (!colorOs()) {
            JSONObject()
                .put("ok", false)
                .put("code", "DEVICE_UNSUPPORTED")
                .put("message", "此设备不支持系统记忆来源；仍可查询已授权的通知历史")
        } else runCatching {
            JSONObject(colorOsMemoryTools.searchOrders(args).content)
        }.getOrElse {
            JSONObject().put("ok", false).put("code", "COLOROS_MEMORY_QUERY_FAILED")
        }
        val notificationResult = if (AgentNotificationHistoryService.isEnabled(context)) {
            runCatching {
                val raw = JSONObject(
                    notificationHistory.search(
                        query = query,
                        packageName = "",
                        maxAgeHours = 168,
                        limit = 50,
                    ),
                )
                if (query.isBlank()) {
                    val filtered = JSONArray()
                    val items = raw.optJSONArray("items") ?: JSONArray()
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        val text = listOf("title", "text", "sub_text")
                            .joinToString(" ") { item.optString(it) }
                        if (ORDER_KEYWORDS.any(text::contains)) filtered.put(item)
                        if (filtered.length() >= limit) break
                    }
                    raw.put("items", filtered).put("count", filtered.length())
                }
                raw
            }.getOrElse {
                JSONObject().put("ok", false).put("code", "NOTIFICATION_HISTORY_QUERY_FAILED")
            }
        } else {
            JSONObject()
                .put("ok", false)
                .put("code", "NOTIFICATION_HISTORY_ACCESS_REQUIRED")
                .put("message", "授予通知使用权后可从新通知中识别订单状态")
        }
        return AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", memoryResult.optBoolean("ok") || notificationResult.optBoolean("ok"))
                .put("tool", "search_personal_orders")
                .put("system_memory", memoryResult)
                .put("notification_history", notificationResult)
                .toString(),
            sensitive = true,
        )
    }

    private fun setAlarm(args: JSONObject): String {
        val hour = args.getInt("hour")
        val minute = args.getInt("minute")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_VIBRATE, args.optBoolean("vibrate", true))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.optString("label").trim().takeIf(String::isNotBlank)?.let {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, it)
        }
        val repeatDays = args.optJSONArray("repeat_days")
        if (repeatDays != null && repeatDays.length() > 0) {
            intent.putIntegerArrayListExtra(
                AlarmClock.EXTRA_DAYS,
                ArrayList((0 until repeatDays.length()).map { index ->
                    repeatDays.getString(index).toCalendarDay()
                }),
            )
        }
        return startClockIntent(
            directIntent = intent,
            fallbackAction = AlarmClock.ACTION_SHOW_ALARMS,
            tool = "set_alarm",
        ).put("hour", hour).put("minute", minute).toString()
    }

    private fun setTimer(args: JSONObject): String {
        val seconds = args.getInt("duration_seconds")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.optString("label").trim().takeIf(String::isNotBlank)?.let {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, it)
        }
        return startClockIntent(
            directIntent = intent,
            fallbackAction = AlarmClock.ACTION_SHOW_TIMERS,
            tool = "set_timer",
        ).put("duration_seconds", seconds).toString()
    }

    private fun startClockIntent(
        directIntent: Intent,
        fallbackAction: String,
        tool: String,
    ): JSONObject {
        val packageManager = context.packageManager
        val preferred = Intent(directIntent).setPackage(COLOROS_CLOCK_PACKAGE)
        val direct = when {
            preferred.resolveActivity(packageManager) != null -> preferred
            directIntent.resolveActivity(packageManager) != null -> directIntent
            else -> null
        }
        if (direct != null && runCatching { context.startActivity(direct) }.isSuccess) {
            logger.info("Agent direct tool action=$tool outcome=dispatched")
            return JSONObject().put("ok", true).put("tool", tool).put("mode", "direct")
        }

        val fallback = Intent(fallbackAction)
            .setPackage(COLOROS_CLOCK_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .takeIf { it.resolveActivity(packageManager) != null }
            ?: packageManager.getLaunchIntentForPackage(COLOROS_CLOCK_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (fallback != null && runCatching { context.startActivity(fallback) }.isSuccess) {
            return JSONObject()
                .put("ok", false)
                .put("code", "DIRECT_CLOCK_ACTION_FAILED")
                .put("message", "系统未确认直接创建，已打开时钟页面，请让用户完成确认")
                .put("tool", tool)
                .put("mode", "ui_fallback")
        }
        return JSONObject(error("CLOCK_UNAVAILABLE", "没有可处理该请求的时钟应用"))
    }

    private fun deviceStatus(): String {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val storage = StatFs(Environment.getDataDirectory().absolutePath)
        return JSONObject()
            .put("ok", true)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android_version", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("security_patch", Build.VERSION.SECURITY_PATCH)
            .put("uptime_ms", SystemClock.elapsedRealtime())
            .put("battery_percent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", battery.isCharging)
            .put("memory_available_bytes", memory.availMem)
            .put("memory_total_bytes", memory.totalMem)
            .put("storage_available_bytes", storage.availableBytes)
            .put("storage_total_bytes", storage.totalBytes)
            .toString()
    }

    @Suppress("DEPRECATION")
    private fun networkInfo(): String {
        val connectivity =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val transports = JSONArray().also { array ->
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) array.put("wifi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) array.put("cellular")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) array.put("ethernet")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) array.put("vpn")
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = runCatching { wifi.connectionInfo }.getOrNull()
        val rootWifiStatus = if (rootAvailable()) root.execute("cmd wifi status", maxOutputBytes = 32 * 1024)
            .takeIf { it.ok }
            ?.stdout
            .orEmpty() else ""
        val fallbackSsid = WIFI_STATUS_SSID.find(rootWifiStatus)
            ?.groupValues?.get(1)?.trim()?.trim('"')
        val fallbackRssi = WIFI_STATUS_RSSI.find(rootWifiStatus)
            ?.groupValues?.get(1)?.toIntOrNull()
        return JSONObject()
            .put("ok", true)
            .put("connected", capabilities != null)
            .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("metered", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
            .put("transports", transports)
            .put("wifi_enabled", wifi.isWifiEnabled)
            .also { result ->
                val ssid = info?.ssid?.takeUnless { it == WifiManager.UNKNOWN_SSID }?.trim('"')
                    ?: fallbackSsid
                val rssi = info?.rssi?.takeUnless { it == -127 } ?: fallbackRssi
                ssid?.let { result.put("ssid", it) }
                rssi?.let { result.put("rssi_dbm", it) }
            }
            .toString()
    }

    private fun mediaControl(args: JSONObject): String {
        val keyCode = when (args.getString("action").lowercase(Locale.ROOT)) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return error("INVALID_ARGUMENT", "不支持的媒体动作")
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return ok("media_control").put("action", args.getString("action")).toString()
    }

    private fun setVolume(args: JSONObject): String {
        val streamName = args.getString("stream").lowercase(Locale.ROOT)
        val stream = when (streamName) {
            "media" -> AudioManager.STREAM_MUSIC
            "alarm" -> AudioManager.STREAM_ALARM
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> return error("INVALID_ARGUMENT", "不支持的音量通道")
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val percent = args.getInt("percent")
        val level = ((percent / 100.0) * max).toInt().coerceIn(0, max)
        return runCatching {
            audio.setStreamVolume(stream, level, 0)
            ok("set_volume")
                .put("stream", streamName)
                .put("percent", percent)
                .put("level", audio.getStreamVolume(stream))
                .put("max_level", max)
                .toString()
        }.getOrElse { error("VOLUME_CHANGE_FAILED", "系统拒绝修改该音量通道") }
    }

    private fun getSetting(args: JSONObject): String {
        val namespace = args.getString("namespace").lowercase(Locale.ROOT)
        val key = args.getString("key")
        var publicReadFailure: String? = null
        val publicValue = try {
            when (namespace) {
                "system" -> Settings.System.getString(context.contentResolver, key)
                "secure" -> Settings.Secure.getString(context.contentResolver, key)
                "global" -> Settings.Global.getString(context.contentResolver, key)
                else -> null
            }
        } catch (_: SecurityException) {
            publicReadFailure = "SETTING_ACCESS_DENIED"
            null
        } catch (_: RuntimeException) {
            publicReadFailure = "SETTING_READ_FAILED"
            null
        }
        if (publicReadFailure != null && !rootAvailable()) {
            return error(publicReadFailure, "系统不允许读取此设置，或设置服务暂不可用")
        }
        val rootValue = if (publicValue == null && rootAvailable()) root.execute(
            "settings --user current get ${shellQuote(namespace)} ${shellQuote(key)}",
        ) else null
        if (publicReadFailure != null && rootValue?.ok != true) {
            return error(publicReadFailure, "系统不允许读取此设置，或设置服务暂不可用")
        }
        val value = publicValue ?: rootValue?.takeIf { it.ok }?.stdout
            ?.trim()
            ?.takeUnless { it == "null" }
        return ok("get_setting")
            .put("namespace", namespace)
            .put("key", key)
            .put("value", value ?: JSONObject.NULL)
            .toString()
    }

    private fun setSetting(args: JSONObject): String {
        val namespace = args.getString("namespace").lowercase(Locale.ROOT)
        val key = args.getString("key")
        val result = root.execute(
            "settings --user current put ${shellQuote(namespace)} ${shellQuote(key)} " +
                shellQuote(args.getString("value")),
        )
        return rootMutationResult("set_setting", result)
    }

    private fun setDeviceState(args: JSONObject): String {
        val enabled = args.getBoolean("enabled")
        val command = when (args.getString("target").lowercase(Locale.ROOT)) {
            "wifi" -> "cmd wifi set-wifi-enabled ${if (enabled) "enabled" else "disabled"}"
            "bluetooth" -> "cmd bluetooth_manager ${if (enabled) "enable" else "disable"}"
            else -> return error("INVALID_ARGUMENT", "不支持的设备状态")
        }
        return rootMutationResult("set_device_state", root.execute(command))
    }

    private fun appStateControl(args: JSONObject): String {
        val packageName = args.getString("package_name")
        if (!PACKAGE_NAME.matches(packageName)) return error("INVALID_PACKAGE", "包名格式无效")
        val appExists = runCatching {
            context.packageManager.getApplicationInfo(
                packageName,
                android.content.pm.PackageManager.ApplicationInfoFlags.of(0L),
            )
        }.isSuccess
        if (!appExists) return error("APP_NOT_FOUND", "未找到指定应用")
        val action = args.getString("action").lowercase(Locale.ROOT)
        val command = when (action) {
            "force_stop" -> "am force-stop --user current ${shellQuote(packageName)}"
            "freeze" -> "pm disable-user --user current ${shellQuote(packageName)}"
            "unfreeze" -> "pm enable --user current ${shellQuote(packageName)}"
            else -> return error("INVALID_ARGUMENT", "不支持的应用状态动作")
        }
        return rootMutationResult("app_state_control", root.execute(command))
    }

    private fun topMemoryApps(args: JSONObject): String {
        val limit = args.optInt("limit", 10).coerceIn(1, 30)
        val result = root.execute("ps -A -o PID,RSS,NAME", maxOutputBytes = 512 * 1024)
        if (!result.ok) return rootError(result)
        val items = result.stdout.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 3)
                if (parts.size != 3) return@mapNotNull null
                val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
                val rssKb = parts[1].toLongOrNull() ?: return@mapNotNull null
                ProcessUsage(pid, rssKb, parts[2])
            }
            .sortedByDescending(ProcessUsage::rssKb)
            .take(limit)
            .toList()
        return ok("top_memory_apps")
            .put("items", JSONArray().also { array ->
                items.forEach {
                    array.put(
                        JSONObject()
                            .put("pid", it.pid)
                            .put("process", it.name)
                            .put("rss_bytes", it.rssKb * 1024L),
                    )
                }
            })
            .put("truncated", result.truncated)
            .toString()
    }

    private fun topStorageApps(args: JSONObject): String {
        val limit = args.optInt("limit", 10).coerceIn(1, 30)
        val result = root.execute(
            "dumpsys diskstats",
            timeoutMillis = 20_000L,
            maxOutputBytes = 2 * 1024 * 1024,
        )
        if (!result.ok) return rootError(result)
        val packages = parseJsonArrayLine(result.stdout, "Package Names:")
        val appSizes = parseLongArrayLine(result.stdout, "App Sizes:")
        val dataSizes = parseLongArrayLine(result.stdout, "App Data Sizes:")
        val cacheSizes = parseLongArrayLine(result.stdout, "Cache Sizes:")
        if (packages == null || appSizes == null || dataSizes == null || cacheSizes == null) {
            return error("STORAGE_STATS_UNAVAILABLE", "系统未返回可解析的应用存储统计")
        }
        val items = (0 until packages.length())
            .mapNotNull { index ->
                val packageName = packages.optString(index).takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                StorageUsage(
                    packageName = packageName,
                    appBytes = appSizes.getOrElse(index) { 0L },
                    dataBytes = dataSizes.getOrElse(index) { 0L },
                    cacheBytes = cacheSizes.getOrElse(index) { 0L },
                )
            }
            .sortedByDescending(StorageUsage::totalBytes)
            .take(limit)
        return ok("top_storage_apps")
            .put("items", JSONArray().also { array ->
                items.forEach {
                    array.put(
                        JSONObject()
                            .put("package_name", it.packageName)
                            .put("total_bytes", it.totalBytes)
                            .put("app_bytes", it.appBytes)
                            .put("data_bytes", it.dataBytes)
                            .put("cache_bytes", it.cacheBytes),
                    )
                }
            })
            .toString()
    }

    private fun wifiCredentials(args: JSONObject): String {
        val requestedSsid = args.optString("ssid").trim().trim('"')
        val result = root.execute(
            "cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            maxOutputBytes = 2 * 1024 * 1024,
        )
        if (!result.ok) return rootError(result)
        val networks = NETWORK_BLOCK.findAll(result.stdout).mapNotNull { match ->
            val block = match.value
            val ssid = XML_SSID.find(block)?.groupValues?.get(1)?.decodeXml()?.trim('"')
                ?: return@mapNotNull null
            val password = XML_PSK.find(block)
                ?.groupValues?.get(1)
                ?.decodeXml()
                ?.trim('"')
                ?.takeUnless { it == "null" }
            JSONObject()
                .put("ssid", ssid)
                .put("password", password ?: JSONObject.NULL)
        }.filter {
            requestedSsid.isBlank() || it.optString("ssid").equals(requestedSsid, ignoreCase = true)
        }.distinctBy {
            it.optString("ssid").lowercase(Locale.ROOT)
        }.take(args.optInt("limit", 20).coerceIn(1, 50)).toList()
        return ok("wifi_credentials")
            .put("items", JSONArray(networks))
            .put("count", networks.size)
            .toString()
    }

    private fun recentNotifications(args: JSONObject): String {
        val limit = args.optInt("limit", 10).coerceIn(1, 20)
        val packageFilter = args.optString("package_name").trim()
        if (!rootAvailable()) return listenerNotifications(packageFilter, limit)
        val listed = root.execute("cmd notification list", maxOutputBytes = 256 * 1024)
        if (!listed.ok) return rootError(listed)
        val items = JSONArray()
        listed.stdout.lineSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && (!packageFilter.isNotBlank() || "|$packageFilter|" in it) }
            .take(limit)
            .forEach { key ->
                val detail = root.execute(
                    "cmd notification get ${shellQuote(key)}",
                    maxOutputBytes = 128 * 1024,
                )
                if (!detail.ok) return@forEach
                val text = detail.stdout
                items.put(
                    JSONObject()
                        .put("package_name", NOTIFICATION_PACKAGE.find(text)?.groupValues?.get(1).orEmpty())
                        .put("title", notificationExtra(text, "android.title"))
                        .put("text", notificationExtra(text, "android.text"))
                        .put("sub_text", notificationExtra(text, "android.subText")),
                )
            }
        return ok("recent_notifications").put("items", items).put("count", items.length()).toString()
    }

    private fun listenerNotifications(packageFilter: String, limit: Int): String {
        if (!AgentNotificationHistoryService.isEnabled(context)) {
            return error("NOTIFICATION_ACCESS_REQUIRED", "请先在权限健康页授予 Eta 通知使用权")
        }
        val notifications = AgentNotificationHistoryService.currentNotifications()
            ?: return error("NOTIFICATION_LISTENER_UNAVAILABLE", "通知服务尚未连接，请稍后重试")
        val items = JSONArray()
        notifications.asSequence()
            .filter { packageFilter.isBlank() || it.packageName == packageFilter }
            .sortedByDescending { it.postTime }
            .take(limit)
            .forEach { notification ->
                val extras = notification.notification.extras
                items.put(
                    JSONObject()
                        .put("package_name", notification.packageName)
                        .put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(8_000))
                        .put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().take(8_000))
                        .put("sub_text", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().take(8_000)),
                )
            }
        return ok("recent_notifications")
            .put("source", "notification_listener")
            .put("items", items)
            .put("count", items.length())
            .toString()
    }

    private fun readSmsCode(args: JSONObject): String {
        val maxAgeMinutes = args.optInt("max_age_minutes", 10).coerceIn(1, 1_440)
        val result = root.execute(
            "content query --uri content://sms/inbox --projection address:body:date --sort 'date DESC'",
            maxOutputBytes = 512 * 1024,
        )
        if (!result.ok) return rootError(result)
        val cutoff = System.currentTimeMillis() - maxAgeMinutes * 60_000L
        val items = JSONArray()
        result.stdout.lineSequence().forEach { line ->
            if (items.length() >= 10) return@forEach
            val date = SMS_DATE.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return@forEach
            if (date < cutoff) return@forEach
            val body = SMS_BODY.find(line)?.groupValues?.get(1).orEmpty()
            val contextMatch = OTP_CONTEXT.find(body) ?: return@forEach
            val code = OTP.findAll(body)
                .minByOrNull { match ->
                    kotlin.math.abs(match.range.first - contextMatch.range.first)
                }
                ?.groupValues?.get(1)
                ?: return@forEach
            items.put(
                JSONObject()
                    .put("code", code)
                    .put("sender", SMS_ADDRESS.find(line)?.groupValues?.get(1).orEmpty())
                    .put("timestamp_ms", date),
            )
        }
        return ok("read_sms_code").put("items", items).put("count", items.length()).toString()
    }

    private fun getLogcat(args: JSONObject): String {
        val maxLines = args.optInt("max_lines", 200).coerceIn(20, 500)
        val query = args.optString("query").trim()
        val result = root.execute(
            "logcat -d -v threadtime -t $maxLines",
            maxOutputBytes = 512 * 1024,
        )
        if (!result.ok) return rootError(result)
        val lines = result.stdout.lineSequence()
            .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            .take(maxLines)
            .toList()
        return ok("get_logcat")
            .put("lines", JSONArray(lines))
            .put("count", lines.size)
            .put("truncated", result.truncated)
            .toString()
    }

    private fun rootMutationResult(
        tool: String,
        result: BoundedRootCommandExecutor.Result,
    ): String = if (result.ok) {
        ok(tool).put("changed", true).toString()
    } else {
        rootError(result)
    }

    private fun rootError(result: BoundedRootCommandExecutor.Result): String {
        val code = when {
            result.errorCode.isNotBlank() -> result.errorCode
            result.timedOut -> "ROOT_COMMAND_TIMEOUT"
            else -> "ROOT_COMMAND_FAILED"
        }
        return error(code, "Root 系统接口执行失败（exit=${result.exitCode}）")
    }

    private fun parseJsonArrayLine(source: String, prefix: String): JSONArray? =
        source.lineSequence().firstOrNull { it.startsWith(prefix) }
            ?.substringAfter(prefix)?.trim()
            ?.let { runCatching { JSONArray(it) }.getOrNull() }

    private fun parseLongArrayLine(source: String, prefix: String): List<Long>? {
        val array = parseJsonArrayLine(source, prefix) ?: return null
        return (0 until array.length()).map { array.optLong(it) }
    }

    private fun notificationExtra(source: String, key: String): String? =
        Regex("""(?m)^\s*${Regex.escape(key)}=[^(]+\((.*)\)\s*$""")
            .find(source)?.groupValues?.get(1)?.takeUnless { it == "null" }

    private fun String.toCalendarDay(): Int = when (lowercase(Locale.ROOT)) {
        "sun" -> Calendar.SUNDAY
        "mon" -> Calendar.MONDAY
        "tue" -> Calendar.TUESDAY
        "wed" -> Calendar.WEDNESDAY
        "thu" -> Calendar.THURSDAY
        "fri" -> Calendar.FRIDAY
        "sat" -> Calendar.SATURDAY
        else -> throw IllegalArgumentException("不支持的重复日期")
    }

    private fun String.decodeXml(): String =
        replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun ok(tool: String): JSONObject =
        JSONObject().put("ok", true).put("tool", tool)

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun text(content: String) = AgentModelClient.ToolResult(content)

    private fun sensitive(content: String) =
        AgentModelClient.ToolResult(content = content, sensitive = true)

    private data class ProcessUsage(val pid: Int, val rssKb: Long, val name: String)

    private data class StorageUsage(
        val packageName: String,
        val appBytes: Long,
        val dataBytes: Long,
        val cacheBytes: Long,
    ) {
        val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
    }

    private companion object {
        val ORDER_KEYWORDS = listOf(
            "订单", "外卖", "取餐", "配送", "骑手", "送达", "商家", "快递", "车票", "机票",
            "酒店", "电影票",
        )
        const val COLOROS_CLOCK_PACKAGE = "com.coloros.alarmclock"
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        val NETWORK_BLOCK = Regex("<Network>.*?</Network>", setOf(RegexOption.DOT_MATCHES_ALL))
        val XML_SSID = Regex("""<string name="SSID">(.*?)</string>""")
        val XML_PSK = Regex("""<string name="PreSharedKey">(.*?)</string>""")
        val NOTIFICATION_PACKAGE = Regex("""NotificationRecord\([^:]+:\s+pkg=([^\s]+)""")
        val SMS_ADDRESS = Regex("""(?:^|,\s*)address=([^,]*)""")
        val SMS_BODY = Regex("""(?:^|,\s*)body=(.*?)(?:,\s*date=|$)""")
        val SMS_DATE = Regex("""(?:^|,\s*)date=(\d+)""")
        val OTP = Regex("""(?<!\d)(\d{4,8})(?!\d)""")
        val OTP_CONTEXT = Regex(
            """验证码|校验码|动态码|确认码|一次性密码|verification\s*code|one[- ]time\s*(?:code|password)|\botp\b""",
            RegexOption.IGNORE_CASE,
        )
        val WIFI_STATUS_SSID = Regex("""\bSSID:\s*([^,\r\n]+)""")
        val WIFI_STATUS_RSSI = Regex("""\bRSSI:\s*(-?\d+)""")
    }
}
