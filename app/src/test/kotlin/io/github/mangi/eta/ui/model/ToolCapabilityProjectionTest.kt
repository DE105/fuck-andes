package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.RootRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCapabilityProjectionTest {
    private fun card(id: String) = ToolItemUi(id, id, id)

    @Test fun currentDeviceRetainsPartialCapabilitiesAndHidesRootOnlyTools() {
        val groups = listOf(ToolGroupUi("device", "设备", listOf(card("terminal"), card("wifi_credentials"), card("observe_screen"))))
        assertEquals(listOf("terminal", "observe_screen"),
            projectToolGroups(groups, false, false, true).single().tools.map { it.id })
        assertEquals(groups, projectToolGroups(groups, true, false, true))
        assertEquals(groups, projectToolGroups(groups, false, true, true))
    }

    @Test fun viewingAllDoesNotMutateSourceOrChangeToolOrder() {
        val groups = listOf(ToolGroupUi("root", "增强", listOf(card("wifi_credentials"))))
        assertTrue(projectToolGroups(groups, false, false, false).isEmpty())
        assertEquals(groups, projectToolGroups(groups, true, false, false))
        assertTrue(projectToolGroups(groups, false, false, false).isEmpty())
    }

    @Test fun browserCardsReferToTheRealBrowserTool() {
        listOf("browser_use", "browser_read", "browser_interact", "browser_screenshot").forEach { id ->
            assertEquals("browser_use", actualToolName(id))
            assertEquals(AgentToolRequirements.find("browser_use"), toolCardRequirement(id))
        }
    }

    @Test fun ordinaryPermissionsDoNotHideDiscoverableTools() {
        assertTrue(visibleOnCurrentDevice("observe_screen", false, false))
        assertTrue(visibleOnCurrentDevice("search_notification_history", false, false))
        assertEquals(RootRequirement.PARTIAL, toolCardRequirement("terminal").rootRequirement)
        assertFalse(visibleOnCurrentDevice("search_coloros_memories", true, false))
    }

    @Test fun missingOrdinaryPermissionOpensPermissionsBeforePartialRootBenefits() {
        val capabilities = AgentToolCapabilities(rootAvailable = false, notificationsAllowed = false)
        assertEquals(AgentToolsAction.OpenPermissions, toolCardAction("recent_notifications", capabilities))
        assertEquals(AgentToolsAction.OpenEnhancements, toolCardAction("terminal", capabilities))
        assertEquals(AgentToolsAction.OpenBrowser, toolCardAction("browser_read", capabilities))
        assertEquals(AgentToolsAction.OpenEnhancements,
            toolCardAction("search_coloros_memories", capabilities.copy(rootAvailable = true, colorOs = false)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownCardCannotSilentlyAcquireCapabilityDefaults() {
        toolCardRequirement("unknown")
    }
}
