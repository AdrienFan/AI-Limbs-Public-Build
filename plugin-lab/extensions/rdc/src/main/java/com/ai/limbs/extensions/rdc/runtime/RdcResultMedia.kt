package com.ai.limbs.extensions.rdc.runtime

import org.json.JSONArray
import org.json.JSONObject

/** Promote attached images into media blocks while keeping binary data out of text pagination. */
internal object RdcResultMedia {
    fun split(result: JSONObject): Pair<JSONObject, JSONArray> {
        val clean = JSONObject(result.toString()).apply { remove("events") }
        val images = JSONArray()
        val seen = mutableSetOf<String>()
        var encodedBytes = 0
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    value.optJSONArray("mcp_content")?.let { attached ->
                        for (index in 0 until attached.length()) {
                            val item = attached.getJSONObject(index)
                            require(item.getString("type") == "image") { "Only image media blocks are supported" }
                            require(item.getString("mimeType") in setOf("image/jpeg", "image/png")) { "Unsupported image MIME type" }
                            val data = item.getString("data")
                            require(data.isNotEmpty() && data.length <= MAX_ENCODED_BYTES) { "Image exceeds media delivery limit" }
                            if (seen.add(item.getString("mimeType") + ":" + data)) {
                                encodedBytes += data.length
                                require(images.length() < 4 && encodedBytes <= MAX_ENCODED_BYTES) { "Too many or oversized image blocks" }
                                images.put(JSONObject(item.toString()))
                            }
                        }
                        value.remove("mcp_content")
                    }
                    value.keys().asSequence().toList().forEach { visit(value.get(it)) }
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.get(index))
            }
        }
        visit(clean)
        return clean to images
    }

    private const val MAX_ENCODED_BYTES = 2 * 1024 * 1024
}
