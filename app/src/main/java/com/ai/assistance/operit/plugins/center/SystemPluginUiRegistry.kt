package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.plugins.system.SystemPluginUiRendererV2
import com.ai.assistance.operit.plugins.system.SystemToolboxEntryV1
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Host-side registry for system-owned UI entry points.
 *
 * The Stable Kernel only owns lifecycle and routing.  The renderer stored here is supplied by
 * Plugin Center and is the single authority that understands ordinary plugin UI component schemas.
 * New composite controls therefore replace/update Plugin Center instead of adding Host render code.
 */
internal class SystemPluginUiRegistry {
    private data class Owned(
        val token: String,
        val ownerPluginId: String,
        val entry: SystemToolboxEntryV1
    )

    private data class OwnedRenderer(
        val token: String,
        val ownerPluginId: String,
        val renderer: SystemPluginUiRendererV2
    )

    private val entries = ConcurrentHashMap<String, Owned>()
    private val mutableToolboxEntries = MutableStateFlow<List<SystemToolboxEntryV1>>(emptyList())
    private val rendererLock = Any()
    private var ownedRenderer: OwnedRenderer? = null
    private val mutablePluginSurfaceRenderer = MutableStateFlow<SystemPluginUiRendererV2?>(null)

    val toolboxEntries: StateFlow<List<SystemToolboxEntryV1>> = mutableToolboxEntries.asStateFlow()

    /**
     * Current renderer for ordinary plugin UI documents.  UI screens observe this flow so a Plugin
     * Center upgrade/repair can detach and replace the renderer without restarting the Host.
     */
    val pluginSurfaceRenderer: StateFlow<SystemPluginUiRendererV2?> =
        mutablePluginSurfaceRenderer.asStateFlow()

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

    /**
     * Registers the one renderer that interprets opaque plugin UI documents.
     *
     * Only the admitted Plugin Center can reach this API.  A single renderer avoids split-brain UI
     * semantics where two system components disagree about what a component type means.
     */
    fun registerPluginSurfaceRenderer(
        ownerPluginId: String,
        renderer: SystemPluginUiRendererV2
    ): AutoCloseable {
        // Defense in depth: SystemUiHostV2 already admits only system.role=plugin_center, but the
        // registry also pins the exact owner identity so a future system-plugin role cannot inherit
        // ordinary-plugin UI language ownership by accident.
        requirePluginCenterUiOwner(ownerPluginId)
        val token = UUID.randomUUID().toString()
        synchronized(rendererLock) {
            if (ownedRenderer != null) {
                throw PluginInstallException(
                    "SYSTEM_UI_RENDERER_CONFLICT",
                    "An ordinary-plugin UI renderer is already registered"
                )
            }
            ownedRenderer = OwnedRenderer(token, ownerPluginId, renderer)
            mutablePluginSurfaceRenderer.value = renderer
        }
        return AutoCloseable {
            synchronized(rendererLock) {
                if (ownedRenderer?.token == token) {
                    ownedRenderer = null
                    mutablePluginSurfaceRenderer.value = null
                }
            }
        }
    }

    fun entry(id: String): SystemToolboxEntryV1? = entries[id.trim()]?.entry

    fun hasEntryForOwner(ownerPluginId: String): Boolean = entries.values.any { it.ownerPluginId == ownerPluginId }

    fun hasPluginSurfaceRendererForOwner(ownerPluginId: String): Boolean =
        synchronized(rendererLock) { ownedRenderer?.ownerPluginId == ownerPluginId }

    fun hasPluginCenterEntry(): Boolean = entries.values.any { it.ownerPluginId == PLUGIN_CENTER_OWNER_ID }

    private fun requirePluginCenterUiOwner(ownerPluginId: String) {
        if (ownerPluginId.trim() != PLUGIN_CENTER_OWNER_ID) {
            throw PluginInstallException(
                "SYSTEM_UI_RENDERER_OWNER_FORBIDDEN",
                "Ordinary-plugin UI renderer ownership is private to $PLUGIN_CENTER_OWNER_ID"
            )
        }
    }

    private fun publish() {
        mutableToolboxEntries.value = entries.values.map { it.entry }.sortedBy { it.title.lowercase() }
    }

    private companion object {
        const val PLUGIN_CENTER_OWNER_ID = "ai_limbs.system.plugin_center"
    }
}
