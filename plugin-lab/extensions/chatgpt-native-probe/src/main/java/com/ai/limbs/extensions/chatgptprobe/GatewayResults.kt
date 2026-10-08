package com.ai.limbs.extensions.chatgptprobe

import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class GatewayResultUnavailable(message: String) : IllegalStateException(message)

internal class GatewayResults(
    private val store: GatewayBlobStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val binding: String = "",
    private val validImage: (ByteArray, String) -> Boolean = { bytes, mime ->
        when (mime) {
            "image/png" -> bytes.size >= 24 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            // Convert signed bytes explicitly: unary minus on Byte produces Int in Kotlin.
            "image/jpeg" -> bytes.size >= 4 && bytes[0].toInt() == -1 && bytes[1].toInt() == -40 && bytes[bytes.size - 2].toInt() == -1 && bytes.last().toInt() == -39
            else -> false
        }
    }
) {
    private data class CachedSize(val expires: Long, val bytes: Long)
    private var cacheIndex: MutableMap<String, CachedSize>? = null

    fun adapt(result: JSONObject, metrics: GatewayResultMetrics = GatewayResultMetrics()): JSONObject {
        val queuedAt = metrics.clock()
        return synchronized(this) {
            metrics.duration("result_adapter_wait_ms", metrics.clock() - queuedAt)
            metrics.measure("result_adapter_work_ms") { adaptLocked(result, metrics) }
        }
    }

    private fun adaptLocked(result: JSONObject, metrics: GatewayResultMetrics): JSONObject {
        val clean = JSONObject(result.toString())
        val content = JSONArray()
        val mediaErrors = JSONArray()
        val handles = JSONArray()
        val seen = mutableSetOf<String>()
        var encodedSize = 0
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    // events is ordinary provider data too. Deleting it hid gateway diagnostics
                    // and domain event lists; retain it and apply normal pagination/media limits.
                    val attachments = value.optJSONArray("mcp_content")
                    if (value.has("mcp_content") && !value.isNull("mcp_content") && attachments == null) {
                        mediaErrors.put(JSONObject().put("reason", "mcp_content must be an array of image blocks"))
                    }
                    value.remove("mcp_content")
                    if (attachments != null) for (index in 0 until attachments.length()) {
                        try {
                            val item = attachments.getJSONObject(index)
                            require(item.getString("type") == "image") { "Unsupported media type" }
                            val mime = item.getString("mimeType")
                            require(mime == "image/png" || mime == "image/jpeg") { "Unsupported image MIME type" }
                            val data = item.getString("data")
                            require(data.isNotBlank() && data.length <= MEDIA_LIMIT) { "Image exceeds delivery limit" }
                            val hash = gatewayHash(mime + ":" + data)
                            if (!seen.contains(hash)) {
                                require(handles.length() < 4 && encodedSize + data.length <= MEDIA_LIMIT) { "Media budget exhausted" }
                                require(validImage(Base64.getDecoder().decode(data), mime)) { "Invalid image data" }
                                val image = JSONObject().put("type", "image").put("mimeType", mime).put("data", data)
                                val handle = save("media", image.toString(), metrics)
                                image.put("_meta", JSONObject().put("ai_limbs_media_id", handle))
                                handles.put(JSONObject().put("media_id", handle).put("mimeType", mime).put("sha256", hash))
                                content.put(image)
                                seen.add(hash)
                                encodedSize += data.length
                                metrics.count("media_base64_chars", data.length.toLong())
                            }
                        } catch (error: Exception) {
                            mediaErrors.put(JSONObject().put("index", index).put("reason", error.javaClass.simpleName))
                        }
                    }
                    value.keys().asSequence().toList().forEach { visit(value.get(it)) }
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.get(index))
            }
        }
        visit(clean)
        val failed = failed(clean)
        if (failed) {
            // Top-level error_code was rewritten after delivery by the receiving integration.
            // Keep the exact producer outcome namespaced; never infer a code from its message.
            val outcome = JSONObject()
            listOf("success", "error", "error_code", "status", "exit_code", "execution_state",
                "automatic_reexecution", "next_action", "execution_policy").forEach { field ->
                if (clean.has(field)) outcome.put(field, clean.get(field))
            }
            clean.put("ai_limbs_outcome", outcome)
        }
        if (handles.length() > 0 || mediaErrors.length() > 0) clean.put("media_delivery", JSONObject()
            .put("attachments", handles).put("errors", mediaErrors).put("partial", mediaErrors.length() > 0)
            .put("next_action", "Use ai_limbs_media_read for saved media; do not repeat the original action to retry delivery."))
        val text = clean.toString()
        val textBytes = text.toByteArray(Charsets.UTF_8).size
        metrics.count("structured_text_bytes", textBytes.toLong())
        val structured = if (textBytes <= INLINE_BYTES) clean else {
            // Let the engine report preparation failure using the already received Host outcome.
            // Swallowing a cache error here falsely returns isError=false and bypasses its counter.
            val cursor = save("result", text, metrics)
            readResult(cursor, 0).put("operation_result_is_error", failed)
        }
        if (structured !== clean) {
            // Policy and retry guidance must remain immediately visible even when output is paged.
            listOf("success", "error", "error_code", "status", "exit_code", "execution_state",
                "automatic_reexecution", "execution_policy", "next_action", "media_delivery", "ai_limbs_outcome").forEach { field ->
                if (clean.has(field)) structured.put(field, clean.get(field))
            }
        }
        // MCP recommends serialized JSON text alongside structuredContent. Preserve the original
        // code in text as well as the outcome namespace, including a paged first envelope.
        val output = JSONArray().put(JSONObject().put("type", "text").put("text", structured.toString()))
        for (index in 0 until content.length()) output.put(content.get(index))
        return JSONObject().put("content", output).put("structuredContent", structured).put("isError", failed)
    }

    @Synchronized fun readResult(cursor: String, offset: Int): JSONObject {
        val entry = read(cursor, "result")
        val text = entry.getString("value")
        require(offset in 0..text.length && !(offset > 0 && offset < text.length && text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate())) { "Invalid result offset" }
        var end = (offset + PAGE_CHARS).coerceAtMost(text.length)
        if (end < text.length && end > offset && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return JSONObject().put("paged", true).put("cursor", cursor).put("output", text.substring(offset, end))
            .put("offset", offset).put("next_offset", if (end < text.length) end else JSONObject.NULL)
            .put("total_chars", text.length).put("offset_unit", "UTF-16").put("sha256", gatewayHash(text))
            .put("expires_at_ms", entry.getLong("expires"))
    }

    @Synchronized fun readMedia(id: String, contentOnly: Boolean = false): JSONObject {
        val media = JSONObject(read(id, "media").getString("value"))
        val structured = JSONObject().put("media_id", id).put("retrieved", true)
        val envelope = JSONObject().put("content", JSONArray()
            .put(media).put(JSONObject().put("type", "text").put("text", structured.toString())))
            .put("isError", false)
        // Opt-in A/B diagnostic: no structuredContent only when explicitly requested.
        // The default remains byte-for-byte compatible with the existing tool result.
        if (!contentOnly) envelope.put("structuredContent", structured)
        return envelope
    }

    @Synchronized fun pageResult(cursor: String, offset: Int): JSONObject {
        val structured = readResult(cursor, offset)
        return JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", structured.toString())))
            .put("structuredContent", structured).put("isError", false)
    }

    private fun read(id: String, kind: String): JSONObject {
        require(id.matches(Regex("${kind}_[a-f0-9]{32}"))) { "Invalid $kind identifier" }
        val entry = store.read(id)?.let(::JSONObject) ?: throw GatewayResultUnavailable("Cached $kind is unavailable")
        if (entry.getString("binding") != binding) throw GatewayResultUnavailable("Cached result belongs to another tunnel")
        if (entry.getLong("expires") <= now()) throw GatewayResultUnavailable("Cached $kind has expired")
        return entry
    }

    private fun save(kind: String, value: String, metrics: GatewayResultMetrics): String {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES) { "Result exceeds cache limit" }
        val entry = JSONObject().put("expires", now() + CACHE_TTL_MS).put("binding", binding).put("value", value).toString()
        // One cold scan per adapter lifetime; subsequent writes account from metadata only.
        // Updating the index AFTER a successful write preserves failure/capacity semantics.
        var index = cacheIndex
        if (index == null) {
            val loaded = metrics.measure("cache_scan_ms") {
                metrics.count("cache_scan_passes", 1L)
                val entries = mutableMapOf<String, CachedSize>()
                store.names().filter { it.startsWith("result_") || it.startsWith("media_") }.forEach { name ->
                    metrics.count("cache_entries_scanned", 1L)
                    val text = store.read(name) ?: return@forEach
                    val size = text.toByteArray(Charsets.UTF_8).size.toLong()
                    metrics.count("cache_plaintext_bytes_scanned", size)
                    val expires = JSONObject(text).getLong("expires")
                    if (expires <= now()) store.delete(name) else entries[name] = CachedSize(expires, size)
                }
                entries
            }
            cacheIndex = loaded
            index = loaded
        }
        val current = checkNotNull(index)
        current.filterValues { it.expires <= now() }.keys.toList().forEach { name ->
            store.delete(name)
            current.remove(name)
        }
        val size = entry.toByteArray(Charsets.UTF_8).size.toLong()
        require(current.size < MAX_CACHE_ENTRIES && current.values.sumOf { it.bytes } + size <= MAX_CACHE_BYTES) {
            "Result cache capacity reached"
        }
        val id = kind + "_" + UUID.randomUUID().toString().replace("-", "")
        metrics.measure("cache_write_ms") { store.write(id, entry) }
        current[id] = CachedSize(JSONObject(entry).getLong("expires"), size)
        return id
    }

    companion object {
        const val INLINE_BYTES = 12_000
        const val PAGE_CHARS = 4_000
        const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val MEDIA_LIMIT = 2 * 1024 * 1024
        private const val MAX_RESULT_BYTES = 4 * 1024 * 1024
        private const val MAX_CACHE_BYTES = 32 * 1024 * 1024
        private const val MAX_CACHE_ENTRIES = 64

        fun failed(value: JSONObject): Boolean {
            if (value.optString("execution_state") == "UNKNOWN") return true
            if (value.optBoolean("isError") || (value.has("success") && !value.isNull("success") && value.opt("success") == false)) return true
            val error = value.opt("error")
            if (error != null && error != JSONObject.NULL && error.toString().isNotBlank()) return true
            return value.optJSONObject("execution_policy")?.optString("outcome") in setOf("FORBID", "ASK")
        }
    }
}
