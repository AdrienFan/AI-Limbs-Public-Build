package com.ai.assistance.operit.plugins.self

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Host policy, deliberately outside module data and immutable program versions.
 * All methods run under SelfModuleStore's process/file lifecycle lock. */
internal class SelfModuleAuthorizationPolicy(
    root: File,
    private val clock: () -> Long,
    private val save: (File, JSONObject) -> Unit
) {
    private val file = File(root, "authorizations.json")
    private fun read() = if (file.exists()) JSONObject(file.readText()) else JSONObject()
        .put("grants", JSONObject()).put("processed_requests", JSONArray()).put("observed_at_ms", 0L)
    private fun observe(value: JSONObject): Long {
        val now = maxOf(clock(), value.getLong("observed_at_ms"))
        value.put("observed_at_ms", now)
        val grants = value.getJSONObject("grants")
        grants.keys().forEach { operation ->
            val grant = grants.getJSONObject(operation)
            if (grant.optString("status") == "ACTIVE" && grant.getString("mode") == "TIMED" && now >= grant.getLong("expires_at_ms"))
                grant.put("status", "EXPIRED")
        }
        save(file, value)
        return now
    }
    fun snapshot(module: JSONObject?): JSONObject {
        val value = read()
        // No policy file is created just for an empty/default display.
        if (file.exists()) observe(value)
        val all = JSONArray()
        val effective = JSONArray()
        value.getJSONObject("grants").keys().forEach { operation ->
            val grant = value.getJSONObject("grants").getJSONObject(operation)
            all.put(grant)
            if (module != null && grant.optString("status") == "ACTIVE" &&
                grant.getString("identity_id") == module.getString("identity_id") &&
                grant.getString("data_id") == module.getString("data_id")) effective.put(grant)
        }
        return JSONObject().put("grants", all).put("effective_grants", effective)
    }
    fun applied(requestId: String): Boolean {
        val ids = read().getJSONArray("processed_requests")
        return (0 until ids.length()).any { ids.getString(it) == requestId }
    }
    fun approve(module: JSONObject, operation: String, mode: String, duration: Long?, requestId: String, reason: String): JSONObject {
        val value = read()
        val now = observe(value)
        check(!applied(requestId)) { "SELF_REQUEST_TERMINAL" }
        val grant = JSONObject().put("operation", operation).put("mode", mode).put("status", "ACTIVE")
            .put("identity_id", module.getString("identity_id")).put("data_id", module.getString("data_id"))
            .put("approved_at_ms", now).put("expires_at_ms", if (mode == "TIMED") Math.addExact(now, Math.multiplyExact(requireNotNull(duration), 1000L)) else JSONObject.NULL)
            .put("request_id", requestId).put("reason", reason)
        value.getJSONObject("grants").put(operation, grant)
        value.getJSONArray("processed_requests").put(requestId)
        save(file, value)
        return JSONObject(grant.toString())
    }
    fun revoke(operation: String, reason: String): JSONObject {
        val value = read()
        val now = observe(value)
        val grant = value.getJSONObject("grants").optJSONObject(operation)
        grant?.put("status", "REVOKED")?.put("revoked_at_ms", now)?.put("revocation_reason", reason)
        save(file, value)
        return JSONObject().put("success", true).put("operation", operation).put("revoked", grant != null)
    }
}
