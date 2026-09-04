package io.github.mangi.eta.ui.app

import io.github.mangi.eta.R
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
class KimiWebUiStateTest {
    @Test
    fun preparationAndRunningBothOfferStopButIdleAndFailuresDoNot() {
        assertTrue(KimiWebUiState(KimiWebPhase.STARTING).canStop)
        assertTrue(KimiWebUiState(KimiWebPhase.RUNNING).canStop)
        assertFalse(KimiWebUiState(KimiWebPhase.NOT_INSTALLED).canStop)
        assertFalse(KimiWebUiState(KimiWebPhase.FAILED).canStop)
    }

    @Test
    fun permissionAndProcessFailuresHaveTheirOwnRecoveryMessage() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(context.getString(R.string.capability_kimi_root_required),
            KimiWebLaunchResult.Failed("ROOT_REQUIRED").message(context))
        assertEquals(context.getString(R.string.capability_background_failed),
            KimiWebLaunchResult.Failed("BACKGROUND_START_NOT_ALLOWED").message(context))
        assertEquals(context.getString(R.string.capability_kimi_exited),
            KimiWebLaunchResult.Failed("KIMI_EXITED").message(context))
    }
}
