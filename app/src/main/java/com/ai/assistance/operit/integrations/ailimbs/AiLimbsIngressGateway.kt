package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import com.ai.assistance.operit.core.tools.system.resident.ResidentCoreDispatcherClient
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

data class AiLimbsIngressSession(
    val sourceId: String,
    val executionSession: AiLimbsExecutionSession
) {
    init {
        require(sourceId.isNotBlank()) { "AI Limbs ingress source_id must not be blank" }
    }
}

data class AiLimbsIngressResult(
    val payload: JSONObject,
    val accessBootstrap: String?
)

internal data class AiLimbsIngressRuntime(
    val execute: suspend (String, JSONObject) -> JSONObject,
    val authoritativeInvoke: (suspend (String, JSONObject) -> AiLimbsIngressResult)? = null,
    val authoritativeBootstrapRearm: (() -> Unit)? = null
)

private data class AiLimbsIngressBackend(
    val runtime: AiLimbsIngressRuntime,
    val readAccessBootstrap: suspend () -> String,
    val cycleRuntime: AiLimbsInteractionCycleRuntimeState?
)

private fun createIngressRuntime(
    context: Context,
    session: AiLimbsExecutionSession,
    sharedAccessGate: AiLimbsAccessGate? = null
): AiLimbsIngressRuntime {
    val executor = AiLimbsRemoteInvocationExecutor(
        context.applicationContext,
        session,
        sharedAccessGate
    )
    return AiLimbsIngressRuntime(execute = executor::execute)
}

private fun createDefaultIngressBackend(
    context: Context,
    ingressSession: AiLimbsIngressSession
): AiLimbsIngressBackend {
    val appContext = context.applicationContext
    val coreClient = ResidentCoreDispatcherClient.forExternalCoreOrNull(appContext, ingressSession)
    if (coreClient != null) {
        return AiLimbsIngressBackend(
            runtime = AiLimbsIngressRuntime(
                execute = { _, _ -> error("Host-local Dispatcher is disabled while Resident Core owns policy") },
                authoritativeInvoke = coreClient::invoke,
                authoritativeBootstrapRearm = coreClient::rearmBootstrapBlocking
            ),
            readAccessBootstrap = { error("Access Bootstrap is owned by Resident Core") },
            cycleRuntime = null
        )
    }
    val sharedCycleRuntime = AiLimbsInteractionCycleRuntime.state(appContext)
    return AiLimbsIngressBackend(
        runtime = createIngressRuntime(
            appContext,
            ingressSession.executionSession,
            sharedCycleRuntime.accessGate
        ),
        readAccessBootstrap = AiLimbsAccessContextService(appContext)::readAccessContext,
        cycleRuntime = sharedCycleRuntime
    )
}

/**
 * Stable, provider-neutral ingress boundary for remote AI Limbs transports.
 *
 * Providers own framing and delivery. AI Limbs owns one process-wide interaction cycle and only
 * rolls it at a later ingress boundary; expiration never interrupts an invocation already running.
 */
class AiLimbsIngressGateway internal constructor(
    val ingressSession: AiLimbsIngressSession,
    private val runtime: AiLimbsIngressRuntime,
    private val readAccessBootstrap: suspend () -> String,
    private val cycleRuntime: AiLimbsInteractionCycleRuntimeState?
) {
    private val localBootstrapPending = AtomicBoolean(true)

    internal constructor(
        ingressSession: AiLimbsIngressSession,
        executeRemote: suspend (String, JSONObject) -> JSONObject,
        readAccessBootstrap: suspend () -> String
    ) : this(
        ingressSession = ingressSession,
        runtime = AiLimbsIngressRuntime(executeRemote),
        readAccessBootstrap = readAccessBootstrap,
        cycleRuntime = null
    )

    private constructor(
        ingressSession: AiLimbsIngressSession,
        backend: AiLimbsIngressBackend
    ) : this(
        ingressSession = ingressSession,
        runtime = backend.runtime,
        readAccessBootstrap = backend.readAccessBootstrap,
        cycleRuntime = backend.cycleRuntime
    )

    private constructor(
        context: Context,
        ingressSession: AiLimbsIngressSession,
        sharedCycleRuntime: AiLimbsInteractionCycleRuntimeState
    ) : this(
        ingressSession = ingressSession,
        runtime = createIngressRuntime(
            context,
            ingressSession.executionSession,
            sharedCycleRuntime.accessGate
        ),
        readAccessBootstrap = AiLimbsAccessContextService(context.applicationContext)::readAccessContext,
        cycleRuntime = sharedCycleRuntime
    )

    constructor(
        context: Context,
        ingressSession: AiLimbsIngressSession
    ) : this(
        ingressSession,
        createDefaultIngressBackend(context, ingressSession)
    )

    internal companion object {
        fun authoritativeCore(
            context: Context,
            ingressSession: AiLimbsIngressSession,
            sharedCycleRuntime: AiLimbsInteractionCycleRuntimeState
        ): AiLimbsIngressGateway =
            AiLimbsIngressGateway(context.applicationContext, ingressSession, sharedCycleRuntime)
    }

    private suspend fun executeRawWithinSession(tool: String, args: JSONObject): JSONObject =
        runtime.execute(tool, args)

    suspend fun invoke(tool: String, args: JSONObject): AiLimbsIngressResult {
        runtime.authoritativeInvoke?.let { invokeRemote ->
            return invokeRemote(tool, args)
        }
        return invoke { executeRawWithinSession(tool, args) }
    }

    internal suspend fun invokePayload(tool: String, args: JSONObject): JSONObject {
        val result = invoke(tool, args)
        return result.accessBootstrap?.let { bootstrap ->
            JSONObject(result.payload.toString()).put("access_bootstrap", bootstrap)
        } ?: result.payload
    }

    private suspend fun invoke(execute: suspend () -> JSONObject): AiLimbsIngressResult {
        val lease = cycleRuntime?.beginInvocation()
        if (lease != null && !lease.admitted) {
            val errorCode = lease.blockedReason ?: "INTERACTION_CYCLE_RESET_PENDING"
            return AiLimbsIngressResult(
                payload = JSONObject()
                    .put("success", false)
                    .put("error_code", errorCode)
                    .put("type", errorCode)
                    .put("scope", "interaction_cycle")
                    .put("retry_original_capability", true)
                    .put("target_generation", lease.generation),
                accessBootstrap = null
            )
        }
        return try {
            val generation = lease?.generation ?: cycleRuntime?.currentGeneration()
            val bootstrap = takeAccessBootstrap(generation)
            if (bootstrap != null) {
                AiLimbsIngressResult(
                    payload = JSONObject().put("success", true),
                    accessBootstrap = bootstrap
                )
            } else {
                AiLimbsIngressResult(
                    payload = execute(),
                    accessBootstrap = null
                )
            }
        } finally {
            if (lease != null) cycleRuntime?.endInvocation()
        }
    }

    fun resetAccessBootstrap() {
        runtime.authoritativeBootstrapRearm?.let { rearmRemote ->
            rearmRemote()
            return
        }
        val shared = cycleRuntime
        if (shared != null) {
            shared.rearmCurrentBootstrap()
        } else {
            localBootstrapPending.set(true)
        }
    }

    private suspend fun takeAccessBootstrap(generation: Long?): String? {
        val shared = cycleRuntime
        if (shared == null) {
            if (!localBootstrapPending.compareAndSet(true, false)) return null
            return try {
                readAccessBootstrap()
            } catch (error: Exception) {
                localBootstrapPending.set(true)
                throw error
            }
        }

        val resolvedGeneration = generation ?: shared.currentGeneration()
        if (!shared.claimBootstrap(resolvedGeneration)) return null
        return try {
            readAccessBootstrap()
        } catch (error: Exception) {
            shared.rearmBootstrap(resolvedGeneration)
            throw error
        }
    }
}
