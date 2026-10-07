package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.fail

class VisualCaptureEvidenceTest {
    private fun status() = JSONObject().put("session_id", "owned").put("active", true).put("projection_ready", true)
        .put("state", "READY").put("capture", JSONObject().put("active", true).put("frame_available", true)
            .put("producer_error", JSONObject.NULL).put("captured_content_visible", true)
            .put("geometry", JSONObject().put("geometry_id", "g")))
    @Test fun validStaticSourceAndUnknownPlatformVisibilityAreAccepted() {
        VisualCaptureEvidence.requireActive(status(), "owned", "g")
        val unknown = status(); unknown.getJSONObject("capture").put("captured_content_visible", JSONObject.NULL)
        VisualCaptureEvidence.requireActive(unknown, "owned", "g")
    }
    @Test fun staleCacheCannotHideStoppedInvisibleFailedOrChangedSource() {
        val bad = listOf(status().put("active", false), status().put("projection_ready", false),
            status().put("state", "STARTING"), status().put("session_id", "another")) +
            listOf("active" to false, "frame_available" to false, "captured_content_visible" to false,
                "producer_error" to "broken").map { (key, value) -> status().apply { getJSONObject("capture").put(key, value) } } +
            status().apply { getJSONObject("capture").getJSONObject("geometry").put("geometry_id", "rotated") } +
            status().apply { getJSONObject("capture").remove("producer_error") }
        for (snapshot in bad) {
            try { VisualCaptureEvidence.requireActive(snapshot, "owned", "g"); fail("Invalid source accepted") }
            catch (_: Exception) { }
        }
    }
}
