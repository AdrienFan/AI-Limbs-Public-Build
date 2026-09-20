package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.plugins.system.SystemHostGatewayV1
import org.json.JSONObject

/**
 * Stage-2 unified runtime capability call contract.
 *
 * This type intentionally carries only the existing Host Gateway call shape.
 * Ownership, registry metadata and transport selection are introduced in later stages.
 */
data class RuntimeCapabilityCall(
    val capabilityId: String,
    val operation: String,
    val parameters: JSONObject = JSONObject()
)

/**
 * Stage-2 unified runtime capability result contract.
 *
 * Errors keep the legacy gateway semantics and are still propagated as exceptions.
 */
data class RuntimeCapabilityResult(
    val payload: JSONObject
)

/**
 * Minimal unified capability entry point.
 *
 * Arch Test 2 does not replace any existing caller or routing path. The legacy adapter
 * delegates directly to SystemHostGatewayV1 so policy, identity, routing and error
 * behavior remain owned by the existing implementation.
 */
interface RuntimeCapabilityApi {
    suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult

    companion object {
        fun fromLegacyHostGateway(gateway: SystemHostGatewayV1): RuntimeCapabilityApi =
            object : RuntimeCapabilityApi {
                override suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult {
                    return RuntimeCapabilityResult(
                        gateway.invokeHostPrimitive(
                            call.capabilityId,
                            call.operation,
                            call.parameters
                        )
                    )
                }
            }
    }
}
