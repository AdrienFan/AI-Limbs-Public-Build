package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import org.junit.Assert.*
import org.junit.Test

class AiLimbsNamedScopePriorityTest {
    private val owner = "plugin.regression.studio"
    private fun match(id: String, score: Int = 100, strong: Boolean = false) =
        AiLimbsCapabilitySearchMatch(AiLimbsCapabilityDefinition(id, id, "plugin", id,
            emptyList(), ToolCatalogEntry(targetToolName = id, displayName = id,
                description = "fixture", parameterHints = emptyList(),
                sourceKind = ToolCatalogSourceKind.PACKAGE)), score, 3, 3, strong)
    private val scope = AiLimbsCapabilityScope("plugin:$owner", AiLimbsCapabilityScopeKind.PLUGIN,
        owner, "Regression Studio", null, listOf("$owner.command", "$owner.status", "$owner.session"),
        listOf("$owner.command", "$owner.status", "$owner.session"))
    private val broad = listOf(match("native.status", 200), match("$owner.command"),
        match("$owner.status"), match("$owner.session"), match("other.session", 90))
    private fun organize(query: String, matches: List<AiLimbsCapabilitySearchMatch> = broad, limit: Int = 5) =
        AiLimbsGlobalScopeOrganizer.organize(query, matches, limit, listOf(scope),
            matches.filter { it.definition.invokeId.startsWith("$owner.") }
                .associate { it.definition.invokeId to owner })

    @Test fun namedOwnerWinsBeforeLimitAndOtherCandidatesRemain() {
        val first = organize("studio command status session", limit = 1).single()
        assertEquals(owner, (first as AiLimbsOrganizedSearchItem.Scope).scope.ownerPluginId)
        val all = organize("studio command status session")
        assertTrue(all.any { it is AiLimbsOrganizedSearchItem.Capability && it.match.definition.invokeId == "native.status" })
    }

    @Test fun genericAndSubstringQueriesKeepOriginalOrder() {
        for (query in listOf("command status session", "studiobook command")) {
            val first = organize(query).first() as AiLimbsOrganizedSearchItem.Capability
            assertEquals("native.status", first.match.definition.invokeId)
        }
    }

    @Test fun explicitCapabilityIdentityKeepsSpecificLeaf() {
        val matches = listOf(match("$owner.command", 500, true), match("native.status", 200),
            match("$owner.status", 100), match("$owner.session", 90))
        val first = organize("$owner.command", matches).first() as AiLimbsOrganizedSearchItem.Capability
        assertEquals("$owner.command", first.match.definition.invokeId)
        assertFalse(organize("$owner.command", matches).any { it is AiLimbsOrganizedSearchItem.Scope })
    }

    @Test fun fullDisplayNameSupportsUnknownOwners() {
        assertTrue(organize("Regression Studio command status").first() is AiLimbsOrganizedSearchItem.Scope)
    }
}
