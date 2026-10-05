package com.ai.limbs.extensions.chatgptprobe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal class MemoryGatewayStore : GatewayBlobStore {
    private val values = mutableMapOf<String, String>()
    var failNextWrite = false
    @Synchronized override fun read(name: String) = values[name]
    @Synchronized override fun write(name: String, value: String) {
        if (failNextWrite) { failNextWrite = false; throw java.io.IOException("Simulated disk failure") }
        values[name] = value
    }
    @Synchronized override fun delete(name: String) { values.remove(name) }
    @Synchronized override fun names() = values.keys.toList()
}

class GatewayResultsTest {
    @Test fun unicodePagesReassembleAndDoNotPageTheirOwnEnvelope() {
        val store = MemoryGatewayStore()
        val adapter = GatewayResults(store)
        val original = JSONObject().put("text", "汉😀".repeat(6000))
            .put("execution_policy", JSONObject().put("outcome", "ASK")).put("next_action", "obtain Host receipt")
        val first = adapter.adapt(original).getJSONObject("structuredContent")
        assertEquals("ASK", first.getJSONObject("execution_policy").getString("outcome"))
        assertEquals("obtain Host receipt", first.getString("next_action"))
        val cursor = first.getString("cursor")
        val reconstructed = StringBuilder(first.getString("output"))
        var page = first
        while (!page.isNull("next_offset")) {
            page = adapter.pageResult(cursor, page.getInt("next_offset")).getJSONObject("structuredContent")
            assertEquals(cursor, page.getString("cursor"))
            val text = page.getString("output")
            assertFalse(text.firstOrNull()?.isLowSurrogate() == true)
            assertFalse(text.lastOrNull()?.isHighSurrogate() == true)
            reconstructed.append(text)
        }
        assertEquals(original.toString(), reconstructed.toString())
        assertEquals(gatewayHash(original.toString()), first.getString("sha256"))
        assertEquals(1, store.names().size)
        assertThrows(IllegalArgumentException::class.java) { adapter.readResult(cursor, -1) }
    }

    @Test fun nativeImageIsSeparateFromJsonAndCanBeReadWithoutExecution() {
        val store = MemoryGatewayStore()
        val adapter = GatewayResults(store)
        val result = adapter.adapt(JSONObject().put("success", true).put("payload", JSONObject()
            .put("mcp_content", JSONArray().put(image(PNG)))))
        assertFalse(result.getBoolean("isError"))
        assertEquals("image", result.getJSONArray("content").getJSONObject(1).getString("type"))
        assertFalse(result.getJSONObject("structuredContent").toString().contains(PNG))
        val handle = result.getJSONObject("structuredContent").getJSONObject("media_delivery")
            .getJSONArray("attachments").getJSONObject(0).getString("media_id")
        assertEquals(PNG, adapter.readMedia(handle).getJSONArray("content").getJSONObject(0).getString("data"))
    }

    @Test fun invalidMediaDoesNotTurnACompletedActionIntoBusinessFailure() {
        val adapter = GatewayResults(MemoryGatewayStore())
        val result = adapter.adapt(JSONObject().put("success", true).put("error", JSONObject.NULL)
            .put("mcp_content", JSONArray().put(image("invalid%%%"))))
        assertFalse(result.getBoolean("isError"))
        assertTrue(result.getJSONObject("structuredContent").getJSONObject("media_delivery").getBoolean("partial"))
        assertEquals(1, result.getJSONArray("content").length())
    }

    @Test fun hostErrorsAndRefusalsKeepTheirMeaning() {
        val adapter = GatewayResults(MemoryGatewayStore())
        assertFalse(adapter.adapt(JSONObject().put("error", JSONObject.NULL)).getBoolean("isError"))
        assertTrue(adapter.adapt(JSONObject().put("success", false).put("next_action", "inspect policy")).getBoolean("isError"))
        val result = adapter.adapt(JSONObject().put("execution_policy", JSONObject().put("outcome", "FORBID")))
        assertTrue(result.getBoolean("isError"))
        assertEquals("FORBID", result.getJSONObject("structuredContent").getJSONObject("execution_policy").getString("outcome"))
    }

    @Test fun expirationAndTunnelBoundaryAreExplicit() {
        var time = 1000L
        val store = MemoryGatewayStore()
        val adapter = GatewayResults(store, now = { time }, binding = "tunnel-a")
        val cursor = adapter.adapt(JSONObject().put("text", "x".repeat(20_000))).getJSONObject("structuredContent").getString("cursor")
        assertThrows(GatewayResultUnavailable::class.java) { GatewayResults(store, now = { time }, binding = "tunnel-b").readResult(cursor, 0) }
        time += GatewayResults.CACHE_TTL_MS
        assertThrows(GatewayResultUnavailable::class.java) { adapter.readResult(cursor, 0) }
    }

    @Test fun oversizedResultReportsDeliveryFailureWithoutReexecution() {
        val output = GatewayResults(MemoryGatewayStore()).adapt(JSONObject().put("success", true).put("text", "a".repeat(4 * 1024 * 1024 + 1)))
        assertFalse(output.getBoolean("isError"))
        assertTrue(output.getJSONObject("structuredContent").getBoolean("execution_completed"))
        assertTrue(output.getJSONObject("structuredContent").has("result_delivery_error"))
    }

    private fun image(data: String) = JSONObject().put("type", "image").put("mimeType", "image/png").put("data", data)
    companion object { const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6GAAAAABJRU5ErkJggg==" }
}
