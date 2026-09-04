package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.ui.model.projectToolGroups
import io.github.mangi.eta.ui.model.toolCardRequirement
import org.junit.Assert.assertEquals
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
