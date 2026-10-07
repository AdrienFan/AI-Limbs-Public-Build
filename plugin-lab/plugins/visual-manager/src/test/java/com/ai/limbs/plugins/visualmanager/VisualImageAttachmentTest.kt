package com.ai.limbs.plugins.visualmanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualImageAttachmentTest {
    private fun encoded(kind: String = "screen", width: Int = 1024, height: Int = 853) = JSONObject()
        .put("kind", kind).put("captured_at_ms", 1780000000123L)
        .put("width", width).put("height", height).put("mime_type", "image/jpeg")
        .put("data", "aW1hZ2UtYnl0ZXM=")

    @Test fun firstScreenAndCameraFramesUseTheAttachmentWithoutDuplicatingImageBytes() {
        for (kind in listOf("screen", "camera")) {
            val image = encoded(kind)
            val result = VisualImageAttachment.attach(JSONObject().put("preview", image), image)
            val response = JSONObject(result.toString()) // Same serialization boundary as UI capability calls.
            val metadata = response.getJSONObject("preview")
            assertFalse(metadata.has("data"))
            assertEquals(1, response.getJSONArray("mcp_content").length())
            val preview = VisualPreview.fromResponse(response, metadata)
            assertEquals(kind, preview.kind)
            assertEquals("aW1hZ2UtYnl0ZXM=", preview.data)
            assertEquals(1024, preview.width)
            assertEquals(853, preview.height)
        }
    }

    @Test fun reopenedPreviewAndSavedImageReadsSupportResultMetadataAliasing() {
        for (kind in listOf("screen", "camera")) {
            val image = encoded(kind).put("asset_id", "saved-image")
            val response = JSONObject(VisualImageAttachment.attach(image, image).toString())
            assertFalse(response.has("data"))
            val preview = VisualPreview.fromResponse(response, response)
            assertEquals(kind, preview.kind)
            assertEquals(1780000000123L, preview.capturedAtMs)
            assertEquals("aW1hZ2UtYnl0ZXM=", preview.data)
        }
    }

    @Test fun thumbnailDimensionsComeFromTheEncodedResponse() {
        val image = encoded(width = 240, height = 200)
        val response = VisualImageAttachment.attach(image, image)
        val preview = VisualPreview.fromResponse(response, response)
        assertEquals(240, preview.width)
        assertEquals(200, preview.height)
    }

    @Test fun metadataDataIsNeverUsedAsAFallbackForMissingAttachments() {
        val response = encoded()
        assertThrows(Exception::class.java) { VisualPreview.fromResponse(response, response) }
        response.put("mcp_content", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { VisualPreview.fromResponse(response, response) }
    }

    @Test fun invalidAttachmentIsRejectedBeforeBecomingUiState() {
        for (field in listOf("data", "mimeType", "type")) {
            val response = encoded().let { VisualImageAttachment.attach(it, it) }
            response.getJSONArray("mcp_content").getJSONObject(0).remove(field)
            assertThrows(Exception::class.java) { VisualPreview.fromResponse(response, response) }
        }
        for (mutation in listOf<(JSONObject) -> Unit>(
            { it.put("data", " ") }, { it.put("mimeType", "image/png") }
        )) {
            val response = encoded().let { VisualImageAttachment.attach(it, it) }
            mutation(response.getJSONArray("mcp_content").getJSONObject(0))
            assertThrows(IllegalArgumentException::class.java) { VisualPreview.fromResponse(response, response) }
        }
    }

    @Test fun ambiguousImageAttachmentsAreRejected() {
        val response = encoded().let { VisualImageAttachment.attach(it, it) }
        val content = response.getJSONArray("mcp_content")
        content.put(JSONObject(content.getJSONObject(0).toString()))
        assertThrows(IllegalArgumentException::class.java) { VisualPreview.fromResponse(response, response) }
    }

    @Test fun invalidMetadataIsRejectedBeforeBecomingUiState() {
        for ((field, value) in listOf("width" to 0, "height" to -1, "captured_at_ms" to 0, "kind" to "unknown")) {
            val response = encoded().let { VisualImageAttachment.attach(it, it) }.put(field, value)
            assertThrows(IllegalArgumentException::class.java) { VisualPreview.fromResponse(response, response) }
        }
    }
}
