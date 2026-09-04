package io.github.mangi.eta.agent.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAccessPolicyTest {
    @Test fun firstDetectionRequestsAndExistingAuthorizationIsRechecked() {
        assertTrue(shouldRequestRoot(explicit = false, attempted = false, wasGranted = false))
        assertTrue(shouldRequestRoot(explicit = false, attempted = true, wasGranted = true))
    }

    @Test fun denialStaysQuietUntilUserRequestsAgain() {
        assertFalse(shouldRequestRoot(explicit = false, attempted = true, wasGranted = false))
        assertTrue(shouldRequestRoot(explicit = true, attempted = true, wasGranted = false))
    }

    @Test fun availabilityDoesNotGrantRootWhileWaiting() {
        assertFalse(RootAccessState(suPresent = true, isChecking = true).isGranted)
        assertFalse(RootAccessState(RootAccessStatus.TIMED_OUT, suPresent = true).isGranted)
        assertTrue(RootAccessState(RootAccessStatus.GRANTED, suPresent = true).isGranted)
    }
}
