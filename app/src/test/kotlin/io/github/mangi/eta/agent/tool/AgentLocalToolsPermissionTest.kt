package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentScreenObservationContract
import io.github.mangi.eta.core.AgentLogger
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLocalToolsPermissionTest {
    @Test
    fun revokedRootRejectsOldPrivilegedCallsBeforeAnyOperation() {
        val root = AtomicBoolean(true)
        val tools = tools(
            rootAvailable = root::get,
            beforeToolExecution = { error("缺少 Root 的旧调用不应进入执行阶段") },
        )
        root.set(false)
        listOf(
            "set_setting" to "{}",
            "terminal" to "{\"action\":\"open\",\"identity\":\"root\"}",
            "press_key" to "{\"button\":\"PASTE\"}",
        ).forEach { (name, args) ->
            val result = tools.execute(AgentModelClient.ToolCall("old-$name", name, args))
            assertEquals("ROOT_REQUIRED", JSONObject(result.content).getString("code"))
        }
        tools.close()
    }

    @Test
    fun terminalPermissionIsRecheckedImmediatelyBeforeExecution() {
        val enabled = AtomicBoolean(true)
        val tools = tools(terminalEnabled = enabled::get)
        enabled.set(false)

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "run_command",
                argumentsJson = "{\"command\":\"id\"}",
            )
        )

        assertEquals("TERMINAL_TOOLS_DISABLED", JSONObject(result.content).getString("code"))
        tools.close()
    }

    @Test
    fun browserPermissionIsRecheckedImmediatelyBeforeExecution() {
        val enabled = AtomicBoolean(true)
        val tools = tools(browserEnabled = enabled::get)
        enabled.set(false)

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "browser_use",
                argumentsJson = "{\"action\":\"get_page_info\"}",
            )
        )

        assertEquals("BROWSER_TOOLS_DISABLED", JSONObject(result.content).getString("code"))
        tools.close()
    }

    @Test
    fun memoryPermissionIsRecheckedImmediatelyBeforeExecution() {
        val enabled = AtomicBoolean(true)
        val tools = tools(memoryEnabled = enabled::get)
        enabled.set(false)

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-memory",
                name = "memory_get",
                argumentsJson = "{}",
            ),
        )

        assertEquals("MEMORY_DISABLED", JSONObject(result.content).getString("code"))
        assertEquals(true, result.sensitive)
        tools.close()
    }

    @Test
    fun foregroundToolIsRejectedWhenEntrySurfaceIsNotReady() {
        val tools = tools(
            beforeToolExecution = {
                ToolExecutionDecision.Reject(
                    code = "ENTRY_SURFACE_NOT_READY",
                    message = "入口窗口尚未确认关闭",
                )
            },
        )

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "tap",
                argumentsJson = "{\"x\":100,\"y\":200,\"coordinate_space\":\"screen\"}",
            ),
        )

        assertEquals("ENTRY_SURFACE_NOT_READY", JSONObject(result.content).getString("code"))
        tools.close()
    }

    @Test
    fun foregroundToolPropagatesAccessibilityGateFailure() {
        val tools = tools(
            beforeToolExecution = {
                ToolExecutionDecision.Reject(
                    code = "ACCESSIBILITY_PROTECTION_UNAVAILABLE",
                    message = "无障碍保护后端不可用",
                )
            },
        )

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "scroll",
                argumentsJson = "{\"direction\":\"down\"}",
            ),
        )
        val json = JSONObject(result.content)

        assertEquals("ACCESSIBILITY_PROTECTION_UNAVAILABLE", json.getString("code"))
        assertEquals("无障碍保护后端不可用", json.getString("message"))
        tools.close()
    }

    @Test
    fun screenshotCoordinatesRequireACurrentScreenshotCoordinateSpace() {
        val tools = tools()

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "tap",
                argumentsJson = "{\"x\":100,\"y\":200,\"coordinate_space\":\"screenshot\"}",
            ),
        )

        assertEquals("INVALID_ARGUMENT", JSONObject(result.content).getString("code"))
        tools.close()
    }

    @Test
    fun textInputWithoutAccessibilityDoesNotSendBlindShellKeys() {
        val tools = tools()

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "input_text",
                argumentsJson = "{\"text\":\"hello\"}",
            ),
        )

        assertEquals("ACCESSIBILITY_UNAVAILABLE", JSONObject(result.content).getString("code"))
        tools.close()
    }

    @Test
    fun defaultScreenObservationReturnsTreeWithoutImage() {
        var received: AgentScreenObservationContract.Options? = null
        val tools = tools(
            screenObservationProvider = { options ->
                received = options
                observation(
                    observationId = "o-tree",
                    screenshotRequested = false,
                )
            },
        )

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "observe-tree",
                name = "observe_screen",
                argumentsJson = "{}",
            ),
        )
        val content = JSONObject(result.content)

        assertFalse(checkNotNull(received).includeScreenshot)
        assertTrue(checkNotNull(received).includeUiTree)
        assertEquals(60, checkNotNull(received).maxNodes)
        assertEquals("o-tree", content.getString("observation_id"))
        assertFalse(content.getJSONObject("screenshot").getBoolean("requested"))
        assertTrue(result.images.isEmpty())
        tools.close()
    }

    @Test
    fun explicitScreenshotRefreshesTreeAndReturnsImage() {
        var received: AgentScreenObservationContract.Options? = null
        val image = AgentModelClient.ModelImage(
            reference = "data:image/webp;base64,AA==",
            mimeType = "image/webp",
            bytes = 1,
            width = 1080,
            height = 2400,
            source = "screen",
        )
        val tools = tools(
            screenObservationProvider = { options ->
                received = options
                observation(
                    observationId = "o-image",
                    screenshotRequested = true,
                    image = image,
                )
            },
        )

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "observe-image",
                name = "observe_screen",
                argumentsJson = """{"include_screenshot":true}""",
            ),
        )

        assertTrue(checkNotNull(received).includeScreenshot)
        assertTrue(checkNotNull(received).includeUiTree)
        assertEquals("o-image", JSONObject(result.content).getString("observation_id"))
        assertEquals(listOf(image), result.images)
        tools.close()
    }

    private fun tools(
        terminalEnabled: () -> Boolean = { false },
        browserEnabled: () -> Boolean = { false },
        memoryEnabled: () -> Boolean = { false },
        rootAvailable: () -> Boolean = { false },
        screenObservationProvider: (
            (AgentScreenObservationContract.Options) -> RootShellDeviceController.Observation
        )? = null,
        beforeToolExecution: (String) -> ToolExecutionDecision = {
            ToolExecutionDecision.Allow
        },
    ): AgentLocalTools =
        AgentLocalTools(
            context = RuntimeEnvironment.getApplication() as Context,
            logger = NoOpLogger,
            browserRunId = "test-run",
            terminalToolsEnabled = terminalEnabled,
            browserToolsEnabled = browserEnabled,
            memoryToolsEnabled = memoryEnabled,
            rootAvailable = rootAvailable,
            screenObservationProvider = screenObservationProvider,
            beforeToolExecution = beforeToolExecution,
        )

    private fun observation(
        observationId: String,
        screenshotRequested: Boolean,
        image: AgentModelClient.ModelImage? = null,
    ): RootShellDeviceController.Observation {
        val elementObservation = RootShellDeviceController.ElementObservation(
            id = observationId,
            source = RootShellDeviceController.ElementSource.ACCESSIBILITY,
            packageName = "example.app",
            windowId = 1,
            nodes = emptyList(),
            maxNodes = 60,
            truncated = false,
        )
        val content = JSONObject()
            .put("ok", true)
            .put("tool", "observe_screen")
            .put("observation_id", observationId)
            .put(
                "screenshot",
                JSONObject()
                    .put("requested", screenshotRequested)
                    .put("attached", image != null),
            )
            .toString()
        return RootShellDeviceController.Observation(
            content = content,
            image = image,
            elementObservation = elementObservation,
            coordinateSpace = image?.let {
                RootShellDeviceController.CoordinateSpace(
                    screenWidth = 1080,
                    screenHeight = 2400,
                    screenshotWidth = checkNotNull(it.width),
                    screenshotHeight = checkNotNull(it.height),
                )
            },
        )
    }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
