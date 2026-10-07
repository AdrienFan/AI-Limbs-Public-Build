package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject

internal object VisualActionMetadata {
    fun refresh(meta: JSONObject, observedElapsedMs: Long): JSONObject {
        val operation = meta.optJSONObject("last_operation") ?: return meta
        val completed = operation.getLong("completed_elapsed_ms")
        val captured = meta.optJSONObject("freshness")?.optLong("captured_elapsed_ms", -1L) ?: -1L
        operation.put("age_ms", (observedElapsedMs - completed).coerceAtLeast(0L))
            .put("frame_after_action", if (captured < 0) JSONObject.NULL else captured >= completed)
        return meta
    }
}
