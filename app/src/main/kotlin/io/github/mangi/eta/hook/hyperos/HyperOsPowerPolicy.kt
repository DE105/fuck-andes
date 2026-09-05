package io.github.mangi.eta.hook.hyperos

internal object HyperOsPowerPolicy {
    fun isAssistantShortcut(function: String?, source: String?): Boolean =
        function == "launch_voice_assistant" &&
            (source == "long_press_power_key" || source == "imperceptible_press_power_key")
}
