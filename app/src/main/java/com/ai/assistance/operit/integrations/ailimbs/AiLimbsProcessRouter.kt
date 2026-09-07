package com.ai.assistance.operit.integrations.ailimbs

import org.json.JSONObject

/**
 * Transport-neutral process router for AI Limbs.
 *
 * Ingress adapters (RDC, TriggerCMD, API, future bridges) must not know which concrete
 * System Environment plugin owns Linux process execution. They pass process semantics here,
 * and this router enters the ordinary Dispatcher/Capability path inside the caller session.
 */
internal class AiLimbsProcessRouter(
    private val ingressGateway: AiLimbsIngressGateway
) {
    suspend fun executeSystemEnvironment(
        operation: String,
        args: JSONObject
    ): JSONObject {
        val parameters = JSONObject(args.toString())
            .put("operation", operation.trim().lowercase())
        return ingressGateway.executeWithinSession(
            SYSTEM_ENVIRONMENT_PROCESS_CAPABILITY,
            parameters
        )
    }

    private companion object {
        /**
         * Stable provider alias. Ubuntu may currently implement it, but the transport layer
         * never imports or names that implementation and another provider can replace it.
         */
        const val SYSTEM_ENVIRONMENT_PROCESS_CAPABILITY =
            "plugin.system_environment.process"
    }
}
