package com.ai.limbs.plugins.visualmanager

import org.json.JSONArray
import org.json.JSONObject

/** The image bytes occur only in mcp_content; preview/record objects remain metadata. */
internal object VisualImageAttachment {
    fun content(image: JSONObject): JSONArray = JSONArray().put(JSONObject()
        .put("type", "image").put("mimeType", image.getString("mime_type"))
        .put("data", image.getString("data")))

    fun attach(result: JSONObject, image: JSONObject): JSONObject {
        val attachment = content(image)
        image.remove("data")
        return result.put("mcp_content", attachment)
    }
}

/** Validate the response inside the operation coroutine, before publishing Compose state. */
internal data class VisualPreview(
    val kind: String,
    val capturedAtMs: Long,
    val width: Int,
    val height: Int,
    val data: String
) {
    companion object {
        fun fromResponse(result: JSONObject, metadata: JSONObject): VisualPreview {
            val content = result.getJSONArray("mcp_content")
            val images = (0 until content.length()).map { content.getJSONObject(it) }
                .filter { it.getString("type") == "image" }
            require(images.size == 1) { "视觉响应必须包含一个图片附件" }
            val attachment = images.single()
            val mimeType = metadata.getString("mime_type")
            require(mimeType.startsWith("image/") && attachment.getString("mimeType") == mimeType) {
                "视觉图片附件格式与元数据不一致"
            }
            val data = attachment.getString("data")
            require(data.isNotBlank()) { "视觉图片附件为空" }
            val kind = metadata.getString("kind")
            require(kind == "screen" || kind == "camera") { "视觉图片来源无效" }
            val capturedAtMs = metadata.getLong("captured_at_ms")
            val width = metadata.getInt("width")
            val height = metadata.getInt("height")
            require(capturedAtMs > 0 && width > 0 && height > 0) { "视觉图片元数据无效" }
            return VisualPreview(kind, capturedAtMs, width, height, data)
        }
    }
}
