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
