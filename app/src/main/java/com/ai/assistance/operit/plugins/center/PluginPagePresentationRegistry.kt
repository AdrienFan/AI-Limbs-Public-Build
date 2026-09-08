package com.ai.assistance.operit.plugins.center

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PluginPagePresentationMode {
    NORMAL,
    FULLSCREEN_PORTRAIT,
    FULLSCREEN_LANDSCAPE;

    companion object {
        fun parse(raw: String): PluginPagePresentationMode = when (raw.trim().lowercase()) {
            "normal" -> NORMAL
            "fullscreen_portrait" -> FULLSCREEN_PORTRAIT
            "fullscreen_landscape" -> FULLSCREEN_LANDSCAPE
            else -> throw PluginInstallException(
                "UI_PRESENTATION_MODE_INVALID",
                "Unsupported plugin page presentation mode: $raw"
            )
        }
    }
}

data class PluginPagePresentationRequest(
    val ownerPluginId: String,
    val screenId: String,
    val mode: PluginPagePresentationMode
)

internal class PluginPagePresentationRegistry {
    private val mutableRequests = MutableStateFlow<Map<String, PluginPagePresentationRequest>>(emptyMap())
    val requests: StateFlow<Map<String, PluginPagePresentationRequest>> = mutableRequests.asStateFlow()

    @Volatile
    private var activeScreenId: String? = null

    @Synchronized
    fun setActiveScreen(screenId: String?) {
        val normalized = screenId?.trim()?.takeIf { it.isNotEmpty() }
        val previous = activeScreenId
        if (previous == normalized) return
        if (previous != null) {
            val current = mutableRequests.value.toMutableMap()
            if (current.remove(previous) != null) mutableRequests.value = current.toMap()
        }
        activeScreenId = normalized
    }

    fun isActiveScreen(screenId: String): Boolean = activeScreenId == screenId.trim()

    @Synchronized
    fun set(ownerPluginId: String, screenId: String, mode: PluginPagePresentationMode) {
        val current = mutableRequests.value.toMutableMap()
        if (mode == PluginPagePresentationMode.NORMAL) {
            current.remove(screenId)
        } else {
            current[screenId] = PluginPagePresentationRequest(ownerPluginId, screenId, mode)
        }
        mutableRequests.value = current.toMap()
    }

    fun get(screenId: String): PluginPagePresentationRequest? = mutableRequests.value[screenId]

    @Synchronized
    fun clearOwner(ownerPluginId: String) {
        val filtered = mutableRequests.value.filterValues { it.ownerPluginId != ownerPluginId }
        if (filtered.size != mutableRequests.value.size) mutableRequests.value = filtered
    }
}
