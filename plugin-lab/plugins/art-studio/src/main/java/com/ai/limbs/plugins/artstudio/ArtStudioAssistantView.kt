package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

/** Entry's AI view renders the current ArtStore document without mounting a phone page. */
internal class ArtStudioAssistantView {
    private var viewport: StudioAssistantViewport? = null

    private fun current(snapshot: JSONObject?): StudioAssistantViewport? {
        if (snapshot == null) { viewport = null; return null }
        val state = snapshot.getJSONObject("state")
        val id = snapshot.getString("id")
        val width = state.getInt("width")
        val height = state.getInt("height")
        val previous = viewport
        if (previous == null || previous.documentId != id ||
            previous.sourceWidth != width || previous.sourceHeight != height)
            viewport = StudioAssistantViewport(id, width, height)
        return requireNotNull(viewport)
    }

    // All calls, including rendering and publication, hold ArtStore's existing document lock.
    fun describe(snapshot: JSONObject?): JSONObject =
        current(snapshot)?.describe() ?: JSONObject().put("target", "assistant")
            .put("canvasAttached", false).put("pageVisible", false)
            .put("viewSurface", "offscreen_bitmap")

    fun execute(store: ArtStore, snapshot: JSONObject?, operation: String, p: JSONObject): JSONObject {
        val before = requireNotNull(current(snapshot)) { "请先从画室能力入口新建或打开工程" }
        val next = when (operation) {
            "command" -> before.command(p.getString("command"))
            "zoom" -> before.zoom(p.getString("documentId"), p.getDouble("percent"))
            else -> error("未知 AI 视图操作：$operation")
        }
        val document = requireNotNull(snapshot)
        val receipt = render(store, document, next)
        // Do not announce an applied view or change state if rendering failed.
        viewport = next
        return next.describe().put("accepted", true)
            .put("documentId", next.documentId).put("revision", document.getInt("revision"))
            .put("command", if (operation == "command") p.getString("command") else JSONObject.NULL)
            .put("requestedPercent", if (operation == "zoom") p.getDouble("percent") else JSONObject.NULL)
            .put("viewPreview", receipt.getJSONObject("metadata"))
            .put("mcp_content", JSONArray().put(receipt.getJSONObject("content")))
    }

    private fun render(store: ArtStore, snapshot: JSONObject, view: StudioAssistantViewport): JSONObject {
        val state = snapshot.getJSONObject("state")
        ArtImagePolicy.requireBytes(
            ArtImagePolicy.renderBytes(store, state, view.sourceWidth, view.sourceHeight) +
                view.width.toLong() * view.height * 8L, "AI 视图合成")
        // Full compositor resolution is required: zooming must reveal source pixels, not enlarge a thumbnail.
        val source = ArtRenderer.render(store, snapshot)
        try {
            val output = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(output)
                canvas.drawColor(Color.rgb(32, 32, 32))
                canvas.translate(view.width / 2f, view.height / 2f)
                canvas.rotate(view.rotation.toFloat())
                canvas.scale((if (view.mirrored) -view.scale else view.scale).toFloat(), view.scale.toFloat())
                canvas.translate(-view.sourceWidth / 2f, -view.sourceHeight / 2f)
                canvas.drawRect(0f, 0f, view.sourceWidth.toFloat(), view.sourceHeight.toFloat(),
                    Paint().apply { color = Color.WHITE })
                canvas.drawBitmap(source, null,
                    RectF(0f, 0f, view.sourceWidth.toFloat(), view.sourceHeight.toFloat()),
                    Paint(Paint.FILTER_BITMAP_FLAG))
                val metadata = JSONObject().put("kind", "assistant_view").put("target", "assistant")
                    .put("documentId", snapshot.getString("id")).put("revision", snapshot.getInt("revision"))
                    .put("width", view.width).put("height", view.height)
                    .put("sourceWidth", view.sourceWidth).put("sourceHeight", view.sourceHeight)
                    .put("percent", view.scale * 100.0).put("rotation", view.rotation)
                    .put("mirrored", view.mirrored).put("transparencyBackground", "white")
                return ArtCanvasFeedback.encode(output, metadata)
            } finally { output.recycle() }
        } finally { source.recycle() }
    }
}
