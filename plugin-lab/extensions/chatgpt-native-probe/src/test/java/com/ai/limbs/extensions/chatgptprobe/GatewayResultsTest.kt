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
    @Test fun gatewayDiagnosticsAndNestedDomainEventsArePreserved() {
        val source = JSONObject().put("success", true)
            .put("events", JSONObject().put("last_error", "dns_resolution_failed")
                .put("last_diagnostic", JSONObject().put("stage", "dns")))
            .put("domain", JSONObject().put("events", JSONArray().put(JSONObject().put("eventId", "evt_1"))))
        val result = GatewayResults(MemoryGatewayStore()).adapt(source)
        val delivered = result.getJSONObject("structuredContent")
        assertJsonEquals(source, delivered, "preserved provider events")
        assertEquals(source.toString(), result.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun largeEventsUsePaginationWithoutDroppingTheirData() {
        val adapter = GatewayResults(MemoryGatewayStore())
        val events = JSONArray().put(JSONObject().put("text", "汉😀".repeat(6000)))
        val first = adapter.adapt(JSONObject().put("events", events)).getJSONObject("structuredContent")
        assertTrue(first.getBoolean("paged"))
        val text = StringBuilder(first.getString("output"))
        var page = first
        while (!page.isNull("next_offset")) {
            page = adapter.readResult(first.getString("cursor"), page.getInt("next_offset"))
            text.append(page.getString("output"))
        }
        assertJsonEquals(events, JSONObject(text.toString()).getJSONArray("events"), "paged events")
    }

    @Test fun sourceOutcomeSurvivesTopLevelClientClassification() {
        for (code in listOf("UBUNTU_COMMAND_EXIT_NONZERO", "UBUNTU_RUNTIME_NOT_RUNNING",
            "UBUNTU_COMMAND_TIMEOUT", "EXAMPLE_PROVIDER_REJECTED")) {
            val original = JSONObject().put("success", false).put("error_code", code)
                .put("error", "producer error").put("execution_state", "UNKNOWN")
                .put("automatic_reexecution", false)
                .put("next_action", JSONObject().put("inspect", "provider"))
            val delivered = GatewayResults(MemoryGatewayStore()).adapt(original)
            val client = JSONObject(delivered.toString())
            client.getJSONObject("structuredContent").put("error_code", "INVALID_ARGUMENT")
            val source = client.getJSONObject("structuredContent").getJSONObject("ai_limbs_outcome")
            val text = JSONObject(client.getJSONArray("content").getJSONObject(0).getString("text"))
            assertTrue(client.getBoolean("isError"))
            assertEquals(code, source.getString("error_code"))
            assertEquals(code, text.getString("error_code"))
            assertEquals(code, text.getJSONObject("ai_limbs_outcome").getString("error_code"))
            assertEquals("UNKNOWN", source.getString("execution_state"))
            assertFalse(source.getBoolean("automatic_reexecution"))
            assertEquals("provider", source.getJSONObject("next_action").getString("inspect"))
            assertFalse(original.has("ai_limbs_outcome"))
        }
    }

    @Test fun everyPagedTextBlockMatchesItsStructuredPageWithoutReexecution() {
        val adapter = GatewayResults(MemoryGatewayStore())
        val delivered = adapter.adapt(JSONObject().put("success", false)
            .put("error_code", "PROVIDER_TASK_FAILED").put("status", "COMPLETED")
            .put("exit_code", 7).put("output", "汉😀".repeat(8000)))
        val first = delivered.getJSONObject("structuredContent")
        assertEquals(first.toString(), delivered.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("PROVIDER_TASK_FAILED", first.getJSONObject("ai_limbs_outcome").getString("error_code"))
        val combined = StringBuilder(first.getString("output"))
        var page = first
        while (!page.isNull("next_offset")) {
            val read = adapter.pageResult(first.getString("cursor"), page.getInt("next_offset"))
            page = read.getJSONObject("structuredContent")
            assertEquals(page.toString(), read.getJSONArray("content").getJSONObject(0).getString("text"))
            assertFalse(read.getBoolean("isError"))
            combined.append(page.getString("output"))
        }
        val recovered = JSONObject(combined.toString())
        assertEquals("PROVIDER_TASK_FAILED", recovered.getString("error_code"))
        assertEquals("PROVIDER_TASK_FAILED", recovered.getJSONObject("ai_limbs_outcome").getString("error_code"))
        assertEquals("汉😀".repeat(8000), recovered.getString("output"))
    }

    @Test fun successTextIsJsonWithoutAnInventedFailureOutcome() {
        val source = JSONObject().put("success", true).put("output", "兰儿😀")
        val result = GatewayResults(MemoryGatewayStore()).adapt(source)
        assertEquals(result.getJSONObject("structuredContent").toString(),
            result.getJSONArray("content").getJSONObject(0).getString("text"))
        assertFalse(result.getJSONObject("structuredContent").has("ai_limbs_outcome"))
        assertFalse(result.getBoolean("isError"))
    }

    @Test fun pagedDomainFailureKeepsRecoveryFieldsInImmediateEnvelope() {
        val result = GatewayResults(MemoryGatewayStore()).adapt(JSONObject().put("success", false)
            .put("status", "PROCESS_EXITED").put("exit_code", -1)
            .put("error_code", "UBUNTU_PROCESS_EXITED").put("execution_state", "UNKNOWN")
            .put("automatic_reexecution", false).put("next_action", JSONObject().put("inspect", "runtime"))
            .put("output", "partial output".repeat(3000)))
        assertTrue(result.getBoolean("isError"))
        val first = result.getJSONObject("structuredContent")
        assertTrue(first.getBoolean("paged"))
        assertEquals("UBUNTU_PROCESS_EXITED", first.getString("error_code"))
        assertEquals("PROCESS_EXITED", first.getString("status"))
        assertEquals(-1, first.getInt("exit_code"))
        assertEquals("UNKNOWN", first.getString("execution_state"))
        assertFalse(first.getBoolean("automatic_reexecution"))
        assertEquals("runtime", first.getJSONObject("next_action").getString("inspect"))
    }
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
        // ASK is a policy refusal, so the delivered result includes the producer outcome namespace.
        // Compare the full current contract while still verifying every original Unicode character.
        val expectedOutcome = JSONObject()
            .put("execution_policy", original.getJSONObject("execution_policy"))
            .put("next_action", original.getString("next_action"))
        val expected = JSONObject(original.toString()).put("ai_limbs_outcome", expectedOutcome)
        val reassembled = reconstructed.toString()
        val restored = JSONObject(reassembled)
        assertJsonEquals(expected, restored, "reassembled result")
        assertEquals(original.getString("text"), restored.getString("text"))
        assertJsonEquals(expectedOutcome, first.getJSONObject("ai_limbs_outcome"), "first-page outcome")
        assertFalse(restored.getJSONObject("ai_limbs_outcome").has("error_code"))
        assertFalse(original.has("ai_limbs_outcome"))
        assertEquals(gatewayHash(reassembled), first.getString("sha256"))
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

    @Test fun mediaReadDiagnosticVariantsUseIdenticalImageBytes() {
        val adapter = GatewayResults(MemoryGatewayStore())
        val result = adapter.adapt(JSONObject().put("success", true)
            .put("mcp_content", JSONArray().put(image(JPEG).put("mimeType", "image/jpeg"))))
        val handle = result.getJSONObject("structuredContent").getJSONObject("media_delivery")
            .getJSONArray("attachments").getJSONObject(0).getString("media_id")
        val both = adapter.readMedia(handle)
        val contentOnly = adapter.readMedia(handle, contentOnly = true)
        assertTrue(both.has("structuredContent"))
        assertFalse(contentOnly.has("structuredContent"))
        assertEquals(both.getJSONArray("content").toString(), contentOnly.getJSONArray("content").toString())
        assertEquals(JPEG, contentOnly.getJSONArray("content").getJSONObject(0).getString("data"))
        assertFalse(contentOnly.getBoolean("isError"))
    }
    @Test fun jpegDeliveryKeepsTheImageAndRejectsAMissingEndMarker() {
        val adapter = GatewayResults(MemoryGatewayStore())
        fun result(data: String) = adapter.adapt(JSONObject().put("success", true)
            .put("mcp_content", JSONArray().put(image(data).put("mimeType", "image/jpeg"))))
        val delivered = result(JPEG)
        assertEquals(JPEG, delivered.getJSONArray("content").getJSONObject(1).getString("data"))
        val bytes = java.util.Base64.getDecoder().decode(JPEG)
        val truncated = result(java.util.Base64.getEncoder().encodeToString(bytes.copyOf(bytes.size - 2)))
        assertFalse(truncated.getBoolean("isError"))
        assertEquals(1, truncated.getJSONArray("content").length())
        assertTrue(truncated.getJSONObject("structuredContent").getJSONObject("media_delivery").getBoolean("partial"))
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

    @Test fun oversizedResultPropagatesPreparationFailureToTheEngine() {
        val store = MemoryGatewayStore()
        val source = JSONObject().put("success", true).put("text", "a".repeat(4 * 1024 * 1024 + 1))
        assertThrows(IllegalArgumentException::class.java) { GatewayResults(store).adapt(source) }
        assertTrue(source.getBoolean("success"))
        assertTrue(store.names().isEmpty())
    }

    @Test fun cacheWriteFailurePropagatesWithoutChangingTheHostResult() {
        val store = MemoryGatewayStore()
        store.failNextWrite = true
        val source = JSONObject().put("success", true).put("text", "x".repeat(20_000))
        assertThrows(java.io.IOException::class.java) { GatewayResults(store).adapt(source) }
        assertTrue(source.getBoolean("success"))
        assertTrue(store.names().isEmpty())
    }

    @Test fun warmCacheDoesNotReadUnrelatedBodiesAndExpirationReclaimsCapacity() {
        var clock = 0L
        var namesCalls = 0
        var readCalls = 0
        val memory = MemoryGatewayStore()
        val store = object : GatewayBlobStore {
            override fun names(): List<String> { namesCalls++; return memory.names() }
            override fun read(name: String): String? { readCalls++; return memory.read(name) }
            override fun write(name: String, value: String) = memory.write(name, value)
            override fun delete(name: String) = memory.delete(name)
        }
        val adapter = GatewayResults(store, now = { clock })
        repeat(64) { adapter.adapt(JSONObject().put("text", "x".repeat(13_000))) }
        assertEquals(1, namesCalls)
        // Only the newly saved first page is read per result; old bodies are never rescanned.
        assertEquals(64, readCalls)
        assertThrows(IllegalArgumentException::class.java) {
            adapter.adapt(JSONObject().put("text", "x".repeat(13_000)))
        }
        clock = GatewayResults.CACHE_TTL_MS
        val result = adapter.adapt(JSONObject().put("text", "x".repeat(13_000)))
        assertFalse(result.getBoolean("isError"))
        assertEquals(1, memory.names().size)
        assertEquals(1, namesCalls)
    }

    // Test compilation uses Android org.json, which does not expose JSONObject.similar().
    // Compare complete structures with supported APIs so object key order is irrelevant.
    private fun assertJsonEquals(expected: Any?, actual: Any?, path: String) {
        when (expected) {
            is JSONObject -> {
                assertTrue("$path must be an object", actual is JSONObject)
                val actualObject = actual as JSONObject
                val expectedKeys = expected.keys().asSequence().toSet()
                assertEquals("$path keys", expectedKeys, actualObject.keys().asSequence().toSet())
                for (key in expectedKeys) {
                    assertJsonEquals(expected.get(key), actualObject.get(key), "$path.$key")
                }
            }
            is JSONArray -> {
                assertTrue("$path must be an array", actual is JSONArray)
                val actualArray = actual as JSONArray
                assertEquals("$path length", expected.length(), actualArray.length())
                for (index in 0 until expected.length()) {
                    assertJsonEquals(expected.get(index), actualArray.get(index), "$path[$index]")
                }
            }
            else -> assertEquals("$path value", expected, actual)
        }
    }

    private fun image(data: String) = JSONObject().put("type", "image").put("mimeType", "image/png").put("data", data)
    companion object {
        const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6GAAAAABJRU5ErkJggg=="
        // A generated black 1x1 JPEG fixture; Android decoding is validated separately at runtime.
        const val JPEG = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD5/ooooA//2Q=="
    }
}
