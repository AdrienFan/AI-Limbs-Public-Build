package com.ai.assistance.operit.plugins.system

import com.ai.assistance.operit.plugins.center.AiLimbsHostPrimitiveCatalog
import com.ai.assistance.operit.plugins.center.HostPrimitiveDefinition
import com.ai.assistance.operit.plugins.center.HostPrimitiveExposure
import com.ai.assistance.operit.plugins.center.HostSurfacePolicy
import com.ai.assistance.operit.plugins.center.PluginHostCapabilityRegistry
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.PluginManager
import com.ai.assistance.operit.plugins.center.PluginSurfaceIds
import org.json.JSONObject

data class SystemHostPrimitiveDescriptor(
    val number: Int,
    val id: String,
    val title: String,
    val maturity: String,
    val exposure: String,
    val requestableScope: Boolean,
    val policyAllowed: Boolean?,
    val callable: Boolean
)

interface SystemHostGatewayV1 {
    fun listHostPrimitives(): List<SystemHostPrimitiveDescriptor>
    fun describeHostPrimitive(id: String): SystemHostPrimitiveDescriptor?
    suspend fun invokeHostPrimitive(id: String, parameters: JSONObject = JSONObject()): JSONObject
}
interface PluginPlatformControlV1 {
    fun developerModeEnabled(): Boolean
    fun hostPrimitiveSnapshots(): List<SystemHostPrimitiveDescriptor>
    suspend fun setDeveloperMode(enabled: Boolean)
    suspend fun setHostPrimitiveAllowed(primitiveId: String, allowed: Boolean)
}

interface SystemPluginHostV1 {
    val hostAbi: Int
    val hostGateway: SystemHostGatewayV1
    val pluginPlatform: PluginPlatformControlV1
}

internal class KernelSystemHostGatewayV1(
    private val ownerPluginId: String,
    private val admittedRole: String,
    private val capabilityRegistry: PluginHostCapabilityRegistry,
    private val surfacePolicy: HostSurfacePolicy
) : SystemHostGatewayV1 {
    init {
        requirePluginCenterRole(admittedRole)
    }

    override fun listHostPrimitives(): List<SystemHostPrimitiveDescriptor> =
        AiLimbsHostPrimitiveCatalog.all.map(::descriptor)

    override fun describeHostPrimitive(id: String): SystemHostPrimitiveDescriptor? =
        AiLimbsHostPrimitiveCatalog.find(id)?.let(::descriptor)
    override suspend fun invokeHostPrimitive(id: String, parameters: JSONObject): JSONObject {
        requirePluginCenterRole(admittedRole)
        return capabilityRegistry.invokeSystemHost(
            ownerPluginId = ownerPluginId,
            capabilityId = id,
            parameters = parameters
        )
    }

    private fun descriptor(definition: HostPrimitiveDefinition): SystemHostPrimitiveDescriptor {
        val policyAllowed =
            if (definition.requestableScope && definition.exposure == HostPrimitiveExposure.BOUND) {
                surfacePolicy.isAllowed(PluginSurfaceIds.hostPrimitive(definition.id))
            } else {
                null
            }
        return SystemHostPrimitiveDescriptor(
            number = definition.number,
            id = definition.id,
            title = definition.title,
            maturity = definition.maturity.name,
            exposure = definition.exposure.name,
            requestableScope = definition.requestableScope,
            policyAllowed = policyAllowed,
            callable = capabilityRegistry.isHostCallable(definition.id)
        )
    }
}

internal class KernelPluginPlatformControlV1(
    private val admittedRole: String,
    private val manager: PluginManager,
    private val capabilityRegistry: PluginHostCapabilityRegistry,
    private val surfacePolicy: HostSurfacePolicy
) : PluginPlatformControlV1 {
    init {
        requirePluginCenterRole(admittedRole)
    }

    override fun developerModeEnabled(): Boolean = surfacePolicy.developerMode

    override fun hostPrimitiveSnapshots(): List<SystemHostPrimitiveDescriptor> =
        AiLimbsHostPrimitiveCatalog.all.map { definition ->
            val policyAllowed =
                if (definition.requestableScope && definition.exposure == HostPrimitiveExposure.BOUND) {
                    surfacePolicy.isAllowed(PluginSurfaceIds.hostPrimitive(definition.id))
                } else {
                    null
                }
            SystemHostPrimitiveDescriptor(
                definition.number,
                definition.id,
                definition.title,
                definition.maturity.name,
                definition.exposure.name,
                definition.requestableScope,
                policyAllowed,
                capabilityRegistry.isHostCallable(definition.id)
            )
        }
    override suspend fun setDeveloperMode(enabled: Boolean) {
        requirePluginCenterRole(admittedRole)
        surfacePolicy.setDeveloperMode(enabled)
    }

    override suspend fun setHostPrimitiveAllowed(primitiveId: String, allowed: Boolean) {
        requirePluginCenterRole(admittedRole)
        val primitive = AiLimbsHostPrimitiveCatalog.find(primitiveId)
            ?: throw PluginInstallException(
                "HOST_PRIMITIVE_UNKNOWN",
                "Unknown AI Limbs Host Primitive: $primitiveId"
            )
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

internal class KernelSystemPluginHostV1(
    override val hostAbi: Int,
    override val hostGateway: SystemHostGatewayV1,
    override val pluginPlatform: PluginPlatformControlV1
) : SystemPluginHostV1

private fun requirePluginCenterRole(role: String) {
    if (role.trim().lowercase() != SystemPluginProtocolV1.ROLE_PLUGIN_CENTER) {
        throw PluginInstallException(
            "SYSTEM_ROLE_FORBIDDEN",
            "System Host Gateway V1 requires system.role=plugin_center"
        )
    }
}
