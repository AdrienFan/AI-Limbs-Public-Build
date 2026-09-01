package com.ai.assistance.operit.plugins.center

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

enum class HostSurfaceKind {
    EXTENSION_POINT,
    HOST_CAPABILITY,
    PLUGIN_CAPABILITY_BUS,
    PLUGIN_SERVICE_BUS,
    PLUGIN_PROVIDER_BUS,
    HOST_PROVIDER
}

data class HostSurfaceDefinition(
    val id: String,
    val title: String,
    val detail: String,
    val kind: HostSurfaceKind,
    val requiredScope: String? = null,
    val publicContracts: List<String> = emptyList()
)

data class HostSurfaceSnapshot(
    val definition: HostSurfaceDefinition,
    val allowed: Boolean
)

object PluginSurfaceIds {
    const val PUBLISH_CAPABILITY = "bus:plugin.capability"
    const val PUBLISH_SERVICE = "bus:plugin.service"
    const val PUBLISH_PROVIDER = "bus:plugin.provider"
    const val HOST_NOTIFICATION = "host:notification.surface"
    fun extension(point: String) = "extension:${point.trim().lowercase()}"
    fun hostCapability(id: String) = "host:${id.trim().lowercase()}"
    fun hostPrimitive(id: String) = "primitive:${id.trim().lowercase()}"
}

class HostSurfacePolicy(context: Context) {
    private val prefs = pluginCenterPreferences(context, PREFS, LEGACY_PREFS)
    private val definitions = ConcurrentHashMap<String, HostSurfaceDefinition>()

    val developerMode: Boolean
        get() = prefs.getBoolean(KEY_DEVELOPER_MODE, false)

    fun setDeveloperMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEVELOPER_MODE, enabled).apply()
    }

    fun register(definition: HostSurfaceDefinition) {
        val normalized = definition.copy(id = definition.id.trim().lowercase())
        definitions[normalized.id] = normalized
    }

    fun snapshots(): List<HostSurfaceSnapshot> = definitions.values
        .sortedWith(compareBy(HostSurfaceDefinition::kind, HostSurfaceDefinition::id))
        .map { HostSurfaceSnapshot(it, isAllowed(it.id)) }

    fun isAllowed(surfaceId: String): Boolean {
        val id = surfaceId.trim().lowercase()
        if (!definitions.containsKey(id)) return false
        val key = allowedKey(id)
        return if (prefs.contains(key)) prefs.getBoolean(key, true) else true
    }

    fun setAllowed(surfaceId: String, allowed: Boolean) {
        check(developerMode) { "请先开启开发模式再修改宿主接口" }
        val id = surfaceId.trim().lowercase()
        require(definitions.containsKey(id)) { "未知宿主接口：$surfaceId" }
        prefs.edit().putBoolean(allowedKey(id), allowed).apply()
    }

    fun setAllowed(surfaceIds: Collection<String>, allowed: Boolean) {
        check(developerMode) { "请先开启开发模式再修改宿主接口" }
        val ids = surfaceIds.map { it.trim().lowercase() }.distinct()
        ids.forEach { id -> require(definitions.containsKey(id)) { "未知宿主接口：$id" } }
        prefs.edit().apply {
            ids.forEach { id -> putBoolean(allowedKey(id), allowed) }
        }.apply()
    }

    fun requireAllowed(surfaceId: String) {
        val id = surfaceId.trim().lowercase()
        if (!isAllowed(id)) {
            val name = definitions[id]?.title ?: id
            throw PluginInstallException("HOST_SURFACE_BLOCKED", "宿主接口未开放：$name")
        }
    }

    fun blockingSurfaces(manifest: PluginManifest): List<HostSurfaceDefinition> =
        requiredSurfaceIds(manifest)
            .filterNot(::isAllowed)
            .mapNotNull(definitions::get)
            .sortedBy { it.id }

    fun blockingReason(manifest: PluginManifest): String? {
        val blocked = blockingSurfaces(manifest)
        if (blocked.isEmpty()) return null
        return "宿主接口未开放：" + blocked.joinToString { it.title }
    }

    fun requireManifestAllowed(manifest: PluginManifest) {
        blockingReason(manifest)?.let { reason ->
            throw PluginInstallException("HOST_SURFACE_BLOCKED", reason)
        }
    }

    private fun requiredSurfaceIds(manifest: PluginManifest): Set<String> = buildSet {
        if (manifest.provides.capabilities.isNotEmpty()) add(PluginSurfaceIds.PUBLISH_CAPABILITY)
        if (manifest.provides.services.isNotEmpty()) add(PluginSurfaceIds.PUBLISH_SERVICE)
        if (manifest.provides.providers.isNotEmpty()) add(PluginSurfaceIds.PUBLISH_PROVIDER)
        manifest.provides.extensions.forEach { add(PluginSurfaceIds.extension(it.point)) }
        definitions.values.forEach { surface ->
            if (surface.requiredScope != null &&
                surface.requiredScope in manifest.permissions.requestedScopes
            ) add(surface.id)
        }
    }

    private fun allowedKey(id: String) = "allowed:$id"

    companion object {
        private const val PREFS = "plugin_center_host_surface_policy"
        private const val LEGACY_PREFS = "plugin_lab_host_surface_policy"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
    }
}
