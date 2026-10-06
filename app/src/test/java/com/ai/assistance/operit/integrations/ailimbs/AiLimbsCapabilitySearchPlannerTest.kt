package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiLimbsCapabilitySearchPlannerTest {
    private val owner = "plugin.test.unknown_workbench"
    private fun definition(action: String, ownerId: String? = owner): AiLimbsCapabilityDefinition {
        val id = if (ownerId == null) "native.$action" else "$ownerId.$action"
        return AiLimbsCapabilityDefinition(id, action, "fixture", id, listOf("$id.alias"),
            ToolCatalogEntry(id, action, "Fixture $action", emptyList(), ToolCatalogSourceKind.INTERNAL),
            ownerPluginId = ownerId)
    }
    private val definitions = listOf(definition("command"), definition("status"), definition("session"))
    private val scope = AiLimbsCapabilityScope("plugin:$owner", AiLimbsCapabilityScopeKind.PLUGIN,
        owner, "Unknown Workbench", null, definitions.map { it.capabilityId }, definitions.map { it.invokeId })
    private val unexpectedSource: suspend () -> List<AiLimbsCapabilityDefinition> = {
        throw AssertionError("Unrelated catalog must not be collected")
    }

    @Test fun explicitScopeUsesOnlyRegisteredOwnerEvenForUnknownIntent() = runBlocking {
        val foreign = definition("foreign", "plugin.test.foreign")
        val plan = AiLimbsCapabilitySearchPlanner.plan("qzxv no such intent", scope,
            definitions + foreign, listOf(scope), unexpectedSource, unexpectedSource)
        assertEquals("scope", plan.mode)
        assertEquals(definitions, plan.definitions)
        assertEquals(listOf(scope.scopeId), plan.scopeIds)
    }

    @Test fun exactRegisteredAliasSkipsAllOtherSourcesAndKeepsCanonicalAddress() = runBlocking {
        val selected = definitions.first()
        val plan = AiLimbsCapabilitySearchPlanner.plan(selected.aliases.first().uppercase(), null,
            definitions, listOf(scope), unexpectedSource, unexpectedSource)
        assertEquals("exact_identity", plan.mode)
        assertEquals(listOf(selected), plan.definitions)
    }

    @Test fun namedOwnerUsesResidualIntentAndSelectsUniqueAction() = runBlocking {
        val plan = AiLimbsCapabilitySearchPlanner.plan("Unknown Workbench command", null,
            definitions + definition("command", "plugin.test.foreign"), listOf(scope), unexpectedSource, unexpectedSource)
        assertEquals("named_scope", plan.mode)
        assertEquals("command", plan.query)
        assertEquals(listOf(definitions.first()), plan.definitions)
    }

    @Test fun ambiguousActionWithinNamedOwnerKeepsBothCandidates() = runBlocking {
        val candidates = listOf(definition("runtime.stop"), definition("session.stop"))
        val multiple = scope.copy(capabilityIds = candidates.map { it.capabilityId }, invokeIds = candidates.map { it.invokeId })
        val plan = AiLimbsCapabilitySearchPlanner.plan("Unknown Workbench stop", null,
            candidates, listOf(multiple), unexpectedSource, unexpectedSource)
        assertEquals(candidates, plan.definitions)
    }

    @Test fun namedSeveralOwnersPreservesTheirCandidates() = runBlocking {
        val other = definition("session", "plugin.test.another")
        val otherScope = scope.copy(scopeId = "plugin:plugin.test.another", ownerPluginId = "plugin.test.another",
            displayName = "Another", capabilityIds = listOf(other.capabilityId), invokeIds = listOf(other.invokeId))
        val plan = AiLimbsCapabilitySearchPlanner.plan("Unknown Workbench Another command status session", null,
            definitions + other, listOf(scope, otherScope), unexpectedSource, unexpectedSource)
        assertEquals((definitions + other).toSet(), plan.definitions.toSet())
        assertEquals(setOf(scope.scopeId, otherScope.scopeId), plan.scopeIds.toSet())
    }

    @Test fun exactNativeAddressNeedsHostMetadataButNeverPackageCollection() = runBlocking {
        val native = definition("start_app", null)
        var hostCalls = 0
        val plan = AiLimbsCapabilitySearchPlanner.plan(native.capabilityId, null,
            definitions, listOf(scope), { hostCalls++; listOf(native) }, unexpectedSource)
        assertEquals("exact_identity", plan.mode)
        assertEquals(listOf(native), plan.definitions)
        assertEquals(1, hostCalls)
    }

    @Test fun crossModuleIntentCollectsFullCatalogExactlyOnce() = runBlocking {
        var fullCalls = 0
        val plan = AiLimbsCapabilitySearchPlanner.plan("create transparent picture", null,
            definitions, listOf(scope), { emptyList() }, { fullCalls++; definitions })
        assertEquals("global", plan.mode)
        assertEquals(definitions, plan.definitions)
        assertEquals(1, fullCalls)
    }

    @Test fun ownerNamesUseWordBoundariesAndHanNamesWithoutSpaces() {
        assertFalse(AiLimbsScopeQuery.mentions("Unknown Workbenchbook command", scope))
        assertTrue(AiLimbsScopeQuery.mentions("Unknown Workbench command", scope))
        assertEquals("command", AiLimbsScopeQuery.intent("Unknown Workbench command", scope))
        val han = scope.copy(displayName = "绘图工坊", ownerPluginId = "plugin.test.paint_workspace")
        assertTrue(AiLimbsScopeQuery.mentions("绘图工坊创建透明图层", han))
        assertEquals("创建透明图层", AiLimbsScopeQuery.intent("绘图工坊创建透明图层", han))
    }
    @Test fun identicalFriendlyNamesAcrossOwnersDoNotChooseAnArbitraryCapability() = runBlocking {
        val candidates = listOf(definition("status"), definition("status", "plugin.test.foreign"))
        var fullCalls = 0
        val plan = AiLimbsCapabilitySearchPlanner.plan("status", null, candidates, listOf(scope),
            { emptyList() }, { fullCalls++; candidates })
        assertEquals("global", plan.mode)
        assertEquals(candidates, plan.definitions)
        assertEquals(1, fullCalls)
    }

}
