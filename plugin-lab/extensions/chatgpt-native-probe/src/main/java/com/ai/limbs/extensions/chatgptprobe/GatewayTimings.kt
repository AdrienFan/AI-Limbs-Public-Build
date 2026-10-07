package com.ai.limbs.extensions.chatgptprobe

import org.json.JSONArray
import org.json.JSONObject

/** Bounded, monotonic local timings. Never store parameters, tokens or result contents. */
internal class GatewayTimings(private val clock: () -> Long = System::nanoTime) {
    private data class Sample(
        val tool: String, val points: MutableMap<String, Long>, var attempts: Int = 0,
        var capabilityId: String? = null, val costs: MutableMap<String, Long> = linkedMapOf(),
        val metrics: MutableMap<String, Long> = linkedMapOf()
    )
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

    @Synchronized fun capability(id: String, value: String) {
        // Only public capability identifiers may enter diagnostics, never arbitrary input text.
        if (value.length <= 256 && CAPABILITY_ID.matches(value)) samples[id]?.capabilityId = value
    }

    @Synchronized fun cost(id: String, field: String, elapsedNanos: Long) {
        if (field !in COST_FIELDS) return
        val sample = samples[id] ?: return
        sample.costs[field] = (sample.costs[field] ?: 0L) + elapsedNanos.coerceAtLeast(0L)
    }

    @Synchronized fun metrics(id: String, values: Map<String, Long>) {
        val sample = samples[id] ?: return
        values.forEach { (field, value) ->
            if (field in GatewayResultMetrics.FIELDS) sample.metrics[field] = value.coerceAtLeast(0L)
        }
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
            sample.capabilityId?.let { value.put("capability_id", it) }
            sample.costs.forEach { (field, nanos) -> value.put(field, nanos / 1_000_000L) }
            sample.metrics.forEach { (field, metric) -> value.put(field, metric) }
            put(value)
        }
    }
    companion object {
        private val CAPABILITY_ID = Regex("[A-Za-z0-9_.:@/-]+")
        private val COST_FIELDS = setOf("capability_resolve_ms", "host_invoke_ms",
            "result_adapt_ms", "cached_read_ms")
    }

}
