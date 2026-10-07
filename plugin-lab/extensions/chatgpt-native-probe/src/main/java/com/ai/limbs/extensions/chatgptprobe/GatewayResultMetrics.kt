package com.ai.limbs.extensions.chatgptprobe

/** Request-local costs only. Never retain record names, parameters or cached contents. */
internal class GatewayResultMetrics(val clock: () -> Long = System::nanoTime) {
    private val nanos = linkedMapOf<String, Long>()
    private val counts = linkedMapOf<String, Long>()

    fun duration(field: String, value: Long) {
        if (field in DURATION_FIELDS) nanos[field] = (nanos[field] ?: 0L) + value.coerceAtLeast(0L)
    }

    fun count(field: String, value: Long) {
        if (field in COUNT_FIELDS) counts[field] = (counts[field] ?: 0L) + value.coerceAtLeast(0L)
    }

    fun <T> measure(field: String, block: () -> T): T {
        val started = clock()
        try { return block() } finally { duration(field, clock() - started) }
    }

    fun snapshot(): Map<String, Long> = linkedMapOf<String, Long>().apply {
        nanos.forEach { (field, value) -> put(field, value / 1_000_000L) }
        putAll(counts)
    }

    companion object {
        val DURATION_FIELDS = setOf("result_adapter_wait_ms", "result_adapter_work_ms",
            "cache_scan_ms", "cache_write_ms")
        val COUNT_FIELDS = setOf("cache_scan_passes", "cache_entries_scanned",
            "cache_plaintext_bytes_scanned", "media_base64_chars", "structured_text_bytes")
        val FIELDS = DURATION_FIELDS + COUNT_FIELDS
    }
}
