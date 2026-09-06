package io.github.mangi.eta.hook.hyperos

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class HyperOsScreenSearchRequestTest {
    @Test
    fun `导航识屏接受横条及两种兼容来源`() {
        for (source in listOf("long_press_fullscreen_gesture_line", "long_press_home_key", "two_gesture_long_press")) {
            assertTrue(HyperOsScreenSearchRequest.matches(request(source)))
        }
    }

    @Test
    fun `单有来源不能接管普通助手请求`() {
        val wrongFunction = request().putExtra("voice_assist_function_key", "launch_voice_assistant")
        val wrongTrigger = request().putExtra("triggerType", "PowerLongPress")
        val wrongAction = request().setAction(Intent.ACTION_VOICE_COMMAND)
        for (intent in listOf(wrongFunction, wrongTrigger, wrongAction)) {
            assertFalse(HyperOsScreenSearchRequest.matches(intent))
        }
    }

    @Test
    fun `电源键语音唤醒和未知来源不进入导航识屏`() {
        for (source in listOf("long_press_power_key", "imperceptible_press_power_key", "voice_wakeup", "other", "")) {
            assertFalse(HyperOsScreenSearchRequest.matches(request(source)))
        }
    }

    @Test
    fun `空请求和缺失字段保持原生行为`() {
        assertFalse(HyperOsScreenSearchRequest.matches(null))
        assertFalse(HyperOsScreenSearchRequest.matches(Intent()))
        for (key in listOf("voice_assist_function_key", "triggerType", "voice_assist_start_from_key")) {
            val intent = request().apply { removeExtra(key) }
            assertFalse(HyperOsScreenSearchRequest.matches(intent))
        }
    }

    @Test
    fun `字段类型错误不匹配且检查不修改原请求`() {
        for (key in listOf("voice_assist_function_key", "triggerType", "voice_assist_start_from_key")) {
            assertFalse(HyperOsScreenSearchRequest.matches(request().putExtra(key, 1)))
        }
        val intent = request()
        val before = Intent(intent)
        assertTrue(HyperOsScreenSearchRequest.matches(intent))
        assertTrue(intent.filterEquals(before))
        assertTrue(intent.extras!!.keySet() == before.extras!!.keySet())
    }

    private fun request(source: String = "long_press_fullscreen_gesture_line"): Intent =
        Intent(Intent.ACTION_ASSIST)
            .putExtra("voice_assist_function_key", "start_screen_recognition")
            .putExtra("triggerType", "NavLongPress")
            .putExtra("voice_assist_start_from_key", source)
}
