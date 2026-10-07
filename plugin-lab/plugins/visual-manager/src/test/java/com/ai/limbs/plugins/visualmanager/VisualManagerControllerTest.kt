package com.ai.limbs.plugins.visualmanager

import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessRuntimeLogger
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualManagerControllerTest {
    @Test fun automaticNativeObservationUsesUiWithoutOpeningScreenHardware() = runBlocking {
        val f = Fixture { id, op, _ ->
            assertEquals("host.ui.automation@1", id); assertEquals("snapshot", op)
            JSONObject().put("success", true).put("result", JSONObject().put("packageName", "native.app")
                .put("uiElements", JSONObject().put("className", "android.widget.TextView").put("text", "可读文字")))
        }
        try {
            val result = f.controller.call("observe", JSONObject())
            assertTrue(result.getBoolean("success")); assertEquals("ui", result.getJSONObject("visual_mode").getString("mode"))
            assertTrue(result.getJSONObject("page").has("snapshot_id")); assertFalse(result.has("mcp_content"))
            assertEquals(listOf("host.ui.automation@1/snapshot"), f.calls)
        } finally { f.scope.cancel() }
    }
    @Test fun treeFailureCannotTriggerAutomaticVisualFallback() = runBlocking {
        for (response in listOf(JSONObject().put("success", false).put("error", "permission denied"),
            JSONObject().put("success", true).put("result", JSONObject()))) {
            val f = Fixture { id, op, _ ->
                assertEquals("host.ui.automation@1", id); assertEquals("snapshot", op); response
            }
            try {
                val result = f.controller.call("observe", JSONObject())
                assertFalse(result.getBoolean("success")); assertEquals(1, f.calls.size)
                assertFalse(result.has("mcp_content"))
            } finally { f.scope.cancel() }
        }
    }
    @Test fun explicitVisualModeSkipsUiAndNeverStartsAnInactiveSession() = runBlocking {
        val f = Fixture { id, op, _ ->
            assertEquals("host.screen.session@1", id); assertEquals("status", op)
            JSONObject().put("active", false).put("projection_ready", false)
        }
        try {
            val result = f.controller.call("observe", JSONObject().put("mode", "visual"))
            assertFalse(result.getBoolean("success")); assertEquals(1, f.calls.size)
            assertTrue(result.getString("error").contains("SCREEN_SESSION_REQUIRED"))
        } finally { f.scope.cancel() }
    }
    @Test fun sparseSurfaceModeRequiresExistingSessionAndCannotAutoAuthorize() = runBlocking {
        val f = Fixture { id, op, _ -> when (op) {
            "snapshot" -> JSONObject().put("success", true).put("result", JSONObject().put("uiElements",
                JSONObject().put("className", "android.view.SurfaceView").put("bounds", "[0,0][100,100]")))
            "status" -> { assertEquals("host.screen.session@1", id); JSONObject().put("active", false).put("projection_ready", false) }
            else -> error("Must not start or request consent")
        } }
        try {
            val result = f.controller.call("observe", JSONObject())
            assertFalse(result.getBoolean("success")); assertEquals(2, f.calls.size)
        } finally { f.scope.cancel() }
    }
    @Test fun stopAfterACompletedTapCannotEraseTheActionOrDeliverAnInvalidatedFrame() {
        val result = JSONObject().put("action_success", true).put("operation_id", "once")
            .put("observation_success", true).put("frame", JSONObject()).put("preview", JSONObject())
            .put("mcp_content", JSONArray()).put("wait_success", true).put("visual_wait", JSONObject())
        val stopped = observationStoppedAfterAction(result)
        assertTrue(stopped.getBoolean("action_success")); assertEquals("once", stopped.getString("operation_id"))
        assertFalse(stopped.getBoolean("observation_success")); assertFalse(stopped.getBoolean("automatic_reexecution"))
        for (key in listOf("frame", "preview", "mcp_content", "wait_success", "visual_wait")) assertFalse(stopped.has(key))
    }
    @Test fun invalidObservationSettingsAreRejectedBeforeAnyTapOrHostCall() = runBlocking {
        for (request in listOf(JSONObject().put("observe_mode", "invalid"),
            JSONObject().put("timeout_ms", 15001), JSONObject().put("stable_ratio", 0.5),
            JSONObject().put("region_width", 0.001))) {
            val f = Fixture { _, _, _ -> error("Must validate before action") }
            try {
                val result = f.controller.call("tap_on_frame", request.put("frame_id", "unknown").put("x", 0.5).put("y", 0.5))
                assertFalse(result.getBoolean("success")); assertTrue(f.calls.isEmpty())
            } finally { f.scope.cancel() }
        }
    }
    @Test fun waitWithoutABaselineCannotStartAProjectionOrReadACachedImage() = runBlocking {
        for (name in listOf("wait_for_visual_change", "wait_until_stable")) {
            val f = Fixture { _, _, _ -> error("Wait must not open sessions") }
            try {
                val result = f.controller.call(name, JSONObject().put("frame_id", "unknown"))
                assertFalse(result.getBoolean("success")); assertTrue(f.calls.isEmpty())
                assertFalse(result.has("mcp_content"))
            } finally { f.scope.cancel() }
        }
    }
    @Test fun readOnlyFrameCannotRequestAnArchiveThroughInternalCalls() = runBlocking {
        val f = Fixture { _, _, _ -> error("Must reject before invoking Host") }
        try {
            val result = f.controller.call("get_frame", JSONObject().put("kind", "screen")
                .put("session_id", "owned").put("save", true))
            assertFalse(result.getBoolean("success"))
            assertTrue(result.getString("error").contains("frame save=true"))
            assertTrue(f.calls.isEmpty())
        } finally { f.scope.cancel() }
    }

    private fun contextRequest() = JSONObject().put("schema", 1).put("event", "user_message").put("context_id", "turn-1")
        .put("requested_elapsed_ms", 100L).put("deadline_elapsed_ms", 12100L)

    @Test fun inactiveCameraContextNeverOpensHardwareOrRequestsPermission() = runBlocking {
        val f = Fixture { id, op, _ ->
            assertEquals("host.camera.session@1", id); assertEquals("status", op)
            JSONObject().put("active", false).put("sessions", JSONArray())
        }
        try {
            val response = f.controller.readMessageContext(contextRequest())
            assertEquals("INACTIVE", response.getString("status"))
            assertFalse(response.has("mcp_content")); assertEquals(1, f.calls.size)
        } finally { f.scope.cancel() }
    }

    @Test fun cameraContextCaptureFailureReturnsNoCachedImageAndDoesNotRetry() = runBlocking {
        val f = Fixture { id, op, p ->
            assertEquals("host.camera.session@1", id)
            when (op) {
                "status" -> JSONObject().put("active", true).put("sessions", JSONArray().put(JSONObject().put("session_id", "owned")))
                "frame" -> {
                    assertEquals("owned", p.getString("session_id")); assertEquals(12100L, p.getLong("deadline_elapsed_ms"))
                    error("Camera stopped")
                }
                else -> error("Must not start or stop camera")
            }
        }
        try {
            val response = f.controller.readMessageContext(contextRequest())
            assertEquals("FAILED", response.getString("status")); assertFalse(response.has("mcp_content"))
            assertEquals(2, f.calls.size)
        } finally { f.scope.cancel() }
    }

    private fun feedbackRequest() = JSONObject().put("schema", 1).put("event", "screen_interaction")
        .put("operation_id", "action-1").put("completed_elapsed_ms", 100L).put("deadline_elapsed_ms", 6100L)

    @Test fun inactiveScreenFeedbackNeverRequestsConsentOrCapturesAnything() = runBlocking {
        for (active in listOf(false, true)) {
            val f = Fixture { id, op, _ ->
                assertEquals("host.screen.session@1", id)
                assertEquals("status", op)
                JSONObject().put("active", active).put("projection_ready", false).put("sessions", JSONArray())
            }
            try {
                val response = f.controller.postActionFeedback(feedbackRequest())
                assertEquals("INACTIVE", response.getString("status"))
                assertFalse(response.has("mcp_content"))
                assertEquals(1, f.calls.size)
            } finally { f.scope.cancel() }
        }
    }

    @Test fun stoppedDuringFeedbackReturnsFailureWithoutRetryOrOldPreview() = runBlocking {
        val f = Fixture { id, op, p ->
            assertEquals("host.screen.session@1", id)
            when (op) {
                "status" -> JSONObject().put("active", true).put("projection_ready", true)
                    .put("sessions", JSONArray().put(JSONObject().put("session_id", "owned").put("state", "READY")))
                "frame" -> {
                    assertTrue(p.getBoolean("fresh"))
                    assertEquals("owned", p.getString("session_id"))
                    error("Shared screen stopped during capture")
                }
                else -> error("Feedback must not open or stop sessions")
            }
        }
        try {
            val response = f.controller.postActionFeedback(feedbackRequest())
            assertEquals("FAILED", response.getString("status"))
            assertEquals("Shared screen stopped during capture", response.getString("error"))
            assertFalse(response.has("mcp_content"))
            assertEquals(listOf("host.screen.session@1/status", "host.screen.session@1/frame"), f.calls)
        } finally { f.scope.cancel() }
    }

    private class Fixture(val response: (String, String, JSONObject) -> JSONObject) {
        val calls = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logger = object : InProcessRuntimeLogger {
            override fun d(tag: String, message: String) = 0
            override fun i(tag: String, message: String) = 0
            override fun w(tag: String, message: String) = 0
            override fun w(tag: String, message: String, error: Throwable) = 0
            override fun e(tag: String, message: String) = 0
            override fun e(tag: String, message: String, error: Throwable) = 0
        }
        val host = Proxy.newProxyInstance(InProcessPluginUiHost::class.java.classLoader,
            arrayOf(InProcessPluginUiHost::class.java)) { _, method, args ->
            when (method.name) {
                "getPluginId" -> VISUAL_PLUGIN_ID
                "getLogger" -> logger
                "getScope" -> scope
                "invokeHostCapability" -> {
                    val id = args!![0] as String
                    val p = JSONObject(args[1] as String)
                    val op = p.getString("operation")
                    calls += "$id/$op"
                    response(id, op, p).toString()
                }
                else -> error("Unexpected Host access: ${method.name}")
            }
        } as InProcessPluginUiHost
        val controller = VisualManagerController(host)
    }

    @Test fun missingCameraPermissionCannotReportStartedOrOpenHardware() = runBlocking {
        val f = Fixture { _, op, _ -> when (op) {
            "status" -> JSONObject().put("active", false).put("sessions", JSONArray())
            "check" -> JSONObject().put("permission_granted", false)
            else -> error("Hardware must not be opened")
        } }
        try {
            val result = f.controller.call("start", JSONObject().put("kind", "camera").put("source_id", "0"))
            assertFalse(result.getBoolean("success"))
            assertEquals("NEEDS_PERMISSION", result.getString("error_code"))
            assertEquals(listOf("host.camera.session@1/status", "host.permission@1/check"), f.calls)
        } finally { f.scope.cancel() }
    }

    @Test fun deniedConsentIsAVisibleFailureAndIsNotRetried() = runBlocking {
        val f = Fixture { _, op, _ ->
            assertEquals("request", op)
            JSONObject().put("permission_granted", false).put("state", "DENIED")
        }
        try {
            val result = f.controller.call("permission", JSONObject().put("operation", "request"))
            assertFalse(result.getBoolean("success"))
            assertEquals("NEEDS_PERMISSION", result.getString("error_code"))
            assertEquals(1, f.calls.size)
        } finally { f.scope.cancel() }
    }

    @Test fun oneFailedStopCannotBePresentedAsAllStopped() = runBlocking {
        val f = Fixture { id, op, _ ->
            assertEquals("stop", op)
            if (id == "host.camera.session@1") error("Camera release failed")
            JSONObject().put("stopped", JSONArray().put("screen-session")).put("active", false)
        }
        try {
            val result = f.controller.call("stop", JSONObject().put("kind", "all"))
            assertFalse(result.getBoolean("success"))
            assertEquals("STOP_INCOMPLETE", result.getString("error_code"))
            val details = result.getJSONObject("details")
            assertFalse(details.getJSONObject("screen").getBoolean("active"))
            assertFalse(details.getJSONObject("camera").getBoolean("success"))
            assertEquals(2, f.calls.size)
        } finally { f.scope.cancel() }
    }

    @Test fun transportOkWithCancelledScreenshotDoesNotSaveOrPreview() = runBlocking {
        val f = Fixture { id, op, _ ->
            assertEquals("host.screen.capture@1", id)
            assertEquals("capture_frame", op)
            JSONObject().put("ok", true).put("success", false).put("path", "").put("error", "User cancelled")
        }
        try {
            val result = f.controller.call("capture", JSONObject().put("kind", "screen").put("source_id", "display:0"))
            assertFalse(result.getBoolean("success"))
            assertEquals("User cancelled", result.getString("error"))
            assertFalse(result.has("managed_asset"))
            assertFalse(result.has("preview"))
            assertEquals(1, f.calls.size)
        } finally { f.scope.cancel() }
    }

    @Test fun activeCameraMustBeStoppedBeforeOpeningAnotherSource() = runBlocking {
        val f = Fixture { _, op, _ ->
            assertEquals("status", op)
            JSONObject().put("active", true).put("sessions", JSONArray().put(JSONObject().put("session_id", "owned")))
        }
        try {
            val result = f.controller.call("start", JSONObject().put("kind", "camera").put("source_id", "1"))
            assertFalse(result.getBoolean("success"))
            assertEquals(1, f.calls.size)
        } finally { f.scope.cancel() }
    }


    @Test fun failureStateIsPublishedImmediatelyWithoutAQueryTimer() = runBlocking {
        val f = Fixture { _, _, _ -> JSONObject().put("permission_granted", false).put("state", "DENIED") }
        try {
            val result = f.controller.call("permission", JSONObject().put("operation", "request"))
            assertFalse(result.getBoolean("success"))
            val signal = JSONObject(f.controller.stateProvider.stateJson.value!!)
            assertTrue(signal.getLong("revision") > 0L)
            assertEquals(1, f.calls.size)
        } finally { f.scope.cancel() }
    }
}
