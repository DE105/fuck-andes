package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAccessibilityKeeperTest {
    @Test
    fun `stale protection setting without framework does not request recovery`() {
        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { true },
            requestRecovery = { error("不应请求未连接的保护后端") },
            awaitServiceBinding = { error("不应等待未发起的恢复") },
            protectionAvailable = { false },
        )

        assertEquals("ACCESSIBILITY_UNAVAILABLE", result.code)
        assertFalse(result.recoveryRequested)
    }

    @Test
    fun `connected service skips protection recovery`() {
        var recoveryCalls = 0

        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { true },
            protectionEnabled = { true },
            requestRecovery = {
                recoveryCalls++
                true
            },
            awaitServiceBinding = { false },
        )

        assertTrue(result.available)
        assertFalse(result.recoveryRequested)
        assertEquals(0, recoveryCalls)
    }

    @Test
    fun `disabled protection rejects without changing settings`() {
        var recoveryCalls = 0

        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { false },
            requestRecovery = {
                recoveryCalls++
                true
            },
            awaitServiceBinding = { true },
        )

        assertFalse(result.available)
        assertFalse(result.recoveryRequested)
        assertEquals("ACCESSIBILITY_UNAVAILABLE", result.code)
        assertEquals(0, recoveryCalls)
    }

    @Test
    fun `unavailable system backend rejects the gui operation`() {
        var bindingChecks = 0

        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { true },
            requestRecovery = { false },
            awaitServiceBinding = {
                bindingChecks++
                true
            },
        )

        assertFalse(result.available)
        assertTrue(result.recoveryRequested)
        assertEquals("ACCESSIBILITY_PROTECTION_UNAVAILABLE", result.code)
        assertEquals(0, bindingChecks)
    }

    @Test
    fun `approved recovery waits for the real service binding`() {
        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { true },
            requestRecovery = { true },
            awaitServiceBinding = { true },
        )

        assertTrue(result.available)
        assertTrue(result.recoveryRequested)
    }

    @Test
    fun `binding timeout rejects after bounded system recovery`() {
        val result = AgentAccessibilityKeeper.ensureAvailable(
            serviceAvailable = { false },
            protectionEnabled = { true },
            requestRecovery = { true },
            awaitServiceBinding = { false },
        )

        assertFalse(result.available)
        assertTrue(result.recoveryRequested)
        assertEquals("ACCESSIBILITY_REPAIR_TIMEOUT", result.code)
    }
}
