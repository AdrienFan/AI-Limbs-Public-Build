package com.ai.assistance.operit.plugins.center

import org.json.JSONObject

/**
 * Transport contract used by the unified runtime capability router.
 *
 * Arch Test 4 introduces only the local Host transport. Remote Host, plugin-runtime and
 * external-daemon transports remain deliberately unavailable until their own stages.
 */
internal fun interface RuntimeCapabilityTransport {
    suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult
}

/**
 * Local Host transport for capabilities that execute inside the current Android Host process.
 *
 * The production constructor delegates to the existing SystemHostPrimitiveExecutor so the
 * handler, policy checks, parameter semantics and returned JSON remain unchanged.
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
 * Stage-4 router. Only host.ui.layout@1 is admitted here.
 *
 * Keeping this allow-list to one descriptor is intentional: Resident remote routing belongs to
 * Arch Test 5 and the general owner router belongs to Arch Test 7.
 */
internal class RuntimeCapabilityRouter(
    private val localHostTransport: RuntimeCapabilityTransport
) : RuntimeCapabilityApi {
    override suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult {
        val normalizedId = call.capabilityId.trim().lowercase()
        check(normalizedId in LOCAL_HOST_MIGRATED_IDS) {
            "Runtime capability is not migrated to LocalHostTransport in Arch Test 4: $normalizedId"
        }

        val descriptor = CapabilityRegistry.requireDescriptor(normalizedId)
        check(descriptor.executionOwner == CapabilityExecutionOwner.HOST) {
            "LocalHostTransport requires HOST ownership: ${descriptor.id}"
        }

        return localHostTransport.invoke(
            call.copy(capabilityId = descriptor.id)
        )
    }

    private companion object {
        val LOCAL_HOST_MIGRATED_IDS = setOf("host.ui.layout@1")
    }
}
