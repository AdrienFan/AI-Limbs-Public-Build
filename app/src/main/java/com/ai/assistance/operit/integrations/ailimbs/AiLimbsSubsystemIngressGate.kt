package com.ai.assistance.operit.integrations.ailimbs

import org.json.JSONObject

/** Intercepts the first external AI ingress into any child that publishes discovery knowledge. */
internal class AiLimbsSubsystemIngressGate(
    private val policyEngine: AiLimbsExecutionPolicyEngine
) {
    fun intercept(invocation: AiLimbsNormalizedInvocation): JSONObject? {
        if (policyEngine.session.transport == AiLimbsExecutionTransport.PLUGIN_RUNTIME) return null
        val route = invocation.route as? AiLimbsCapabilityRoute.Plugin ?: return null
        val extensionId = route.registration.ownerPluginId
        val discovery = AiLimbsSubsystemDiscoveryRegistry.resolve(extensionId) ?: return null
        val decision = policyEngine.subsystemDiscoveryDecision(extensionId)
        if (decision == AiLimbsSubsystemDiscoveryDecision.ALLOW) return null

        val response = JSONObject()
            .put("success", false)
            .put("scope", "interaction_cycle")
            .put("subsystem_extension_id", discovery.extensionId)
            .put("requested_name", invocation.requestedName)
            .put("canonical_name", invocation.canonicalName)
            .put("original_capability_executed", false)
            .put("retry_original_capability", true)

        return when (decision) {
            AiLimbsSubsystemDiscoveryDecision.DELIVER ->
                response
                    .put("error_code", "SUBSYSTEM_AI_INGRESS_DISCOVERY")
                    .put("type", "SUBSYSTEM_AI_INGRESS_DISCOVERY")
                    .put("delivery_state", "DELIVERING")
                    .put("schema_id", discovery.schemaId)
                    .put("discovery", discovery.payload())
            AiLimbsSubsystemDiscoveryDecision.BLOCK ->
                response
                    .put("error_code", "SUBSYSTEM_AI_INGRESS_DISCOVERY_IN_PROGRESS")
                    .put("type", "SUBSYSTEM_AI_INGRESS_DISCOVERY_IN_PROGRESS")
                    .put("delivery_state", "DELIVERING")
                    .put("wait_for_discovery_result", true)
            AiLimbsSubsystemDiscoveryDecision.ALLOW -> error("unreachable")
        }
    }
}
