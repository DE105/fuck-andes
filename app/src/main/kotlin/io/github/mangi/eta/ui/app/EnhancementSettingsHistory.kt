package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs

/** 只用于断连后的界面展示；从不作为 Hook 配置来源，也不接收离线配置修改。 */
internal class EnhancementSettingsHistory(context: Context) {
    private val snapshot = context.applicationContext.getSharedPreferences("eta_enhancement_ui_history", Context.MODE_PRIVATE)

    val hasConnected: Boolean get() = snapshot.getBoolean("has_connected", false)
    val hasUsedSystemizer: Boolean get() = snapshot.getBoolean("has_used_systemizer", false)

    fun captureConnected(preferences: SharedPreferences) {
        snapshot.edit().apply {
            putBoolean("has_connected", true)
            Prefs.Keys.BOOLEAN_DEFAULTS.filterKeys { it !in Prefs.Keys.LOCAL_AGENT_KEYS }.forEach { (key, default) ->
                putBoolean(key, preferences.getBoolean(key, default))
            }
            putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, Prefs.powerAssistantTarget(preferences).persistedValue)
        }.apply()
    }

    fun checked(key: String, default: Boolean): Boolean = snapshot.getBoolean(key, default)

    fun powerTarget(): PowerAssistantTarget = Prefs.powerAssistantTarget(snapshot)

    fun recordCommittedBoolean(key: String, value: Boolean) {
        if (key !in Prefs.Keys.LOCAL_AGENT_KEYS) snapshot.edit().putBoolean(key, value).apply()
    }

    fun recordCommittedTarget(target: PowerAssistantTarget) {
        snapshot.edit().putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, target.persistedValue).apply()
    }

    fun recordSystemizerUse() {
        snapshot.edit().putBoolean("has_used_systemizer", true).apply()
    }
}
