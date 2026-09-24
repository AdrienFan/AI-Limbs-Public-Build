package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.sin

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
        fun compositePaint(layer: JSONObject): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (layer.getDouble("opacity") * 255).toInt().coerceIn(0, 255)
            if (Build.VERSION.SDK_INT >= 29) blendMode = when (layer.getString("blend")) {
                "multiply" -> android.graphics.BlendMode.MULTIPLY
                "screen" -> android.graphics.BlendMode.SCREEN
                "add" -> android.graphics.BlendMode.PLUS
                else -> android.graphics.BlendMode.SRC_OVER
            }
        }
        fun drawChildren(target: Canvas, parentId: String, depth: Int, draw: (Canvas, String, Int) -> Unit) {
            require(depth <= layers.length()) { "图层组存在循环引用" }
            for (i in 0 until layers.length()) {
                val layer = layers.getJSONObject(i)
                if (layer.optString("parentId") != parentId || !layer.getBoolean("visible")) continue
                target.save()
                target.translate(layer.getDouble("x").toFloat(), layer.getDouble("y").toFloat())
                target.rotate(layer.getDouble("rotation").toFloat())
                val scale = layer.getDouble("scale").toFloat()
                target.scale(scale, scale)
                val paint = compositePaint(layer)
                if (layer.getString("kind") == "group") {
                    // Isolate the complete group before applying opacity or blend once.
                    target.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), paint)
                    draw(target, layer.getString("id"), depth + 1)
                    target.restore()
                } else {
                    val buffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
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
                        target.drawBitmap(buffer, 0f, 0f, paint)
                    } finally { buffer.recycle() }
                }
                target.restore()
            }
        }
        // Kotlin local functions cannot refer forward to themselves, so pass the renderer explicitly.
        lateinit var draw: (Canvas, String, Int) -> Unit
        draw = { target, parent, depth -> drawChildren(target, parent, depth, draw) }
        draw(canvas, "", 0)
        return bitmap
    }

    fun drawStroke(canvas: Canvas, stroke: JSONObject) {
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
        if (tool == "soft") {
            paint.setShadowLayer(width * 0.7f, 0f, 0f, paint.color)
            paint.alpha = (paint.alpha * 0.45f).toInt()
        } else if (tool == "pencil") {
            paint.alpha = (paint.alpha * 0.58f).toInt()
        }
        if (tool == "spray") {
            paint.style = Paint.Style.FILL
            paint.alpha = (paint.alpha * 0.45f).toInt()
            val random = java.util.Random(0xA11L)
            for (i in 0 until points.length()) {
                val point = points.getJSONArray(i)
                val before = points.getJSONArray((i - 1).coerceAtLeast(0))
                val x = point.getDouble(0); val y = point.getDouble(1)
                val px = before.getDouble(0); val py = before.getDouble(1)
                val steps = (hypot(x - px, y - py) / width).toInt().coerceIn(1, 8)
                for (step in 1..steps) {
                    val centerX = px + (x - px) * step / steps
                    val centerY = py + (y - py) * step / steps
                    repeat(8) {
                        val angle = random.nextDouble() * Math.PI * 2
                        val radius = kotlin.math.sqrt(random.nextDouble()) * width * point.optDouble(2, 1.0) / 2
                        canvas.drawCircle((centerX + cos(angle) * radius).toFloat(),
                            (centerY + sin(angle) * radius).toFloat(), (width * 0.035f).coerceAtLeast(0.5f), paint)
                    }
                }
            }
            return
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

    fun export(dataDir: File, store: ArtStore, snapshot: JSONObject, format: String, name: String,
               options: JSONObject = JSONObject()): JSONObject {
        require(format == "png" || format == "jpeg")
        val mime = if (format == "png") "image/png" else "image/jpeg"
        val filename = (name.ifBlank { "AI-Limbs-Art-${UUID.randomUUID()}" }
            .replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)) + ".$format"
        val directory = File(dataDir, "exports")
        require(directory.mkdirs() || directory.isDirectory)
        val destination = File(directory, filename)
        val temp = File(directory, ".${UUID.randomUUID()}.tmp")
        try {
            val bitmap = render(store, snapshot, opaque = format == "jpeg")
            var output = bitmap
            try {
                val x = options.optInt("x", 0)
                val y = options.optInt("y", 0)
                val cropWidth = options.optInt("cropWidth", bitmap.width)
                val cropHeight = options.optInt("cropHeight", bitmap.height)
                require(x >= 0 && y >= 0 && cropWidth > 0 && cropHeight > 0 &&
                    x.toLong() + cropWidth <= bitmap.width && y.toLong() + cropHeight <= bitmap.height) {
                    "导出裁切超出画布边界"
                }
                val width = options.optInt("width", cropWidth)
                val height = options.optInt("height", cropHeight)
                require(width in 64..4096 && height in 64..4096) { "导出尺寸需要在 64–4096 像素之间" }
                if (x != 0 || y != 0 || cropWidth != bitmap.width || cropHeight != bitmap.height) {
                    output = Bitmap.createBitmap(bitmap, x, y, cropWidth, cropHeight)
                }
                if (width != output.width || height != output.height) {
                    val scaled = Bitmap.createScaledBitmap(output, width, height, true)
                    if (output !== bitmap) output.recycle()
                    output = scaled
                }
                FileOutputStream(temp).use { stream ->
                    require(output.compress(if (format == "png") Bitmap.CompressFormat.PNG
                        else Bitmap.CompressFormat.JPEG, 95, stream))
                    stream.fd.sync()
                }
            } finally {
                if (output !== bitmap) output.recycle()
                bitmap.recycle()
            }
            require(temp.renameTo(destination)) { "无法保存导出图片" }
        } catch (error: Throwable) {
            throw error
        } finally {
            temp.delete()
        }
        return JSONObject().put("path", destination.absolutePath).put("name", filename)
            .put("mime", mime).put("bytes", destination.length())
    }
}
