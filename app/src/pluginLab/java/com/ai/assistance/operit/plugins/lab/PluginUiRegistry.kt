package com.ai.assistance.operit.plugins.lab

import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.PluginExtensionPoints
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class PluginHomeTileSpec(
    val ownerPluginId: String,
    val id: String,
    val title: String,
    val description: String,
    val screenId: String
)

sealed class PluginScreenBlock {
    data class Text(val text: String) : PluginScreenBlock()
    data class CapabilityButton(
        val label: String,
        val capabilityId: String,
        val parameters: JSONObject = JSONObject()
    ) : PluginScreenBlock()
}

data class PluginScreenSpec(
    val ownerPluginId: String,
    val id: String,
    val title: String,
    val description: String?,
    val blocks: List<PluginScreenBlock>
)

enum class PluginThemeMode { LIGHT, DARK }

data class PluginThemeSpec(
    val ownerPluginId: String,
    val id: String,
    val mode: PluginThemeMode,
    val pureBlack: Boolean
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
        val token = UUID.randomUUID().toString()
        val candidate = Owned(token, ownerPluginId, spec)
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
}
