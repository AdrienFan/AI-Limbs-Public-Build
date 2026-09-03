package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsCapabilityRegistry
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsDispatcher
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionPolicyEngine
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionSession
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionTransport
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsPluginCapabilityExecutor
import com.ai.assistance.operit.plugins.system.SystemHostPrimitiveAvailability
import com.ai.assistance.operit.util.AppLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Kernel-owned capability bridge.
 * Plugin-owned capabilities live in plugin.*; Host calls use the versioned AI Limbs Host Primitive IDs.
 */
internal class PluginHostCapabilityRegistry(
    context: Context?,
    private val surfacePolicy: HostSurfacePolicy?,
    private val usageStore: PluginUsageStore? = null
) : PluginCapabilityBinder, PluginCapabilityInvokerFactory {
    internal constructor() : this(null, null, null)
    private val appContext = context?.applicationContext
    private val systemExecutor = context?.let(::SystemHostPrimitiveExecutor)

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

    // Ordinary plugin scope adapters remain intentionally narrow here.
    // Plugin Center system-role access uses SystemHostPrimitiveExecutor and the full 39-item Gateway catalog.
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
            throw PluginInstallException("CAPABILITY_NAMESPACE_FORBIDDEN", "Plugin capabilities must use the plugin.* namespace: $capabilityId")
        }
        val aliases = capability.invokeAliases.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        if (aliases.any { !it.startsWith("plugin.") || !PLUGIN_CAPABILITY_ID.matches(it) }) {
            throw PluginInstallException("CAPABILITY_ALIAS_NAMESPACE_FORBIDDEN", "Plugin capability aliases must use plugin.* namespace")
        }
        val token = UUID.randomUUID().toString()
        val candidate = OwnedCapability(token, ownerPluginId, capability)
        val existing = capabilities.putIfAbsent(normalized, candidate)
        if (existing != null) throw PluginInstallException("CAPABILITY_CONFLICT", "$normalized is already owned by ${existing.ownerPluginId}")
        val catalogEntry = pluginCatalogEntry(ownerPluginId, normalized, capability)
        val dynamicHandle = try {
            AiLimbsCapabilityRegistry.registerPluginCapability(
                ownerPluginId, normalized, aliases, catalogEntry,
                AiLimbsPluginCapabilityExecutor { args -> executePluginDirect(normalized, args) }
            )
        } catch (error: Throwable) {
            capabilities.remove(normalized, candidate)
            throw PluginInstallException("CAPABILITY_REGISTRY_CONFLICT", error.message ?: "Could not register plugin capability", error)
        }
        return AutoCloseable {
            dynamicHandle.close()
            capabilities.computeIfPresent(normalized) { _, current -> if (current.token == token) null else current }
        }
    }

    /**
     * Ensures an opaque UI document can invoke only a capability owned by its Host-attested plugin.
     *
     * Plugin Center controls component semantics, but it must not gain cross-plugin execution merely
     * because a JSON document names another plugin's capability id or alias.
     */
    internal fun requireOwnedCapability(ownerPluginId: String, capabilityId: String) {
        val normalized = capabilityId.trim().lowercase()
        val owned = capabilities[normalized] ?: capabilities.values.firstOrNull { candidate ->
            candidate.spec.invokeAliases.any { it.trim().lowercase() == normalized }
        }
        if (owned == null || owned.ownerPluginId != ownerPluginId) {
            throw PluginInstallException(
                "UI_CAPABILITY_OWNER_MISMATCH",
                "UI capability is not owned by $ownerPluginId: $capabilityId"
            )
        }
    }

    suspend fun invokePlugin(capabilityId: String, parameters: JSONObject = JSONObject()): JSONObject {
        val normalized = capabilityId.trim().lowercase()
        val context = appContext ?: return executePluginDirect(normalized, parameters)
        val session = AiLimbsExecutionSession(AiLimbsExecutionTransport.PLUGIN_RUNTIME, "plugin-ui:$normalized")
        return AiLimbsDispatcher(context, AiLimbsExecutionPolicyEngine(context, session))
            .execute(normalized, JSONObject(parameters.toString()))
    }

    internal suspend fun invokeDelegated(
        ownerPluginId: String,
        grantedScopes: Set<String>,
        capabilityId: String,
        parameters: JSONObject = JSONObject()
    ): JSONObject {
        val normalized = capabilityId.trim().lowercase()
        if (normalized.isBlank()) {
            throw PluginInstallException("CAPABILITY_ID_REQUIRED", "Delegated capability ID is required")
        }
        val primitive = AiLimbsHostPrimitiveCatalog.find(normalized)
        if (primitive != null) {
            return create(ownerPluginId, grantedScopes)
                .invoke(primitive.id, JSONObject(parameters.toString()))
        }
        if (normalized.startsWith("host.") || normalized.startsWith("kernel.")) {
            throw PluginInstallException(
                "HOST_PRIMITIVE_UNKNOWN",
                "Unknown or unavailable AI Limbs Host Primitive: $normalized"
            )
        }
        if (normalized.startsWith("plugin.")) {
            surfacePolicy?.requireAllowed(PluginSurfaceIds.PUBLISH_CAPABILITY)
        }
        val context = appContext
            ?: throw PluginInstallException(
                "HOST_RUNTIME_UNAVAILABLE",
                "Delegated capability dispatch requires the Android Host runtime"
            )
        val session = AiLimbsExecutionSession(
            AiLimbsExecutionTransport.PLUGIN_RUNTIME,
            "plugin:$ownerPluginId"
        )
        return AiLimbsDispatcher(context, AiLimbsExecutionPolicyEngine(context, session))
            .execute(normalized, JSONObject(parameters.toString()))
    }

    private suspend fun executePluginDirect(capabilityId: String, parameters: JSONObject): JSONObject {
        val capability = capabilities[capabilityId]
            ?: throw PluginInstallException("CAPABILITY_NOT_ACTIVE", "Capability is not active: $capabilityId")
        val result = capability.spec.executor.execute(JSONObject(parameters.toString()))
        usageStore?.recordUse(capability.ownerPluginId)
        return result
    }

    private fun pluginCatalogEntry(ownerPluginId: String, capabilityId: String, spec: PluginCapabilitySpec): ToolCatalogEntry {
        val parameters = spec.parameters.map { ToolParameterSchema(it.name, it.type, it.description, it.required, it.default) }
        return ToolCatalogEntry(
            targetToolName = capabilityId,
            displayName = spec.displayName,
            description = spec.description,
            parameterHints = parameters.map { "${it.name} [${it.type}, ${if (it.required) "required" else "optional"}]: ${it.description}" },
            sourceKind = ToolCatalogSourceKind.PACKAGE,
            keywords = spec.keywords,
            suggestedParamsJson = spec.suggestedParamsJson,
            parameters = parameters,
            sourceName = "plugin:$ownerPluginId",
            sourceLocator = "ai-limbs://plugin/$ownerPluginId/$capabilityId",
            sourceEnabled = true,
            inputSchema = spec.inputSchema,
            searchMetadata = (spec.invokeAliases + capabilityId + ownerPluginId).distinct()
        )
    }

    fun activeIds(): Set<String> = capabilities.keys.toSortedSet()

    internal fun isHostCallable(capabilityId: String): Boolean =
        systemExecutor?.isCallable(capabilityId) ?: (capabilityId.trim().lowercase() in hostCapabilities)

    internal fun systemHostOperations(capabilityId: String): List<String> =
        systemExecutor?.operationNames(capabilityId).orEmpty()

    internal fun systemHostAvailability(capabilityId: String, operation: String? = null): SystemHostPrimitiveAvailability {
        val normalized = capabilityId.trim().lowercase()
        val primitive = AiLimbsHostPrimitiveCatalog.find(normalized)
            ?: return SystemHostPrimitiveAvailability(normalized, operation, false, false, false, "HOST_PRIMITIVE_UNKNOWN", "Unknown AI Limbs Host Primitive")
        val executor = systemExecutor
            ?: return SystemHostPrimitiveAvailability(primitive.id, operation, true, false, false, "HOST_GATEWAY_NOT_READY", "System Host Gateway executor is not initialized")
        val requested = operation?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val callable = executor.isCallable(primitive.id)
        if (requested == null) {
            return SystemHostPrimitiveAvailability(primitive.id, null, true, callable, callable, if (callable) null else "HOST_PRIMITIVE_NOT_BOUND", if (callable) null else "No runtime-bound operation is available")
        }
        val knownOperation = requested in executor.operationNames(primitive.id)
        if (!knownOperation) return SystemHostPrimitiveAvailability(primitive.id, requested, true, callable, false, "HOST_OPERATION_UNKNOWN", "Unknown Host Primitive operation")
        val available = executor.isOperationAvailable(primitive.id, requested)
        return SystemHostPrimitiveAvailability(primitive.id, requested, true, callable, available, if (available) null else "HOST_PRIMITIVE_OPERATION_NOT_BOUND", if (available) null else "Operation is declared but has no stable runtime adapter in this kernel build")
    }

    internal suspend fun invokeSystemHost(
        ownerPluginId: String,
        capabilityId: String,
        operation: String,
        parameters: JSONObject = JSONObject()
    ): JSONObject {
        val normalized = capabilityId.trim().lowercase()
        val primitive = AiLimbsHostPrimitiveCatalog.find(normalized)
            ?: throw PluginInstallException("HOST_PRIMITIVE_UNKNOWN", "Unknown AI Limbs Host Primitive: $normalized")
        val executor = systemExecutor
            ?: throw PluginInstallException("HOST_GATEWAY_NOT_READY", "System Host Gateway executor is not initialized")
        return executor.invoke(ownerPluginId, primitive.id, operation, JSONObject(parameters.toString()))
    }

    internal suspend fun invokeSystemHost(ownerPluginId: String, capabilityId: String, parameters: JSONObject = JSONObject()): JSONObject {
        val copy = JSONObject(parameters.toString())
        val operation = copy.optString("operation").trim().ifBlank {
            throw PluginInstallException("HOST_OPERATION_REQUIRED", "Host Gateway invoke requires an operation")
        }
        copy.remove("operation")
        return invokeSystemHost(ownerPluginId, capabilityId, operation, copy)
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
