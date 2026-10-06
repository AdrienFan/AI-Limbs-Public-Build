package com.ai.limbs.extensions.chatgptprobe

import org.json.JSONArray
import org.json.JSONObject

/** Bounded, monotonic local timings. Never store parameters, tokens or result contents. */
internal class GatewayTimings(private val clock: () -> Long = System::nanoTime) {
    private data class Sample(val tool: String, val points: MutableMap<String, Long>, var attempts: Int = 0)
    private val samples = linkedMapOf<String, Sample>()

    @Synchronized fun accepted(id: String, tool: String, receivedAt: Long) {
        samples[id] = Sample(tool, mutableMapOf("received" to receivedAt, "accepted" to clock()))
        while (samples.size > 16) samples.remove(samples.keys.first())
    }

    @Synchronized fun mark(id: String, stage: String) {
        val sample = samples[id] ?: return
        val now = clock()
        if (stage == "posting") {
            sample.attempts++
            sample.points.putIfAbsent("first_post", now)
            sample.points.remove("posted")
        }
        sample.points[stage] = now
    }

    @Synchronized fun snapshot(): JSONArray = JSONArray().apply {
        samples.values.forEach { sample ->
            val value = JSONObject().put("tool", sample.tool).put("delivery_attempts", sample.attempts)
            fun duration(label: String, from: String, to: String) {
                val start = sample.points[from]
                val end = sample.points[to]
                value.put(label, if (start != null && end != null) (end - start).coerceAtLeast(0L) / 1_000_000L else JSONObject.NULL)
            }
            duration("admission_ms", "received", "accepted")
            duration("queue_ms", "accepted", "started")
            duration("processing_ms", "started", "processed")
            duration("ready_persist_ms", "processed", "ready")
            duration("delivery_wait_ms", "ready", "first_post")
            duration("response_post_ms", "posting", "posted")
            duration("ack_persist_ms", "posted", "acked")
            duration("local_to_ack_ms", "received", "acked")
            put(value)
        }
    }
}
