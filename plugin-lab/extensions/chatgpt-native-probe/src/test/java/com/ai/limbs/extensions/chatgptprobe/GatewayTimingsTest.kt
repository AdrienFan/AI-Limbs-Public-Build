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
}
