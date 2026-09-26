package com.ai.assistance.operit.plugins.center.isolation

import com.ai.assistance.operit.core.tools.system.resident.ResidentCoreDispatchWire
import org.json.JSONObject

/** Worker-side bridge back into the one authoritative Resident Core policy plane. */
internal class PluginRuntimeCoreClient(
    private val ownerCorePid: Int,
    private val ownerCoreSession: String
) {
    suspend fun invokeDelegated(
        pluginId: String,
        version: String,
        capabilityId: String,
        parameters: JSONObject
    ): JSONObject {
        val response = ResidentCoreDispatchWire.request(
            ownerCoreSession,
            ownerCorePid,
            "plugin_delegate",
            JSONObject()
                .put("plugin_id", pluginId)
                .put("version", version)
                .put("capability_id", capabilityId)
                .put("parameters", JSONObject(parameters.toString()))
        )
        check(response.optBoolean("success", false)) {
            response.optString("error", "Resident Core rejected plugin delegation")
        }
        val result = response.getJSONObject("result")
        check(result.getString("plugin_id") == pluginId) { "Delegated plugin identity changed" }
        check(result.getString("version") == version) { "Delegated plugin version changed" }
        check(result.getString("core_session") == ownerCoreSession) { "Resident Core session changed" }
        return result.getJSONObject("result")
    }
    fun attestPluginTrust(pluginId: String, version: String): JSONObject {
        val response = ResidentCoreDispatchWire.request(
            ownerCoreSession,
            ownerCorePid,
            "plugin_trust_attest",
            JSONObject()
                .put("plugin_id", pluginId)
                .put("version", version)
        )
        check(response.optBoolean("success", false)) {
            response.optString("error", "Resident Core rejected plugin trust attestation")
        }
        val result = response.getJSONObject("result")
        check(result.optBoolean("trusted", false)) { "Resident Core did not attest plugin trust" }
        check(result.getString("plugin_id") == pluginId) { "Attested plugin identity changed" }
        check(result.getString("version") == version) { "Attested plugin version changed" }
        check(result.getString("core_session") == ownerCoreSession) { "Resident Core session changed" }
        return result
    }

    fun describeService(

        pluginId: String,
        version: String,
        serviceId: String,
        minApi: Int?
    ): JSONObject {
        val payload = JSONObject().put("plugin_id", pluginId).put("version", version)
            .put("service_id", serviceId).put("min_api", minApi ?: JSONObject.NULL)
        val response = ResidentCoreDispatchWire.request(ownerCoreSession, ownerCorePid, "plugin_service_describe", payload)
        check(response.optBoolean("success", false)) { response.optString("error", "Core service describe failed") }
        return response.getJSONObject("result")
    }

    suspend fun invokeService(
        pluginId: String,
        version: String,
        serviceId: String,
        minApi: Int?,
        operation: String,
        parameters: JSONObject
    ): JSONObject {
        val payload = JSONObject().put("plugin_id", pluginId).put("version", version)
            .put("service_id", serviceId).put("min_api", minApi ?: JSONObject.NULL)
            .put("service_operation", operation).put("parameters", JSONObject(parameters.toString()))
        val response = ResidentCoreDispatchWire.request(ownerCoreSession, ownerCorePid, "plugin_service_invoke", payload)
        check(response.optBoolean("success", false)) { response.optString("error", "Core service invoke failed") }
        val result = response.getJSONObject("result")
        check(result.getString("core_session") == ownerCoreSession) { "Resident Core session changed" }
        return result.getJSONObject("result")
    }

}
