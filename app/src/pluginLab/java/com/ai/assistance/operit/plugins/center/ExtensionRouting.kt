package com.ai.assistance.operit.plugins.center

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PluginExtensionPoints {
    const val UI_HOME_TILE = "ai_limbs.ui.home_tile"
    const val UI_SCREEN = "ai_limbs.ui.screen"
    const val UI_THEME = "ai_limbs.ui.theme"
}

data class ExtensionPointDefinition(
    val point: String,
    val apiVersion: Int,
    val binder: (PluginContributionRecord) -> AutoCloseable
)

data class ExtensionBindingSnapshot(
    val ownerPluginId: String,
    val point: String,
    val extensionId: String,
    val apiVersion: Int
)

class ExtensionBindingHandle internal constructor(
    val ownerPluginId: String,
    val point: String,
    val extensionId: String,
    private val onClose: () -> Unit
) : AutoCloseable {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
            onClose()
        }
    }
}

class ExtensionPointRegistry {
    private val points = ConcurrentHashMap<String, ExtensionPointDefinition>()

    fun register(definition: ExtensionPointDefinition) {
        val point = normalizePoint(definition.point)
        if (definition.apiVersion <= 0) {
            throw PluginInstallException(
                "EXTENSION_POINT_API_INVALID",
                "Extension point API version must be positive: $point"
            )
        }
        val normalized = definition.copy(point = point)
        val previous = points.putIfAbsent(point, normalized)
        if (previous != null && previous != normalized) {
            throw PluginInstallException(
                "EXTENSION_POINT_CONFLICT",
                "Extension point is already registered: $point"
            )
        }
    }

    fun unregister(point: String) {
        points.remove(normalizePoint(point))
    }

    fun resolve(point: String): ExtensionPointDefinition? =
        points[normalizePoint(point)]

    fun list(): List<ExtensionPointDefinition> =
        points.values.sortedBy { it.point }

    private fun normalizePoint(point: String): String {
        val normalized = point.trim().lowercase()
        if (!POINT_PATTERN.matches(normalized)) {
            throw PluginInstallException(
                "EXTENSION_POINT_INVALID",
                "Invalid extension point: $point"
            )
        }
        return normalized
    }

    companion object {
        private val POINT_PATTERN = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
    }
}

class ExtensionRouter(
    private val points: ExtensionPointRegistry,
    private val surfacePolicy: HostSurfacePolicy
) {
    private data class ActiveBinding(
        val token: String,
        val record: PluginContributionRecord,
        val downstream: AutoCloseable
    )

    private val bindings = ConcurrentHashMap<String, ActiveBinding>()

    fun bind(record: PluginContributionRecord): ExtensionBindingHandle {
        if (record.kind != PluginContributionKind.EXTENSION) {
            throw PluginInstallException(
                "EXTENSION_RECORD_INVALID",
                "Only EXTENSION contributions can be routed"
            )
        }
        val point = record.extensionPoint
            ?: throw PluginInstallException("EXTENSION_POINT_MISSING", "Extension point is missing")
        val definition = points.resolve(point)
            ?: throw PluginInstallException("EXTENSION_POINT_UNSUPPORTED", "Unsupported extension point: $point")
        surfacePolicy.requireAllowed(PluginSurfaceIds.extension(point))
        val apiVersion = record.apiVersion
            ?: throw PluginInstallException("EXTENSION_API_MISSING", "Extension API version is missing")
        if (apiVersion != definition.apiVersion) {
            throw PluginInstallException(
                "EXTENSION_API_INCOMPATIBLE",
                "$point requires API ${definition.apiVersion}, plugin registered API $apiVersion"
            )
        }

        val key = bindingKey(record.ownerPluginId, point, record.id)
        val token = UUID.randomUUID().toString()
        val downstream = definition.binder(record)
        val installed = synchronized(this) {
            if (bindings.containsKey(key)) {
                false
            } else {
                bindings[key] = ActiveBinding(token, record, downstream)
                true
            }
        }
        if (!installed) {
            runCatching { downstream.close() }
            throw PluginInstallException(
                "EXTENSION_BINDING_CONFLICT",
                "Extension is already bound: $key"
            )
        }

        return ExtensionBindingHandle(record.ownerPluginId, point, record.id) {
            val removed = synchronized(this) {
                val current = bindings[key]
                if (current?.token == token) bindings.remove(key) else null
            }
            removed?.let { runCatching { it.downstream.close() } }
        }
    }

    fun listBindings(): List<ExtensionBindingSnapshot> =
        bindings.values.map(::snapshot).sortedWith(
            compareBy(ExtensionBindingSnapshot::ownerPluginId, ExtensionBindingSnapshot::point, ExtensionBindingSnapshot::extensionId)
        )

    fun listBindingsByOwner(pluginId: String): List<ExtensionBindingSnapshot> =
        bindings.values
            .filter { it.record.ownerPluginId == pluginId }
            .map(::snapshot)
            .sortedWith(compareBy(ExtensionBindingSnapshot::point, ExtensionBindingSnapshot::extensionId))

    private fun snapshot(binding: ActiveBinding): ExtensionBindingSnapshot =
        ExtensionBindingSnapshot(
            ownerPluginId = binding.record.ownerPluginId,
            point = binding.record.extensionPoint ?: "",
            extensionId = binding.record.id,
            apiVersion = binding.record.apiVersion ?: 0
        )

    private fun bindingKey(ownerPluginId: String, point: String, extensionId: String): String =
        "$ownerPluginId|${point.trim().lowercase()}|${extensionId.trim()}"
}
