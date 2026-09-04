package com.ai.assistance.operit.plugins.system

import androidx.compose.runtime.Composable
import com.ai.assistance.operit.plugins.center.AiLimbsHostPrimitiveCatalog
import com.ai.assistance.operit.plugins.center.CallerAwarePluginServiceEndpoint
import com.ai.assistance.operit.plugins.center.HostPrimitiveDefinition
import com.ai.assistance.operit.plugins.center.HostPrimitiveExposure
import com.ai.assistance.operit.plugins.center.HostSurfacePolicy
import com.ai.assistance.operit.plugins.center.PluginContributionKind
import com.ai.assistance.operit.plugins.center.PluginContributionRecord
import com.ai.assistance.operit.plugins.center.PluginContributionRegistry
import com.ai.assistance.operit.plugins.center.PluginHostCapabilityRegistry
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.PluginManager
import com.ai.assistance.operit.plugins.center.PluginSurfaceIds
import com.ai.assistance.operit.plugins.center.SystemPluginUiRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class SystemHostPrimitiveDescriptor(
    val number: Int,
    val id: String,
    val title: String,
    val description: String,
    val boundary: String,
    val maturity: String,
    val exposure: String,
    val requestableScope: Boolean,
    val policyAllowed: Boolean?,
    val callable: Boolean
)

data class SystemHostPrimitiveAvailability(
    val id: String,
    val operation: String?,
    val known: Boolean,
    val callable: Boolean,
    val available: Boolean,
    val reasonCode: String? = null,
    val reason: String? = null
)

interface SystemHostGatewayV1 {
    fun listHostPrimitives(): List<SystemHostPrimitiveDescriptor>
    fun describeHostPrimitive(id: String): SystemHostPrimitiveDescriptor?
    fun listHostPrimitiveOperations(id: String): List<String>
    fun availabilityHostPrimitive(id: String, operation: String? = null): SystemHostPrimitiveAvailability
    suspend fun invokeHostPrimitive(id: String, parameters: JSONObject = JSONObject()): JSONObject
    suspend fun invokeHostPrimitive(id: String, operation: String, parameters: JSONObject = JSONObject()): JSONObject
}

interface PluginPlatformControlV1 {
    fun developerModeEnabled(): Boolean
    fun developerDiscoveryEnabled(): Boolean
    fun hostPrimitiveSnapshots(): List<SystemHostPrimitiveDescriptor>
    suspend fun setDeveloperMode(enabled: Boolean)
    suspend fun setDeveloperDiscoveryEnabled(enabled: Boolean)
    suspend fun setHostPrimitiveAllowed(primitiveId: String, allowed: Boolean)
}

interface SystemJsonServiceV1 {
    suspend fun call(operation: String, parameters: JSONObject = JSONObject()): JSONObject
}

interface SystemUiNavigatorV1 {
    fun backToToolbox(message: String? = null)
}

interface SystemUiPageV1 {
    @Composable
    fun Content(navigator: SystemUiNavigatorV1)
}

data class SystemToolboxEntryV1(
    val id: String,
    val title: String,
    val description: String?,
    val iconKey: String = "extension",
    val page: SystemUiPageV1
)

interface SystemUiHostV1 {
    fun registerToolboxEntry(entry: SystemToolboxEntryV1): AutoCloseable
}

/**
 * Opaque ordinary-plugin UI surface passed from the Host shell to Plugin Center.
 *
 * Stable Kernel owns identity, routing and lifecycle only.  [documentJson] is intentionally opaque:
 * the Host must not inspect component `type` values or attach component-specific behavior.  The
 * [schemaId] names the Plugin Center contract that interprets the document.
 */
fun interface SystemPluginUiActionsV2 {
    /**
     * Invoke a capability as the Host-attested owner of the current screen.
     *
     * The renderer never receives a free-form pluginId here. This prevents a generic UI component
     * from turning into an impersonation primitive while still letting buttons call their owner.
     */
    suspend fun invokeCapability(capabilityId: String, parameters: JSONObject): JSONObject
}

data class SystemPluginUiSurfaceV2(
    val ownerPluginId: String,
    val screenId: String,
    val title: String,
    val description: String?,
    val schemaId: String,
    val documentJson: String,
    val actions: SystemPluginUiActionsV2
)

/**
 * Renderer supplied by Plugin Center for all ordinary-plugin UI documents.
 *
 * This is deliberately one generic renderer instead of one Host interface per widget.  Adding a
 * searchable queue, file picker, child-extension panel or any future composite control therefore
 * updates Plugin Center only; Stable Kernel changes only if this generic surface contract changes.
 */
interface SystemPluginUiRendererV2 {
    @Composable
    fun Render(surface: SystemPluginUiSurfaceV2)
}

interface SystemUiHostV2 : SystemUiHostV1 {
    /**
     * Registers the single semantic owner of ordinary-plugin UI documents.
     *
     * This is Plugin Center-private control-plane infrastructure. It is intentionally NOT a Host
     * Primitive, requested scope, user-toggleable surface, or published Plugin Center service.
     * Ordinary .ailp/.ailx packages may consume the UI language through ai_limbs.ui.screen@2, but
     * they can never register/replace the renderer or extend component semantics through this ABI.
     * The admitted Plugin Center is the only system plugin that receives this Host contract.
     */
    fun registerPluginSurfaceRenderer(renderer: SystemPluginUiRendererV2): AutoCloseable
}

/**
 * Read-only view of a provider contribution exposed to Plugin Center's UI runtime.
 *
 * [payload] keeps the already-versioned public provider contract object.  This directory does not
 * let Plugin Center publish, replace or mutate providers; provider ownership remains with the
 * ordinary plugin that registered the contribution.
 */
data class SystemPluginProviderBindingV2(
    val ownerPluginId: String,
    val id: String,
    val metadata: Map<String, String>,
    val payload: Any?
)

interface SystemPluginProviderDirectoryV2 {
    fun resolve(id: String): SystemPluginProviderBindingV2?
    fun snapshot(): List<SystemPluginProviderBindingV2>

    /**
     * Cold observation stream for provider appearance/disappearance.
     * A cold Flow avoids giving this read-only directory its own lifecycle or background scope.
     */
    fun observe(id: String): Flow<SystemPluginProviderBindingV2?>
}

interface SystemPluginHostV1 {
    val hostAbi: Int
    val hostGateway: SystemHostGatewayV1
    val pluginPlatform: PluginPlatformControlV1
    val pluginAdmin: SystemJsonServiceV1
    val adminSecurity: SystemJsonServiceV1
    val selfMaintenance: SystemJsonServiceV1
    val navigation: SystemJsonServiceV1
    val ui: SystemUiHostV1
}

data class SystemPluginServiceCallerV2(
    val pluginId: String,
    val roles: Set<String>,
    val grantedScopes: Set<String>
)

fun interface SystemPluginServiceEndpointV2 {
    suspend fun invoke(
        caller: SystemPluginServiceCallerV2,
        operation: String,
        parameters: JSONObject
    ): JSONObject
}

interface SystemPluginServicePublisherV2 {
    fun publish(
        id: String,
        apiVersion: Int,
        endpoint: SystemPluginServiceEndpointV2,
        metadata: Map<String, String> = emptyMap()
    ): AutoCloseable
}

interface SystemPluginDelegatedCapabilityInvokerV2 {
    suspend fun invokeAsActivePlugin(
        pluginId: String,
        capabilityId: String,
        parameters: JSONObject = JSONObject()
    ): JSONObject
}

interface SystemPluginHostV2 : SystemPluginHostV1 {
    override val ui: SystemUiHostV2
    val services: SystemPluginServicePublisherV2
    val delegatedCapabilities: SystemPluginDelegatedCapabilityInvokerV2

    /**
     * Read-only provider discovery used by Plugin Center's generic UI components.  For example,
     * DynamicPanel resolves its state provider and ChildExtensionList resolves Extension Hub here.
     */
    val providers: SystemPluginProviderDirectoryV2
}

interface SystemPluginEntryV1 {
    fun mount(host: SystemPluginHostV1): AutoCloseable
}

internal class KernelSystemHostGatewayV1(
    private val ownerPluginId: String,
    private val admittedRole: String,
    private val capabilityRegistry: PluginHostCapabilityRegistry,
    private val surfacePolicy: HostSurfacePolicy
) : SystemHostGatewayV1 {
    init { requirePluginCenterRole(admittedRole) }

    override fun listHostPrimitives(): List<SystemHostPrimitiveDescriptor> =
        AiLimbsHostPrimitiveCatalog.all.map(::descriptor)

    override fun describeHostPrimitive(id: String): SystemHostPrimitiveDescriptor? =
        AiLimbsHostPrimitiveCatalog.find(id)?.let(::descriptor)

    override fun listHostPrimitiveOperations(id: String): List<String> {
        requirePluginCenterRole(admittedRole)
        return capabilityRegistry.systemHostOperations(id)
    }

    override fun availabilityHostPrimitive(id: String, operation: String?): SystemHostPrimitiveAvailability {
        requirePluginCenterRole(admittedRole)
        return capabilityRegistry.systemHostAvailability(id, operation)
    }

    override suspend fun invokeHostPrimitive(id: String, parameters: JSONObject): JSONObject {
        requirePluginCenterRole(admittedRole)
        return capabilityRegistry.invokeSystemHost(ownerPluginId, id, parameters)
    }

    override suspend fun invokeHostPrimitive(id: String, operation: String, parameters: JSONObject): JSONObject {
        requirePluginCenterRole(admittedRole)
        return capabilityRegistry.invokeSystemHost(ownerPluginId, id, operation, parameters)
    }

    private fun descriptor(definition: HostPrimitiveDefinition): SystemHostPrimitiveDescriptor {
        val policyAllowed = if (definition.requestableScope && definition.exposure == HostPrimitiveExposure.BOUND) {
            surfacePolicy.isAllowed(PluginSurfaceIds.hostPrimitive(definition.id))
        } else null
        return SystemHostPrimitiveDescriptor(
            definition.number, definition.id, definition.title,
            definition.description, definition.boundary,
            definition.maturity.name, definition.exposure.name,
            definition.requestableScope, policyAllowed,
            capabilityRegistry.isHostCallable(definition.id)
        )
    }
}

internal class KernelPluginPlatformControlV1(
    private val admittedRole: String,
    private val manager: PluginManager,
    private val capabilityRegistry: PluginHostCapabilityRegistry,
    private val surfacePolicy: HostSurfacePolicy
) : PluginPlatformControlV1 {
    init { requirePluginCenterRole(admittedRole) }
    override fun developerModeEnabled(): Boolean = surfacePolicy.developerMode
    override fun developerDiscoveryEnabled(): Boolean = surfacePolicy.developerDiscoveryEnabled
    override fun hostPrimitiveSnapshots(): List<SystemHostPrimitiveDescriptor> =
        AiLimbsHostPrimitiveCatalog.all.map { definition ->
            val allowed = if (definition.requestableScope && definition.exposure == HostPrimitiveExposure.BOUND) {
                surfacePolicy.isAllowed(PluginSurfaceIds.hostPrimitive(definition.id))
            } else null
            SystemHostPrimitiveDescriptor(
                definition.number, definition.id, definition.title,
                definition.description, definition.boundary,
                definition.maturity.name, definition.exposure.name,
                definition.requestableScope, allowed,
                capabilityRegistry.isHostCallable(definition.id)
            )
        }
    override suspend fun setDeveloperMode(enabled: Boolean) {
        requirePluginCenterRole(admittedRole)
        surfacePolicy.setDeveloperMode(enabled)
    }
    override suspend fun setDeveloperDiscoveryEnabled(enabled: Boolean) {
        requirePluginCenterRole(admittedRole)
        surfacePolicy.setDeveloperDiscoveryEnabled(enabled)
    }
    override suspend fun setHostPrimitiveAllowed(primitiveId: String, allowed: Boolean) {
        requirePluginCenterRole(admittedRole)
        val primitive = AiLimbsHostPrimitiveCatalog.find(primitiveId)
            ?: throw PluginInstallException("HOST_PRIMITIVE_UNKNOWN", "Unknown AI Limbs Host Primitive: $primitiveId")
        if (!primitive.requestableScope || primitive.exposure != HostPrimitiveExposure.BOUND) {
            throw PluginInstallException(
                "HOST_PRIMITIVE_NOT_TOGGLEABLE",
                "Host Primitive policy is not user-toggleable: ${primitive.id} (${primitive.exposure})"
            )
        }
        surfacePolicy.setAllowed(PluginSurfaceIds.hostPrimitive(primitive.id), allowed)
        manager.reconcileHostSurfacePolicy()
    }
}

internal class KernelSystemUiHostV1(
    private val ownerPluginId: String,
    private val admittedRole: String,
    private val registry: SystemPluginUiRegistry
) : SystemUiHostV2 {
    init { requirePluginCenterRole(admittedRole) }

    override fun registerToolboxEntry(entry: SystemToolboxEntryV1): AutoCloseable {
        requirePluginCenterRole(admittedRole)
        return registry.registerToolboxEntry(ownerPluginId, entry)
    }

    override fun registerPluginSurfaceRenderer(renderer: SystemPluginUiRendererV2): AutoCloseable {
        requirePluginCenterRole(admittedRole)
        return registry.registerPluginSurfaceRenderer(ownerPluginId, renderer)
    }
}

/**
 * Kernel adapter for Plugin Center's read-only provider directory.
 *
 * Provider data is copied into a neutral binding, while the payload object remains the public
 * provider contract.  No registration API is exposed here, so moving UI rendering to Plugin Center
 * does not accidentally grant it ownership over ordinary-plugin providers.
 */
internal class KernelSystemPluginProviderDirectoryV2(
    private val admittedRole: String,
    private val contributions: PluginContributionRegistry
) : SystemPluginProviderDirectoryV2 {
    init { requirePluginCenterRole(admittedRole) }

    override fun resolve(id: String): SystemPluginProviderBindingV2? {
        requirePluginCenterRole(admittedRole)
        return contributions.find(PluginContributionKind.PROVIDER, id)?.let(::binding)
    }

    override fun snapshot(): List<SystemPluginProviderBindingV2> {
        requirePluginCenterRole(admittedRole)
        return contributions.listAll()
            .filter { it.kind == PluginContributionKind.PROVIDER }
            .map(::binding)
            .sortedBy { it.id }
    }

    override fun observe(id: String): Flow<SystemPluginProviderBindingV2?> {
        requirePluginCenterRole(admittedRole)
        return contributions.revision.map { resolve(id) }
    }

    private fun binding(record: PluginContributionRecord) = SystemPluginProviderBindingV2(
        ownerPluginId = record.ownerPluginId,
        id = record.id,
        metadata = record.metadata.toMap(),
        payload = record.payload
    )
}

internal class KernelSystemPluginServicePublisherV2(
    private val ownerPluginId: String,
    private val admittedRole: String,
    private val contributions: PluginContributionRegistry
) : SystemPluginServicePublisherV2 {
    init { requirePluginCenterRole(admittedRole) }

    override fun publish(
        id: String,
        apiVersion: Int,
        endpoint: SystemPluginServiceEndpointV2,
        metadata: Map<String, String>
    ): AutoCloseable {
        requirePluginCenterRole(admittedRole)
        val normalized = id.trim().lowercase()
        if (!normalized.startsWith(SERVICE_NAMESPACE) || normalized.length == SERVICE_NAMESPACE.length) {
            throw PluginInstallException(
                "SYSTEM_SERVICE_NAMESPACE_FORBIDDEN",
                "Plugin Center services must use the $SERVICE_NAMESPACE namespace"
            )
        }
        if (apiVersion <= 0) {
            throw PluginInstallException("SERVICE_API_INVALID", "Service API version must be positive")
        }
        val payload = CallerAwarePluginServiceEndpoint { caller, operation, parameters ->
            endpoint.invoke(
                SystemPluginServiceCallerV2(
                    pluginId = caller.pluginId,
                    roles = caller.roles.toSet(),
                    grantedScopes = caller.grantedScopes.toSet()
                ),
                operation,
                JSONObject(parameters.toString())
            )
        }
        return contributions.register(
            PluginContributionRecord(
                ownerPluginId = ownerPluginId,
                kind = PluginContributionKind.SERVICE,
                id = normalized,
                apiVersion = apiVersion,
                metadata = metadata.toMap(),
                payload = payload
            )
        )
    }

    private companion object {
        const val SERVICE_NAMESPACE = "system.plugin_center."
    }
}

internal class KernelSystemPluginDelegatedCapabilityInvokerV2(
    private val admittedRole: String,
    private val manager: PluginManager,
    private val capabilityRegistry: PluginHostCapabilityRegistry
) : SystemPluginDelegatedCapabilityInvokerV2 {
    init { requirePluginCenterRole(admittedRole) }

    override suspend fun invokeAsActivePlugin(
        pluginId: String,
        capabilityId: String,
        parameters: JSONObject
    ): JSONObject {
        requirePluginCenterRole(admittedRole)
        val authorization = manager.activeAuthorization(pluginId.trim())
        return capabilityRegistry.invokeDelegated(
            ownerPluginId = authorization.pluginId,
            grantedScopes = authorization.grantedScopes,
            capabilityId = capabilityId,
            parameters = JSONObject(parameters.toString())
        )
    }
}

internal class KernelSystemPluginHostV2(
    override val hostAbi: Int,
    override val hostGateway: SystemHostGatewayV1,
    override val pluginPlatform: PluginPlatformControlV1,
    override val pluginAdmin: SystemJsonServiceV1,
    override val adminSecurity: SystemJsonServiceV1,
    override val selfMaintenance: SystemJsonServiceV1,
    override val navigation: SystemJsonServiceV1,
    override val ui: SystemUiHostV2,
    override val services: SystemPluginServicePublisherV2,
    override val delegatedCapabilities: SystemPluginDelegatedCapabilityInvokerV2,
    override val providers: SystemPluginProviderDirectoryV2
) : SystemPluginHostV2

internal class UnsupportedSystemJsonServiceV1(private val serviceName: String) : SystemJsonServiceV1 {
    override suspend fun call(operation: String, parameters: JSONObject): JSONObject {
        throw PluginInstallException(
            "SYSTEM_SERVICE_NOT_READY",
            "$serviceName operation is not available yet: $operation"
        )
    }
}

private fun requirePluginCenterRole(role: String) {
    if (role.trim().lowercase() != SystemPluginProtocolV1.ROLE_PLUGIN_CENTER) {
        throw PluginInstallException(
            "SYSTEM_ROLE_FORBIDDEN",
            "System Host V1 requires system.role=plugin_center"
        )
    }
}
