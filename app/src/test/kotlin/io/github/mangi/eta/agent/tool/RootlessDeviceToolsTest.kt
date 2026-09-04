package io.github.mangi.eta.agent.tool

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.provider.Settings
import io.github.mangi.eta.agent.device.AgentNotificationHistoryService
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RootlessDeviceToolsTest {
    @Test
    fun disconnectedGuiFailsBeforeShellAndDoesNotPublishObservation() {
        val controller = RootShellDeviceController(NoOpLogger, rootAvailable = { false })
        val observation = controller.observe(true, true, 60)
        assertEquals("ACCESSIBILITY_UNAVAILABLE", JSONObject(observation.content).getString("code"))
        assertNull(observation.elementObservation)
        assertNull(observation.coordinateSpace)
        assertNull(observation.image)
        listOf(
            controller.tap(10, 10),
            controller.swipe(10, 10, 20, 20, 100),
            controller.scroll("down"),
            controller.waitForText("目标", 60_000, true, "exact"),
            controller.waitForPackage("example.app", 60_000),
        ).forEach { result ->
            assertEquals("ACCESSIBILITY_UNAVAILABLE", JSONObject(result).getString("code"))
        }
        assertEquals("ROOT_REQUIRED", JSONObject(controller.pressKey("PASTE")).getString("code"))
    }

    @Test
    fun rootExecutorRejectsBeforeStartingAProcess() {
        BoundedRootCommandExecutor(NoOpLogger, rootAvailable = { false }).use { executor ->
            assertEquals("ROOT_REQUIRED", executor.execute("id").errorCode)
        }
    }

    @Test
    fun publicNetworkSettingsAndOrderSourcesNeverInvokeRoot() {
        val context = RuntimeEnvironment.getApplication()
        Settings.System.putString(context.contentResolver, "eta_test_setting", "public-value")
        rejectingRootExecutor().use { root ->
            val tools = AgentStructuredDeviceTools(context, NoOpLogger, root, rootAvailable = { false })
            assertTrue(JSONObject(tools.execute("network_info", JSONObject())!!.content).getBoolean("ok"))
            val setting = JSONObject(tools.execute("get_setting", JSONObject()
                .put("namespace", "system").put("key", "eta_test_setting"))!!.content)
            assertEquals("public-value", setting.getString("value"))
            val missing = JSONObject(tools.execute("get_setting", JSONObject()
                .put("namespace", "system").put("key", "eta_missing_setting"))!!.content)
            assertTrue(missing.isNull("value"))
            val orders = JSONObject(tools.execute("search_personal_orders", JSONObject())!!.content)
            assertEquals("ROOT_REQUIRED", orders.getJSONObject("system_memory").getString("code"))
        }
    }

    @Test
    fun rootedNonColorOsOrdersSkipSystemMemoryWithoutInvokingRoot() {
        rejectingRootExecutor().use { root ->
            val tools = AgentStructuredDeviceTools(
                RuntimeEnvironment.getApplication(), NoOpLogger, root,
                rootAvailable = { true }, colorOs = { false },
            )
            val result = JSONObject(tools.execute("search_personal_orders", JSONObject())!!.content)
            assertEquals("DEVICE_UNSUPPORTED", result.getJSONObject("system_memory").getString("code"))
        }
    }

    @Test
    fun currentNotificationsUseConnectedListenerAndRejectStaleConnection() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationListenerAccessGranted(
            ComponentName(context, AgentNotificationHistoryService::class.java), true,
        )
        val serviceController = Robolectric.buildService(AgentNotificationHistoryService::class.java).create()
        val service = serviceController.get()
        try {
            service.onListenerConnected()
            shadowOf(service).addActiveNotification("example.target", 1,
                Notification.Builder(context, "test").setContentTitle("当前通知").setContentText("正文").build())
            shadowOf(service).addActiveNotification("example.other", 2,
                Notification.Builder(context, "test").setContentTitle("其他通知").build())
            rejectingRootExecutor().use { root ->
                val tools = AgentStructuredDeviceTools(context, NoOpLogger, root, rootAvailable = { false })
                val result = tools.execute("recent_notifications", JSONObject().put("package_name", "example.target"))!!
                assertTrue(result.sensitive)
                val json = JSONObject(result.content)
                assertTrue(json.getBoolean("ok"))
                assertEquals(1, json.getInt("count"))
                assertEquals("当前通知", json.getJSONArray("items").getJSONObject(0).getString("title"))
                service.onListenerDisconnected()
                val disconnected = JSONObject(tools.execute("recent_notifications", JSONObject())!!.content)
                assertFalse(disconnected.getBoolean("ok"))
                assertEquals("NOTIFICATION_LISTENER_UNAVAILABLE", disconnected.getString("code"))
            }
        } finally {
            serviceController.destroy()
        }
    }

    private fun rejectingRootExecutor() = BoundedRootCommandExecutor(NoOpLogger) {
        error("普通实现不应尝试调用 Root")
    }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
