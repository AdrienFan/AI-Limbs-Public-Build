package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker
import com.ai.assistance.operit.core.tools.system.resident.ResidentHostComponentProxy
import org.json.JSONObject

/** Transport contract used by the unified runtime capability router. */
internal fun interface RuntimeCapabilityTransport {
    suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult
}

/**
 * Current-process transport backed by the existing SystemHostPrimitiveExecutor.
 *
 * The historical name is kept for compatibility with Arch Tests 4-6. In Test 7 the same
 * current-process transport also serves BUSINESS-owned descriptors because the business owner
 * is whichever process currently owns PluginPlatformKernel.
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
 * Host-side execution still reaches the same KernelHostPrimitiveAdapter handler.
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

/** Adapter slot for descriptors whose canonical execution owner is PLUGIN_RUNTIME. */
internal class PluginRuntimeTransportAdapter(
    private val delegate: RuntimeCapabilityTransport? = null
) : RuntimeCapabilityTransport {
    override suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult =
        delegate?.invoke(call)
            ?: throw PluginInstallException(
                "RUNTIME_CAPABILITY_TRANSPORT_UNAVAILABLE",
                "No PLUGIN_RUNTIME transport adapter is registered for ${call.capabilityId}"
            )
}

/** Adapter slot for descriptors whose canonical execution owner is EXTERNAL_DAEMON. */
internal class ExternalDaemonTransportAdapter(
    private val delegate: RuntimeCapabilityTransport? = null
) : RuntimeCapabilityTransport {
    override suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult =
        delegate?.invoke(call)
            ?: throw PluginInstallException(
                "RUNTIME_CAPABILITY_TRANSPORT_UNAVAILABLE",
                "No EXTERNAL_DAEMON transport adapter is registered for ${call.capabilityId}"
            )
}

/**
 * One owner -> transport table for a single invocation environment.
 *
 * Process knowledge lives here, not in callers or primitive handlers:
 * - HOST is local in Android Host / UI proxy and remote from Resident BUSINESS.
 * - BUSINESS is always local to the process that currently owns business.
 * - PLUGIN_RUNTIME and EXTERNAL_DAEMON are explicit adapter slots. The canonical Registry does
 *   not yet assign descriptors to either owner, so Test 7 adds the route without inventing work.
 */
internal class RuntimeCapabilityTransportSet private constructor(
    private val transports: Map<CapabilityExecutionOwner, RuntimeCapabilityTransport>
) {
    fun resolve(owner: CapabilityExecutionOwner): RuntimeCapabilityTransport =
        requireNotNull(transports[owner]) {
            "Runtime capability transport is not configured for owner: $owner"
        }

    companion object {
        fun forRuntime(
            runtimeRole: PluginRuntimeRole,
            ownerPluginId: String,
            executor: SystemHostPrimitiveExecutor,
            pluginRuntimeAdapter: RuntimeCapabilityTransport? = null,
            externalDaemonAdapter: RuntimeCapabilityTransport? = null
        ): RuntimeCapabilityTransportSet {
            val local = LocalHostTransport(ownerPluginId, executor)
            val host = when (runtimeRole) {
                PluginRuntimeRole.BUSINESS -> RemoteHostTransport(ownerPluginId)
                PluginRuntimeRole.LEGACY_HOST,
                PluginRuntimeRole.UI_PROXY -> local
            }
            return RuntimeCapabilityTransportSet(
                mapOf(
                    CapabilityExecutionOwner.HOST to host,
                    CapabilityExecutionOwner.BUSINESS to local,
                    CapabilityExecutionOwner.PLUGIN_RUNTIME to
                        PluginRuntimeTransportAdapter(pluginRuntimeAdapter),
                    CapabilityExecutionOwner.EXTERNAL_DAEMON to
                        ExternalDaemonTransportAdapter(externalDaemonAdapter)
                )
            )
        }

        internal fun explicit(
            host: RuntimeCapabilityTransport,
            business: RuntimeCapabilityTransport,
            pluginRuntime: RuntimeCapabilityTransport,
            externalDaemon: RuntimeCapabilityTransport
        ): RuntimeCapabilityTransportSet =
            RuntimeCapabilityTransportSet(
                mapOf(
                    CapabilityExecutionOwner.HOST to host,
                    CapabilityExecutionOwner.BUSINESS to business,
                    CapabilityExecutionOwner.PLUGIN_RUNTIME to pluginRuntime,
                    CapabilityExecutionOwner.EXTERNAL_DAEMON to externalDaemon
                )
            )
    }
}

/**
 * Canonical descriptor-owner router.
 *
 * Test 7 removes the incrementally migrated capability whitelist. Every canonical descriptor
 * selects its transport exclusively from CapabilityDescriptor.executionOwner.
 */
internal class RuntimeCapabilityRouter(
    private val transports: RuntimeCapabilityTransportSet
) : RuntimeCapabilityApi {
    override suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult {
        val normalizedId = call.capabilityId.trim().lowercase()
        val descriptor = CapabilityRegistry.requireDescriptor(normalizedId)
        check(descriptor.policy == CapabilityPolicyRef.LEGACY_EXISTING_POLICY) {
            "Unsupported runtime capability policy: ${descriptor.policy}"
        }
        val canonicalCall = call.copy(capabilityId = descriptor.id)
        return transports.resolve(descriptor.executionOwner).invoke(canonicalCall)
    }

    companion object {
        fun forRuntime(
            runtimeRole: PluginRuntimeRole,
            ownerPluginId: String,
            executor: SystemHostPrimitiveExecutor,
            pluginRuntimeAdapter: RuntimeCapabilityTransport? = null,
            externalDaemonAdapter: RuntimeCapabilityTransport? = null
        ): RuntimeCapabilityRouter =
            RuntimeCapabilityRouter(
                RuntimeCapabilityTransportSet.forRuntime(
                    runtimeRole = runtimeRole,
                    ownerPluginId = ownerPluginId,
                    executor = executor,
                    pluginRuntimeAdapter = pluginRuntimeAdapter,
                    externalDaemonAdapter = externalDaemonAdapter
                )
            )
    }
}
