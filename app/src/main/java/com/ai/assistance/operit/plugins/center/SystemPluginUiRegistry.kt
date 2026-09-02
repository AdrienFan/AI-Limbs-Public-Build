package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.plugins.system.SystemToolboxEntryV1
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SystemPluginUiRegistry {
    private data class Owned(
        val token: String,
        val ownerPluginId: String,
        val entry: SystemToolboxEntryV1
    )

    private val entries = ConcurrentHashMap<String, Owned>()
    private val mutableToolboxEntries = MutableStateFlow<List<SystemToolboxEntryV1>>(emptyList())
    val toolboxEntries: StateFlow<List<SystemToolboxEntryV1>> = mutableToolboxEntries.asStateFlow()

    fun registerToolboxEntry(ownerPluginId: String, entry: SystemToolboxEntryV1): AutoCloseable {
        val id = entry.id.trim()
        if (id.isEmpty()) throw PluginInstallException("SYSTEM_UI_ENTRY_ID_EMPTY", "System UI entry id is empty")
        val token = UUID.randomUUID().toString()
        val owned = Owned(token, ownerPluginId, entry.copy(id = id))
        if (entries.putIfAbsent(id, owned) != null) {
            throw PluginInstallException("SYSTEM_UI_ENTRY_CONFLICT", "System UI entry already exists: $id")
        }
        publish()
        return AutoCloseable {
            entries.computeIfPresent(id) { _, current -> if (current.token == token) null else current }
            publish()
        }
    }

    fun entry(id: String): SystemToolboxEntryV1? = entries[id.trim()]?.entry

    fun hasEntryForOwner(ownerPluginId: String): Boolean = entries.values.any { it.ownerPluginId == ownerPluginId }

    fun hasPluginCenterEntry(): Boolean = entries.values.any { it.ownerPluginId.startsWith("ai_limbs.system.") }

    private fun publish() {
        mutableToolboxEntries.value = entries.values.map { it.entry }.sortedBy { it.title.lowercase() }
    }
}
