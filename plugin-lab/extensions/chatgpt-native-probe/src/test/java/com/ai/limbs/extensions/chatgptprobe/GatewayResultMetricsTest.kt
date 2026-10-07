package com.ai.limbs.extensions.chatgptprobe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GatewayResultMetricsTest {
    @Test fun imageAndPagedTextScanCacheOnceWithoutExposingContents() {
        var tick = 0L
        val backing = MemoryGatewayStore()
        for (name in listOf("media_private_seed", "result_private_seed")) {
            backing.write(name, JSONObject().put("expires", 10_000L).put("binding", "")
                .put("value", "private cached body").toString())
        }
        val store = object : GatewayBlobStore {
            override fun names(): List<String> { tick += 1_000_000L; return backing.names() }
            override fun read(name: String): String? { tick += 1_000_000L; return backing.read(name) }
            override fun write(name: String, value: String) { tick += 2_000_000L; backing.write(name, value) }
            override fun delete(name: String) = backing.delete(name)
        }
        val metrics = GatewayResultMetrics { tick }
        val source = JSONObject().put("success", true).put("output", "x".repeat(13_000))
            .put("mcp_content", JSONArray().put(JSONObject().put("type", "image")
                .put("mimeType", "image/jpeg").put("data", "/9j/2Q==")))
        val adapter = GatewayResults(store, now = { 0L })
        val delivered = adapter.adapt(source, metrics)
        val costs = metrics.snapshot()
        assertFalse(delivered.getBoolean("isError"))
        assertTrue(delivered.getJSONObject("structuredContent").getBoolean("paged"))
        assertEquals(2, delivered.getJSONArray("content").length())
        assertEquals(1L, costs.getValue("cache_scan_passes"))
        assertEquals(2L, costs.getValue("cache_entries_scanned"))
        assertEquals(3L, costs.getValue("cache_scan_ms"))
        assertEquals(4L, costs.getValue("cache_write_ms"))
        // Adapter work also includes the one read of its newly saved first result page.
        assertEquals(8L, costs.getValue("result_adapter_work_ms"))
        assertEquals(0L, costs.getValue("result_adapter_wait_ms"))
        assertEquals(8L, costs.getValue("media_base64_chars"))
        assertTrue(costs.getValue("cache_plaintext_bytes_scanned") > 0)
        assertTrue(costs.getValue("structured_text_bytes") > 13_000)
        assertFalse(costs.toString().contains("private"))
        assertTrue(source.has("mcp_content"))
        assertFalse(delivered.getJSONObject("structuredContent").has("cache_scan_ms"))
    }

    @Test fun failedWriteRetainsMeasuredCostAndExistingFailureSemantics() {
        var tick = 0L
        val store = object : GatewayBlobStore {
            override fun names() = emptyList<String>()
            override fun read(name: String): String? = null
            override fun delete(name: String) = Unit
            override fun write(name: String, value: String) {
                tick += 5_000_000L
                throw java.io.IOException("simulated storage failure")
            }
        }
        val metrics = GatewayResultMetrics { tick }
        try {
            GatewayResults(store).adapt(JSONObject().put("output", "x".repeat(13_000)), metrics)
            fail("Expected the original storage error")
        } catch (expected: java.io.IOException) {
            assertEquals("simulated storage failure", expected.message)
        }
        assertEquals(5L, metrics.snapshot().getValue("cache_write_ms"))
        assertEquals(5L, metrics.snapshot().getValue("result_adapter_work_ms"))
        assertFalse(metrics.snapshot().containsKey("media_base64_chars"))
    }
}
