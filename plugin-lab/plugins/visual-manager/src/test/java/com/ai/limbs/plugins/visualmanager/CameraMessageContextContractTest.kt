package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CameraMessageContextContractTest {
    private fun request() = JSONObject().put("schema", 1).put("event", "user_message").put("context_id", "turn-1")
        .put("requested_elapsed_ms", 100L).put("deadline_elapsed_ms", 12100L)
    private fun frame(start: Long = 110L, method: String = "capture_request") = JSONObject().put("captured_at_ms", 10000L)
        .put("freshness", JSONObject().put("method", method).put("frame_timestamp_ns", 12345L)
            .put("requested_elapsed_ms", start).put("captured_elapsed_ms", 120L))
    @Test fun currentCaptureProofIsAccepted() { CameraMessageContextContract.validate(request()); CameraMessageContextContract.requireFresh(request(), frame()) }
    @Test fun oldFrameAndPreviewProofAreRejected() {
        for (frame in listOf(frame(90L), frame(method = "preview"))) {
            try { CameraMessageContextContract.requireFresh(request(), frame); fail("Stale camera image accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
