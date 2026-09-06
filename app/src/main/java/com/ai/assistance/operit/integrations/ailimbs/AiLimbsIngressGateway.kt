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

/**
 * Stable, provider-neutral ingress boundary for remote AI Limbs transports.
 *
 * Providers own framing and delivery. The gateway owns session identity, access bootstrap state,
 * and the shared execution path into Policy + Dispatcher.
 */
class AiLimbsIngressGateway internal constructor(
    val ingressSession: AiLimbsIngressSession,
    private val executeRemote: suspend (String, JSONObject) -> JSONObject,
    private val readAccessBootstrap: suspend () -> String
) {
    private val bootstrapPending = AtomicBoolean(true)

    constructor(
        context: Context,
        ingressSession: AiLimbsIngressSession
    ) : this(
        ingressSession = ingressSession,
        executeRemote =
            AiLimbsRemoteInvocationExecutor(
                context.applicationContext,
                ingressSession.executionSession
            )::execute,
        readAccessBootstrap =
            AiLimbsAccessContextService(context.applicationContext)::readAccessContext
    )

    internal suspend fun executeWithinSession(tool: String, args: JSONObject): JSONObject =
        executeRemote(tool, args)

    suspend fun invoke(tool: String, args: JSONObject): AiLimbsIngressResult =
        complete(executeWithinSession(tool, args))

    suspend fun complete(payload: JSONObject): AiLimbsIngressResult =
        AiLimbsIngressResult(
            payload = payload,
            accessBootstrap = takeAccessBootstrap()
        )

    fun resetAccessBootstrap() {
        bootstrapPending.set(true)
    }

    private suspend fun takeAccessBootstrap(): String? {
        if (!bootstrapPending.compareAndSet(true, false)) return null
        return try {
            readAccessBootstrap()
        } catch (error: Exception) {
            bootstrapPending.set(true)
            throw error
        }
    }
}
