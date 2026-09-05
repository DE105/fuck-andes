package io.github.mangi.eta.agent.device

import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal class RootShellDeviceController(
    private val logger: AgentLogger,
    private val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) {
    data class Observation(
        val content: String,
        val image: AgentModelClient.ModelImage?,
        val elementObservation: ElementObservation?,
        val coordinateSpace: CoordinateSpace?,
    )

    data class ElementObservation(
        val id: String,
        val source: ElementSource,
        val packageName: String,
        val windowId: Int?,
        val nodes: List<UiNode>,
        val maxNodes: Int,
        val truncated: Boolean,
        val treeSignature: String = "",
        internal val accessibilitySnapshot: AgentAccessibilityService.NodeSnapshot? = null,
    )

    enum class ElementSource(val wireName: String) {
        ACCESSIBILITY("accessibility"),
        UIAUTOMATOR("uiautomator"),
    }

    data class CoordinateSpace(
        val screenWidth: Int,
        val screenHeight: Int,
        val screenshotWidth: Int,
        val screenshotHeight: Int
    ) {
        fun fromScreenshot(x: Int, y: Int): ScreenPoint {
            require(x in 0 until screenshotWidth && y in 0 until screenshotHeight) {
                "截图坐标超出范围：($x,$y) not in ${screenshotWidth}x$screenshotHeight"
            }
            return ScreenPoint(
                x = (x.toFloat() * screenWidth / screenshotWidth).toInt(),
                y = (y.toFloat() * screenHeight / screenshotHeight).toInt()
            )
        }

        fun summary(): String =
            "screen=${screenWidth}x$screenHeight,screenshot=${screenshotWidth}x$screenshotHeight"
    }

    data class ScreenPoint(val x: Int, val y: Int)

    fun screenDimensions(): Pair<Int, Int> = screenSize()

    data class UiNode(
        val index: Int,
        val text: String,
        val desc: String,
        val className: String,
        val packageName: String,
        val viewId: String,
        val bounds: Rect,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val focused: Boolean,
        val editable: Boolean,
        val password: Boolean,
        val enabled: Boolean
    ) {
        val centerX: Int get() = bounds.centerX()
        val centerY: Int get() = bounds.centerY()
    }

    fun observe(includeScreenshot: Boolean, includeUiTree: Boolean, maxNodes: Int): Observation {
        val accessibility = AgentAccessibilityService.current()
        if (accessibility == null && !rootAvailable()) {
            return Observation(accessibilityUnavailable(), null, null, null)
        }
        val nodeLimit = maxNodes.coerceIn(1, 120)
        val display = screenSize()
        val focus = accessibility
            ?.currentPackageName()
            ?.takeIf { it.isNotBlank() }
            ?.let { packageName ->
                JSONObject()
                    .put("package", packageName)
                    .put("component", packageName)
                    .put("source", "accessibility")
            }
            ?: focusedWindow()
        val elementObservation = if (includeUiTree) {
            val accessibilitySnapshot = accessibility?.captureNodeSnapshot(nodeLimit)
            if (accessibilitySnapshot != null) {
                ElementObservation(
                    id = accessibilitySnapshot.id,
                    source = ElementSource.ACCESSIBILITY,
                    packageName = accessibilitySnapshot.packageName,
                    windowId = accessibilitySnapshot.windowId,
                    nodes = accessibilitySnapshot.nodes.map { it.toUiNode() },
                    maxNodes = nodeLimit,
                    truncated = accessibilitySnapshot.truncated,
                    accessibilitySnapshot = accessibilitySnapshot,
                )
            } else if (rootAvailable()) {
                val rootNodes = dumpUiNodes(nodeLimit)
                ElementObservation(
                    id = "u${ROOT_OBSERVATION_IDS.incrementAndGet()}",
                    source = ElementSource.UIAUTOMATOR,
                    packageName = rootNodes.firstOrNull()?.packageName.orEmpty(),
                    windowId = null,
                    nodes = rootNodes,
                    maxNodes = nodeLimit,
                    truncated = rootNodes.size >= nodeLimit,
                    treeSignature = uiTreeSignature(rootNodes),
                )
            } else {
                null
            }
        } else {
            null
        }
        val nodes = elementObservation?.nodes.orEmpty()
        val capture = if (includeScreenshot) captureScreenshot() else ScreenCapture.notRequested()
        val image = capture.image
        val coordinateSpace = if (image?.width != null && image.height != null) {
            CoordinateSpace(
                screenWidth = display.first,
                screenHeight = display.second,
                screenshotWidth = image.width,
                screenshotHeight = image.height
            )
        } else {
            null
        }
        val json = JSONObject()
            .put("ok", true)
            .put("tool", "observe_screen")
            .put("screen", JSONObject().put("width", display.first).put("height", display.second))
            .put(
                "accessibility",
                JSONObject()
                    .put("available", accessibility != null)
                    .put("package", accessibility?.currentPackageName().orEmpty())
                    .put(
                        "note",
                        if (accessibility != null) {
                            "节点来自无障碍服务，支持 tap_element、replace_text、clear_text、scroll_element 等稳定节点动作"
                        } else {
                            "无障碍服务未启用，节点来自 uiautomator；坐标工具会回退到 Root Shell"
                        }
                    )
            )
            .put(
                "coordinate_contract",
                if (coordinateSpace == null) {
                    JSONObject()
                        .put("default_coordinate_space", "screen")
                        .put("note", "未附加截图，坐标工具使用真实设备屏幕坐标")
                } else {
                    JSONObject()
                        .put("default_coordinate_space", "screenshot")
                        .put(
                            "screenshot",
                            JSONObject()
                                .put("width", coordinateSpace.screenshotWidth)
                                .put("height", coordinateSpace.screenshotHeight)
                        )
                        .put(
                            "screen",
                            JSONObject()
                                .put("width", coordinateSpace.screenWidth)
                                .put("height", coordinateSpace.screenHeight)
                        )
                        .put(
                            "scale_to_screen",
                            JSONObject()
                                .put("x", coordinateSpace.screenWidth.toDouble() / coordinateSpace.screenshotWidth)
                                .put("y", coordinateSpace.screenHeight.toDouble() / coordinateSpace.screenshotHeight)
                        )
                        .put("note", "tap、tap_area、long_press、swipe 默认接收截图像素坐标；ui_nodes.center 是 screen 坐标")
                }
            )
            .put("focus", focus)
            .put("observation_id", elementObservation?.id ?: JSONObject.NULL)
            .put("observation_source", elementObservation?.source?.wireName ?: JSONObject.NULL)
            .put("window_id", elementObservation?.windowId ?: JSONObject.NULL)
            .put("node_limit", elementObservation?.maxNodes ?: 0)
            .put("ui_tree_truncated", elementObservation?.truncated ?: false)
            .put("ui_nodes", nodes.toJsonArray())
            .put(
                "screenshot",
                if (image == null) {
                    capture.toJson().put("attached", false)
                } else {
                    capture.toJson()
                        .put("attached", true)
                        .put("mime_type", image.mimeType)
                        .put("bytes", image.bytes)
                        .put("width", image.width)
                        .put("height", image.height)
                }
            )
        return Observation(json.toString(), image, elementObservation, coordinateSpace)
    }

    fun tap(x: Int, y: Int): String {
        if (AgentAccessibilityService.current() == null && !rootAvailable()) return accessibilityUnavailable()
        validatePoint(x, y)
        AgentAccessibilityService.current()?.let { service ->
            val result = service.gestureTap(x.toFloat(), y.toFloat())
            if (result.ok) {
                waitForUiSettle("tap")
                return nodeActionJson("tap", result)
            }
            if (!GestureFallbackPolicy.mayFallbackToRoot(result.code)) {
                return nodeActionJson("tap", result)
            }
            if (!rootAvailable()) return nodeActionJson("tap", result)
        }
        return inputCommand("input tap $x $y", "tap")
    }

    fun longPress(x: Int, y: Int, durationMs: Int): String {
        if (AgentAccessibilityService.current() == null && !rootAvailable()) return accessibilityUnavailable()
        validatePoint(x, y)
        val duration = durationMs.coerceIn(300, 3_000)
        AgentAccessibilityService.current()?.let { service ->
            val result = service.gestureTap(x.toFloat(), y.toFloat(), duration.toLong())
            if (result.ok) {
                waitForUiSettle("long_press")
                return nodeActionJson("long_press", result.copy(method = "GESTURE_LONG_PRESS"))
            }
            if (!GestureFallbackPolicy.mayFallbackToRoot(result.code)) {
                return nodeActionJson("long_press", result)
            }
            if (!rootAvailable()) return nodeActionJson("long_press", result)
        }
        return inputCommand("input swipe $x $y $x $y $duration", "long_press")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String {
        if (AgentAccessibilityService.current() == null && !rootAvailable()) return accessibilityUnavailable()
        validatePoint(x1, y1)
        validatePoint(x2, y2)
        val duration = durationMs.coerceIn(100, 2_000)
        AgentAccessibilityService.current()?.let { service ->
            val result = service.gestureSwipe(
                x1.toFloat(),
                y1.toFloat(),
                x2.toFloat(),
                y2.toFloat(),
                duration.toLong(),
            )
            if (result.ok) {
                waitForUiSettle("swipe")
                return nodeActionJson("swipe", result)
            }
            if (!GestureFallbackPolicy.mayFallbackToRoot(result.code)) {
                return nodeActionJson("swipe", result)
            }
            if (!rootAvailable()) return nodeActionJson("swipe", result)
        }
        return inputCommand("input swipe $x1 $y1 $x2 $y2 $duration", "swipe")
    }

    fun scroll(direction: String): String {
        val parsed = ScrollDirection.parse(direction)
            ?: return scrollErrorJson(
                "scroll",
                null,
                "INVALID_ARGUMENT",
                "direction 仅支持 up/down/left/right",
            )
        AgentAccessibilityService.current()?.let { service ->
            return scrollActionJson("scroll", service.scrollCurrent(parsed))
        }
        if (!rootAvailable()) return accessibilityUnavailable()
        val beforeNodes = dumpUiNodes(120)
        val targetBounds = beforeNodes
            .asSequence()
            .filter(UiNode::scrollable)
            .maxByOrNull { node -> node.bounds.width().toLong() * node.bounds.height().toLong() }
            ?.bounds
            ?: screenContentBounds()
        return rootScroll(
            tool = "scroll",
            direction = parsed,
            bounds = targetBounds,
            beforeNodes = beforeNodes,
            maxNodes = 120,
            targetIndex = null,
        )
    }

    fun inputText(text: String): String {
        if (text.isEmpty()) return errorJson("INVALID_ARGUMENT", "text 不能为空")
        if (text.length > MAX_INPUT_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "input_text 最多支持 $MAX_INPUT_TEXT_CHARS 个字符")
        }
        AgentAccessibilityService.current()?.let { service ->
            val result = service.inputTextFocused(text)
            if (result.ok) {
                waitForUiSettle("input_text")
                return nodeActionJson("input_text", result)
            }
            return nodeActionJson("input_text", result)
        }
        return errorJson(
            "ACCESSIBILITY_UNAVAILABLE",
            "input_text 需要无障碍服务确认真实输入焦点；本次未发送任何按键",
        )
    }

    fun replaceText(
        text: String,
        index: Int?,
        observation: ElementObservation?,
    ): String {
        if (text.length > MAX_REPLACE_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "replace_text 最多支持 $MAX_REPLACE_TEXT_CHARS 个字符")
        }
        AgentAccessibilityService.current()?.let { service ->
            val snapshot = observation?.accessibilitySnapshot
            val result = service.setTextNode(snapshot, index, text)
            if (result.ok) {
                waitForUiSettle("replace_text")
                return nodeActionJson("replace_text", result)
            }
            return nodeActionJson("replace_text", result)
        }
        return errorJson("ACCESSIBILITY_UNAVAILABLE", "replace_text 需要先启用 Eta Agent 无障碍服务")
    }

    fun clearText(index: Int?, observation: ElementObservation?): String =
        replaceText("", index, observation).let { result ->
            val json = JSONObject(result)
            json.put("tool", "clear_text").toString()
        }

    fun tapElement(observation: ElementObservation, index: Int): String {
        val snapshot = observation.accessibilitySnapshot
        if (snapshot != null) {
            val service = AgentAccessibilityService.current()
                ?: return errorJson("ACCESSIBILITY_UNAVAILABLE", "无障碍服务已断开，请重新观察屏幕")
            val result = service.clickNode(snapshot, index)
            if (result.ok) {
                waitForUiSettle("tap")
                return nodeActionJson("tap_element", result)
            }
            return nodeActionJson("tap_element", result)
        }
        if (!rootAvailable()) return rootRequired()
        val resolved = resolveUiAutomatorNode(observation, index)
            ?: return errorJson("STALE_NODE", "无法在当前界面唯一确认目标节点，请重新观察屏幕")
        val node = resolved.node
        return tap(node.centerX, node.centerY).rewriteTool("tap_element")
    }

    fun longPressElement(
        observation: ElementObservation,
        index: Int,
        durationMs: Int,
    ): String {
        val snapshot = observation.accessibilitySnapshot
        if (snapshot != null) {
            val service = AgentAccessibilityService.current()
                ?: return errorJson("ACCESSIBILITY_UNAVAILABLE", "无障碍服务已断开，请重新观察屏幕")
            val result = service.longClickNode(snapshot, index, durationMs.toLong())
            if (result.ok) {
                waitForUiSettle("long_press")
                return nodeActionJson("long_press_element", result)
            }
            return nodeActionJson("long_press_element", result)
        }
        if (!rootAvailable()) return rootRequired()
        val resolved = resolveUiAutomatorNode(observation, index)
            ?: return errorJson("STALE_NODE", "无法在当前界面唯一确认目标节点，请重新观察屏幕")
        val node = resolved.node
        return longPress(node.centerX, node.centerY, durationMs).rewriteTool("long_press_element")
    }

    fun scrollElement(
        observation: ElementObservation,
        index: Int,
        direction: String,
    ): String {
        val parsed = ScrollDirection.parse(direction)
            ?: return scrollErrorJson(
                "scroll_element",
                null,
                "INVALID_ARGUMENT",
                "direction 仅支持 up/down/left/right",
            )
        val snapshot = observation.accessibilitySnapshot
        if (snapshot != null) {
            val service = AgentAccessibilityService.current()
                ?: return scrollErrorJson(
                    "scroll_element",
                    parsed,
                    "ACCESSIBILITY_UNAVAILABLE",
                    "无障碍服务已断开，请重新观察屏幕",
                )
            return scrollActionJson(
                tool = "scroll_element",
                result = service.scrollNode(snapshot, index, parsed),
            )
        }
        if (!rootAvailable()) return rootRequired()
        val resolved = resolveUiAutomatorNode(observation, index)
            ?: return scrollErrorJson(
                "scroll_element",
                parsed,
                "STALE_NODE",
                "无法在当前界面唯一确认滚动目标，请重新观察屏幕",
            )
        val node = resolved.node
        if (!node.scrollable) {
            return scrollErrorJson(
                "scroll_element",
                parsed,
                "NOT_SCROLLABLE",
                "指定节点不可滚动",
            )
        }
        return rootScroll(
            tool = "scroll_element",
            direction = parsed,
            bounds = node.bounds,
            beforeNodes = resolved.currentNodes,
            maxNodes = observation.maxNodes,
            targetIndex = index,
        )
    }

    fun pressKey(button: String): String {
        val normalized = button.uppercase()
        if (normalized == "PASTE" && !rootAvailable()) return rootRequired()
        AgentAccessibilityService.current()?.let { service ->
            when (normalized) {
                "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS" -> {
                    val actionResult = service.globalActionResult(normalized)
                    if (actionResult.ok) {
                        waitForUiSettle("press_key")
                        return okJson("press_key", "accessibility").let {
                            JSONObject(it).put("button", normalized).toString()
                        }
                    }
                    if (actionResult.code == "ACTION_OUTCOME_UNKNOWN") {
                        return nodeActionJson("press_key", actionResult).let {
                            JSONObject(it).put("button", normalized).toString()
                        }
                    }
                    if (!rootAvailable()) return nodeActionJson("press_key", actionResult)
                }
                "ENTER" -> {
                    val result = service.imeEnter()
                    if (result.ok) {
                        waitForUiSettle("press_key")
                        return nodeActionJson("press_key", result).let {
                            JSONObject(it).put("button", normalized).toString()
                        }
                    }
                    return nodeActionJson("press_key", result).let {
                        JSONObject(it).put("button", normalized).toString()
                    }
                }
            }
        }
        if (!rootAvailable()) return accessibilityUnavailable()
        val keyCode = when (normalized) {
            "BACK" -> 4
            "HOME" -> 3
            "ENTER" -> 66
            "RECENTS" -> 187
            "PASTE" -> 279
            "NOTIFICATIONS" -> return inputCommand(
                "cmd statusbar expand-notifications",
                "press_key",
            ).let { JSONObject(it).put("button", normalized).toString() }
            "QUICK_SETTINGS" -> return inputCommand(
                "cmd statusbar expand-settings",
                "press_key",
            ).let { JSONObject(it).put("button", normalized).toString() }
            else -> return errorJson("INVALID_ARGUMENT", "button 仅支持 BACK/HOME/ENTER/RECENTS/PASTE/NOTIFICATIONS/QUICK_SETTINGS")
        }
        return inputCommand("input keyevent $keyCode", "press_key")
    }

    fun waitMs(durationMs: Int): String {
        val duration = durationMs.coerceIn(100, 30_000)
        Thread.sleep(duration.toLong())
        return JSONObject()
            .put("ok", true)
            .put("tool", "wait")
            .put("duration_ms", duration)
            .toString()
    }

    fun waitForText(text: String, timeoutMs: Int, includeDesc: Boolean, matchMode: String): String {
        val needle = text.trim()
        if (needle.isBlank()) return errorJson("INVALID_ARGUMENT", "text 不能为空")
        val timeout = timeoutMs.coerceIn(500, 60_000)
        val deadline = System.currentTimeMillis() + timeout
        var attempts = 0
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            val service = AgentAccessibilityService.current()
            if (service == null && !rootAvailable()) return accessibilityUnavailable()
            val nodes = service
                ?.queryNodes(120)
                ?.map { it.toUiNode() }
                ?.takeIf { it.isNotEmpty() }
                ?: if (rootAvailable()) dumpUiNodes(120) else emptyList()
            val match = nodes.firstOrNull { node ->
                val haystacks = if (includeDesc) listOf(node.text, node.desc) else listOf(node.text)
                haystacks.any { value -> matches(value, needle, matchMode) }
            }
            if (match != null) {
                val matchedNode = match.toJson()
                matchedNode.remove("index")
                matchedNode.put("actionable", false)
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "wait_for_text")
                    .put("attempts", attempts)
                    .put("matched_node", matchedNode)
                    .put("note", "等待查询不会发布元素快照；如需节点动作，请重新调用 observe_screen")
                    .toString()
            }
            Thread.sleep(350)
        }
        return JSONObject()
            .put("ok", false)
            .put("tool", "wait_for_text")
            .put("code", "TIMEOUT")
            .put("message", "等待文本超时：$needle")
            .put("attempts", attempts)
            .toString()
    }

    fun waitForPackage(packageName: String, timeoutMs: Int): String {
        val target = packageName.trim()
        if (target.isBlank()) return errorJson("INVALID_ARGUMENT", "package_name 不能为空")
        val timeout = timeoutMs.coerceIn(500, 60_000)
        val deadline = System.currentTimeMillis() + timeout
        var attempts = 0
        var lastPackage = ""
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            val service = AgentAccessibilityService.current()
            if (service == null && !rootAvailable()) return accessibilityUnavailable()
            lastPackage = service?.currentPackageName().orEmpty()
            if (lastPackage == target) {
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "wait_for_package")
                    .put("package_name", target)
                    .put("attempts", attempts)
                    .toString()
            }
            val focus = focusedWindow()
            if (focus.optString("package") == target) {
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "wait_for_package")
                    .put("package_name", target)
                    .put("attempts", attempts)
                    .put("focus", focus)
                    .toString()
            }
            Thread.sleep(350)
        }
        return JSONObject()
            .put("ok", false)
            .put("tool", "wait_for_package")
            .put("code", "TIMEOUT")
            .put("message", "等待应用前台超时：$target")
            .put("last_package", lastPackage)
            .put("attempts", attempts)
            .toString()
    }

    fun clipboardSet(context: Context, text: String): String {
        if (text.length > MAX_CLIPBOARD_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "剪贴板文本最多支持 $MAX_CLIPBOARD_TEXT_CHARS 个字符")
        }
        val serviceResult = AgentAccessibilityService.current()?.copyToClipboard(text)
        val ok = serviceResult?.ok ?: runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("eta_agent", text))
            true
        }.getOrDefault(false)
        val json = JSONObject()
            .put("ok", ok)
            .put("tool", "set_clipboard")
            .put("chars", text.length)
        if (!ok) {
            json
                .put("code", serviceResult?.code?.ifBlank { null } ?: "CLIPBOARD_WRITE_FAILED")
                .put(
                    "message",
                    serviceResult?.message?.ifBlank { null } ?: "写入系统剪贴板失败",
                )
        }
        return json.toString()
    }

    fun clipboardGet(context: Context): String {
        val serviceResult = AgentAccessibilityService.current()?.readClipboard()
        val result = serviceResult ?: run {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = runCatching { clipboard.primaryClip }.getOrNull()
            if (clip == null || clip.itemCount <= 0) {
                AgentAccessibilityService.ClipboardReadResult.failure()
            } else {
                AgentAccessibilityService.ClipboardReadResult(
                    ok = true,
                    text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty(),
                )
            }
        }
        val json = JSONObject()
            .put("ok", result.ok)
            .put("tool", "get_clipboard")
            .put("text", result.text.take(8_000))
            .put("truncated", result.text.length > 8_000)
        if (!result.ok) {
            json
                .put("code", result.code)
                .put("message", "剪贴板为空，或当前应用无权读取剪贴板")
        }
        return json.toString()
    }

    fun pasteText(text: String): String {
        if (text.length > MAX_CLIPBOARD_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "paste_text 最多支持 $MAX_CLIPBOARD_TEXT_CHARS 个字符")
        }
        AgentAccessibilityService.current()?.let { service ->
            val result = service.pasteText(text)
            if (result.ok) {
                waitForUiSettle("input_text")
                return nodeActionJson("paste_text", result)
            }
            return nodeActionJson("paste_text", result)
        }
        return errorJson(
            "ACCESSIBILITY_UNAVAILABLE",
            "paste_text 需要无障碍服务确认真实输入焦点；本次未修改剪贴板",
        )
    }

    fun openSystemPanel(panel: String): String {
        val normalized = panel.lowercase()
        val accessibilityAction = when (normalized) {
            "notifications", "notification" -> "NOTIFICATIONS"
            "quick_settings", "quicksettings", "settings" -> "QUICK_SETTINGS"
            else -> return errorJson("INVALID_ARGUMENT", "panel 仅支持 notifications/quick_settings")
        }
        AgentAccessibilityService.current()?.let { service ->
            val actionResult = service.globalActionResult(accessibilityAction)
            if (actionResult.ok) {
                waitForUiSettle("press_key")
                return okJson("open_system_panel", "accessibility").let {
                    JSONObject(it).put("panel", normalized).toString()
                }
            }
            if (actionResult.code == "ACTION_OUTCOME_UNKNOWN") {
                return nodeActionJson("open_system_panel", actionResult).let {
                    JSONObject(it).put("panel", normalized).toString()
                }
            }
            if (!rootAvailable()) return nodeActionJson("open_system_panel", actionResult)
        }
        if (!rootAvailable()) return accessibilityUnavailable()
        val command = when (accessibilityAction) {
            "NOTIFICATIONS" -> "cmd statusbar expand-notifications"
            else -> "cmd statusbar expand-settings"
        }
        return inputCommand(command, "open_system_panel")
    }

    private fun captureScreenshot(): ScreenCapture {
        val excludedPackages = screenshotExcludedPackages()
        // 优先用无障碍截图：takeScreenshotOfWindow 逐窗口过滤 TYPE_ACCESSIBILITY_OVERLAY，
        // 天然排除浮层（glow/orb/bubble 等），对 Agent 透明
        val service = AgentAccessibilityService.current()
        if (service != null) {
            val captureStartedAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                service.captureScreenshotExcludingOverlays(excludedPackages)
            }.getOrNull()
            val bitmap = result?.bitmap
            if (bitmap != null) {
                val capturedAt = SystemClock.elapsedRealtime()
                val image = try {
                    runCatching {
                        AgentImageCodec.fromScreenBitmap(bitmap, source = "screen")
                    }.onFailure { throwable ->
                        logger.warn(
                            "Agent device action=encode_screenshot outcome=failed " +
                                "source=accessibility type=${throwable.javaClass.simpleName}"
                        )
                    }.getOrNull()
                } finally {
                    bitmap.recycle()
                }
                if (image != null) {
                    logger.debug {
                        "Agent device action=capture_screenshot outcome=completed source=accessibility " +
                            "capture_ms=${capturedAt - captureStartedAt} " +
                            "encode_ms=${SystemClock.elapsedRealtime() - capturedAt} " +
                            "image=${image.width}x${image.height} bytes=${image.bytes}"
                    }
                }
                if (image != null && image.bytes > 0) {
                    return ScreenCapture(
                        image = image,
                        source = "accessibility",
                        complete = result.complete,
                        partial = result.partial,
                        expectedWindows = result.expectedWindows,
                        capturedWindows = result.capturedWindows,
                        missingWindowIds = result.missingWindowIds,
                        failureCodes = result.failureCodes,
                        timedOut = result.timedOut,
                        criticalWindowMissing = result.criticalWindowMissing,
                    )
                }
            }
            if (result?.criticalWindowMissing == true) {
                logger.warn(
                    "Agent device action=capture_screenshot outcome=failed " +
                        "reason=critical_window_missing failures=${result.failureCodes.size}"
                )
                return ScreenCapture(
                    image = null,
                    source = "accessibility",
                    complete = false,
                    partial = false,
                    expectedWindows = result.expectedWindows,
                    capturedWindows = result.capturedWindows,
                    missingWindowIds = result.missingWindowIds,
                    failureCodes = result.failureCodes,
                    timedOut = result.timedOut,
                    criticalWindowMissing = true,
                )
            }
        }
        if (
            !rootAvailable() ||
            !ScreenshotOutcomePolicy.mayFallbackToRoot(
                excludedPackagesPresent = excludedPackages.isNotEmpty(),
                criticalWindowMissing = false,
            )
        ) {
            logger.warn(
                "Agent device action=capture_screenshot outcome=failed " +
                    "reason=package_exclusion_unavailable excludedPackages=${excludedPackages.size}"
            )
            return ScreenCapture.failed(source = "accessibility")
        }
        logger.debug {
            "Agent device action=capture_screenshot outcome=fallback source=root"
        }
        // 无需排除入口窗口时才回退 root screencap；否则宁可返回无图，也不把错误浮窗交给模型。
        val result = runSuBytes("screencap -p", timeoutSeconds = 8)
        if (result.exitCode != 0 || result.output.isEmpty()) {
            logger.warn(
                "Agent device action=capture_screenshot outcome=failed source=root " +
                    "exitCode=${result.exitCode} outputBytes=${result.output.size} " +
                    "errorChars=${result.stderr.length}"
            )
            return ScreenCapture.failed(source = "root")
        }
        val encodeStartedAt = SystemClock.elapsedRealtime()
        val image = runCatching {
            AgentImageCodec.fromScreenBytes(result.output, source = "screen")
        }.onFailure { throwable ->
            logger.warn(
                "Agent device action=encode_screenshot outcome=failed source=root " +
                    "type=${throwable.javaClass.simpleName}"
            )
        }.getOrNull() ?: return ScreenCapture.failed(source = "root")
        logger.debug {
            "Agent device action=capture_screenshot outcome=completed source=root " +
                "encode_ms=${SystemClock.elapsedRealtime() - encodeStartedAt} " +
                "image=${image.width}x${image.height} bytes=${image.bytes}"
        }
        return ScreenCapture(
            image = image,
            source = "root",
            complete = true,
            partial = false,
            expectedWindows = 1,
            capturedWindows = 1,
        )
    }

    private fun dumpUiNodes(maxNodes: Int): List<UiNode> {
        if (!rootAvailable()) return emptyList()
        val result = runSuText(
            "uiautomator dump --compressed /data/local/tmp/eta_window.xml >/dev/null && " +
                "cat /data/local/tmp/eta_window.xml && rm -f /data/local/tmp/eta_window.xml",
            timeoutSeconds = 10
        )
        if (result.exitCode != 0 || result.output.isBlank()) {
            logger.warn(
                "Agent device action=dump_ui outcome=failed source=root " +
                    "exitCode=${result.exitCode} outputChars=${result.output.length}"
            )
            return emptyList()
        }
        return parseUiNodes(result.output, maxNodes)
    }

    private fun parseUiNodes(xml: String, maxNodes: Int): List<UiNode> {
        val nodes = mutableListOf<UiNode>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && nodes.size < maxNodes) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                val visible = parser.attr("visible-to-user") != "false"
                val bounds = parser.attr("bounds").toRectOrNull()
                if (visible && bounds != null && bounds.width() > 2 && bounds.height() > 2) {
                    val text = parser.attr("text").take(120)
                    val desc = parser.attr("content-desc").take(120)
                    val clickable = parser.attr("clickable").toBoolean()
                    val scrollable = parser.attr("scrollable").toBoolean()
                    val focused = parser.attr("focused").toBoolean()
                    val enabled = parser.attr("enabled") != "false"
                    if (text.isNotBlank() || desc.isNotBlank() || clickable || scrollable || focused) {
                        nodes += UiNode(
                            index = nodes.size,
                            text = text,
                            desc = desc,
                            className = parser.attr("class"),
                            packageName = parser.attr("package"),
                            viewId = parser.attr("resource-id"),
                            bounds = bounds,
                            clickable = clickable,
                            longClickable = parser.attr("long-clickable").toBoolean(),
                            scrollable = scrollable,
                            focused = focused,
                            editable = parser.attr("class").contains("EditText", ignoreCase = true),
                            password = parser.attr("password").toBoolean(),
                            enabled = enabled
                        )
                    }
                }
            }
            event = parser.next()
        }
        return nodes
    }

    private fun focusedWindow(): JSONObject {
        if (!rootAvailable()) return JSONObject().put("source", "unavailable")
        val result = runSuText("dumpsys window", timeoutSeconds = 8)
        val focused = FocusedWindowParser.parse(result.output)
        return JSONObject()
            .put("raw", focused?.rawLine?.take(240).orEmpty())
            .put("component", focused?.component.orEmpty())
            .put("package", focused?.packageName.orEmpty())
    }

    private fun screenSize(): Pair<Int, Int> {
        AgentAccessibilityService.current()?.displaySize()?.let { return it }
        if (!rootAvailable()) throw DeviceControlUnavailableException()
        val result = runSuText("wm size", timeoutSeconds = 5)
        return AndroidDisplaySizeParser.parse(result.output)
            ?: error("无法读取屏幕尺寸：${result.output.take(160)}")
    }

    private fun screenContentBounds(): Rect {
        val (width, height) = screenSize()
        return Rect(
            0,
            (height * 0.1f).toInt(),
            width,
            (height * 0.9f).toInt(),
        )
    }

    private fun rootScroll(
        tool: String,
        direction: ScrollDirection,
        bounds: Rect,
        beforeNodes: List<UiNode>,
        maxNodes: Int,
        targetIndex: Int?,
    ): String {
        val startedAt = SystemClock.elapsedRealtime()
        val gesture = direction.gestureWithin(bounds)
            ?: return scrollErrorJson(
                tool,
                direction,
                "INVALID_NODE_BOUNDS",
                "滚动区域过小或不在屏幕内",
            )
        val result = runSuText(
            "input swipe ${gesture.start.x} ${gesture.start.y} " +
                "${gesture.end.x} ${gesture.end.y} 300",
            timeoutSeconds = 8,
        )
        if (result.output == "ROOT_REQUIRED" && result.exitCode == -1) return rootRequired()
        val commandOutcome = ShellActionOutcomePolicy.classify(result.exitCode)
        if (commandOutcome == ShellActionOutcomePolicy.Outcome.FAILED) {
            return scrollErrorJson(
                tool,
                direction,
                "COMMAND_FAILED",
                result.output.ifBlank { "exit=${result.exitCode}" },
            )
        }
        if (commandOutcome == ShellActionOutcomePolicy.Outcome.SUCCEEDED) {
            Thread.sleep(450L)
        }
        val afterNodes = dumpUiNodes(maxNodes)
        val inferredDelta = inferRootScrollDelta(beforeNodes, afterNodes, bounds, direction)
        val evidence = ScrollEvidenceContract.classify(
            direction = direction,
            delta = inferredDelta,
            movementSource = inferredDelta?.let { ScrollMovementSource.ANCHOR_MOTION },
            atBoundary = false,
        )
        val moved = evidence == ScrollEvidence.MOVED_BY_ANCHOR_MOTION
        val json = JSONObject()
            .put("ok", moved)
            .put("tool", tool)
            .put("direction", direction.name.lowercase())
            .put("moved", moved)
            .put("at_boundary", JSONObject.NULL)
            .put("executor", "root")
            .put("method", "INPUT_SWIPE")
            .put("verified_by", if (moved) "ui_node_motion" else "none")
            .put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt)
        inferredDelta?.let { delta ->
            if (direction.axis == ScrollAxis.VERTICAL) {
                json.put("delta_y", delta)
            } else {
                json.put("delta_x", delta)
            }
        }
        if (targetIndex != null) json.put("target_index", targetIndex)
        if (!moved) {
            if (commandOutcome == ShellActionOutcomePolicy.Outcome.TIMED_OUT) {
                json
                    .put("code", "ACTION_OUTCOME_UNKNOWN")
                    .put(
                        "message",
                        "Root 滚动命令超时，动作可能已经执行但位移无法确认；请先重新观察",
                    )
            } else if (evidence == ScrollEvidence.DIRECTION_MISMATCH) {
                json
                    .put("code", "DIRECTION_MISMATCH")
                    .put("message", "界面向请求方向的反方向移动")
            } else {
                json
                    .put("code", "ACTION_OUTCOME_UNKNOWN")
                    .put(
                        "message",
                        "滚动手势已发出，但无法确认方向或位移；请先重新观察，禁止直接重试",
                    )
            }
        }
        return json.toString()
    }

    private fun resolveUiAutomatorNode(
        observation: ElementObservation,
        index: Int,
    ): ResolvedUiAutomatorNode? {
        if (observation.source != ElementSource.UIAUTOMATOR) return null
        val original = observation.nodes.firstOrNull { node -> node.index == index } ?: return null
        val currentNodes = dumpUiNodes(observation.maxNodes)
        if (uiTreeSignature(currentNodes) != observation.treeSignature) return null
        val current = currentNodes.firstOrNull { node -> node.index == index } ?: return null
        val matched = current.takeIf { candidate ->
            candidate.packageName == original.packageName &&
                candidate.className == original.className &&
                candidate.viewId == original.viewId &&
                candidate.text == original.text &&
                candidate.desc == original.desc &&
                candidate.bounds == original.bounds &&
                candidate.clickable == original.clickable &&
                candidate.longClickable == original.longClickable &&
                candidate.scrollable == original.scrollable &&
                candidate.editable == original.editable &&
                candidate.password == original.password &&
                candidate.enabled == original.enabled
        }
        return matched?.let { node -> ResolvedUiAutomatorNode(node, currentNodes) }
    }

    /**
     * Root 滚动只能用滚动前后都唯一存在的稳定节点证明方向；任意树变化不足以证明滚动。
     * 屏幕内容向上移动代表滚动位置向下，因此最后要反转节点位移符号。
     */
    private fun inferRootScrollDelta(
        beforeNodes: List<UiNode>,
        afterNodes: List<UiNode>,
        region: Rect,
        direction: ScrollDirection,
    ): Int? {
        if (beforeNodes.isEmpty() || afterNodes.isEmpty()) return null

        fun UiNode.motionKey(): String? {
            if (viewId.isBlank() && text.isBlank() && desc.isBlank()) return null
            return "$packageName|$className|$viewId|$text|$desc"
        }

        fun uniqueNodes(nodes: List<UiNode>): Map<String, UiNode> = nodes
            .asSequence()
            .filter { node -> Rect.intersects(node.bounds, region) }
            .mapNotNull { node -> node.motionKey()?.let { key -> key to node } }
            .groupBy({ pair -> pair.first }, { pair -> pair.second })
            .mapNotNull { (key, matches) ->
                matches.singleOrNull()?.let { node -> key to node }
            }
            .toMap()

        val before = uniqueNodes(beforeNodes)
        val after = uniqueNodes(afterNodes)
        val contentDeltas = before.mapNotNull { (key, oldNode) ->
            val newNode = after[key] ?: return@mapNotNull null
            val oldAxis = if (direction.axis == ScrollAxis.VERTICAL) oldNode.centerY else oldNode.centerX
            val newAxis = if (direction.axis == ScrollAxis.VERTICAL) newNode.centerY else newNode.centerX
            newAxis - oldAxis
        }
        return RootScrollMotionContract.inferScrollDelta(contentDeltas)
    }

    private fun uiTreeSignature(nodes: List<UiNode>, region: Rect? = null): String =
        nodes.asSequence()
            .filter { node -> region == null || Rect.intersects(node.bounds, region) }
            .joinToString(separator = "\u001f") { node ->
            "${node.packageName}|${node.className}|${node.viewId}|${node.text}|" +
                "${node.desc}|${node.bounds.toShortString()}"
        }

    private fun inputCommand(command: String, tool: String): String {
        if (!rootAvailable()) return rootRequired()
        val result = runSuText(command, timeoutSeconds = 8)
        if (result.output == "ROOT_REQUIRED" && result.exitCode == -1) return rootRequired()
        return when (ShellActionOutcomePolicy.classify(result.exitCode)) {
            ShellActionOutcomePolicy.Outcome.SUCCEEDED -> {
                waitForUiSettle(tool)
                JSONObject()
                    .put("ok", true)
                    .put("tool", tool)
                    .toString()
            }
            ShellActionOutcomePolicy.Outcome.TIMED_OUT -> errorJson(
                "ACTION_OUTCOME_UNKNOWN",
                "Root 动作命令超时，动作可能已经执行；请先重新观察，避免重复操作",
            )
            ShellActionOutcomePolicy.Outcome.FAILED ->
                errorJson("COMMAND_FAILED", result.output.ifBlank { "exit=${result.exitCode}" })
        }
    }

    private fun waitForUiSettle(tool: String) {
        val delayMs = when (tool) {
            "tap", "long_press", "press_key" -> 350L
            "swipe" -> 650L
            "input_text" -> 500L
            else -> 250L
        }
        Thread.sleep(delayMs)
    }

    private fun validatePoint(x: Int, y: Int) {
        val (width, height) = screenSize()
        require(x in 0 until width && y in 0 until height) {
            "坐标超出屏幕范围：($x,$y) not in ${width}x$height"
        }
    }

    private fun List<UiNode>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            forEach { node -> array.put(node.toJson()) }
        }

    private fun UiNode.toJson(): JSONObject =
        JSONObject()
            .put("index", index)
            .put("text", text)
            .put("desc", desc)
            .put("class", className)
            .put("package", packageName)
            .put("view_id", viewId)
            .put("bounds", bounds.toShortString())
            .put("center", JSONObject().put("x", centerX).put("y", centerY))
            .put("clickable", clickable)
            .put("long_clickable", longClickable)
            .put("scrollable", scrollable)
            .put("focused", focused)
            .put("editable", editable)
            .put("password", password)
            .put("enabled", enabled)

    private fun XmlPullParser.attr(name: String): String =
        getAttributeValue(null, name).orEmpty()

    private fun String.toRectOrNull(): Rect? {
        val match = Regex("""\[(\-?\d+),(\-?\d+)]\[(\-?\d+),(\-?\d+)]""").find(this) ?: return null
        return Rect(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt()
        )
    }

    private fun runSuText(command: String, timeoutSeconds: Long): ShellTextResult {
        if (!rootAvailable()) return ShellTextResult(-1, "ROOT_REQUIRED")
        val result = runRootProcess(command, timeoutSeconds)
        val output = listOf(result.output.decodeToString(), result.stderr.decodeToString())
            .filter(String::isNotBlank).joinToString("\n").trim()
        return ShellTextResult(result.exitCode, output)
    }

    private fun runSuBytes(command: String, timeoutSeconds: Long): ShellBytesResult {
        if (!rootAvailable()) return ShellBytesResult(-1, ByteArray(0), "ROOT_REQUIRED")
        val result = runRootProcess(command, timeoutSeconds)
        return ShellBytesResult(result.exitCode, result.output, result.stderr.decodeToString())
    }

    private fun runRootProcess(command: String, timeoutSeconds: Long): ProcessBytesResult {
        val envelope = RootCommandEnvelope(command)
        val result = runProcess(timeoutSeconds, "su", "-c", envelope.script)
        val rootOutput = envelope.inspect(result.stderr.decodeToString())
        val completed = result.exitCode >= 0 && result.stderrComplete
        if (rootOutput.denied(completed)) {
            RootAccess.markDenied()
            logger.warn("Agent root access outcome=denied code=ROOT_REQUIRED")
            return ProcessBytesResult(-1, ByteArray(0), "ROOT_REQUIRED".toByteArray())
        }
        return result.copy(stderr = rootOutput.stderr.toByteArray())
    }

    private fun runProcess(
        timeoutSeconds: Long,
        vararg command: String
    ): ProcessBytesResult {
        val process = runCatching {
            ProcessBuilder(*command)
                .redirectErrorStream(false)
                .start()
        }.getOrElse {
            return ProcessBytesResult(-1, ByteArray(0), it.message.orEmpty().toByteArray())
        }

        val output = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val outputThread = thread(name = "agent-root-stdout") {
            process.inputStream.use { input -> output.readFrom(input) }
        }
        val stderrThread = thread(name = "agent-root-stderr") {
            process.errorStream.use { input -> stderr.readFrom(input) }
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            outputThread.join(500)
            stderrThread.join(500)
            return ProcessBytesResult(
                ShellActionOutcomePolicy.PROCESS_TIMEOUT_EXIT_CODE,
                output.bytes(),
                "命令执行超时".toByteArray(),
            )
        }

        outputThread.join(500)
        stderrThread.join(500)
        return ProcessBytesResult(process.exitValue(), output.bytes(), stderr.bytes(), stderr.completed)
    }

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message.take(240))
            .toString()

    private fun accessibilityUnavailable(): String = errorJson(
        "ACCESSIBILITY_UNAVAILABLE",
        "Eta 无障碍服务未连接；请开启服务后重新观察屏幕",
    )

    private fun rootRequired(): String = errorJson("ROOT_REQUIRED", "此操作需要设备 Root 权限")

    private fun scrollErrorJson(
        tool: String,
        direction: ScrollDirection?,
        code: String,
        message: String,
    ): String = JSONObject()
        .put("ok", false)
        .put("tool", tool)
        .put("direction", direction?.name?.lowercase() ?: JSONObject.NULL)
        .put("moved", false)
        .put("at_boundary", JSONObject.NULL)
        .put("code", code)
        .put("message", message.take(240))
        .toString()

    private fun nodeActionJson(
        tool: String,
        result: AgentAccessibilityService.NodeActionResult,
    ): String {
        val json = JSONObject()
            .put("ok", result.ok)
            .put("tool", tool)
            .put("executor", "accessibility")
        if (result.method.isNotBlank()) json.put("method", result.method)
        if (result.clipboardWritten) json.put("clipboard_written", true)
        result.verified?.let { verified -> json.put("verified", verified) }
        if (!result.ok) {
            json
                .put("code", result.code)
                .put("message", result.message.take(240))
        }
        return json.toString()
    }

    private fun scrollActionJson(
        tool: String,
        result: AgentAccessibilityService.ScrollActionResult,
    ): String {
        val json = JSONObject()
            .put("ok", result.ok)
            .put("tool", tool)
            .put("direction", result.direction.name.lowercase())
            .put("moved", result.moved)
            .put("at_boundary", result.atBoundary ?: JSONObject.NULL)
            .put("executor", "accessibility")
            .put("method", result.method)
            .put("verified_by", result.verifiedBy)
            .put("elapsed_ms", result.elapsedMs)
        result.deltaX?.let { json.put("delta_x", it) }
        result.deltaY?.let { json.put("delta_y", it) }
        result.targetIndex?.let { json.put("target_index", it) }
        if (!result.ok) {
            json
                .put("code", result.code)
                .put("message", result.message.take(240))
        }
        return json.toString()
    }

    private fun String.rewriteTool(tool: String): String =
        runCatching { JSONObject(this).put("tool", tool).toString() }.getOrDefault(this)

    private fun okJson(tool: String, executor: String): String =
        JSONObject()
            .put("ok", true)
            .put("tool", tool)
            .put("executor", executor)
            .toString()

    private fun matches(value: String, needle: String, matchMode: String): Boolean =
        when (matchMode.lowercase()) {
            "exact" -> value == needle
            "prefix" -> value.startsWith(needle)
            "regex" -> runCatching { Regex(needle).containsMatchIn(value) }.getOrDefault(false)
            else -> value.contains(needle, ignoreCase = true)
        }

    private fun AgentAccessibilityService.UiNode.toUiNode(): UiNode =
        UiNode(
            index = index,
            text = text,
            desc = desc,
            className = className,
            packageName = packageName,
            viewId = viewId,
            bounds = bounds,
            clickable = clickable,
            longClickable = longClickable,
            scrollable = scrollable,
            focused = focused,
            editable = editable,
            password = password,
            enabled = enabled
        )

    private data class ShellTextResult(val exitCode: Int, val output: String)
    private data class ShellBytesResult(val exitCode: Int, val output: ByteArray, val stderr: String)
    private data class ProcessBytesResult(
        val exitCode: Int,
        val output: ByteArray,
        val stderr: ByteArray,
        val stderrComplete: Boolean = false,
    )
    private data class ResolvedUiAutomatorNode(
        val node: UiNode,
        val currentNodes: List<UiNode>,
    )

    private data class ScreenCapture(
        val image: AgentModelClient.ModelImage?,
        val source: String,
        val complete: Boolean,
        val partial: Boolean,
        val expectedWindows: Int,
        val capturedWindows: Int,
        val missingWindowIds: List<Int> = emptyList(),
        val failureCodes: Map<Int, Int> = emptyMap(),
        val timedOut: Boolean = false,
        val criticalWindowMissing: Boolean = false,
        val requested: Boolean = true,
    ) {
        fun toJson(): JSONObject {
            val failures = JSONArray()
            failureCodes.forEach { (windowId, errorCode) ->
                failures.put(
                    JSONObject()
                        .put("window_id", windowId)
                        .put("error_code", errorCode),
                )
            }
            return JSONObject()
                .put("requested", requested)
                .put("source", source)
                .put(
                    "quality",
                    ScreenshotOutcomePolicy.classify(
                        requested = requested,
                        hasImage = image != null,
                        complete = complete,
                    ).wireName,
                )
                .put("complete", complete)
                .put("partial", partial)
                .put("expected_windows", expectedWindows)
                .put("captured_windows", capturedWindows)
                .put("missing_window_ids", JSONArray(missingWindowIds))
                .put("failures", failures)
                .put("timed_out", timedOut)
                .put("critical_window_missing", criticalWindowMissing)
        }

        companion object {
            fun notRequested(): ScreenCapture = ScreenCapture(
                image = null,
                source = "none",
                complete = false,
                partial = false,
                expectedWindows = 0,
                capturedWindows = 0,
                requested = false,
            )

            fun failed(source: String): ScreenCapture = ScreenCapture(
                image = null,
                source = source,
                complete = false,
                partial = false,
                expectedWindows = 0,
                capturedWindows = 0,
            )
        }
    }

    private class ByteArrayOutputCollector {
        private val output = java.io.ByteArrayOutputStream()
        @Volatile var completed: Boolean = false
            private set

        fun readFrom(input: java.io.InputStream) {
            runCatching {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        completed = true
                        break
                    }
                    output.write(buffer, 0, read)
                }
            }.onFailure { throwable ->
                if (throwable !is IOException) throw throwable
            }
        }

        fun bytes(): ByteArray = output.toByteArray()
    }

    companion object {
        private const val MAX_INPUT_TEXT_CHARS = 1_000
        private const val MAX_REPLACE_TEXT_CHARS = 4_000
        private const val MAX_CLIPBOARD_TEXT_CHARS = 20_000
        private val ROOT_OBSERVATION_IDS = AtomicLong(0)
    }
}
