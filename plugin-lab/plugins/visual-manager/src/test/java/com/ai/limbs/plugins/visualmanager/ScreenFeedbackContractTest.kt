package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScreenFeedbackContractTest {
    private fun request() = JSONObject().put("schema", 1).put("event", "screen_interaction")
        .put("operation_id", "action-1").put("completed_elapsed_ms", 100L).put("deadline_elapsed_ms", 6100L)
    private fun frame(requested: Long = 101L, captured: Long = 102L) = JSONObject()
        .put("captured_at_ms", 1000L).put("freshness", JSONObject().put("method", "new_surface")
            .put("requested_elapsed_ms", requested).put("captured_elapsed_ms", captured))

    @Test fun newSurfaceRequestedAfterActionProvidesFreshnessProof() {
        ScreenFeedbackContract.validateRequest(request())
        ScreenFeedbackContract.requireFresh(request(), frame())
        assertEquals("action-1", ScreenFeedbackContract.response(request(), "READY").getString("operation_id"))
    }
    @Test fun historicalPreviewAndPreActionRequestsCannotClaimFreshness() {
        assertThrows(Exception::class.java) {
            ScreenFeedbackContract.requireFresh(request(), JSONObject().put("captured_at_ms", 1000L))
        }
        assertThrows(IllegalArgumentException::class.java) { ScreenFeedbackContract.requireFresh(request(), frame(99L)) }
        assertThrows(IllegalArgumentException::class.java) { ScreenFeedbackContract.requireFresh(request(), frame(102L, 101L)) }
    }
}
