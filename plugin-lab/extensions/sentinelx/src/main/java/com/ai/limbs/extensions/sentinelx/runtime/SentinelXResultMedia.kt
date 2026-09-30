package com.ai.limbs.extensions.sentinelx.runtime

import org.json.JSONArray
import org.json.JSONObject

/** Binary fields never enter text pagination, including malformed media from a tool provider. */
internal object SentinelXResultMedia {
    data class Extracted(val payload: JSONObject, val images: JSONArray, val errors: JSONArray)

    fun split(result: JSONObject): Extracted {
        val clean = JSONObject(result.toString()).apply { remove("events") }
        val images = JSONArray()
        val errors = JSONArray()
        val seen = mutableSetOf<String>()
        var encodedBytes = 0
        fun recordError(message: String) {
            errors.put(JSONObject().put("error_code", "invalid_media").put("error", message.take(256)))
        }
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (value.has("mcp_content")) {
                        val attached = value.remove("mcp_content")
                        if (attached !is JSONArray) recordError("mcp_content must be an array of image blocks")
                        else for (index in 0 until attached.length()) {
                            try {
                                val item = attached.getJSONObject(index)
                                require(item.getString("type") == "image") { "Only image media blocks are supported" }
                                val mime = item.getString("mimeType")
                                require(mime in setOf("image/jpeg", "image/png")) { "Unsupported image MIME type" }
                                val data = item.getString("data")
                                require(data.isNotEmpty() && data.length <= MAX_ENCODED_BYTES) { "Image exceeds media delivery limit" }
                                val identity = mime + ":" + data
                                if (identity !in seen) {
                                    require(images.length() < 4 && encodedBytes + data.length <= MAX_ENCODED_BYTES) {
                                        "Too many or oversized image blocks"
                                    }
                                    seen.add(identity)
                                    encodedBytes += data.length
                                    images.put(JSONObject(item.toString()))
                                }
                            } catch (error: Exception) {
                                // The source tool has already run. Media errors must not replace
                                // its successful business receipt with a retryable tool failure.
                                recordError(error.message ?: error.javaClass.simpleName)
                            }
                        }
                    }
                    value.keys().asSequence().toList().forEach { visit(value.get(it)) }
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.get(index))
            }
        }
        visit(clean)
        return Extracted(clean, images, errors)
    }

    private const val MAX_ENCODED_BYTES = 2 * 1024 * 1024
}
