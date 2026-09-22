package com.ai.assistance.operit.plugins.center

import com.ai.limbs.plugin.runtime.InProcessSystemIds
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
    val contract: CanonicalContributionContract,
    val payload: Any?
) {
    val ownerPluginId: String get() = contract.ownerPluginId
    val kind: PluginContributionKind get() = contract.kind
    val id: String get() = contract.id
    val apiVersion: Int? get() = contract.apiVersion
    val extensionPoint: String? get() = contract.extensionPoint
    val metadata: Map<String, String> get() = contract.metadata
}

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
        val contract = validatedContract(
            kind = PluginContributionKind.CAPABILITY,
            id = id,
            metadata = metadata
        )
        val record = PluginContributionRecord(contract, capability)
        val registration = registry.register(record)
        track(registration)
        try {
            track(capabilityBinder.register(manifest.pluginId, contract.id, capability))
        } catch (error: Throwable) {
            registration.close()
            throw error
        }
    }

    fun registerProvider(
        id: String,
        payload: Any,
        metadata: Map<String, String> = emptyMap()
    ) {
        val contract = validatedContract(
            kind = PluginContributionKind.PROVIDER,
            id = id,
            metadata = metadata
        )
        track(registry.register(PluginContributionRecord(contract, payload)))
    }

    fun registerService(
        id: String,
        apiVersion: Int,
        payload: Any,
        metadata: Map<String, String> = emptyMap()
    ) {
        val contract = validatedContract(
            kind = PluginContributionKind.SERVICE,
            id = id,
            apiVersion = apiVersion,
            metadata = metadata
        )
        track(registry.register(PluginContributionRecord(contract, payload)))
    }

    fun registerExtension(
        point: String,
        id: String,
        payload: Any,
        metadata: Map<String, String> = emptyMap()
    ) {
        val contract = validatedContract(
            kind = PluginContributionKind.EXTENSION,
            id = id,
            extensionPoint = point,
            metadata = metadata
        )
        val record = PluginContributionRecord(contract, payload)
        val registration = registry.register(record)
        track(registration)
        try {
            track(extensionRouter.bind(record))
        } catch (error: Throwable) {
            registration.close()
            throw error
        }
    }

    private fun validatedContract(
        kind: PluginContributionKind,
        id: String,
        apiVersion: Int? = null,
        extensionPoint: String? = null,
        metadata: Map<String, String>
    ): CanonicalContributionContract {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) {
            throw PluginInstallException(
                "CONTRIBUTION_ID_INVALID",
                "${kind.name.lowercase()} contribution id must not be blank"
            )
        }

        val contract = when (kind) {
            PluginContributionKind.CAPABILITY -> {
                surfacePolicy.requireAllowed(PluginSurfaceIds.PUBLISH_CAPABILITY)
                requireDeclared(kind, normalizedId)
                if (apiVersion != null || extensionPoint != null) {
                    throw PluginInstallException(
                        "CONTRIBUTION_CONTRACT_INVALID",
                        "capability:$normalizedId does not accept apiVersion or extensionPoint"
                    )
                }
                CanonicalContributionContracts.capability(
                    ownerPluginId = manifest.pluginId,
                    id = normalizedId,
                    metadata = metadata
                )
            }
            PluginContributionKind.SERVICE -> {
                surfacePolicy.requireAllowed(PluginSurfaceIds.PUBLISH_SERVICE)
                requireDeclared(kind, normalizedId)
                val version = apiVersion ?: throw PluginInstallException(
                    "SERVICE_API_INVALID",
                    "Service API version is required: $normalizedId"
                )
                if (version <= 0 || extensionPoint != null) {
                    throw PluginInstallException(
                        "SERVICE_API_INVALID",
                        "Service API version must be positive: $normalizedId"
                    )
                }
                CanonicalContributionContracts.service(
                    ownerPluginId = manifest.pluginId,
                    id = normalizedId,
                    apiVersion = version,
                    metadata = metadata
                )
            }
            PluginContributionKind.PROVIDER -> {
                surfacePolicy.requireAllowed(PluginSurfaceIds.PUBLISH_PROVIDER)
                requireDeclared(kind, normalizedId)
                if (apiVersion != null || extensionPoint != null) {
                    throw PluginInstallException(
                        "CONTRIBUTION_CONTRACT_INVALID",
                        "provider:$normalizedId does not accept apiVersion or extensionPoint"
                    )
                }
                CanonicalContributionContracts.provider(
                    ownerPluginId = manifest.pluginId,
                    id = normalizedId,
                    metadata = metadata
                )
            }
            PluginContributionKind.EXTENSION -> {
                val normalizedPoint = extensionPoint?.trim()?.lowercase().orEmpty()
                if (normalizedPoint.isBlank()) {
                    throw PluginInstallException(
                        "EXTENSION_POINT_INVALID",
                        "Extension point must not be blank: $normalizedId"
                    )
                }
                surfacePolicy.requireAllowed(PluginSurfaceIds.extension(normalizedPoint))
                val declaration = manifest.provides.extensions.firstOrNull {
                    it.point == normalizedPoint && it.id == normalizedId
                } ?: throw PluginInstallException(
                    "REGISTRATION_NOT_DECLARED",
                    "extension:$normalizedPoint:$normalizedId was not declared by ${manifest.pluginId}"
                )
                if (apiVersion != null && apiVersion != declaration.apiVersion) {
                    throw PluginInstallException(
                        "EXTENSION_API_MISMATCH",
                        "extension:$normalizedPoint:$normalizedId declared API ${declaration.apiVersion}, got $apiVersion"
                    )
                }
                CanonicalContributionContracts.extension(
                    ownerPluginId = manifest.pluginId,
                    point = normalizedPoint,
                    id = normalizedId,
                    apiVersion = declaration.apiVersion,
                    metadata = metadata
                )
            }
        }

        if (contract.ownerPluginId != manifest.pluginId) {
            throw PluginInstallException(
                "CONTRIBUTION_OWNER_MISMATCH",
                "Contribution owner ${contract.ownerPluginId} does not match ${manifest.pluginId}"
            )
        }
        return contract
    }

    private fun requireDeclared(kind: PluginContributionKind, id: String) {
        val declared = when (kind) {
            PluginContributionKind.CAPABILITY -> manifest.provides.capabilities
            PluginContributionKind.SERVICE -> manifest.provides.services
            PluginContributionKind.PROVIDER -> manifest.provides.providers
            PluginContributionKind.EXTENSION ->
                throw IllegalStateException("Extensions use typed declaration validation")
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
    private val revocationFailures = mutableListOf<Throwable>()
    private var pendingRevocations = 0
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
            }.also { pendingRevocations += it.size }
        }
        snapshot.forEach { handle ->
            try { handle.close() }
            catch (error: Exception) {
                synchronized(this) { revocationFailures += error }
                com.ai.assistance.operit.util.AppLogger.e(
                    "PluginMountScope", "Owned resource failed to close", error
                )
            } finally {
                synchronized(this) { pendingRevocations-- }
            }
        }
    }

    internal fun requireCleanRevocation() {
        val failures = synchronized(this) {
            check(pendingRevocations == 0) { "Plugin resource revocation is still in progress" }
            revocationFailures.toList()
        }
        if (failures.isEmpty()) return
        val failure = IllegalStateException("${failures.size} plugin resource(s) failed to close")
        failures.forEach(failure::addSuppressed)
        throw failure
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
