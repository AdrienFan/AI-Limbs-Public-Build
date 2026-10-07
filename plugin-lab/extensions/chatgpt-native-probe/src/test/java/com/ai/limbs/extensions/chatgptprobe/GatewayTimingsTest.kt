package com.ai.limbs.extensions.chatgptprobe

import org.junit.Assert.*
import org.junit.Test

class GatewayTimingsTest {
    @Test fun stageDurationsUseMonotonicClockAndExposeNoIdentifiers() {
        var tick = 2_000_000L
        val timings = GatewayTimings { tick }
        timings.accepted("private-request-id", "ai_limbs_capability_invoke", 0L)
        for (stage in listOf("started", "processed", "ready", "posting", "posted", "acked")) {
            tick += 3_000_000L
            timings.mark("private-request-id", stage)
        }
        val sample = timings.snapshot().getJSONObject(0)
        assertEquals(2L, sample.getLong("admission_ms"))
        for (field in listOf("queue_ms", "processing_ms", "ready_persist_ms", "delivery_wait_ms", "response_post_ms", "ack_persist_ms")) {
            assertEquals(3L, sample.getLong(field))
        }
        assertEquals(20L, sample.getLong("local_to_ack_ms"))
        assertEquals(1, sample.getInt("delivery_attempts"))
        assertFalse(sample.toString().contains("private-request-id"))
    }

    @Test fun boundedSamplesDoNotInventMissingStages() {
        val timings = GatewayTimings { 0L }
        repeat(20) { timings.accepted("id-$it", "ping", 0L) }
        assertEquals(16, timings.snapshot().length())
        assertTrue(timings.snapshot().getJSONObject(0).isNull("local_to_ack_ms"))
        timings.mark("id-0", "acked")
        assertEquals(16, timings.snapshot().length())
    }
    @Test fun latestPostAttemptClearsEarlierSetupAndIgnoresPrivateFields() {
        val timings = GatewayTimings { 0L }
        timings.accepted("private-id", "ai_limbs_capability_invoke", 0L)
        timings.metrics("private-id", mapOf("cache_scan_ms" to 2L))
        timings.networkMetrics("private-id", mapOf("post_dns_ms" to 10L, "post_upload_ms" to 20L))
        timings.networkMetrics("private-id", mapOf("post_upload_ms" to 3L, "private-host" to 999L))
        val sample = timings.snapshot().getJSONObject(0)
        assertFalse(sample.has("post_dns_ms"))
        assertEquals(3L, sample.getLong("post_upload_ms"))
        assertEquals(2L, sample.getLong("cache_scan_ms"))
        assertFalse(sample.toString().contains("private"))
    }

    @Test fun processingDetailsAreBoundedNumericAndAssociatedWithPublicCapability() {
        val timings = GatewayTimings { 0L }
        timings.accepted("private-id", "ai_limbs_capability_invoke", 0L)
        timings.capability("private-id", "tap")
        timings.cost("private-id", "host_invoke_ms", 1_500_000L)
        timings.cost("private-id", "host_invoke_ms", 1_500_000L)
        timings.cost("private-id", "arbitrary-input", 9_000_000L)
        timings.metrics("private-id", mapOf("cache_scan_ms" to 7L,
            "cache_entries_scanned" to 5L, "secret-record-name" to 123L))
        val sample = timings.snapshot().getJSONObject(0)
        assertEquals("tap", sample.getString("capability_id"))
        assertEquals(3L, sample.getLong("host_invoke_ms"))
        assertEquals(7L, sample.getLong("cache_scan_ms"))
        assertEquals(5L, sample.getLong("cache_entries_scanned"))
        assertFalse(sample.has("result_adapt_ms"))
        assertFalse(sample.has("arbitrary-input"))
        assertFalse(sample.toString().contains("private-id"))
        assertFalse(sample.toString().contains("secret-record-name"))
        timings.capability("private-id", "input with private body")
        assertEquals("tap", timings.snapshot().getJSONObject(0).getString("capability_id"))
        repeat(20) { timings.accepted("next-$it", "ping", 0L) }
        timings.metrics("private-id", mapOf("cache_scan_ms" to 10L))
        assertEquals(16, timings.snapshot().length())
    }

}
