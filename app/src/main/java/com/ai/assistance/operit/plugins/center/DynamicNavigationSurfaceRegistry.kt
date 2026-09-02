package com.ai.assistance.operit.plugins.center

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class DynamicNavigationSurfaceSpec(
    val id: String,
    val title: String,
    val iconKey: String,
    val order: Int,
    val createdAt: Long
)

data class DynamicNavigationBinding(
    val surfaceId: String,
    val ownerPluginId: String,
    val tileId: String,
    val screenId: String
)

internal class DynamicNavigationSurfaceRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val mutableSurfaces = MutableStateFlow(loadSurfaces())
    private val mutableBindings = MutableStateFlow(loadBindings())

    val surfaces: StateFlow<List<DynamicNavigationSurfaceSpec>> = mutableSurfaces.asStateFlow()
    val bindings: StateFlow<List<DynamicNavigationBinding>> = mutableBindings.asStateFlow()

    fun snapshot(): List<DynamicNavigationSurfaceSpec> = mutableSurfaces.value

    fun find(surfaceId: String): DynamicNavigationSurfaceSpec? =
        mutableSurfaces.value.firstOrNull { it.id == surfaceId.trim() }

    fun create(title: String? = null, iconKey: String = DEFAULT_ICON): DynamicNavigationSurfaceSpec = synchronized(lock) {
        val current = mutableSurfaces.value
        val nextIndex = generateSequence(1) { it + 1 }
            .first { index -> current.none { it.title == "新页面 $index" } }
        val spec = DynamicNavigationSurfaceSpec(
            id = "user.navigation.${UUID.randomUUID()}",
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: "新页面 $nextIndex",
            iconKey = iconKey.trim().ifBlank { DEFAULT_ICON },
            order = (current.maxOfOrNull { it.order } ?: 0) + 10,
            createdAt = System.currentTimeMillis()
        )
        publishSurfaces(current + spec)
        spec
    }

    fun rename(surfaceId: String, title: String, iconKey: String? = null): DynamicNavigationSurfaceSpec = synchronized(lock) {
        val id = surfaceId.trim()
        val newTitle = title.trim()
        val current = mutableSurfaces.value
        val existing = current.firstOrNull { it.id == id }
            ?: throw PluginInstallException("DYNAMIC_SURFACE_NOT_FOUND", "动态页面不存在：$id")
        val updated = existing.copy(
            title = newTitle,
            iconKey = iconKey?.trim()?.takeIf { it.isNotEmpty() } ?: existing.iconKey
        )
        publishSurfaces(current.map { if (it.id == id) updated else it })
        updated
    }

    fun deleteIfEmpty(surfaceId: String) = synchronized(lock) {
        val id = surfaceId.trim()
        if (mutableBindings.value.any { it.surfaceId == id }) {
            throw PluginInstallException(
                "DYNAMIC_SURFACE_NOT_EMPTY",
                "页面仍包含插件或应用，必须先移除全部内容后才能删除"
            )
        }
        val current = mutableSurfaces.value
        if (current.none { it.id == id }) {
            throw PluginInstallException("DYNAMIC_SURFACE_NOT_FOUND", "动态页面不存在：$id")
        }
        publishSurfaces(current.filterNot { it.id == id })
    }

    fun bindingsFor(surfaceId: String): List<DynamicNavigationBinding> =
        mutableBindings.value.filter { it.surfaceId == surfaceId.trim() }

    fun bind(surfaceId: String, tile: PluginHomeTileSpec) = synchronized(lock) {
        val id = surfaceId.trim()
        if (find(id) == null) throw PluginInstallException("DYNAMIC_SURFACE_NOT_FOUND", "动态页面不存在：$id")
        val binding = DynamicNavigationBinding(id, tile.ownerPluginId, tile.id, tile.screenId)
        val current = mutableBindings.value
        if (current.any { it.surfaceId == id && it.tileId == tile.id }) return@synchronized
        publishBindings(current + binding)
    }
    fun unbind(surfaceId: String, tileId: String) = synchronized(lock) {
        val id = surfaceId.trim()
        val tile = tileId.trim()
        publishBindings(mutableBindings.value.filterNot { it.surfaceId == id && it.tileId == tile })
    }

    private fun publishSurfaces(value: List<DynamicNavigationSurfaceSpec>) {
        val sorted = value.sortedWith(compareBy<DynamicNavigationSurfaceSpec> { it.order }.thenBy { it.createdAt })
        prefs.edit().putString(KEY_SURFACES, JSONArray().apply {
            sorted.forEach { spec ->
                put(JSONObject()
                    .put("id", spec.id)
                    .put("title", spec.title)
                    .put("icon_key", spec.iconKey)
                    .put("order", spec.order)
                    .put("created_at", spec.createdAt))
            }
        }.toString()).apply()
        mutableSurfaces.value = sorted
    }

    private fun publishBindings(value: List<DynamicNavigationBinding>) {
        prefs.edit().putString(KEY_BINDINGS, JSONArray().apply {
            value.forEach { binding ->
                put(JSONObject()
                    .put("surface_id", binding.surfaceId)
                    .put("owner_plugin_id", binding.ownerPluginId)
                    .put("tile_id", binding.tileId)
                    .put("screen_id", binding.screenId))
            }
        }.toString()).apply()
        mutableBindings.value = value
    }
    private fun loadSurfaces(): List<DynamicNavigationSurfaceSpec> = runCatching {
        val raw = prefs.getString(KEY_SURFACES, null) ?: return@runCatching emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(DynamicNavigationSurfaceSpec(
                    id = item.getString("id"),
                    title = item.getString("title"),
                    iconKey = item.optString("icon_key", DEFAULT_ICON),
                    order = item.optInt("order", (index + 1) * 10),
                    createdAt = item.optLong("created_at", 0L)
                ))
            }
        }.sortedBy { it.order }
    }.getOrDefault(emptyList())

    private fun loadBindings(): List<DynamicNavigationBinding> = runCatching {
        val raw = prefs.getString(KEY_BINDINGS, null) ?: return@runCatching emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(DynamicNavigationBinding(
                    surfaceId = item.getString("surface_id"),
                    ownerPluginId = item.getString("owner_plugin_id"),
                    tileId = item.getString("tile_id"),
                    screenId = item.getString("screen_id")
                ))
            }
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val PREFS = "ai_limbs_dynamic_navigation_surfaces"
        const val KEY_SURFACES = "surfaces"
        const val KEY_BINDINGS = "bindings"
        const val DEFAULT_ICON = "extension"
    }
}