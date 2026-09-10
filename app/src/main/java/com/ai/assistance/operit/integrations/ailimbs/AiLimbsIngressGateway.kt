package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
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
    val execute: suspend (String, JSONObject) -> JSONObject
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
        context,
        ingressSession,
        AiLimbsInteractionCycleRuntime.state(context.applicationContext)
    )

    internal suspend fun executeWithinSession(tool: String, args: JSONObject): JSONObject =
        runtime.execute(tool, args)

    suspend fun invoke(tool: String, args: JSONObject): AiLimbsIngressResult {
        val lease = cycleRuntime?.beginInvocation()
        return try {
            val payload = executeWithinSession(tool, args)
            val generation = lease?.generation ?: cycleRuntime?.currentGeneration()
            AiLimbsIngressResult(
                payload = payload,
                accessBootstrap = takeAccessBootstrap(generation)
            )
        } finally {
            if (lease != null) cycleRuntime?.endInvocation()
        }
    }

    suspend fun complete(payload: JSONObject): AiLimbsIngressResult {
        val generation = cycleRuntime?.currentGeneration()
        return AiLimbsIngressResult(
            payload = payload,
            accessBootstrap = takeAccessBootstrap(generation)
        )
    }

    fun resetAccessBootstrap() {
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
