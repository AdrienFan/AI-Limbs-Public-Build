package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.util.AppLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Kernel-owned capability bridge.
 * Plugin-owned capabilities live in plugin.*; Host calls use the versioned AI Limbs Host Primitive IDs.
 */
internal class PluginHostCapabilityRegistry(
    private val surfacePolicy: HostSurfacePolicy?,
    private val usageStore: PluginUsageStore? = null
) : PluginCapabilityBinder, PluginCapabilityInvokerFactory {
    internal constructor() : this(null, null)

    private data class OwnedCapability(
        val token: String,
        val ownerPluginId: String,
        val spec: PluginCapabilitySpec
    )

    private data class HostCapability(
        val requiredScope: String,
        val execute: suspend (JSONObject) -> JSONObject
    )

    private val capabilities = ConcurrentHashMap<String, OwnedCapability>()

    // Phase 1: only Structured Logging has a real v2 Host Primitive adapter.
    // The complete 39-item catalog is exposed separately by AiLimbsHostPrimitiveCatalog.
    private val hostCapabilities = mapOf(
        "host.logging@1" to HostCapability("host.logging@1", ::invokeLogging)
    )

    init {
        surfacePolicy?.register(
            HostSurfaceDefinition(
                id = PluginSurfaceIds.PUBLISH_CAPABILITY,
                title = "Plugin Capability Bus",
                detail = "允许插件注册 plugin.* 能力",
                kind = HostSurfaceKind.PLUGIN_CAPABILITY_BUS,
                publicContracts = listOf(
                    "PluginRegistrar.registerCapability",
                    "PluginCapabilitySpec",
                    "PluginCapabilityExecutor"
                )
            )
        )
        surfacePolicy?.register(
            HostSurfaceDefinition(
                id = PluginSurfaceIds.PUBLISH_SERVICE,
                title = "Plugin Service Bus",
                detail = "允许插件发布声明过 API 版本的 service",
                kind = HostSurfaceKind.PLUGIN_SERVICE_BUS,
                publicContracts = listOf(
                    "PluginRegistrar.registerService",
                    "PluginServiceEndpoint",
                    "PluginServiceResolver"
                )
            )
        )
        surfacePolicy?.register(
            HostSurfaceDefinition(
                id = PluginSurfaceIds.PUBLISH_PROVIDER,
                title = "Plugin Provider Bus",
                detail = "允许插件向受控 Provider Directory 发布 provider",
                kind = HostSurfaceKind.PLUGIN_PROVIDER_BUS,
                publicContracts = listOf(
                    "PluginRegistrar.registerProvider",
                    "PluginContributionRecord"
                )
            )
        )
        hostCapabilities.forEach { (id, capability) ->
            val primitive = requireNotNull(AiLimbsHostPrimitiveCatalog.find(id)) {
                "Bound Host Primitive is missing from catalog: $id"
            }
            surfacePolicy?.register(
                HostSurfaceDefinition(
                    id = PluginSurfaceIds.hostPrimitive(id),
                    title = "${primitive.title} · ${primitive.id}",
                    detail = "BOUND · scope: ${capability.requiredScope}",
                    kind = HostSurfaceKind.HOST_CAPABILITY,
                    requiredScope = capability.requiredScope,
                    publicContracts = HOST_PRIMITIVE_INVOKE_CONTRACTS
                )
            )
        }
    }

    override fun register(
        ownerPluginId: String,
        capabilityId: String,
        capability: PluginCapabilitySpec
    ): AutoCloseable {
        surfacePolicy?.requireAllowed(PluginSurfaceIds.PUBLISH_CAPABILITY)
        val normalized = capabilityId.trim().lowercase()
        if (!normalized.startsWith("plugin.") || !PLUGIN_CAPABILITY_ID.matches(normalized)) {
            throw PluginInstallException(
                "CAPABILITY_NAMESPACE_FORBIDDEN",
                "Plugin capabilities must use the plugin.* namespace: $capabilityId"
            )
        }
        val token = UUID.randomUUID().toString()
        val candidate = OwnedCapability(token, ownerPluginId, capability)
        val existing = capabilities.putIfAbsent(normalized, candidate)
        if (existing != null) {
            throw PluginInstallException(
                "CAPABILITY_CONFLICT",
                "$normalized is already owned by ${existing.ownerPluginId}"
            )
        }
        return AutoCloseable {
            capabilities.computeIfPresent(normalized) { _, current ->
                if (current.token == token) null else current
            }
        }
    }

    suspend fun invokePlugin(capabilityId: String, parameters: JSONObject = JSONObject()): JSONObject {
        val normalized = capabilityId.trim().lowercase()
        val capability = capabilities[normalized]
            ?: throw PluginInstallException("CAPABILITY_NOT_ACTIVE", "Capability is not active: $normalized")
        val result = capability.spec.executor.execute(JSONObject(parameters.toString()))
        usageStore?.recordUse(capability.ownerPluginId)
        return result
    }

    fun activeIds(): Set<String> = capabilities.keys.toSortedSet()

    internal fun isHostCallable(capabilityId: String): Boolean =
        capabilityId.trim().lowercase() in hostCapabilities

    internal suspend fun invokeSystemHost(
        ownerPluginId: String,
        capabilityId: String,
        parameters: JSONObject = JSONObject()
    ): JSONObject {
        val normalized = capabilityId.trim().lowercase()
        val primitive = AiLimbsHostPrimitiveCatalog.find(normalized)
            ?: throw PluginInstallException(
                "HOST_PRIMITIVE_UNKNOWN",
                "Unknown AI Limbs Host Primitive: $normalized"
            )
        val capability = hostCapabilities[primitive.id]
            ?: throw PluginInstallException(
                "HOST_PRIMITIVE_NOT_BOUND",
                "Host Primitive has no runtime adapter: ${primitive.id}"
            )
        surfacePolicy?.requireAllowed(PluginSurfaceIds.hostPrimitive(primitive.id))
        AppLogger.d("PluginHostCapability", "System host invoke: $ownerPluginId -> ${primitive.id}")
        return capability.execute(JSONObject(parameters.toString()))
    }

    override fun create(ownerPluginId: String, grantedScopes: Set<String>): PluginCapabilityInvoker =
        PluginCapabilityInvoker { capabilityId, parameters ->
            val normalized = capabilityId.trim().lowercase()
            val primitive = AiLimbsHostPrimitiveCatalog.find(normalized)
                ?: throw PluginInstallException(
                    "HOST_PRIMITIVE_UNKNOWN",
                    "Unknown AI Limbs Host Primitive: $normalized"
                )
            if (!primitive.requestableScope || primitive.exposure != HostPrimitiveExposure.BOUND) {
                throw PluginInstallException(
                    "HOST_PRIMITIVE_NOT_AVAILABLE",
                    "Host Primitive is not callable in this kernel build: ${primitive.id} (${primitive.exposure})"
                )
            }
            val capability = hostCapabilities[primitive.id]
                ?: throw PluginInstallException(
                    "HOST_PRIMITIVE_NOT_BOUND",
                    "Host Primitive has no runtime adapter: ${primitive.id}"
                )
            surfacePolicy?.requireAllowed(PluginSurfaceIds.hostPrimitive(primitive.id))
            if (capability.requiredScope !in grantedScopes) {
                throw PluginInstallException(
                    "PLUGIN_SCOPE_DENIED",
                    "$ownerPluginId was not granted required scope: ${capability.requiredScope}"
                )
            }
            capability.execute(JSONObject(parameters.toString()))
        }

    private suspend fun invokeLogging(parameters: JSONObject): JSONObject {
        return when (parameters.optString("operation", "read").trim().lowercase()) {
            "read" -> readLogs(parameters)
            else -> throw PluginInstallException(
                "HOST_OPERATION_UNSUPPORTED",
                "host.logging@1 supports operation=read in Phase 1"
            )
        }
    }

    private fun readLogs(parameters: JSONObject): JSONObject {
        val maximum = parameters.optInt("max_chars", 60_000).coerceIn(1_000, 120_000)
        val logFile = AppLogger.getLogFile()
        val full = if (logFile?.isFile == true) logFile.readText() else ""
        val content = if (full.length > maximum) full.takeLast(maximum) else full
        return JSONObject()
            .put("content", content)
            .put("truncated", full.length > content.length)
            .put("characters", content.length)
    }

    private companion object {
        val PLUGIN_CAPABILITY_ID = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
        val HOST_PRIMITIVE_INVOKE_CONTRACTS = listOf(
            "PluginContext.capabilityInvoker",
            "PluginCapabilityInvoker.invoke"
        )
    }
}
