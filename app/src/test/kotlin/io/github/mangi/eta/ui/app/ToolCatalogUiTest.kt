package io.github.mangi.eta.ui.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.TravelExplore
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.ui.components.iconForTool
import io.github.mangi.eta.ui.model.projectToolGroups
import io.github.mangi.eta.ui.model.toolCardRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolCatalogUiTest {
    @Test
    fun everyRuntimeToolAndDisplayedCardHasASpecificIcon() {
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
        )
        val runtimeNames = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }
        val cardIds = buildToolsState(RuntimeEnvironment.getApplication()).groups
            .flatMap { it.tools }.map { it.id }
        (runtimeNames + cardIds).distinct().forEach { name ->
            assertNotEquals(name, Icons.Rounded.Build, iconForTool(name))
        }
    }

    @Test
    fun hostedSearchNamesAndDynamicMcpNamesUseTheirToolIcons() {
        assertEquals(Icons.Rounded.TravelExplore, iconForTool("网页搜索"))
        assertEquals(iconForTool("网页搜索"), iconForTool("web_search"))
        assertEquals(Icons.Rounded.Language, iconForTool("browser_use"))
        assertEquals(Icons.Rounded.Extension, iconForTool("mcp_server_search_012345"))
        assertEquals(Icons.Rounded.Build, iconForTool("unknown_tool"))
    }

    @Test
    fun everyDisplayedCardHasExplicitMetadataAndKeepsItsOriginalOrder() {
        val groups = buildToolsState(RuntimeEnvironment.getApplication()).groups
        val allCards = groups.flatMap { it.tools }
        allCards.forEach { toolCardRequirement(it.id) }
        assertEquals(allCards.size, allCards.map { it.id }.distinct().size)
        assertEquals(groups, projectToolGroups(groups, true, false, false))
        assertEquals(groups, projectToolGroups(groups, false, true, true))

        val ordinaryCards = projectToolGroups(groups, false, false, false).flatMap { it.tools }
        assertTrue(ordinaryCards.isNotEmpty())
        assertEquals(allCards.filter { card ->
            val requirement = toolCardRequirement(card.id)
            requirement.rootRequirement != RootRequirement.REQUIRED && !requirement.colorOs
        }, ordinaryCards)
    }
}
