package com.ai.limbs.extensions.rdc.runtime

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Keep complete result bytes; a shortened display must never be the only copy. */
internal class RdcResultPager {
    private data class Saved(val text: String, val format: String, val success: Boolean,
        val sha256: String, val createdAt: Long)
    private val results = LinkedHashMap<String, Saved>()

    fun response(text: String, format: String, success: Boolean): JSONObject {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= INLINE_BYTES) return mcp(text, success)
        if (bytes.size > MAX_STORED_BYTES) return mcp(
            JSONObject().put("success", false).put("error", "Result exceeds 4 MiB; request a smaller source range").toString(), false)
        val cursor = UUID.randomUUID().toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val saved = Saved(text, format, success, digest, System.currentTimeMillis())
        synchronized(results) {
            expire()
            results[cursor] = saved
            while (results.size > MAX_RESULTS) results.remove(results.keys.first())
        }
        return renderPage(cursor, saved, 0)
    }

    fun page(cursor: String, offset: Int): JSONObject? {
        val saved = synchronized(results) { expire(); results[cursor] } ?: return null
        if (offset < 0 || offset > saved.text.length ||
            (offset in 1 until saved.text.length && Character.isLowSurrogate(saved.text[offset]))) return null
        return renderPage(cursor, saved, offset)
    }

    private fun renderPage(cursor: String, saved: Saved, offset: Int): JSONObject {
        var end = (offset + PAGE_CHARS).coerceAtMost(saved.text.length)
        if (end < saved.text.length && end > offset &&
            Character.isHighSurrogate(saved.text[end - 1]) && Character.isLowSurrogate(saved.text[end])) end--
        val next = if (end < saved.text.length) end else JSONObject.NULL
        val parameters = JSONObject().put("cursor", cursor).put("offset", next)
        val body = JSONObject()
            .put("paged", true).put("cursor", cursor).put("offset", offset)
            .put("next_offset", next).put("total_chars", saved.text.length)
            .put("format", saved.format).put("sha256", saved.sha256)
            .put("output", saved.text.substring(offset, end))
            .put("read_invocation", if (next == JSONObject.NULL) JSONObject.NULL else
                JSONObject().put("name", PAGE_TOOL).put("parameters", parameters))
            .put("transport", "start_process shell=operit; command is read_invocation JSON")
        return mcp(body.toString(), saved.success)
    }

    fun clear() = synchronized(results) { results.clear() }
    private fun expire() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        results.entries.removeAll { it.value.createdAt < cutoff }
    }
    private fun mcp(text: String, success: Boolean): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", !success)

    companion object {
        const val PAGE_TOOL = "ai_limbs.bridge.result_page"
        const val INLINE_BYTES = 12_000
        const val PAGE_CHARS = 4_000
        const val MAX_STORED_BYTES = 4 * 1024 * 1024
        private const val MAX_RESULTS = 4
        private const val TTL_MS = 10 * 60 * 1000L
    }
}
