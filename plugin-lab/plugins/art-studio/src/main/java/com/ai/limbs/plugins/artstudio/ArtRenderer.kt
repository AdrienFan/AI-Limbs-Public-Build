package com.ai.limbs.plugins.artstudio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object ArtRenderer {
    fun render(store: ArtStore, snapshot: JSONObject, opaque: Boolean = false): Bitmap {
        val state = snapshot.getJSONObject("state")
        val width = state.getInt("width")
        val height = state.getInt("height")
        require(width in 64..4096 && height in 64..4096)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Color.parseColor(state.getString("background"))
        if (opaque) canvas.drawColor(Color.WHITE)
        canvas.drawColor(background)
        val layers = state.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (layer.getString("kind") == "group" || !layer.getBoolean("visible")) continue
            if (!parentsVisible(layer, layers)) continue
            val buffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val local = Canvas(buffer)
            if (layer.getString("kind") == "image") {
                BitmapFactory.decodeFile(store.assetFile(layer.getString("asset")).absolutePath)?.let { image ->
                    local.drawBitmap(image, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                    image.recycle()
                }
            } else {
                val strokes = layer.getJSONArray("strokes")
                for (s in 0 until strokes.length()) drawStroke(local, strokes.getJSONObject(s))
            }
            canvas.save()
            canvas.translate(layer.getDouble("x").toFloat(), layer.getDouble("y").toFloat())
            canvas.rotate(layer.getDouble("rotation").toFloat())
            val scale = layer.getDouble("scale").toFloat()
            canvas.scale(scale, scale)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (layer.getDouble("opacity") * 255).toInt().coerceIn(0, 255)
                if (Build.VERSION.SDK_INT >= 29) blendMode = when (layer.getString("blend")) {
                    "multiply" -> android.graphics.BlendMode.MULTIPLY
                    "screen" -> android.graphics.BlendMode.SCREEN
                    "add" -> android.graphics.BlendMode.PLUS
                    else -> android.graphics.BlendMode.SRC_OVER
                }
            }
            canvas.drawBitmap(buffer, 0f, 0f, paint)
            canvas.restore()
            buffer.recycle()
        }
        return bitmap
    }

    private fun parentsVisible(layer: JSONObject, layers: JSONArray): Boolean {
        var parentId = layer.optString("parentId")
        repeat(layers.length()) {
            if (parentId.isBlank()) return true
            val parent = (0 until layers.length()).map { layers.getJSONObject(it) }
                .firstOrNull { it.getString("id") == parentId } ?: return false
            if (!parent.getBoolean("visible")) return false
            parentId = parent.optString("parentId")
        }
        return false
    }

    private fun drawStroke(canvas: Canvas, stroke: JSONObject) {
        val points = stroke.getJSONArray("points")
        if (points.length() == 0) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(stroke.optString("color", "#FF000000"))
            alpha = (stroke.optDouble("opacity", 1.0) * 255).toInt().coerceIn(0, 255)
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            if (stroke.optString("tool") == "eraser") {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
        }
        val tool = stroke.optString("tool", "pencil")
        val width = stroke.getDouble("width").toFloat()
        if (tool == "soft" || tool == "spray") {
            paint.setShadowLayer(width * 0.7f, 0f, 0f, paint.color)
            paint.alpha = (paint.alpha * 0.45f).toInt()
        }
        var previous = points.getJSONArray(0)
        if (points.length() == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(previous.getDouble(0).toFloat(), previous.getDouble(1).toFloat(),
                width * previous.optDouble(2, 1.0).toFloat() / 2f, paint)
        }
        for (i in 1 until points.length()) {
            val current = points.getJSONArray(i)
            paint.strokeWidth = width * ((previous.optDouble(2, 1.0) + current.optDouble(2, 1.0)) / 2.0)
                .toFloat().coerceAtLeast(0.1f)
            canvas.drawLine(previous.getDouble(0).toFloat(), previous.getDouble(1).toFloat(),
                current.getDouble(0).toFloat(), current.getDouble(1).toFloat(), paint)
            previous = current
        }
    }

    fun export(context: Context, store: ArtStore, snapshot: JSONObject, format: String, name: String): JSONObject {
        require(format == "png" || format == "jpeg")
        val mime = if (format == "png") "image/png" else "image/jpeg"
        val filename = (name.ifBlank { "AI-Limbs-Art-${UUID.randomUUID()}" }
            .replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)) + ".$format"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AI Limbs Art Studio")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "无法创建图片文件"
        }
        try {
            val bitmap = render(store, snapshot, opaque = format == "jpeg")
            resolver.openOutputStream(uri)?.use { stream ->
                require(bitmap.compress(if (format == "png") Bitmap.CompressFormat.PNG
                    else Bitmap.CompressFormat.JPEG, 95, stream))
            } ?: error("无法写入导出图片")
            bitmap.recycle()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
        return JSONObject().put("uri", uri.toString()).put("name", filename)
    }
}
