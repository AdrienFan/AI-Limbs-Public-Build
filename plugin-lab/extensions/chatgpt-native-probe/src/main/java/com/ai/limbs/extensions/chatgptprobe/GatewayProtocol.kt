package com.ai.limbs.extensions.chatgptprobe

import org.json.JSONArray
import org.json.JSONObject

internal object GatewayProtocol {
    val supportedVersions = setOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")

    fun validRequest(rpc: JSONObject): Boolean = rpc.optString("jsonrpc") == "2.0" &&
        rpc.opt("method") is String && rpc.getString("method").isNotBlank() &&
        (!rpc.has("params") || rpc.opt("params") is JSONObject) &&
        (!rpc.has("id") || rpc.opt("id") is String || rpc.opt("id") is Number)

    fun success(id: Any, result: JSONObject): JSONObject = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    fun error(id: Any?, code: Int, message: String): JSONObject = JSONObject().put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL).put("error", JSONObject().put("code", code).put("message", message))

    fun uncertain(id: Any, reason: String): JSONObject = success(id, JSONObject()
        .put("isError", true)
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Execution state is uncertain. Inspect domain state before repeating the action.")))
        .put("structuredContent", JSONObject().put("success", false).put("execution_state", "UNKNOWN")
            .put("reason", reason).put("automatic_reexecution", false)
            .put("next_action", "Query the domain task or device state; cancellation does not undo effects already applied.")))

    fun delivery(command: JSONObject, rpc: JSONObject? = null): JSONObject {
        val payload = JSONObject().put("request_id", command.getString("request_id")).put("channel", command.getString("channel"))
            .put("resp_code", if (command.optString("command_type") == "jsonrpc" && rpc == null) 202 else 200)
            .put("resp_type", when {
                command.optString("command_type") == "session_termination" -> "session_termination_response"
                rpc == null -> "notify_ack"
                else -> "jsonrpc_response"
            })
        if (rpc != null) payload.put("resp_json", rpc).put("resp_headers", JSONObject().put("Content-Type", JSONArray().put("application/json")))
        return payload
    }
}
