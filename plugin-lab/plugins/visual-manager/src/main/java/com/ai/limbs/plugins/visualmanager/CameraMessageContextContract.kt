package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject

internal object CameraMessageContextContract {
    fun validate(request: JSONObject) {
        require(request.getInt("schema") == 1 && request.getString("event") == "user_message") { "Unsupported message context request" }
        require(request.getString("context_id").isNotBlank()) { "Missing context identity" }
        require(request.getLong("requested_elapsed_ms") >= 0L &&
            request.getLong("deadline_elapsed_ms") > request.getLong("requested_elapsed_ms")) { "Invalid context deadline" }
    }
    fun requireFresh(request: JSONObject, frame: JSONObject) {
        val proof = frame.getJSONObject("freshness")
        require(proof.getString("method") == "capture_request" && proof.getLong("frame_timestamp_ns") > 0L) { "Missing current capture identity" }
        require(proof.getLong("requested_elapsed_ms") >= request.getLong("requested_elapsed_ms") &&
            proof.getLong("captured_elapsed_ms") >= proof.getLong("requested_elapsed_ms") &&
            proof.getLong("captured_elapsed_ms") < request.getLong("deadline_elapsed_ms")) { "Camera context is stale" }
        require(frame.getLong("captured_at_ms") > 0L) { "Missing capture time" }
    }
    fun response(request: JSONObject, status: String) = JSONObject().put("schema", 1)
        .put("context_id", request.getString("context_id")).put("status", status)
}
