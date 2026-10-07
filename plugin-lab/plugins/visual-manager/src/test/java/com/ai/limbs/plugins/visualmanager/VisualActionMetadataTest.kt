package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualActionMetadataTest {
    @Test fun serializedPreviewRetainsActionAndRecomputesAgeWithoutForgingFrameFreshness() {
        val frame = JSONObject().put("frame_id", "original").put("freshness", JSONObject().put("captured_elapsed_ms", 500L))
            .put("last_operation", JSONObject().put("operation_id", "tap-1").put("completed_elapsed_ms", 400L))
        val reopened = JSONObject(VisualActionMetadata.refresh(frame, 600).toString())
        VisualActionMetadata.refresh(reopened, 1400)
        assertEquals("tap-1", reopened.getJSONObject("last_operation").getString("operation_id"))
        assertEquals(1000L, reopened.getJSONObject("last_operation").getLong("age_ms"))
        assertTrue(reopened.getJSONObject("last_operation").getBoolean("frame_after_action"))
        assertEquals(500L, reopened.getJSONObject("freshness").getLong("captured_elapsed_ms"))
    }
    @Test fun preActionFrameAndUnknownFreshnessCannotBePresentedAsPostAction() {
        val frame = JSONObject().put("freshness", JSONObject().put("captured_elapsed_ms", 399L))
            .put("last_operation", JSONObject().put("completed_elapsed_ms", 400L))
        assertFalse(VisualActionMetadata.refresh(frame, 600).getJSONObject("last_operation").getBoolean("frame_after_action"))
        frame.remove("freshness")
        assertTrue(VisualActionMetadata.refresh(frame, 600).getJSONObject("last_operation").isNull("frame_after_action"))
    }
}
