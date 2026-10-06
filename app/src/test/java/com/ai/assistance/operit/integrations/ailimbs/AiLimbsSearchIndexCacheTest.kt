package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCapabilityCatalog
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiLimbsSearchIndexCacheTest {
    private fun entry(id: String = "plugin.test.lexical.echo", description: String = "兰儿回声😀") =
        ToolCatalogEntry(id, "Echo", description, emptyList(), ToolCatalogSourceKind.PACKAGE,
            keywords = listOf("echo"), sourceName = "plugin:plugin.test.lexical")

    @Test fun unchangedMetadataReusesPreparedIndexAcrossQueries() {
        val cache = AiLimbsSearchIndexCache()
        val entries = listOf(entry())
        val first = cache.current(entries, 10)
        val second = cache.current(entries.map { it.copy() }, 10)
        assertFalse(first.reused)
        assertTrue(second.reused)
        assertSame(first.index, second.index)
        assertEquals(entries.single().targetToolName,
            ToolCapabilityCatalog.searchAll(second.index, "兰儿回声").matches.first().entry.targetToolName)
    }

    @Test fun generationChangeInvalidatesEvenEqualMetadata() {
        val cache = AiLimbsSearchIndexCache()
        val first = cache.current(listOf(entry()), 10)
        val second = cache.current(listOf(entry()), 11)
        assertFalse(second.reused)
        assertNotSame(first.index, second.index)
    }

    @Test fun metadataAndSourceEnabledChangesDoNotReuseOldIndex() {
        val cache = AiLimbsSearchIndexCache()
        val old = cache.current(listOf(entry(description = "oldtoken")), 1)
        val changed = cache.current(listOf(entry(description = "newtoken").copy(sourceEnabled = false)), 1)
        assertFalse(changed.reused)
        assertNotSame(old.index, changed.index)
        assertFalse(changed.index.documents.single().entry.sourceEnabled)
        assertEquals("newtoken", changed.index.documents.single().entry.description)
    }

    @Test fun mutatingProducerCollectionsDoesNotMutateSnapshotOrHideChanges() {
        val keywords = mutableListOf("oldtoken")
        val original = entry().copy(keywords = keywords)
        val cache = AiLimbsSearchIndexCache()
        val first = cache.current(listOf(original), 1)
        keywords[0] = "newtoken"
        val second = cache.current(listOf(original), 1)
        assertFalse(second.reused)
        assertEquals(listOf("oldtoken"), first.index.documents.single().entry.keywords)
        assertEquals(listOf("newtoken"), second.index.documents.single().entry.keywords)
    }

    @Test fun removingCapabilitiesRemovesThemFromPreparedSearchResults() {
        val cache = AiLimbsSearchIndexCache()
        val first = cache.current(listOf(entry()), 1)
        assertTrue(ToolCapabilityCatalog.searchAll(first.index, "echo").matches.isNotEmpty())
        val removed = cache.current(emptyList(), 2)
        assertTrue(ToolCapabilityCatalog.searchAll(removed.index, "echo").matches.isEmpty())
    }

    @Test fun registryRevisionChangesOnMountAndEffectiveUnmountButNotRepeatedClose() {
        val id = "plugin.test.index_revision_fixture.echo"
        val before = AiLimbsCapabilityRegistry.metadataRevision()
        val handle = AiLimbsCapabilityRegistry.registerPluginCapability(
            ownerPluginId = "plugin.test.index_revision_fixture", capabilityId = id,
            invokeAliases = listOf("plugin.test.index_revision_fixture.say"), catalogEntry = entry(id),
            effect = AiLimbsEffect.READ_ONLY, domain = AiLimbsDomain.PLUGIN,
            workContextRequiredReceipts = emptySet(), executor = AiLimbsPluginCapabilityExecutor { JSONObject() })
        try {
            assertEquals(before + 1, AiLimbsCapabilityRegistry.metadataRevision())
        } finally { handle.close() }
        val after = AiLimbsCapabilityRegistry.metadataRevision()
        assertEquals(before + 2, after)
        handle.close()
        assertEquals(after, AiLimbsCapabilityRegistry.metadataRevision())
    }
    @Test fun canonicalPluginMetadataWinsOverRawRuntimeStubsAndAliasDuplicates() {
        val id = "plugin.test.index_metadata_fixture.echo"
        val alias = "plugin.test.index_metadata_fixture.say"
        val canonical = entry(id, "Current rich metadata")
        val handle = AiLimbsCapabilityRegistry.registerPluginCapability(
            ownerPluginId = "plugin.test.index_metadata_fixture", capabilityId = id,
            invokeAliases = listOf(alias), catalogEntry = canonical,
            effect = AiLimbsEffect.READ_ONLY, domain = AiLimbsDomain.PLUGIN,
            workContextRequiredReceipts = emptySet(), executor = AiLimbsPluginCapabilityExecutor { JSONObject() })
        val raw = listOf(entry(id, "Stale raw stub"), entry(alias, "Stale alias"))
        try {
            val merged = AiLimbsCapabilityRegistry.mergeInto(raw)
            assertEquals(canonical, merged.single { it.targetToolName == id })
            assertFalse(merged.any { it.targetToolName == alias })
        } finally { handle.close() }
        assertFalse(AiLimbsCapabilityRegistry.mergeInto(raw).any { it.targetToolName == id || it.targetToolName == alias })
    }

    @Test fun orphanPluginStubsCannotReappearButOrdinaryHostToolsRemain() {
        val orphan = entry("plugin.test.orphan_catalog_fixture.echo")
        val host = entry("press_key").copy(sourceKind = ToolCatalogSourceKind.INTERNAL)
        val merged = AiLimbsCapabilityRegistry.mergeInto(listOf(orphan, host))
        assertFalse(merged.any { it.targetToolName == orphan.targetToolName })
        assertTrue(merged.any { it.targetToolName == host.targetToolName })
    }

    @Test fun globalScopeAndExactViewsDoNotEvictEachOther() {
        val all = (1..40).map { entry("plugin.test.shared.$it") }
        val cache = AiLimbsSearchIndexCache()
        val global = cache.current(all, 1)
        val scoped = cache.current(all.take(8), 1)
        val exact = cache.current(all.take(1), 1)
        assertEquals(40, global.preparedDocuments)
        assertEquals(0, scoped.preparedDocuments)
        assertEquals(8, scoped.reusedDocuments)
        assertEquals(0, exact.preparedDocuments)
        val returned = cache.current(all, 1)
        assertTrue(returned.reused)
        assertSame(global.index, returned.index)
    }

    @Test fun firstGlobalViewOnlyPreparesDocumentsMissingFromTheScopedView() {
        val all = (1..12).map { entry("plugin.test.incremental.$it") }
        val cache = AiLimbsSearchIndexCache()
        val scoped = cache.current(all.take(4), 1)
        val global = cache.current(all, 1)
        assertEquals(8, global.preparedDocuments)
        assertEquals(4, global.reusedDocuments)
        assertSame(scoped.index.documents.first(), global.index.documents.first())
    }

    @Test fun evictedCatalogViewReassemblesFromExistingDocumentsWithoutTokenizingAgain() {
        val all = (1..12).map { entry("plugin.test.views.$it") }
        val cache = AiLimbsSearchIndexCache(maxIndexes = 2)
        val first = cache.current(all, 1)
        cache.current(all.take(4), 1)
        cache.current(all.take(1), 1)
        val returned = cache.current(all, 1)
        assertFalse(returned.reused)
        assertEquals(0, returned.preparedDocuments)
        assertEquals(12, returned.reusedDocuments)
        assertSame(first.index.documents.first(), returned.index.documents.first())
    }

    @Test fun metadataChangePreparesOnlyTheChangedDocumentAndKeepsCanonicalMetadata() {
        val all = (1..5).map { entry("plugin.test.changed.$it", "oldtoken") }
        val cache = AiLimbsSearchIndexCache()
        val first = cache.current(all, 1)
        val changed = cache.current(all.dropLast(1) + all.last().copy(description = "newtoken"), 1)
        assertEquals(1, changed.preparedDocuments)
        assertEquals(4, changed.reusedDocuments)
        assertSame(first.index.documents.first(), changed.index.documents.first())
        assertEquals("newtoken", changed.index.documents.last().entry.description)
    }

    @Test fun registryRemovalInvalidatesAllViewsWithoutRebuildingUnchangedLexicalDocuments() {
        val removed = entry("plugin.test.removal.retired", "退役秘钥")
        val retained = entry("plugin.test.removal.retained", "活跃状态")
        val cache = AiLimbsSearchIndexCache()
        cache.current(listOf(removed, retained), 1)
        cache.current(listOf(removed), 1)
        val current = cache.current(listOf(retained), 2)
        assertFalse(current.reused)
        assertEquals(0, current.preparedDocuments)
        assertEquals(1, current.reusedDocuments)
        assertTrue(ToolCapabilityCatalog.searchAll(current.index, "退役秘钥").matches.isEmpty())
        val remounted = cache.current(listOf(retained, removed.copy(description = "remounttoken")), 3)
        assertEquals(1, remounted.preparedDocuments)
        assertEquals("remounttoken", remounted.index.documents.last().entry.description)
    }

    @Test fun documentCacheUsesLeastRecentlyUsedEviction() {
        val cache = AiLimbsSearchIndexCache(maxIndexes = 1, maxDocuments = 2)
        val a = entry("plugin.test.lru.a")
        val b = entry("plugin.test.lru.b")
        val c = entry("plugin.test.lru.c")
        cache.current(listOf(a), 1)
        cache.current(listOf(b), 1)
        cache.current(listOf(a), 1)
        cache.current(listOf(c), 1)
        assertEquals(0, cache.current(listOf(a), 1).preparedDocuments)
        assertEquals(1, cache.current(listOf(b), 1).preparedDocuments)
    }

}
