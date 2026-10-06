package com.ai.assistance.operit.integrations.ailimbs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiLimbsOperationFeedbackTest {
    private fun request() = JSONObject().put("schema", 1).put("event", "screen_interaction")
        .put("operation_id", "operation-1").put("completed_elapsed_ms", 100L)
    private fun response(status: String) = JSONObject().put("schema", 1)
        .put("operation_id", "operation-1").put("status", status)
    private fun ready(requested: Long = 101L) = response("READY")
        .put("freshness", JSONObject().put("method", "new_surface")
            .put("requested_elapsed_ms", requested).put("captured_elapsed_ms", 110L))
        .put("mcp_content", JSONArray().put(JSONObject().put("type", "image")
            .put("mimeType", "image/jpeg").put("data", "aW1hZ2U=")))

    @Test fun accessibilityActionsAreIncludedAndReadOnlyOperationsAreExcluded() {
        for (name in listOf("tap", "long_press", "click_element", "swipe", "set_input_text", "press_key",
            "start_app", "stop_app", "run_ui_subagent")) {
            assertTrue(name, AiLimbsOperationFeedback.requested(name, JSONObject()))
        }
        for (name in listOf("get_page_info", "capture_screenshot", "get_system_setting", "list_files", "browser_click")) {
            assertFalse(name, AiLimbsOperationFeedback.requested(name, JSONObject().put("screen_action", true)))
        }
    }

    @Test fun shellCommandsAreNeverGuessedFromTheirText() {
        assertFalse(AiLimbsOperationFeedback.requested("execute_shell", JSONObject().put("command", "input tap 1 2")))
        assertFalse(AiLimbsOperationFeedback.requested("execute_shell", JSONObject().put("command", "logcat | grep input")))
        assertTrue(AiLimbsOperationFeedback.requested("execute_shell", JSONObject().put("screen_action", true)))
        assertFalse(AiLimbsOperationFeedback.requested("execute_shell", JSONObject().put("screen_action", false)))
        assertThrows(IllegalArgumentException::class.java) {
            AiLimbsOperationFeedback.requested("execute_shell", JSONObject().put("screen_action", "true"))
        }
    }

    @Test fun inactiveSharingAddsNothingToTheOriginalResult() = runBlocking {
        val original = JSONObject().put("success", true).put("result", "original")
        var calls = 0
        val provider = AiLimbsOperationFeedback.Provider("feedback", "owner") { calls++; response("INACTIVE").toString() }
        assertSame(original, AiLimbsOperationFeedback.attach(original, request(), listOf(provider)))
        assertFalse(original.has("operation_feedback"))
        assertEquals(1, calls)
    }

    @Test fun oneCompletedOperationGetsOneFinalImageWithoutChangingItsOutcome() = runBlocking {
        val original = JSONObject().put("success", false).put("error", "Action partially failed")
            .put("events", JSONArray().put("action finished"))
        var calls = 0
        AiLimbsOperationFeedback.attach(original, request(), listOf(
            AiLimbsOperationFeedback.Provider("feedback", "owner") { calls++; ready().toString() }))
        assertEquals(1, calls)
        assertFalse(original.getBoolean("success"))
        assertEquals("Action partially failed", original.getString("error"))
        val feedback = original.getJSONArray("operation_feedback")
        assertEquals(1, feedback.length())
        assertEquals(1, feedback.getJSONObject(0).getJSONArray("mcp_content").length())
    }

    @Test fun staleFrameAndProviderFailuresAreSeparateFromActionSuccess() = runBlocking {
        for (provider in listOf(
            AiLimbsOperationFeedback.Provider("stale", "owner") { ready(99L).toString() },
            AiLimbsOperationFeedback.Provider("failed", "owner") { error("Shared screen stopped") },
            AiLimbsOperationFeedback.Provider("reported", "owner") {
                response("FAILED").put("error_code", "FRAME_FAILED").put("error", "Frame failed")
                    .put("mcp_content", ready().getJSONArray("mcp_content")).toString()
            })) {
            val original = JSONObject().put("success", true).put("result", "applied")
            AiLimbsOperationFeedback.attach(original, request(), listOf(provider))
            assertTrue(original.getBoolean("success"))
            assertEquals("applied", original.getString("result"))
            val feedback = original.getJSONArray("operation_feedback").getJSONObject(0)
            assertEquals("FAILED", feedback.getString("status"))
            assertFalse(feedback.has("mcp_content"))
        }
    }

    @Test fun callerCancellationIsNotConvertedIntoAnImageFailure() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                AiLimbsOperationFeedback.attach(JSONObject().put("success", true), request(), listOf(
                    AiLimbsOperationFeedback.Provider("cancelled", "owner") { throw CancellationException("Stopped") }))
            }
        }
    }
}
