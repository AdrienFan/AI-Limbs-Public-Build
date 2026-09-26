package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.os.Process
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionSession
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionTransport
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsIngressGateway
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsIngressSession
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsInteractionCycleRuntime
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsInteractionCycleRuntimeState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Authoritative Interaction Cycle + Policy + Dispatcher runtime living only inside Resident Core. */
internal class ResidentCoreDispatcherRuntime(
    context: Context,
    private val coreSessionId: String
) {
    private val appContext = context.applicationContext
    private val cycleRuntime: AiLimbsInteractionCycleRuntimeState =
        AiLimbsInteractionCycleRuntime.state(appContext)

    fun invoke(payload: JSONObject): JSONObject {
        val sourceId = payload.getString("source_id").trim()
        require(sourceId.length in 1..256) { "Invalid AI Limbs ingress source id" }
        val sessionJson = payload.getJSONObject("execution_session")
        val transportValue = sessionJson.getString("transport").trim()
        val transport = AiLimbsExecutionTransport.entries.firstOrNull { it.wireValue == transportValue }
            ?: error("Unsupported AI Limbs execution transport: $transportValue")
        val scopeId = sessionJson.getString("scope_id").trim()
        val sourceTransportId = sessionJson.optString("source_transport_id", transport.wireValue).trim()
        require(scopeId.length in 1..256) { "Invalid AI Limbs execution scope id" }
        require(sourceTransportId.length in 1..128) { "Invalid AI Limbs source transport id" }
        val tool = payload.getString("tool").trim()
        require(tool.length in 1..512) { "Invalid AI Limbs tool name" }
        val args = payload.optJSONObject("args") ?: JSONObject()

        val ingress = AiLimbsIngressSession(
            sourceId = sourceId,
            executionSession = AiLimbsExecutionSession(transport, scopeId, sourceTransportId)
        )
        val gateway = AiLimbsIngressGateway.authoritativeCore(appContext, ingress, cycleRuntime)
        val result = runBlocking { gateway.invoke(tool, JSONObject(args.toString())) }
        return JSONObject()
            .put("payload", result.payload)
            .put("access_bootstrap", result.accessBootstrap ?: JSONObject.NULL)
            .put("dispatcher_owner", "resident_core")
            .put("owner_pid", Process.myPid())
            .put("core_session", coreSessionId)
            .put("generation", cycleRuntime.currentGeneration())
    }

    fun invokePluginDelegated(payload: JSONObject): JSONObject {
        check(com.ai.assistance.operit.plugins.center.PluginPlatformKernel.isInitialized) {
            "Plugin kernel is not initialized in Resident Core"
        }
        val pluginId = payload.getString("plugin_id").trim()
        val version = payload.getString("version").trim()
        val capabilityId = payload.getString("capability_id").trim()
        require(pluginId.isNotBlank() && version.isNotBlank() && capabilityId.isNotBlank()) {
            "Plugin worker delegation identity is incomplete"
        }
        val parameters = payload.optJSONObject("parameters") ?: JSONObject()
        val authorization = runBlocking {
            com.ai.assistance.operit.plugins.center.PluginPlatformKernel.manager
                .workerAuthorization(pluginId, version)
        }
        val result = runBlocking {
            com.ai.assistance.operit.plugins.center.PluginPlatformKernel.capabilities.invokeDelegated(
                authorization.pluginId,
                authorization.grantedScopes,
                capabilityId,
                JSONObject(parameters.toString())
            )
        }
        return JSONObject()
            .put("result", result)
            .put("plugin_id", authorization.pluginId)
            .put("version", authorization.version)
            .put("dispatcher_owner", "resident_core")
            .put("owner_pid", Process.myPid())
            .put("core_session", coreSessionId)
    }
    fun attestPluginWorker(payload: JSONObject): JSONObject {
        val pluginId = payload.getString("plugin_id").trim()
        val version = payload.getString("version").trim()
        require(pluginId.isNotBlank() && version.isNotBlank()) {
            "Plugin Worker attestation identity is incomplete"
        }
        val authorization = runBlocking {
            com.ai.assistance.operit.plugins.center.PluginPlatformKernel.manager
                .attestWorkerIdentity(pluginId, version)
        }
        return JSONObject()
            .put("trusted", true)
            .put("plugin_id", authorization.pluginId)
            .put("version", authorization.version)
            .put("roles", org.json.JSONArray(authorization.roles.sorted()))
            .put("granted_scopes", org.json.JSONArray(authorization.grantedScopes.sorted()))
            .put("dispatcher_owner", "resident_core")
            .put("owner_pid", Process.myPid())
            .put("core_session", coreSessionId)
    }

    fun describePluginService(payload: JSONObject): JSONObject {

        val pluginId = payload.getString("plugin_id").trim()
        val version = payload.getString("version").trim()
        val serviceId = payload.getString("service_id").trim()
        val minApi = if (payload.has("min_api") && !payload.isNull("min_api")) payload.getInt("min_api") else null
        return runBlocking {
            com.ai.assistance.operit.plugins.center.PluginPlatformKernel
                .describeWorkerService(pluginId, version, serviceId, minApi)
        }
    }

    fun invokePluginService(payload: JSONObject): JSONObject {
        val pluginId = payload.getString("plugin_id").trim()
        val version = payload.getString("version").trim()
        val serviceId = payload.getString("service_id").trim()
        val operation = payload.getString("service_operation").trim()
        val minApi = if (payload.has("min_api") && !payload.isNull("min_api")) payload.getInt("min_api") else null
        val parameters = payload.optJSONObject("parameters") ?: JSONObject()
        val result = runBlocking {
            com.ai.assistance.operit.plugins.center.PluginPlatformKernel.invokeWorkerService(
                pluginId, version, serviceId, minApi, operation, JSONObject(parameters.toString())
            )
        }
        return JSONObject().put("result", result)
            .put("plugin_id", pluginId).put("version", version)
            .put("core_session", coreSessionId).put("owner_pid", Process.myPid())
    }

    fun rearmBootstrap(): JSONObject {
        cycleRuntime.rearmCurrentBootstrap()
        return snapshot().put("bootstrap_rearmed", true)
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("dispatcher_owner", "resident_core")
        .put("policy_owner", "resident_core")
        .put("owner_pid", Process.myPid())
        .put("core_session", coreSessionId)
        .put("interaction_cycle", cycleRuntime.snapshot())
}
