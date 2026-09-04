package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EnhancementSettingsHistoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearHistory() {
        context.getSharedPreferences("eta_enhancement_ui_history", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun historyPreservesDisplayedValuesWithoutWritingBackToTheRemoteSource() {
        val source = context.getSharedPreferences("test_remote_snapshot", Context.MODE_PRIVATE)
        source.edit().clear()
            .putBoolean(Prefs.Keys.HOTWORD_SELF_HEAL, true)
            .putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, PowerAssistantTarget.GEMINI.persistedValue)
            .commit()
        val history = EnhancementSettingsHistory(context)
        assertFalse(history.hasConnected)
        history.captureConnected(source)
        history.recordCommittedBoolean(Prefs.Keys.HOTWORD_SELF_HEAL, false)
        val restored = EnhancementSettingsHistory(context)
        assertTrue(restored.hasConnected)
        assertFalse(restored.checked(Prefs.Keys.HOTWORD_SELF_HEAL, true))
        assertEquals(PowerAssistantTarget.GEMINI, restored.powerTarget())
        assertTrue(source.getBoolean(Prefs.Keys.HOTWORD_SELF_HEAL, false))
    }

    @Test
    fun existingSystemizerEntrySurvivesLossOfCurrentRootAccess() {
        val history = EnhancementSettingsHistory(context)
        assertFalse(history.hasUsedSystemizer)
        history.recordSystemizerUse()
        assertTrue(EnhancementSettingsHistory(context).hasUsedSystemizer)
    }
}
