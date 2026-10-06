package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCapabilityCatalog
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry

/** Stores lexical metadata only. Policy/receipts/availability are always inspected live. */
internal class AiLimbsSearchIndexCache {
    private var revision = -1L
    private var entries = emptyList<ToolCatalogEntry>()
    private var index: ToolCapabilityCatalog.PreparedIndex? = null
    data class Lookup(val index: ToolCapabilityCatalog.PreparedIndex, val reused: Boolean)

    @Synchronized fun current(source: List<ToolCatalogEntry>, registryRevision: Long): Lookup {
        val existing = index
        if (existing != null && revision == registryRevision && entries == source) {
            return Lookup(existing, true)
        }
        // Own collection copies: mutating a producer list must not mutate the cache key in place.
        val snapshot = source.map { it.copy(parameterHints = it.parameterHints.toList(),
            keywords = it.keywords.toList(), parameters = it.parameters.toList(), searchMetadata = it.searchMetadata.toList()) }
        val prepared = ToolCapabilityCatalog.prepareIndex(snapshot)
        revision = registryRevision
        entries = snapshot
        index = prepared
        return Lookup(prepared, false)
    }
}
