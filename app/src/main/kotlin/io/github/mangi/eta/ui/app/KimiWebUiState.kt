package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.R

internal enum class KimiWebPhase { CHECKING, NOT_INSTALLED, READY, STARTING, RUNNING, FAILED }

internal data class KimiWebUiState(
    val phase: KimiWebPhase = KimiWebPhase.CHECKING,
    val errorCode: String? = null,
) {
    val canStop: Boolean get() = phase == KimiWebPhase.STARTING || phase == KimiWebPhase.RUNNING

    fun actionLabel(context: Context): String = context.getString(when (phase) {
        KimiWebPhase.CHECKING -> R.string.capability_kimi_checking
        KimiWebPhase.NOT_INSTALLED -> R.string.capability_kimi_install
        KimiWebPhase.READY -> R.string.action_launch_kimi_web
        KimiWebPhase.STARTING -> R.string.capability_kimi_preparing
        KimiWebPhase.RUNNING -> R.string.capability_kimi_open
        KimiWebPhase.FAILED -> R.string.capability_kimi_retry
    })
}

internal fun KimiWebLaunchResult.Failed.message(context: Context): String = context.getString(when (code) {
    "ROOT_REQUIRED" -> R.string.capability_kimi_root_required
    "BACKGROUND_START_NOT_ALLOWED" -> R.string.capability_background_failed
    "KIMI_EXITED", "PROCESS_EXITED" -> R.string.capability_kimi_exited
    "LINUX_ENVIRONMENT_NOT_READY", "PROFILE_NOT_INSTALLED" -> R.string.capability_kimi_not_installed
    "PROOT_UNAVAILABLE" -> R.string.capability_kimi_proot_unavailable
    "LOGS_UNAVAILABLE" -> R.string.capability_kimi_logs_unavailable
    "URL_TIMEOUT" -> R.string.linux_kimi_web_failed_url
    "BROWSER_UNAVAILABLE" -> R.string.linux_kimi_web_failed_browser
    else -> R.string.linux_kimi_web_failed_start
})
