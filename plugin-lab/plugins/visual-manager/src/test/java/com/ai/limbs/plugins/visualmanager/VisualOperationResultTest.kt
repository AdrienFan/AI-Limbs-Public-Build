package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualOperationResultTest {
    @Test fun unavailableCoreRetainsItsErrorInsteadOfMissingSuccess() {
        val response = JSONObject().put("ok", false).put("error_code", "RESIDENT_CORE_UNAVAILABLE")
            .put("error", "Resident Core connection is unavailable; recovery has been requested")
        val failure = assertThrows(VisualOperationFailure::class.java) {
            VisualOperationResult.requireSuccess(response)
        }
        assertEquals("RESIDENT_CORE_UNAVAILABLE", failure.code)
        assertEquals(response.getString("error"), failure.message)
        assertSame(response, failure.details)
    }
    @Test fun completedTransportCannotTurnAnInvalidPluginResultIntoSuccess() {
        val response = JSONObject().put("ok", true)
        val failure = assertThrows(VisualOperationFailure::class.java) {
            VisualOperationResult.requireSuccess(response)
        }
        assertEquals("VISUAL_RESULT_INVALID", failure.code)
    }
    @Test fun pluginCancellationPreservesItsOriginalReason() {
        val response = JSONObject().put("success", false).put("error_code", "NEEDS_PERMISSION")
            .put("error", "相机授权未完成")
        val failure = assertThrows(VisualOperationFailure::class.java) {
            VisualOperationResult.requireSuccess(response)
        }
        assertEquals("NEEDS_PERMISSION", failure.code)
        assertEquals("相机授权未完成", failure.message)
    }
    @Test fun validSuccessKeepsTheDashboardAndImagePayloadUnchanged() {
        val response = JSONObject().put("success", true).put("previews", JSONObject())
            .put("data", "encoded-image").put("session_id", "owned")
        assertSame(response, VisualOperationResult.requireSuccess(response))
        assertEquals("encoded-image", response.getString("data"))
        assertEquals("owned", response.getString("session_id"))
    }
    @Test fun transportFailureTakesPrecedenceEvenIfAnInconsistentPayloadSaysSuccess() {
        val response = JSONObject().put("ok", false).put("success", true)
            .put("error_code", "RESIDENT_CORE_UNAVAILABLE").put("error", "connection failed")
        val failure = assertThrows(VisualOperationFailure::class.java) {
            VisualOperationResult.requireSuccess(response)
        }
        assertEquals("RESIDENT_CORE_UNAVAILABLE", failure.code)
    }
}
