package com.ai.assistance.operit.plugins.center

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PluginHomeTileSpec(
    val ownerPluginId: String,
    val id: String,
    val title: String,
    val description: String,
    val screenId: String
)

/**
 * Host-owned metadata for a plugin screen.
 *
 * [documentJson] is intentionally opaque to Stable Kernel code.  The matching system UI renderer
 * is registered by Plugin Center and owns the component schema, rendering rules and interaction
 * semantics.  Keeping the document opaque prevents every new composite control from becoming a
 * Host ABI change.
 */
data class PluginScreenSpec(
    val ownerPluginId: String,
    val id: String,
    val title: String,
    val description: String?,
    val schemaId: String,
    val documentJson: String
)

enum class PluginThemeMode { LIGHT, DARK }

data class PluginThemeSpec(
    val ownerPluginId: String,
    val id: String,
    val mode: PluginThemeMode,
    val pureBlack: Boolean,
    val colors: Map<String, String> = emptyMap(),
    val backgroundGradient: List<String> = emptyList()
)

internal class PluginUiRegistry {
    private data class Owned<T>(val token: String, val ownerPluginId: String, val value: T)

    private val tiles = ConcurrentHashMap<String, Owned<PluginHomeTileSpec>>()
    private val screens = ConcurrentHashMap<String, Owned<PluginScreenSpec>>()
    private val mutableHomeTiles = MutableStateFlow<List<PluginHomeTileSpec>>(emptyList())
    private val mutableScreens = MutableStateFlow<List<PluginScreenSpec>>(emptyList())
    private val themeLock = Any()
    private var activeThemeOwned: Owned<PluginThemeSpec>? = null
    private val mutableActiveTheme = MutableStateFlow<PluginThemeSpec?>(null)

    val homeTiles: StateFlow<List<PluginHomeTileSpec>> = mutableHomeTiles.asStateFlow()
    val activeScreens: StateFlow<List<PluginScreenSpec>> = mutableScreens.asStateFlow()
    val activeTheme: StateFlow<PluginThemeSpec?> = mutableActiveTheme.asStateFlow()

    fun registerHomeTile(ownerPluginId: String, spec: PluginHomeTileSpec): AutoCloseable {
        requireOwner(ownerPluginId, spec.ownerPluginId)
        val token = UUID.randomUUID().toString()
        val candidate = Owned(token, ownerPluginId, spec)
        val existing = tiles.putIfAbsent(spec.id, candidate)
        if (existing != null) {
            throw PluginInstallException("UI_TILE_CONFLICT", "UI tile is already registered: ${spec.id}")
        }
        publishTiles()
        return AutoCloseable {
            tiles.computeIfPresent(spec.id) { _, current ->
                if (current.token == token) null else current
            }
            publishTiles()
        }
    }

    fun registerScreen(ownerPluginId: String, spec: PluginScreenSpec): AutoCloseable {
        requireOwner(ownerPluginId, spec.ownerPluginId)
        val schemaId = spec.schemaId.trim()
        if (schemaId.isEmpty()) {
            throw PluginInstallException("UI_SCREEN_SCHEMA_EMPTY", "UI screen schema id is empty: ${spec.id}")
        }
        if (spec.documentJson.isBlank()) {
            throw PluginInstallException("UI_SCREEN_DOCUMENT_EMPTY", "UI screen document is empty: ${spec.id}")
        }
        if (spec.documentJson.toByteArray().size > MAX_SCREEN_DOCUMENT_BYTES) {
            throw PluginInstallException("UI_SCREEN_DOCUMENT_TOO_LARGE", "UI screen document exceeds 512 KiB: ${spec.id}")
        }
        val normalized = spec.copy(schemaId = schemaId)
        val token = UUID.randomUUID().toString()
        val candidate = Owned(token, ownerPluginId, normalized)
        val existing = screens.putIfAbsent(spec.id, candidate)
        if (existing != null) {
            throw PluginInstallException("UI_SCREEN_CONFLICT", "UI screen is already registered: ${spec.id}")
        }
        publishScreens()
        return AutoCloseable {
            screens.computeIfPresent(spec.id) { _, current ->
                if (current.token == token) null else current
            }
            publishScreens()
        }
    }

    fun screen(id: String): PluginScreenSpec? = screens[id]?.value

    fun homeTile(id: String): PluginHomeTileSpec? = tiles[id]?.value

    fun homeTileSnapshots(): List<PluginHomeTileSpec> = homeTiles.value

    fun registerTheme(ownerPluginId: String, spec: PluginThemeSpec): AutoCloseable {
        requireOwner(ownerPluginId, spec.ownerPluginId)
        val token = UUID.randomUUID().toString()
        synchronized(themeLock) {
            val existing = activeThemeOwned
            if (existing != null) {
                throw PluginInstallException(
                    "UI_THEME_CONFLICT",
                    "Only one UI theme may be active; current owner=${existing.ownerPluginId}"
                )
            }
            activeThemeOwned = Owned(token, ownerPluginId, spec)
            mutableActiveTheme.value = spec
        }
        return AutoCloseable {
            synchronized(themeLock) {
                if (activeThemeOwned?.token == token) {
                    activeThemeOwned = null
                    mutableActiveTheme.value = null
                }
            }
        }
    }

    private fun publishTiles() {
        mutableHomeTiles.value = tiles.values.map { it.value }.sortedBy { it.title.lowercase() }
    }

    private fun publishScreens() {
        mutableScreens.value = screens.values.map { it.value }.sortedBy { it.title.lowercase() }
    }

    private fun requireOwner(expected: String, actual: String) {
        if (expected != actual) {
            throw PluginInstallException("UI_EXTENSION_OWNER_INVALID", "UI extension owner mismatch")
        }
    }

    private companion object {
        // A generic safety bound only.  The Host does not inspect or validate component semantics.
        const val MAX_SCREEN_DOCUMENT_BYTES = 512 * 1024
    }
}
