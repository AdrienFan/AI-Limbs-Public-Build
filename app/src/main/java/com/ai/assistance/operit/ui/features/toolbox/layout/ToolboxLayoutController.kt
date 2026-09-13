package com.ai.assistance.operit.ui.features.toolbox.layout

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

data class UiLayoutEditSession(
    val surface: String,
    val mode: String
)

class ToolboxLayoutController private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableEditSession = MutableStateFlow<UiLayoutEditSession?>(null)
    private val mutableToolboxOrder = MutableStateFlow(loadToolboxOrder())

    val editSession: StateFlow<UiLayoutEditSession?> = mutableEditSession.asStateFlow()
    val toolboxOrder: StateFlow<List<String>> = mutableToolboxOrder.asStateFlow()

    fun start(surface: String, mode: String): UiLayoutEditSession {
        requireSupported(surface, mode)
        return UiLayoutEditSession(surface = TOOLBOX_SURFACE, mode = LAYOUT_MODE).also {
            mutableEditSession.value = it
        }
    }

    fun finish() {
        mutableEditSession.value = null
    }

    fun reset(surface: String) {
        require(surface.trim().lowercase() == TOOLBOX_SURFACE) { "Unsupported UI layout surface: $surface" }
        prefs.edit().remove(KEY_TOOLBOX_ORDER).apply()
        mutableToolboxOrder.value = emptyList()
    }

    fun saveOrder(surface: String, entryIds: List<String>) {
        require(surface.trim().lowercase() == TOOLBOX_SURFACE) { "Unsupported UI layout surface: $surface" }
        val normalized = entryIds.map(String::trim).filter(String::isNotEmpty).distinct()
        prefs.edit().putString(KEY_TOOLBOX_ORDER, JSONArray(normalized).toString()).apply()
        mutableToolboxOrder.value = normalized
    }

    fun resolveToolboxOrder(currentEntryIds: List<String>): List<String> {
        val current = currentEntryIds.map(String::trim).filter(String::isNotEmpty).distinct()
        val available = current.toSet()
        val saved = mutableToolboxOrder.value.filter { it in available }
        return saved + current.filterNot { it in saved.toSet() }
    }

    private fun loadToolboxOrder(): List<String> {
        val raw = prefs.getString(KEY_TOOLBOX_ORDER, null)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
                }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    private fun requireSupported(surface: String, mode: String) {
        require(surface.trim().lowercase() == TOOLBOX_SURFACE) { "Unsupported UI layout surface: $surface" }
        require(mode.trim().lowercase() == LAYOUT_MODE) { "Unsupported UI edit mode: $mode" }
    }

    companion object {
        const val TOOLBOX_SURFACE = "toolbox"
        const val LAYOUT_MODE = "layout"
        private const val PREFS_NAME = "ai_limbs_ui_layout_v1"
        private const val KEY_TOOLBOX_ORDER = "toolbox_order"

        @Volatile
        private var instance: ToolboxLayoutController? = null

        fun get(context: Context): ToolboxLayoutController =
            instance ?: synchronized(this) {
                instance ?: ToolboxLayoutController(context.applicationContext).also { instance = it }
            }
    }
}
