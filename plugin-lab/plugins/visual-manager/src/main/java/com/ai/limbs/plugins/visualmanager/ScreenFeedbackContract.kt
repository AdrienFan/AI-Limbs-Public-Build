package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject

/** The clocks in this contract are Android elapsedRealtime, shared across Host/Core/Worker. */
internal object ScreenFeedbackContract {
    fun validateRequest(request: JSONObject) {
        require(request.getInt("schema") == 1 && request.getString("event") == "screen_interaction") {
            "Unsupported operation feedback request"
        }
        require(request.getString("operation_id").isNotBlank()) { "Missing operation identity" }
        require(request.getLong("completed_elapsed_ms") >= 0L) { "Invalid operation completion time" }
        require(request.getLong("deadline_elapsed_ms") > request.getLong("completed_elapsed_ms")) {
            "Invalid operation feedback deadline"
        }
    }

    fun requireFresh(request: JSONObject, frame: JSONObject) {
        val freshness = frame.getJSONObject("freshness")
        require(freshness.getString("method") == "new_surface") { "Host did not provide a fresh frame" }
        require(freshness.getLong("requested_elapsed_ms") >= request.getLong("completed_elapsed_ms")) {
            "Frame request predates the operation"
        }
        require(freshness.getLong("captured_elapsed_ms") >= freshness.getLong("requested_elapsed_ms")) {
            "Frame capture predates its request"
        }
        require(frame.getLong("captured_at_ms") > 0L) { "Missing frame acquisition time" }
    }

    fun response(request: JSONObject, status: String) = JSONObject().put("schema", 1)
        .put("operation_id", request.getString("operation_id")).put("status", status)
}
