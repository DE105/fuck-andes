package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCommandEnvelopeTest {
    @Test
    fun commandFailureAfterGrantDoesNotInvalidateRoot() {
        val result = RootCommandEnvelope("exit 21", token = "test")
            .inspect("ETA_ROOT_GRANTED_test\npermission denied in actual command\n")
        assertTrue(result.granted)
        assertFalse(result.denied(completed = true))
        assertEquals("permission denied in actual command\n", result.stderr)
    }

    @Test
    fun denialBeforeCommandStartsInvalidatesRootOnlyAfterProcessCompletes() {
        val result = RootCommandEnvelope("input tap 1 2", token = "test").inspect("Permission denied\n")
        assertTrue(result.denied(completed = true))
        assertFalse(result.denied(completed = false))
    }

    @Test
    fun otherInvocationsMarkerDoesNotProveThisCommandWasGranted() {
        val result = RootCommandEnvelope("id", token = "new").inspect("ETA_ROOT_GRANTED_old\n")
        assertFalse(result.granted)
    }

    @Test
    fun originalCommandIsQuotedInsideSeparateShell() {
        val envelope = RootCommandEnvelope("printf '%s' 'hello'; exit 1", token = "test")
        assertTrue(envelope.script.endsWith("exec /system/bin/sh -c 'printf '\\''%s'\\'' '\\''hello'\\''; exit 1'"))
    }
}
