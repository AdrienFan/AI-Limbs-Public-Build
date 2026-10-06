package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCapabilityCatalog
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry

/** Bounded lexical caches shared by catalog views. Policy and availability are always live. */
internal class AiLimbsSearchIndexCache(
    private val maxIndexes: Int = 8,
    private val maxDocuments: Int = 4096
) {
    init { require(maxIndexes > 0 && maxDocuments > 0) }
    private var revision = -1L
    private val indexes = LinkedHashMap<List<ToolCatalogEntry>, ToolCapabilityCatalog.PreparedIndex>(16, 0.75f, true)
    private val documents = LinkedHashMap<ToolCatalogEntry, ToolCapabilityCatalog.PreparedEntry>(16, 0.75f, true)
    data class Lookup(
        val index: ToolCapabilityCatalog.PreparedIndex,
        val reused: Boolean,
        val preparedDocuments: Int,
        val reusedDocuments: Int
    )

    @Synchronized fun current(source: List<ToolCatalogEntry>, registryRevision: Long): Lookup {
        // An old registry generation can never select an old catalog view after mount/unmount.
        if (revision != registryRevision) {
            indexes.clear()
            revision = registryRevision
        }
        val snapshot = source.map { it.copy(parameterHints = it.parameterHints.toList(),
            keywords = it.keywords.toList(), parameters = it.parameters.toList(), searchMetadata = it.searchMetadata.toList()) }
        indexes[snapshot]?.let { return Lookup(it, true, 0, snapshot.size) }
        var prepared = 0
        var reused = 0
        val selected = snapshot.map { entry ->
            val existing = documents[entry]
            if (existing != null) {
                reused++
                existing
            } else {
                val document = ToolCapabilityCatalog.prepareEntry(entry)
                documents[entry] = document
                prepared++
                while (documents.size > maxDocuments) documents.remove(documents.keys.first())
                document
            }
        }
        val index = ToolCapabilityCatalog.PreparedIndex(selected)
        indexes[snapshot] = index
        while (indexes.size > maxIndexes) indexes.remove(indexes.keys.first())
        return Lookup(index, false, prepared, reused)
    }
}
