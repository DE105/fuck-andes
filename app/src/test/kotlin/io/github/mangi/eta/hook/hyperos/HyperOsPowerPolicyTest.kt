package io.github.mangi.eta.hook.hyperos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOsPowerPolicyTest {
    @Test
    fun `只接管电源键助手动作`() {
        assertTrue(HyperOsPowerPolicy.isAssistantShortcut("launch_voice_assistant", "long_press_power_key"))
        assertTrue(HyperOsPowerPolicy.isAssistantShortcut("launch_voice_assistant", "imperceptible_press_power_key"))
        for (action in listOf("show_power_menu", "launch_camera", "sos", "launch_smarthome", null)) {
            assertFalse(HyperOsPowerPolicy.isAssistantShortcut(action, "long_press_power_key"))
        }
        for (source in listOf("long_press_home_key", "double_click_power_key", "", null)) {
            assertFalse(HyperOsPowerPolicy.isAssistantShortcut("launch_voice_assistant", source))
        }
    }
}
