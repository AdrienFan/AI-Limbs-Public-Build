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
            assertEquals("capture", op)
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
