package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsCapabilityRegistry
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsDispatcher
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsDomain
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsEffect
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionPolicyEngine
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionSession
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionTransport
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsIngressGateway
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsIngressSession
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsPluginCapabilityExecutor
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsRequiredReceipt
import com.ai.assistance.operit.plugins.system.SystemHostPrimitiveAvailability
import com.ai.assistance.operit.util.AppLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * The plugin SDK and the policy engine are separate contracts. Translate each policy
 * dimension explicitly so a new SDK enum cannot fail at mount time through Enum.valueOf().
 */
internal fun PluginCapabilityDomain.toAiLimbsPolicyDomain(): AiLimbsDomain = when (this) {
    PluginCapabilityDomain.CORE_PROTOCOL -> AiLimbsDomain.CORE_PROTOCOL
    PluginCapabilityDomain.MANAGED_DOCUMENT -> AiLimbsDomain.MANAGED_DOCUMENT
    PluginCapabilityDomain.LANER_CHAT -> AiLimbsDomain.PLUGIN_CHAT_MODE
    PluginCapabilityDomain.SYSTEM_ENVIRONMENT -> AiLimbsDomain.SYSTEM_ENVIRONMENT
    PluginCapabilityDomain.ANDROID_UI -> AiLimbsDomain.ANDROID_UI
    PluginCapabilityDomain.STORAGE -> AiLimbsDomain.STORAGE
    PluginCapabilityDomain.HOST -> AiLimbsDomain.HOST
    PluginCapabilityDomain.PLUGIN -> AiLimbsDomain.PLUGIN
}

internal fun PluginCapabilityEffect.toAiLimbsPolicyEffect(): AiLimbsEffect = when (this) {
    PluginCapabilityEffect.READ_ONLY -> AiLimbsEffect.READ_ONLY
    PluginCapabilityEffect.STATE_CHANGE -> AiLimbsEffect.STATE_CHANGE
    PluginCapabilityEffect.PERSISTENT_WRITE -> AiLimbsEffect.PERSISTENT_WRITE
    PluginCapabilityEffect.EXTERNAL_COMMUNICATION -> AiLimbsEffect.EXTERNAL_COMMUNICATION
    PluginCapabilityEffect.PROCESS_EXECUTION -> AiLimbsEffect.PROCESS_EXECUTION
    PluginCapabilityEffect.UI_INTERACTION -> AiLimbsEffect.UI_INTERACTION
    PluginCapabilityEffect.EXTERNAL_CAPABILITY -> AiLimbsEffect.EXTERNAL_CAPABILITY
}

internal fun PluginCapabilityReceipt.toAiLimbsPolicyReceipt(): AiLimbsRequiredReceipt = when (this) {
    PluginCapabilityReceipt.WORK_MANUAL -> AiLimbsRequiredReceipt.WORK_MANUAL
}

/**
 * Kernel-owned capability bridge.
 * Plugin-owned capabilities live in plugin.*; Host calls use the versioned AI Limbs Host Primitive IDs.
 */
internal class PluginHostCapabilityRegistry(
    context: Context?,
    private val surfacePolicy: HostSurfacePolicy?,
    private val usageStore: PluginUsageStore? = null,
    private val loggingService: HostLoggingService? = context?.let { HostLoggingService(it, PluginStore.fromContext(it)) },
    private val runtimeRole: PluginRuntimeRole = PluginRuntimeRole.LEGACY_HOST
) : PluginCapabilityGateway, PluginCapabilityInvokerFactory {
    internal constructor() : this(null, null, null, null, PluginRuntimeRole.LEGACY_HOST)
    private val appContext = context?.applicationContext
    private val systemExecutor = context?.let { SystemHostPrimitiveExecutor(it, requireNotNull(loggingService), runtimeRole) }

    private data class OwnedCapability(
        val token: String,
        val ownerPluginId: String,
        val spec: PluginCapabilitySpec
    )

    private val capabilities = ConcurrentHashMap<String, OwnedCapability>()
    private val bridgeIngressGateways = ConcurrentHashMap<String, AiLimbsIngressGateway>()

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
        AiLimbsHostPrimitiveCatalog.all
            .asSequence()
            .filter { it.requestableScope && it.exposure == HostPrimitiveExposure.BOUND }
            .forEach { primitive ->
                surfacePolicy?.register(
                    HostSurfaceDefinition(
                        id = PluginSurfaceIds.hostPrimitive(primitive.id),
                        title = "${primitive.title} · ${primitive.id}",
                        detail = "BOUND · scope: ${primitive.id}",
                        kind = HostSurfaceKind.HOST_CAPABILITY,
                        requiredScope = primitive.id,
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
                ownerPluginId = ownerPluginId,
                capabilityId = normalized,
                invokeAliases = aliases,
                catalogEntry = catalogEntry,
                effect = capability.effect.toAiLimbsPolicyEffect(),
                domain = capability.domain.toAiLimbsPolicyDomain(),
                workContextRequiredReceipts = capability.workContextRequiredReceipts
                    .mapTo(linkedSetOf()) { it.toAiLimbsPolicyReceipt() },
                executor = AiLimbsPluginCapabilityExecutor { args -> executePluginDirect(normalized, args) }
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

    /**
     * Explicit Host UI path for an already-active owner. This intentionally does not enter AI/Bridge
     * policy receipts: the caller is a signed presentation entry and ownership is checked again here.
     */
    internal suspend fun invokeOwnedUiDirect(
        ownerPluginId: String,
        capabilityId: String,
        parameters: JSONObject = JSONObject()
    ): JSONObject {
        val normalized = capabilityId.trim().lowercase()
        val entry = capabilities.entries.firstOrNull { (id, candidate) ->
            id == normalized || candidate.spec.invokeAliases.any { it.trim().lowercase() == normalized }
        } ?: throw PluginInstallException(
            "CAPABILITY_NOT_ACTIVE",
            "Capability is not active: $capabilityId"
        )
        if (entry.value.ownerPluginId != ownerPluginId.trim()) {
            throw PluginInstallException(
                "UI_CAPABILITY_OWNER_MISMATCH",
                "UI capability is not owned by $ownerPluginId: $capabilityId"
            )
        }
        return executePluginDirect(entry.key, parameters)
    }

    suspend fun invokePlugin(capabilityId: String, parameters: JSONObject = JSONObject()): JSONObject {
        val normalized = capabilityId.trim().lowercase()
        val context = appContext ?: return executePluginDirect(normalized, parameters)
        val session = AiLimbsExecutionSession(AiLimbsExecutionTransport.PLUGIN_RUNTIME, "plugin-ui:$normalized")
        return AiLimbsDispatcher(context, AiLimbsExecutionPolicyEngine(context, session))
            .execute(normalized, JSONObject(parameters.toString()))
    }

    override suspend fun invokeDelegated(
        ownerPluginId: String,
        grantedScopes: Set<String>,
        capabilityId: String,
        parameters: JSONObject
    ): JSONObject {
        val normalized = capabilityId.trim().lowercase()
        if (normalized.isBlank()) {
            throw PluginInstallException("CAPABILITY_ID_REQUIRED", "Delegated capability ID is required")
        }
        if (normalized == BRIDGE_REMOTE_INVOKE_CAPABILITY_ID) {
            return invokeBridgeRemote(ownerPluginId, parameters)
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

    private suspend fun invokeBridgeRemote(ownerPluginId: String, parameters: JSONObject): JSONObject {
        if (ownerPluginId != SYSTEM_BRIDGE_PLUGIN_ID) {
            throw PluginInstallException(
                "BRIDGE_DELEGATION_OWNER_MISMATCH",
                "$BRIDGE_REMOTE_INVOKE_CAPABILITY_ID is reserved for $SYSTEM_BRIDGE_PLUGIN_ID"
            )
        }
        val context = appContext
            ?: throw PluginInstallException(
                "BUSINESS_RUNTIME_UNAVAILABLE",
                "Bridge remote ingress requires the AI Limbs business runtime"
            )
        val transportId = normalizeExternalBridgeTransportId(parameters.optString("transport"))
        val transport = AiLimbsExecutionTransport.EXTERNAL_BRIDGE
        val tool = parameters.optString("tool").trim()
        if (tool.isBlank()) {
            throw PluginInstallException("CAPABILITY_ID_REQUIRED", "Bridge remote tool is required")
        }
        val args = parameters.optJSONObject("args") ?: JSONObject()
        val providerId = parameters.optString("provider_id").trim()
        if (providerId.isBlank()) {
            throw PluginInstallException("BRIDGE_PROVIDER_ID_REQUIRED", "Bridge remote provider_id is required")
        }
        val scopeId = parameters.optString("scope_id").trim()
        if (scopeId.isBlank()) {
            throw PluginInstallException("BRIDGE_SCOPE_REQUIRED", "Bridge remote scope_id is required")
        }
        // Bridge scope_id is transport-session metadata only. It MUST NOT create, reset or key
        // an Interaction Cycle; the one authoritative cycle is owned by the current business core.
        val gatewayKey = "$ownerPluginId:$providerId:$transportId"
        val gateway = bridgeIngressGateways.computeIfAbsent(gatewayKey) {
            AiLimbsIngressGateway(
                context,
                AiLimbsIngressSession(
                    sourceId = transportId,
                    executionSession = AiLimbsExecutionSession(
                        transport = transport,
                        scopeId = "bridge:$providerId:$transportId",
                        sourceTransportId = transportId
                    )
                )
            )
        }
        val ingressResult = gateway.invoke(tool, JSONObject(args.toString()))
        return ingressResult.accessBootstrap?.let { bootstrap ->
            prependBridgeAccessBootstrap(ingressResult.payload, bootstrap)
        } ?: ingressResult.payload
    }

    private fun prependBridgeAccessBootstrap(payload: JSONObject, bootstrap: String): JSONObject {
        val result = JSONObject(payload.toString())
        val oldContent = result.optJSONArray("content")
        if (oldContent == null) {
            return result.put("access_bootstrap", bootstrap)
        }
        val newContent = JSONArray().put(
            JSONObject().put("type", "text").put("text", bootstrap)
        )
        for (index in 0 until oldContent.length()) {
            newContent.put(oldContent.opt(index))
        }
        return result.put("content", newContent)
    }

    private suspend fun executePluginDirect(capabilityId: String, parameters: JSONObject): JSONObject {
        val capability = capabilities[capabilityId]
            ?: throw PluginInstallException("CAPABILITY_NOT_ACTIVE", "Capability is not active: $capabilityId")
        val safeParameters = UbuntuHiddenExecutorKeyLimiter.normalize(capabilityId, parameters)
        val result = capability.spec.executor.execute(safeParameters)
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
        systemExecutor?.isCallable(capabilityId) ?: HostPrimitiveGatewayBindings.isCallable(capabilityId)

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
        val executor = systemExecutor
            ?: throw PluginInstallException("HOST_GATEWAY_NOT_READY", "System Host Gateway executor is not initialized")
        val primitive = AiLimbsHostPrimitiveCatalog.find(normalized)
            ?: throw PluginInstallException("HOST_PRIMITIVE_UNKNOWN", "Unknown AI Limbs Host Primitive: $normalized")
        val api: RuntimeCapabilityApi = RuntimeCapabilityRouter.forRuntime(
            runtimeRole = runtimeRole,
            ownerPluginId = ownerPluginId,
            executor = executor
        )
        return api.invoke(
            RuntimeCapabilityCall(
                capabilityId = primitive.id,
                operation = operation,
                parameters = JSONObject(parameters.toString())
            )
        ).payload
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
            if (normalized == BRIDGE_REMOTE_INVOKE_CAPABILITY_ID && ownerPluginId == SYSTEM_BRIDGE_PLUGIN_ID) {
                return@PluginCapabilityInvoker invokeBridgeRemote(ownerPluginId, JSONObject(parameters.toString()))
            }
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
            if (!isHostCallable(primitive.id)) {
                throw PluginInstallException(
                    "HOST_PRIMITIVE_NOT_BOUND",
                    "Host Primitive has no runtime adapter: ${primitive.id}"
                )
            }
            surfacePolicy?.requireScopeAllowed(primitive.id)
            if (primitive.id !in grantedScopes) {
                throw PluginInstallException(
                    "PLUGIN_SCOPE_DENIED",
                    "$ownerPluginId was not granted required scope: ${primitive.id}"
                )
            }
            invokeSystemHostFromPlugin(
                ownerPluginId,
                primitive.id,
                JSONObject(parameters.toString())
            )
        }

    private suspend fun invokeSystemHostFromPlugin(
        ownerPluginId: String,
        primitiveId: String,
        parameters: JSONObject
    ): JSONObject {
        val copy = JSONObject(parameters.toString())
        val operation = copy.optString("operation").trim().lowercase().ifBlank {
            throw PluginInstallException(
                "HOST_OPERATION_REQUIRED",
                "$primitiveId requires an operation"
            )
        }
        copy.remove("operation")
        return invokeSystemHost(ownerPluginId, primitiveId, operation, copy)
    }

    internal fun normalizeExternalBridgeTransportId(rawTransportId: String): String {
        val transportId = rawTransportId.trim().lowercase()
        if (!BRIDGE_TRANSPORT_ID_REGEX.matches(transportId)) {
            throw PluginInstallException(
                "BRIDGE_TRANSPORT_INVALID",
                "Invalid Bridge transport id: $transportId"
            )
        }
        if (transportId == AiLimbsExecutionTransport.PLUGIN_RUNTIME.wireValue) {
            throw PluginInstallException(
                "BRIDGE_TRANSPORT_UNSUPPORTED",
                "Plugin runtime cannot be used as an external Bridge transport"
            )
        }
        return transportId
    }

    private companion object {
        val BRIDGE_TRANSPORT_ID_REGEX = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        const val BRIDGE_REMOTE_INVOKE_CAPABILITY_ID = "core.bridge.remote.invoke"
        const val SYSTEM_BRIDGE_PLUGIN_ID = "plugin.system.bridge"
        val PLUGIN_CAPABILITY_ID = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
        val HOST_PRIMITIVE_INVOKE_CONTRACTS = listOf(
            "PluginContext.capabilityInvoker",
            "PluginCapabilityInvoker.invoke"
        )
    }
}
