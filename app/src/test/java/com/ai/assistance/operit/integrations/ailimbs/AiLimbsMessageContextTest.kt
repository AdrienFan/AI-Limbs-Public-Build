package com.ai.assistance.operit.integrations.ailimbs

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiLimbsMessageContextTest {
    private fun request() = JSONObject().put("schema", 1).put("event", "user_message").put("context_id", "turn-1")
        .put("requested_elapsed_ms", 100L).put("deadline_elapsed_ms", 12100L)
    private fun ready(start: Long = 110L, context: String = "turn-1") = JSONObject().put("schema", 1)
        .put("context_id", context).put("status", "READY").put("image", JSONObject().put("width", 1))
        .put("freshness", JSONObject().put("method", "capture_request").put("requested_elapsed_ms", start).put("captured_elapsed_ms", 120L))
        .put("mcp_content", JSONArray().put(JSONObject().put("type", "image").put("mimeType", "image/jpeg").put("data", "AQID")))
    private fun provider(response: JSONObject) = AiLimbsMessageContext.Provider("context", "owner") { response.toString() }

    @Test fun absentProviderReportsInactiveWithoutAnImage() = runBlocking {
        val result = AiLimbsMessageContext.read(request(), emptyList()) { 130L }
        assertTrue(result.getBoolean("success")); assertFalse(result.getBoolean("active"))
        assertEquals(0, result.getJSONArray("contexts").length())
    }
    @Test fun freshResponseRetainsOneImageAndItsOwner() = runBlocking {
        val result = AiLimbsMessageContext.read(request(), listOf(provider(ready()))) { 130L }
        assertTrue(result.getBoolean("success")); assertTrue(result.getBoolean("active"))
        val context = result.getJSONArray("contexts").getJSONObject(0)
        assertEquals("owner", context.getString("owner_plugin_id"))
        assertEquals(1, context.getJSONArray("mcp_content").length())
    }
    @Test fun staleWrongIdentityAndDuplicatedPayloadAreExplicitFailures() = runBlocking {
        for (response in listOf(ready(90L), ready(context = "old-turn"), ready().put("image", JSONObject().put("data", "AQID")))) {
            val result = AiLimbsMessageContext.read(request(), listOf(provider(response))) { 130L }
            assertFalse(result.getBoolean("success")); assertFalse(result.getBoolean("active"))
            assertFalse(result.getJSONArray("contexts").getJSONObject(0).has("mcp_content"))
        }
    }
    @Test fun expiredDeadlineDoesNotInvokeProvider() = runBlocking {
        var calls = 0
        val provider = AiLimbsMessageContext.Provider("context", "owner") { calls++; ready().toString() }
        val result = AiLimbsMessageContext.read(request(), listOf(provider)) { 12101L }
        assertEquals(0, calls); assertFalse(result.getBoolean("success"))
    }
    @Test fun unmountedProviderFailureDoesNotRetryOrReturnPreview() = runBlocking {
        var calls = 0
        val provider = AiLimbsMessageContext.Provider("context", "owner") { calls++; error("Provider unmounted") }
        val result = AiLimbsMessageContext.read(request(), listOf(provider)) { 130L }
        assertEquals(1, calls); assertFalse(result.getBoolean("success"))
        assertFalse(result.getJSONArray("contexts").getJSONObject(0).has("mcp_content"))
    }
}
