package com.ai.assistance.operit.plugins.center

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PluginContributionKind {
    CAPABILITY,
    SERVICE,
    PROVIDER,
    EXTENSION
}

data class PluginContributionRecord(
    val ownerPluginId: String,
    val kind: PluginContributionKind,
    val id: String,
    val apiVersion: Int?,
    val extensionPoint: String? = null,
    val metadata: Map<String, String>,
    val payload: Any?
)

class PluginRegistrationHandle internal constructor(
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

class PluginContributionRegistry {
    private data class OwnedRecord(
        val token: String,
        val record: PluginContributionRecord
    )

    private val records = ConcurrentHashMap<String, OwnedRecord>()
    private val revisionFlow = MutableStateFlow(0L)

    val revision: StateFlow<Long> = revisionFlow.asStateFlow()

    fun register(record: PluginContributionRecord): PluginRegistrationHandle {
        val key = key(record.kind, record.id, record.extensionPoint)
        val token = UUID.randomUUID().toString()
        val candidate = OwnedRecord(token, record)
        val existing = records.putIfAbsent(key, candidate)
        if (existing != null) {
            throw PluginInstallException(
                "CONTRIBUTION_CONFLICT",
                "${record.kind}:${record.id} is already owned by ${existing.record.ownerPluginId}"
            )
        }
        revisionFlow.update { it + 1L }
        return PluginRegistrationHandle {
            var removed = false
            records.computeIfPresent(key) { _, current ->
                if (current.token == token) {
                    removed = true
                    null
                } else {
                    current
                }
            }
            if (removed) revisionFlow.update { it + 1L }
        }
    }

    fun find(kind: PluginContributionKind, id: String): PluginContributionRecord? =
        records[key(kind, id)]?.record

    fun findExtension(point: String, id: String): PluginContributionRecord? =
        records[key(PluginContributionKind.EXTENSION, id, point)]?.record

    fun listByOwner(pluginId: String): List<PluginContributionRecord> =
        records.values.map { it.record }.filter { it.ownerPluginId == pluginId }

    fun listAll(): List<PluginContributionRecord> =
        records.values.map { it.record }.sortedWith(
            compareBy(PluginContributionRecord::kind, PluginContributionRecord::id)
        )

    private fun key(kind: PluginContributionKind, id: String, extensionPoint: String? = null): String =
        if (kind == PluginContributionKind.EXTENSION) {
            "${kind.name}:${extensionPoint?.trim()?.lowercase()}:${id.trim()}"
        } else {
            "${kind.name}:${id.trim()}"
        }
}

class PluginRegistrar internal constructor(
    private val manifest: PluginManifest,
    private val registry: PluginContributionRegistry,
    private val extensionRouter: ExtensionRouter,
    private val capabilityBinder: PluginCapabilityBinder,
    private val surfacePolicy: HostSurfacePolicy,
    private val track: (AutoCloseable) -> Unit
) {
    fun registerCapability(
        id: String,
        capability: PluginCapabilitySpec,
        metadata: Map<String, String> = emptyMap()
    ) {
        surfacePolicy.requireAllowed(PluginSurfaceIds.PUBLISH_CAPABILITY)
        requireDeclared(PluginContributionKind.CAPABILITY, id)
        val registration =
            registry.register(
                PluginContributionRecord(
                    ownerPluginId = manifest.pluginId,
                    kind = PluginContributionKind.CAPABILITY,
                    id = id,
                    apiVersion = null,
                    metadata = metadata,
                    payload = capability
                )
            )
        track(registration)
        try {
            track(capabilityBinder.register(manifest.pluginId, id, capability))
        } catch (error: Throwable) {
            registration.close()
            throw error
        }
    }

    fun registerService(
        id: String,
        apiVersion: Int,
        payload: Any,
        metadata: Map<String, String> = emptyMap()
    ) {
        surfacePolicy.requireAllowed(PluginSurfaceIds.PUBLISH_SERVICE)
        if (apiVersion <= 0) {
            throw PluginInstallException("SERVICE_API_INVALID", "Service API version must be positive")
        }
        requireDeclared(PluginContributionKind.SERVICE, id)
        track(
            registry.register(
                PluginContributionRecord(
                    ownerPluginId = manifest.pluginId,
                    kind = PluginContributionKind.SERVICE,
                    id = id,
                    apiVersion = apiVersion,
                    metadata = metadata,
                    payload = payload
                )
            )
        )
    }

    fun registerProvider(
        id: String,
        payload: Any,
        metadata: Map<String, String> = emptyMap()
    ) {
        surfacePolicy.requireAllowed(PluginSurfaceIds.PUBLISH_PROVIDER)
        requireDeclared(PluginContributionKind.PROVIDER, id)
        track(
            registry.register(
                PluginContributionRecord(
                    ownerPluginId = manifest.pluginId,
                    kind = PluginContributionKind.PROVIDER,
                    id = id,
                    apiVersion = null,
                    metadata = metadata,
                    payload = payload
                )
            )
        )
    }

    fun registerExtension(
        point: String,
        id: String,
        payload: Any,
        metadata: Map<String, String> = emptyMap()
    ) {
        val normalizedPoint = point.trim().lowercase()
        surfacePolicy.requireAllowed(PluginSurfaceIds.extension(normalizedPoint))
        val declaration = manifest.provides.extensions.firstOrNull {
            it.point == normalizedPoint && it.id == id
        } ?: throw PluginInstallException(
            "REGISTRATION_NOT_DECLARED",
            "extension:$normalizedPoint:$id was not declared by ${manifest.pluginId}"
        )
        val record = PluginContributionRecord(
            ownerPluginId = manifest.pluginId,
            kind = PluginContributionKind.EXTENSION,
            id = id,
            apiVersion = declaration.apiVersion,
            extensionPoint = normalizedPoint,
            metadata = metadata,
            payload = payload
        )
        val registration = registry.register(record)
        track(registration)
        try {
            track(extensionRouter.bind(record))
        } catch (error: Throwable) {
            registration.close()
            throw error
        }
    }

    private fun requireDeclared(kind: PluginContributionKind, id: String) {
        val declared = when (kind) {
            PluginContributionKind.CAPABILITY -> manifest.provides.capabilities
            PluginContributionKind.SERVICE -> manifest.provides.services
            PluginContributionKind.PROVIDER -> manifest.provides.providers
            PluginContributionKind.EXTENSION -> throw IllegalStateException("Extensions use typed declaration validation")
        }
        if (id !in declared) {
            throw PluginInstallException(
                "REGISTRATION_NOT_DECLARED",
                "${kind.name.lowercase()}:$id was not declared by ${manifest.pluginId}"
            )
        }
    }
}

internal class PluginCallerLease {
    @Volatile
    private var active = true

    fun requireActive(pluginId: String) {
        if (!active) {
            throw PluginInstallException(
                "PLUGIN_CALLER_REVOKED",
                "Plugin runtime is no longer active: $pluginId"
            )
        }
    }

    fun revoke() {
        active = false
    }
}

class PluginMountScope internal constructor(
    manifest: PluginManifest,
    registry: PluginContributionRegistry,
    extensionRouter: ExtensionRouter,
    capabilityBinder: PluginCapabilityBinder,
    surfacePolicy: HostSurfacePolicy
) {
    private val handles = ArrayDeque<AutoCloseable>()
    private var acceptingRegistrations = true
    internal val callerLease = PluginCallerLease()

    val registrar = PluginRegistrar(manifest, registry, extensionRouter, capabilityBinder, surfacePolicy) { handle ->
        synchronized(this) {
            if (!acceptingRegistrations) {
                handle.close()
                throw PluginInstallException(
                    "MOUNT_SCOPE_CLOSED",
                    "Plugin attempted to register after its mount scope was closed"
                )
            }
            handles.addLast(handle)
        }
    }

    internal fun trackOwned(handle: AutoCloseable) {
        synchronized(this) {
            if (!acceptingRegistrations) {
                handle.close()
                throw PluginInstallException(
                    "MOUNT_SCOPE_CLOSED",
                    "Plugin attempted to retain a runtime resource after its mount scope was closed"
                )
            }
            handles.addLast(handle)
        }
    }

    fun seal() {
        synchronized(this) {
            acceptingRegistrations = false
        }
    }

    fun revokeAll() {
        callerLease.revoke()
        val snapshot = synchronized(this) {
            acceptingRegistrations = false
            buildList {
                while (handles.isNotEmpty()) add(handles.removeLast())
            }
        }
        snapshot.forEach { runCatching { it.close() } }
    }
}

internal class PluginRuntimeAdapterRegistry {
    private val adapters = ConcurrentHashMap<String, PluginRuntimeAdapter>()

    fun register(adapter: PluginRuntimeAdapter) {
        val kind = adapter.kind.trim().lowercase()
        if (kind.isBlank()) {
            throw PluginInstallException("RUNTIME_KIND_INVALID", "Runtime adapter kind is blank")
        }
        val previous = adapters.putIfAbsent(kind, adapter)
        if (previous != null && previous !== adapter) {
            throw PluginInstallException("RUNTIME_ADAPTER_CONFLICT", "Runtime adapter already registered: $kind")
        }
    }

    fun unregister(kind: String, adapter: PluginRuntimeAdapter? = null) {
        val normalized = kind.trim().lowercase()
        if (adapter == null) adapters.remove(normalized) else adapters.remove(normalized, adapter)
    }

    fun resolve(kind: String): PluginRuntimeAdapter? = adapters[kind.trim().lowercase()]
    fun kinds(): Set<String> = adapters.keys.toSortedSet()
}

internal object NoopPluginRuntimeAdapter : PluginRuntimeAdapter {
    override val kind: String = "none"

    override suspend fun mount(context: PluginRuntimeAdapterContext): PluginRuntimeHandle =
        object : PluginRuntimeHandle {
            override suspend fun stop() = Unit
        }
}
