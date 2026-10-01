package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Feedback is a transient image, never an exported file or an extra document operation. */
internal object ArtCanvasFeedback {
    const val THUMBNAIL_EDGE = 256

    fun affectsCanvas(name: String, parameters: JSONObject): Boolean = when {
        name == "menu.execute" ->
            ArtStudioMenuCatalog.find(parameters.getString("action"))?.optBoolean("documentWrite") == true
        name.startsWith("reference.") -> name !in setOf("reference.list","reference.preview","reference.region")
        name in setOf("text.create", "text.update") -> true
        name.startsWith("path.") -> name != "path.nodes"
        name.startsWith("shape.") -> name !in setOf("shape.list", "shape.hit", "shape.box")
        name.startsWith("stroke.") || name.startsWith("transform.") || name.startsWith("selection.") -> true
        name.startsWith("layer.") -> name !in setOf("layer.list", "layer.search")
        name.startsWith("history.") -> name !in setOf("history.list", "history.timeline")
        name.startsWith("image.") || name == "fill.contiguous" || name == "canvas.crop" -> true
        name.startsWith("edit.") -> name !in setOf("edit.clipboard_info", "edit.copy", "edit.copy_merged")
        else -> name in setOf("document.create", "document.open", "document.import",
            "document.open_image", "document.save_as", "document.duplicate", "document.close",
            "document.discard_and_close", "document.incremental_version", "template.open", "session.open")
    }

    fun attach(store: ArtStore, result: JSONObject, snapshot: JSONObject?): JSONObject {
        if (result.optBoolean("referenceFeedback")) {
            val receipt=ArtReferencePreview.overview(store,requireNotNull(snapshot))
            return result.put("thumbnail",receipt.getJSONObject("thumbnail"))
                .put("mcp_content",receipt.getJSONArray("mcp_content"))
        }
        val image = if (snapshot == null) emptyCanvas() else preview(store, snapshot,
            0, 0, snapshot.getJSONObject("state").getInt("width"),
            snapshot.getJSONObject("state").getInt("height"), THUMBNAIL_EDGE, "thumbnail")
        return result.put("thumbnail", image.getJSONObject("metadata"))
            .put("mcp_content", JSONArray().put(image.getJSONObject("content")))
    }

    fun preview(store: ArtStore, snapshot: JSONObject, x: Int, y: Int,
        width: Int, height: Int, maxEdge: Int, kind: String): JSONObject {
        val state = snapshot.getJSONObject("state")
        require(x >= 0 && y >= 0 && width > 0 && height > 0 &&
            x.toLong() + width <= state.getInt("width") &&
            y.toLong() + height <= state.getInt("height")) { "预览区域超出画布边界" }
        require(maxEdge in 64..1024) { "局部预览长边需要在 64–1024 像素之间" }
        val scale = maxEdge.toDouble() / maxOf(width, height)
        val outputWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val outputHeight = (height * scale).roundToInt().coerceAtLeast(1)
        // Use the same compositor as the phone and exports; include visible groups and blend modes.
        val rendered = ArtRenderer.render(store, snapshot,
            maxEdge = if (kind == "thumbnail") THUMBNAIL_EDGE else null)
        try {
            val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(output)
                canvas.drawColor(Color.WHITE)
                canvas.scale(outputWidth.toFloat() / width, outputHeight.toFloat() / height)
                canvas.translate(-x.toFloat(), -y.toFloat())
                canvas.drawBitmap(rendered, null,
                    android.graphics.RectF(0f, 0f, state.getInt("width").toFloat(), state.getInt("height").toFloat()),
                    Paint(Paint.FILTER_BITMAP_FLAG))
                val metadata = JSONObject().put("kind", kind).put("empty", false)
                    .put("documentId", snapshot.getString("id")).put("revision", snapshot.getInt("revision"))
                    .put("sourceWidth", state.getInt("width")).put("sourceHeight", state.getInt("height"))
                    .put("region", JSONObject().put("x", x).put("y", y).put("width", width).put("height", height))
                    .put("width", outputWidth).put("height", outputHeight)
                    .put("scale", scale).put("transparencyBackground", "white")
                return encode(output, metadata)
            } finally { output.recycle() }
        } finally { rendered.recycle() }
    }

    private fun emptyCanvas(): JSONObject {
        val bitmap = Bitmap.createBitmap(THUMBNAIL_EDGE, THUMBNAIL_EDGE / 2, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(32, 32, 32))
            // Resident app_process may have no default typeface. Text rendering can abort
            // the whole plugin runtime in native code, so this receipt uses geometry only.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(190, 190, 190)
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRect(76f, 26f, 180f, 102f, paint)
            canvas.drawLine(88f, 38f, 168f, 90f, paint)
            canvas.drawLine(168f, 38f, 88f, 90f, paint)
            return encode(bitmap, JSONObject().put("kind", "thumbnail").put("empty", true)
                .put("documentId", JSONObject.NULL).put("revision", JSONObject.NULL)
                .put("label", "无活动画布")
                .put("width", bitmap.width).put("height", bitmap.height))
        } finally { bitmap.recycle() }
    }

    private fun encode(bitmap: Bitmap, metadata: JSONObject): JSONObject {
        val bytes = ByteArrayOutputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)) { "无法编码画布预览" }
            stream.toByteArray()
        }
        metadata.put("mimeType", "image/jpeg").put("bytes", bytes.size)
        return JSONObject().put("metadata", metadata)
            .put("content", JSONObject().put("type", "image").put("mimeType", "image/jpeg")
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))
    }
}
