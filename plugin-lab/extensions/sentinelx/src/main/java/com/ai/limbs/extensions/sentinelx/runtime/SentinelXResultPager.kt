package com.ai.limbs.extensions.sentinelx.runtime

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Retains bounded bridge results so the Hub never has to carry the same large body twice. */
internal class SentinelXResultPager {
    private data class SavedResult(
        val content: String,
        val createdAt: Long,
        val sha256: String
    )

    private val results = LinkedHashMap<String, SavedResult>()

    fun inline(content: String): Boolean =
        content.toByteArray(StandardCharsets.UTF_8).size <= MAX_INLINE_BYTES

    fun canStore(content: String): Boolean =
        content.toByteArray(StandardCharsets.UTF_8).size <= MAX_STORED_BYTES

    fun store(content: String): JSONObject {
        require(canStore(content)) { "Bridge result exceeds the bounded result store" }
        val cursor = UUID.randomUUID().toString()
        val saved = SavedResult(content, System.currentTimeMillis(), sha256(content))
        synchronized(results) {
            expire()
            results[cursor] = saved
            while (results.size > MAX_RESULTS) {
                results.remove(results.keys.first())
            }
        }
        return page(cursor, 0)!!
    }

    fun page(cursor: String, offset: Int): JSONObject? {
        val saved = synchronized(results) {
            expire()
            results[cursor]
        } ?: return null
        if (offset < 0 || offset > saved.content.length ||
            (offset > 0 && offset < saved.content.length &&
                Character.isLowSurrogate(saved.content[offset]))) return null
        var end = (offset + PAGE_CHARS).coerceAtMost(saved.content.length)
        if (end < saved.content.length && end > offset &&
            Character.isHighSurrogate(saved.content[end - 1]) &&
            Character.isLowSurrogate(saved.content[end])) end--
        val next = if (end < saved.content.length) end else JSONObject.NULL
        return JSONObject()
            .put("output", saved.content.substring(offset, end))
            .put("bridge_result", JSONObject()
                .put("paged", true)
                .put("cursor", cursor)
                .put("offset", offset)
                .put("next_offset", next)
                .put("total_chars", saved.content.length)
                .put("sha256", saved.sha256)
                .put("read_tool", "ai_limbs.bridge.result_page"))
    }

    fun clear() = synchronized(results) { results.clear() }

    private fun expire() {
        val cutoff = System.currentTimeMillis() - RESULT_TTL_MS
        results.entries.removeAll { it.value.createdAt < cutoff }
    }

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_INLINE_BYTES = 12_000
        const val MAX_STORED_BYTES = 4 * 1024 * 1024
        const val MAX_RESULTS = 4
        const val RESULT_TTL_MS = 10 * 60 * 1000L
        const val PAGE_CHARS = 4_000
    }
}
