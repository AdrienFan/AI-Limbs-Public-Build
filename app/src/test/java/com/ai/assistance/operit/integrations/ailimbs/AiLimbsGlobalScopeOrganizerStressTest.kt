package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsGlobalScopeOrganizerStressTest {
    private fun definition(
        owner: String,
        index: Int,
        displayName: String = "能力 $index"
    ): AiLimbsCapabilityDefinition {
        val capabilityId = "$owner.capability_$index"
        return AiLimbsCapabilityDefinition(
            capabilityId = capabilityId,
            displayName = displayName,
            provider = "plugin:$owner",
            invokeId = capabilityId,
            aliases = emptyList(),
            catalogEntry =
                ToolCatalogEntry(
                    targetToolName = capabilityId,
                    displayName = displayName,
                    description = "Resolver v3 organizer stress capability",
                    parameterHints = emptyList(),
                    sourceKind = ToolCatalogSourceKind.PACKAGE
                )
        )
    }

    private fun match(
        definition: AiLimbsCapabilityDefinition,
        score: Int = 300,
        matchedTerms: Int = 1,
        totalTerms: Int = 1,
        strongIdentityMatch: Boolean = false
    ) =
        AiLimbsCapabilitySearchMatch(
            definition = definition,
            score = score,
            matchedTerms = matchedTerms,
            totalTerms = totalTerms,
            strongIdentityMatch = strongIdentityMatch
        )

    @Test
    fun hundredPlusCapabilitiesCollapseToOneScopeButKeepFullCapabilityCount() {
        val owner = "plugin.test.mega_studio"
        val allDefinitions = (1..120).map { definition(owner, it, "巨型画室 · 能力 $it") }
        val candidateMatches = allDefinitions.take(20).map(::match)
        val scope =
            AiLimbsCapabilityScope(
                scopeId = "plugin:$owner",
                kind = AiLimbsCapabilityScopeKind.PLUGIN,
                ownerPluginId = owner,
                displayName = "巨型画室",
                description = "120 capability stress scope",
                capabilityIds = allDefinitions.map { it.capabilityId },
                invokeIds = allDefinitions.map { it.invokeId }
            )
        val ownerMap = candidateMatches.associate { it.definition.invokeId to owner }

        val organized =
            AiLimbsGlobalScopeOrganizer.organize(
                query = "巨型画室",
                matches = candidateMatches,
                limit = 5,
                scopes = listOf(scope),
                ownerPluginIdByInvokeId = ownerMap
            )

        assertEquals(1, organized.size)
        val folded = organized.single() as AiLimbsOrganizedSearchItem.Scope
        assertEquals(scope.scopeId, folded.scope.scopeId)
        assertEquals(120, folded.scope.capabilityCount)
    }

    @Test
    fun veryStrongLeafWinnerSurvivesInsideHundredPlusCapabilityOwner() {
        val owner = "plugin.test.mega_studio_specific"
        val top = definition(owner, 1, "巨型画室 · 撤销上一步")
        val others = (2..20).map { definition(owner, it, "巨型画室 · 普通能力 $it") }
        val matches =
            listOf(
                match(
                    definition = top,
                    score = 900,
                    matchedTerms = 4,
                    totalTerms = 4,
                    strongIdentityMatch = true
                )
            ) + others.map {
                match(
                    definition = it,
                    score = 300,
                    matchedTerms = 1,
                    totalTerms = 4,
                    strongIdentityMatch = false
                )
            }
        val allDefinitions = listOf(top) + others + (21..120).map { definition(owner, it) }
        val scope =
            AiLimbsCapabilityScope(
                scopeId = "plugin:$owner",
                kind = AiLimbsCapabilityScopeKind.PLUGIN,
                ownerPluginId = owner,
                displayName = "巨型画室",
                description = null,
                capabilityIds = allDefinitions.map { it.capabilityId },
                invokeIds = allDefinitions.map { it.invokeId }
            )

        val organized =
            AiLimbsGlobalScopeOrganizer.organize(
                query = "巨型画室 撤销 上一步",
                matches = matches,
                limit = 5,
                scopes = listOf(scope),
                ownerPluginIdByInvokeId = matches.associate { it.definition.invokeId to owner }
            )

        assertTrue(organized.first() is AiLimbsOrganizedSearchItem.Capability)
        val first = organized.first() as AiLimbsOrganizedSearchItem.Capability
        assertEquals(top.capabilityId, first.match.definition.capabilityId)
        assertFalse(organized.any { it is AiLimbsOrganizedSearchItem.Scope })
        assertEquals(5, organized.size)
    }

    @Test
    fun manyPluginsStayBoundedAndNeverDuplicateScopes() {
        val owners = (1..40).map { "plugin.test.multi_%02d".format(it) }
        val scopes =
            owners.map { owner ->
                AiLimbsCapabilityScope(
                    scopeId = "plugin:$owner",
                    kind = AiLimbsCapabilityScopeKind.PLUGIN,
                    ownerPluginId = owner,
                    displayName = owner,
                    description = null,
                    capabilityIds = (1..4).map { "$owner.capability_$it" },
                    invokeIds = (1..4).map { "$owner.capability_$it" }
                )
            }

        // A bounded fixture still folds correctly; production now supplies all relevant candidates.
        val topTwenty =
            owners.take(5).flatMap { owner ->
                (1..4).map { index ->
                    match(
                        definition = definition(owner, index, "共同宽泛意图"),
                        score = 320,
                        matchedTerms = 1,
                        totalTerms = 1,
                        strongIdentityMatch = false
                    )
                }
            }
        val ownerMap =
            topTwenty.associate { match ->
                match.definition.invokeId to
                    match.definition.capabilityId.substringBeforeLast(".capability_")
            }

        val organized =
            AiLimbsGlobalScopeOrganizer.organize(
                query = "共同宽泛意图",
                matches = topTwenty,
                limit = 10,
                scopes = scopes,
                ownerPluginIdByInvokeId = ownerMap
            )

        assertTrue(organized.size <= 10)
        val scopeIds =
            organized
                .filterIsInstance<AiLimbsOrganizedSearchItem.Scope>()
                .map { it.scope.scopeId }
        assertEquals(scopeIds.distinct(), scopeIds)
        assertEquals(5, scopeIds.size)
        assertTrue(organized.all { it is AiLimbsOrganizedSearchItem.Scope })
    }

    private fun organizeDefinitions(query: String, definitions: List<AiLimbsCapabilityDefinition>) =
        AiLimbsGlobalScopeOrganizer.organize(
            query = query,
            matches = definitions.map { match(it, score = 320) },
            limit = 10,
            scopes = definitions.groupBy { it.provider.removePrefix("plugin:") }.map { (owner, owned) ->
                AiLimbsCapabilityScope(
                    scopeId = "plugin:$owner",
                    kind = AiLimbsCapabilityScopeKind.PLUGIN,
                    ownerPluginId = owner,
                    displayName = owner,
                    description = null,
                    capabilityIds = owned.map { it.capabilityId },
                    invokeIds = owned.map { it.invokeId }
                )
            },
            ownerPluginIdByInvokeId = definitions.associate {
                it.invokeId to it.provider.removePrefix("plugin:")
            }
        )

    @Test
    fun sharedDisplayNameWithinOwnerRemainsAmbiguousAndFolds() {
        val definitions = (1..4).map { definition("plugin.test.shared_name", it, "共享操作") }
        val organized = organizeDefinitions("共享操作", definitions)
        assertEquals(1, organized.size)
        assertTrue(organized.single() is AiLimbsOrganizedSearchItem.Scope)
    }

    @Test
    fun displayNameUniqueWithinEachOwnerButSharedAcrossOwnersStillFolds() {
        val definitions = listOf("plugin.test.owner_alpha", "plugin.test.owner_beta").flatMap { owner ->
            listOf(definition(owner, 1, "共享操作")) + (2..4).map { definition(owner, it) }
        }
        val organized = organizeDefinitions("共享操作", definitions)
        assertEquals(2, organized.size)
        assertTrue(organized.all { it is AiLimbsOrganizedSearchItem.Scope })
    }

    @Test
    fun uniqueDisplayNamePreservesLeafEvenWithCloseScores() {
        val owner = "plugin.test.unique_name"
        val leaf = definition(owner, 1, "唯一操作")
        val organized = organizeDefinitions("唯一操作", listOf(leaf) + (2..4).map { definition(owner, it) })
        assertEquals(4, organized.size)
        assertEquals(leaf.capabilityId,
            (organized.first() as AiLimbsOrganizedSearchItem.Capability).match.definition.capabilityId)
        assertFalse(organized.any { it is AiLimbsOrganizedSearchItem.Scope })
    }

    @Test
    fun capabilityAddressWinsOverAnotherOwnersMatchingDisplayName() {
        val owner = "plugin.test.address_owner"
        val otherOwner = "plugin.test.label_owner"
        val leaf = definition(owner, 1)
        val definitions = listOf(leaf) + (2..4).map { definition(owner, it) } +
            listOf(definition(otherOwner, 1, leaf.capabilityId)) +
            (2..4).map { definition(otherOwner, it) }
        val organized = organizeDefinitions(leaf.capabilityId, definitions)
        assertEquals(leaf.capabilityId,
            (organized.first() as AiLimbsOrganizedSearchItem.Capability).match.definition.capabilityId)
        val scopes = organized.filterIsInstance<AiLimbsOrganizedSearchItem.Scope>()
        assertEquals(listOf("plugin:$otherOwner"), scopes.map { it.scope.scopeId })
    }

    @Test
    fun uniqueAliasPreservesCanonicalLeafAndSharedAliasRemainsAmbiguous() {
        val owner = "plugin.test.alias_owner"
        val leaf = definition(owner, 1).copy(aliases = listOf("unique-action"))
        val definitions = listOf(leaf) + (2..4).map { definition(owner, it) }
        val organized = organizeDefinitions("UNIQUE-ACTION", definitions)
        assertEquals(leaf.capabilityId,
            (organized.first() as AiLimbsOrganizedSearchItem.Capability).match.definition.capabilityId)
        assertFalse(organized.any { it is AiLimbsOrganizedSearchItem.Scope })
        val ambiguous = organizeDefinitions("shared-action", definitions.map {
            it.copy(aliases = listOf("shared-action"))
        })
        assertEquals(1, ambiguous.size)
        assertTrue(ambiguous.single() is AiLimbsOrganizedSearchItem.Scope)
    }
}
