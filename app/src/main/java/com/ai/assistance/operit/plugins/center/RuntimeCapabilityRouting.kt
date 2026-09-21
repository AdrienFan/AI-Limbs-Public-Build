package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker
import com.ai.assistance.operit.core.tools.system.resident.ResidentHostComponentProxy
import org.json.JSONObject

/** Transport contract used by the unified runtime capability router. */
internal fun interface RuntimeCapabilityTransport {
    suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult
}

/**
 * Local Host transport for capabilities that execute inside the current Android Host process.
 * It delegates to the existing SystemHostPrimitiveExecutor, preserving the existing handler.
 */
internal class LocalHostTransport private constructor(
    private val delegate: suspend (RuntimeCapabilityCall) -> JSONObject
) : RuntimeCapabilityTransport {
    constructor(
        ownerPluginId: String,
        executor: SystemHostPrimitiveExecutor
    ) : this(
        delegate = { call ->
            executor.invoke(
                ownerPluginId = ownerPluginId,
                primitiveId = call.capabilityId,
                operation = call.operation,
                parameters = call.parameters
            )
        }
    )

    override suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult =
        RuntimeCapabilityResult(delegate(call))
}

/**
 * Resident Core -> Android Host transport.
 *
 * The wire payload remains neutral JSON and reuses the existing Resident component broker.
 * The Host side therefore reaches the same KernelHostPrimitiveAdapter handler used before
 * Arch Test 5; only ownership of the cross-process call construction moves into this transport.
 */
internal class RemoteHostTransport(
    private val ownerPluginId: String,
    private val requester: (String, JSONObject) -> JSONObject = { kind, payload ->
        ResidentHostComponentProxy.request(kind, payload)
    }
) : RuntimeCapabilityTransport {
    override suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult {
        val response = try {
            requester(
                ResidentComponentProxyBroker.KIND_HOST_PRIMITIVE,
                JSONObject()
                    .put("owner_plugin_id", ownerPluginId)
                    .put("primitive_id", call.capabilityId)
                    .put("operation", call.operation)
                    .put("parameters", JSONObject(call.parameters.toString()))
            )
        } catch (error: Throwable) {
            throw PluginInstallException(
                "HOST_UI_PROXY_UNAVAILABLE",
                "Host-owned primitive could not reach the Android Host: " +
                    "${call.capabilityId}/${call.operation}",
                error
            )
        }

        if (!response.optBoolean("ok", false)) {
            throw PluginInstallException(
                "HOST_UI_PROXY_FAILED",
                response.optString(
                    "error",
                    "Host-owned primitive failed: ${call.capabilityId}/${call.operation}"
                )
            )
        }
        return RuntimeCapabilityResult(response.optJSONObject("result") ?: JSONObject())
    }
}

/**
 * Unified Host router for the incrementally migrated capability set.
 *
 * Arch Tests 4-6 incrementally admit host.ui.layout@1 and host.privileged.runtime@1.
 * Test 7 expands routing by owner instead of an explicit migrated set.
 */
internal class RuntimeCapabilityRouter(
    private val hostTransport: RuntimeCapabilityTransport
) : RuntimeCapabilityApi {
    override suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult {
        val normalizedId = call.capabilityId.trim().lowercase()
        check(isMigrated(normalizedId)) {
            "Runtime capability is not migrated to unified Host transport: $normalizedId"
        }

        val descriptor = CapabilityRegistry.requireDescriptor(normalizedId)
        check(descriptor.executionOwner == CapabilityExecutionOwner.HOST) {
            "Unified Host transport requires HOST ownership: ${descriptor.id}"
        }

        return hostTransport.invoke(call.copy(capabilityId = descriptor.id))
    }

    companion object {
        private val MIGRATED_HOST_IDS = setOf(
            "host.ui.layout@1",
            "host.privileged.runtime@1"
        )

        fun isMigrated(capabilityId: String): Boolean =
            capabilityId.trim().lowercase() in MIGRATED_HOST_IDS
    }
}
