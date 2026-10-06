package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiLimbsSearchGuidanceTest {
    private val owner = "plugin.test.navigation_workbench"
    private val scope = AiLimbsCapabilityScope("plugin:$owner", AiLimbsCapabilityScopeKind.PLUGIN,
        owner, "Navigation Workbench", null, listOf("$owner.command"), listOf("$owner.command"))
    private val leaf = AiLimbsOrganizedSearchItem.Capability(1,
        AiLimbsCapabilitySearchMatch(AiLimbsCapabilityDefinition("native.status", "Status", "native", "status",
            emptyList(), ToolCatalogEntry("status", "Status", "Fixture", emptyList(), ToolCatalogSourceKind.INTERNAL)),
            200, 1, 1, false))
    private fun action(items: List<AiLimbsOrganizedSearchItem>, query: String, scopeId: String? = null) =
        AiLimbsSearchGuidance.nextAction(items, query, scopeId,
            describe = { id -> JSONObject().put("type", "CAPABILITY_DESCRIBE").put("capability_id", id) },
            search = { intent, id -> JSONObject().put("type", "CAPABILITY_SEARCH").put("query", intent)
                .put("scope", id ?: JSONObject.NULL) })

    @Test fun scopeRankedAheadOfLeavesDeterminesActualNextScopeAndQuery() {
        val result = action(listOf(AiLimbsOrganizedSearchItem.Scope(0, scope), leaf),
            "Navigation Workbench command status session")
        assertEquals("CAPABILITY_SEARCH", result.getString("type"))
        assertEquals(scope.scopeId, result.getString("scope"))
        assertEquals("command status session", result.getString("query"))
        assertFalse(result.toString().contains("<"))
    }

    @Test fun leafRankedAheadOfScopeUsesItsRealCanonicalCapabilityId() {
        val result = action(listOf(leaf, AiLimbsOrganizedSearchItem.Scope(2, scope)), "status")
        assertEquals("CAPABILITY_DESCRIBE", result.getString("type"))
        assertEquals("native.status", result.getString("capability_id"))
        assertFalse(result.toString().contains("<"))
    }

    @Test fun emptyScopedResultsKeepCanonicalDiscoveryRecoveryWithinScope() {
        val result = action(emptyList(), "missing intent", scope.scopeId)
        assertEquals("CAPABILITY_SEARCH", result.getString("type"))
        assertEquals(scope.scopeId, result.getString("scope"))
    }
}
