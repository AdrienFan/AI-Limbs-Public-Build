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

}
