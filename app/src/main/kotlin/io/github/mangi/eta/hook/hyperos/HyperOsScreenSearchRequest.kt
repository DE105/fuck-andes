package io.github.mangi.eta.hook.hyperos

import android.content.Intent

internal object HyperOsScreenSearchRequest {
    private val navigationSources = setOf(
        "long_press_fullscreen_gesture_line",
        "long_press_home_key",
        "two_gesture_long_press",
    )

    fun matches(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_ASSIST) return false
        // 来源字符串也可能用于普通助理请求，必须同时确认识屏功能和导航长按类型。
        return intent.getStringExtra("voice_assist_function_key") == "start_screen_recognition" &&
            intent.getStringExtra("triggerType") == "NavLongPress" &&
            intent.getStringExtra("voice_assist_start_from_key") in navigationSources
    }
}
