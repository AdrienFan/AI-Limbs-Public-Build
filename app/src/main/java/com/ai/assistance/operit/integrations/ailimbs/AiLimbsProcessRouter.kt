package com.ai.assistance.operit.integrations.ailimbs

import org.json.JSONObject

/**
 * Transport-neutral process router for AI Limbs.
 *
 * Ingress adapters (RDC, TriggerCMD, API, future bridges) must not know which concrete
 * Ubuntu child owns Linux process execution. They pass process semantics here,
 * and this router enters the ordinary Dispatcher/Capability path inside the caller session.
 */
internal class AiLimbsProcessRouter(
    private val ingressGateway: AiLimbsIngressGateway
) {
    suspend fun executeUbuntu(
        operation: String,
        args: JSONObject
    ): JSONObject {
        val parameters = JSONObject(args.toString())
            .put("operation", operation.trim().lowercase())
        return ingressGateway.executeWithinSession(
            UBUNTU_PROCESS_CAPABILITY,
            parameters
        )
    }

    private companion object {
        /**
         * Canonical Ubuntu child capability. Transport adapters still depend only on this router,
         * so Ubuntu implementation details remain outside the transport layer.
         */
        const val UBUNTU_PROCESS_CAPABILITY =
            "plugin.ubuntu.process"
    }
}
