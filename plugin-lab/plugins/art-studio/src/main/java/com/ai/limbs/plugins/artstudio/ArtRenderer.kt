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
                        }
                        val strokes = layer.getJSONArray("strokes")
                        val order = layer.optJSONArray("contentOrder")
                        if (order == null) {
                            for (s in 0 until strokes.length()) drawStroke(local, strokes.getJSONObject(s))
                        } else {
                            val byId = (0 until strokes.length()).associate {
                                val stroke = strokes.getJSONObject(it)
                                stroke.getString("id") to stroke
                            }
                            for (n in 0 until order.length()) {
                                val event = order.getJSONObject(n)
                                when (event.getString("kind")) {
                                    "stroke" -> byId[event.getString("id")]?.let { drawStroke(local, it) }
                                    "clear", "fill" -> {
                                        val editPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                                        if (event.getString("kind") == "clear") {
                                            editPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                                        } else editPaint.color = Color.parseColor(event.getString("color"))
                                        val clip = event.optJSONObject("selection")
                                        if (clip != null) {
                                            local.save()
                                            local.clipPath(ArtSelection.path(clip))
                                        }
                                        local.drawRect(event.getInt("x").toFloat(), event.getInt("y").toFloat(),
                                            (event.getInt("x") + event.getInt("width")).toFloat(),
                                            (event.getInt("y") + event.getInt("height")).toFloat(), editPaint)
                                        if (clip != null) local.restore()
                                    }
                                    "paste", "erase" -> {
                                        val inserted = BitmapFactory.decodeFile(
                                            store.assetFile(event.getString("asset")).absolutePath)
                                            ?: error("工程粘贴资源已丢失")
                                        try {
                                            val insertPaint = Paint(Paint.FILTER_BITMAP_FLAG)
                                            if (event.getString("kind") == "erase")
                                                insertPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                                            local.drawBitmap(inserted, event.getInt("x").toFloat(),
                                                event.getInt("y").toFloat(), insertPaint)
                                        } finally { inserted.recycle() }
                                    }
                                    else -> error("未知像素编辑记录")
                                }
                            }
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
            alpha = (Color.alpha(color) * stroke.optDouble("opacity", 1.0)).toInt().coerceIn(0, 255)
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            if (stroke.optString("tool") == "eraser") {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
        }
        val tool = stroke.optString("tool", "pencil")
        val width = stroke.getDouble("width").toFloat()
        if (tool == "dyna") {
            val mass = 1.0 + 159.0 * stroke.optDouble("mass", 0.5)
            val drag = 0.5 * stroke.optDouble("drag", 0.15).let { it * it }
            val first = points.getJSONArray(0)
            var cursorX = first.getDouble(0)
            var cursorY = first.getDouble(1)
            var velocityX = 0.0
            var velocityY = 0.0
            val filtered = JSONArray().put(JSONArray(first.toString()))
            for (i in 1 until points.length()) {
                val target = points.getJSONArray(i)
                val forceX = target.getDouble(0) - cursorX
                val forceY = target.getDouble(1) - cursorY
                if (hypot(forceX, forceY) < 0.000001) continue
                velocityX += forceX / mass
                velocityY += forceY / mass
                if (hypot(velocityX, velocityY) < 0.000001) continue
                velocityX *= 1.0 - drag
                velocityY *= 1.0 - drag
                cursorX += velocityX
                cursorY += velocityY
                val point = JSONArray().put(cursorX).put(cursorY)
                if (target.length() > 2) point.put(target.getDouble(2))
                filtered.put(point)
            }
            drawStroke(canvas, JSONObject(stroke.toString()).put("tool", "ink")
                .put("points", filtered))
            return
        }
        if (tool == "mirror") {
            val regular = JSONObject(stroke.toString()).put("tool", "ink")
            if (stroke.getString("mirrorDirection") != "translate" &&
                stroke.getString("mirrorDirection") != "interval")
                drawStroke(canvas, regular)
            if (stroke.getString("mirrorDirection") == "translate") {
                val random = java.util.Random(stroke.getInt("mirrorSeed").toLong())
                val radius = stroke.getDouble("mirrorRadius")
                for (arm in 0 until stroke.getInt("mirrorCount")) {
                    val angle = random.nextDouble() * 2.0 * Math.PI
                    val distance = random.nextDouble() * radius
                    canvas.save()
                    try {
                        canvas.translate((distance * kotlin.math.cos(angle)).toFloat(),
                            (distance * kotlin.math.sin(angle)).toFloat())
                        drawStroke(canvas, regular)
                    } finally { canvas.restore() }
                }
            } else if (stroke.getString("mirrorDirection") == "interval") {
                val stepX = stroke.getInt("mirrorIntervalX")
                val stepY = stroke.getInt("mirrorIntervalY")
                val origin = points.getJSONArray(0)
                val anchorX = kotlin.math.floor(origin.getDouble(0) / stepX) * stepX
                val anchorY = kotlin.math.floor(origin.getDouble(1) / stepY) * stepY
                for (x in 0..stroke.getInt("canvasWidth") step stepX) {
                    for (y in 0..stroke.getInt("canvasHeight") step stepY) {
                        canvas.save()
                        try {
                            canvas.translate((x - anchorX).toFloat(), (y - anchorY).toFloat())
                            drawStroke(canvas, regular)
                        } finally { canvas.restore() }
                    }
                }
            } else if (stroke.getString("mirrorDirection") == "copytranslate") {
                val axesX = stroke.getDouble("axisX").toFloat()
                val axesY = stroke.getDouble("axisY").toFloat()
                val centers = stroke.getJSONArray("mirrorCenters")
                for (i in 0 until centers.length()) {
                    val point = centers.getJSONArray(i)
                    canvas.save()
                    try {
                        canvas.translate(point.getDouble(0).toFloat() - axesX,
                            point.getDouble(1).toFloat() - axesY)
                        drawStroke(canvas, regular)
                    } finally { canvas.restore() }
                }
            } else if (stroke.getString("mirrorDirection") == "radial" ||
                stroke.getString("mirrorDirection") == "snowflake") {
                val snowflake = stroke.getString("mirrorDirection") == "snowflake"
                val count = stroke.getInt("mirrorCount")
                val arms = if (snowflake) count * 2 else count
                for (arm in 1 until arms) {
                    canvas.save()
                    try {
                        canvas.rotate(360f * arm / arms,
                            stroke.getDouble("axisX").toFloat(),
                            stroke.getDouble("axisY").toFloat())
                        drawStroke(canvas, regular)
                    } finally { canvas.restore() }
                }
                if (snowflake) for (arm in 0 until arms) {
                    // Krita's snowflake mode alternates rotations with reflected arms.
                    val axisX = stroke.getDouble("axisX").toFloat()
                    val axisY = stroke.getDouble("axisY").toFloat()
                    val wedge = 180f / arms
                    canvas.save()
                    try {
                        canvas.rotate((2f * arm - 1f) * wedge, axisX, axisY)
                        canvas.scale(-1f, 1f, axisX, axisY)
                        canvas.rotate(wedge, axisX, axisY)
                        drawStroke(canvas, regular)
                    } finally { canvas.restore() }
                }
            } else {
                val direction = stroke.getString("mirrorDirection")
                canvas.save()
                try {
                    if (direction == "vertical" || direction == "quad")
                        canvas.scale(-1f, 1f, stroke.getDouble("axisX").toFloat(), 0f)
                    else
                        canvas.scale(1f, -1f, 0f, stroke.getDouble("axisY").toFloat())
                    drawStroke(canvas, regular)
                } finally { canvas.restore() }
                if (direction == "quad") {
                    canvas.save()
                    try {
                        canvas.scale(1f, -1f, 0f, stroke.getDouble("axisY").toFloat())
                        drawStroke(canvas, regular)
                    } finally { canvas.restore() }
                    canvas.save()
                    try {
                        canvas.scale(-1f, -1f, stroke.getDouble("axisX").toFloat(),
                            stroke.getDouble("axisY").toFloat())
                        drawStroke(canvas, regular)
                    } finally { canvas.restore() }
                }
            }
            return
        }
        if (tool == "calligraphy") {
            val radians = Math.toRadians(stroke.optDouble("nibAngle", 45.0))
            val nibX = cos(radians).toFloat()
            val nibY = sin(radians).toFloat()
            paint.style = Paint.Style.FILL
            for (i in 0 until points.length()) {
                val current = points.getJSONArray(i)
                val x = current.getDouble(0).toFloat()
                val y = current.getDouble(1).toFloat()
                val radius = width * current.optDouble(2, 1.0).toFloat() / 2f
                if (i > 0) {
                    val previous = points.getJSONArray(i - 1)
                    val px = previous.getDouble(0).toFloat()
                    val py = previous.getDouble(1).toFloat()
                    val pr = width * previous.optDouble(2, 1.0).toFloat() / 2f
                    val ribbon = Path().apply {
                        moveTo(px + nibX * pr, py + nibY * pr)
                        lineTo(x + nibX * radius, y + nibY * radius)
                        lineTo(x - nibX * radius, y - nibY * radius)
                        lineTo(px - nibX * pr, py - nibY * pr)
                        close()
                    }
                    canvas.drawPath(ribbon, paint)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (width * 0.04f).coerceAtLeast(0.5f)
                canvas.drawLine(x - nibX * radius, y - nibY * radius,
                    x + nibX * radius, y + nibY * radius, paint)
                paint.style = Paint.Style.FILL
            }
            return
        }
        if (tool == "soft") {
            paint.setShadowLayer(width * 0.7f, 0f, 0f, paint.color)
            paint.alpha = (paint.alpha * 0.45f).toInt()
        } else if (tool == "pencil") {
            paint.alpha = (paint.alpha * 0.58f).toInt()
        }
        if (tool == "gradient") {
            val first = points.getJSONArray(0)
            val last = points.getJSONArray(points.length() - 1)
            val x0 = first.getDouble(0).toFloat()
            val y0 = first.getDouble(1).toFloat()
            val x1 = last.getDouble(0).toFloat()
            val y1 = last.getDouble(1).toFloat()
            if (hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()) < 0.01) return
            paint.style = Paint.Style.FILL
            val foreground = Color.parseColor(stroke.optString("color", "#FF000000"))
            val alpha = (Color.alpha(foreground) * stroke.optDouble("opacity", 1.0))
                .toInt().coerceIn(0, 255)
            val startColor = (foreground and 0x00FFFFFF) or (alpha shl 24)
            paint.alpha = 255
            val endColor = if (stroke.has("gradientEndColor")) {
                val specified = Color.parseColor(stroke.getString("gradientEndColor"))
                (specified and 0x00FFFFFF) or
                    ((Color.alpha(specified) * stroke.optDouble("opacity", 1.0))
                        .toInt().coerceIn(0, 255) shl 24)
            } else foreground and 0x00FFFFFF
            val reverse = stroke.optBoolean("gradientReverse", false)
            val nearColor = if (reverse) endColor else startColor
            val farColor = if (reverse) startColor else endColor
            paint.shader = when (stroke.optString("gradientMode", "linear")) {
                "radial" -> android.graphics.RadialGradient(x0, y0,
                    hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat(),
                    nearColor, farColor, android.graphics.Shader.TileMode.CLAMP)
                "angular" -> android.graphics.SweepGradient(x0, y0,
                    nearColor, farColor).apply {
                    val degrees = Math.toDegrees(kotlin.math.atan2(
                        (y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
                    setLocalMatrix(android.graphics.Matrix().apply {
                        setRotate(degrees, x0, y0)
                    })
                }
                else -> android.graphics.LinearGradient(x0, y0, x1, y1,
                    nearColor, farColor, android.graphics.Shader.TileMode.CLAMP)
            }
            val boundsWidth = stroke.optInt("previewWidth", canvas.width)
            val boundsHeight = stroke.optInt("previewHeight", canvas.height)
            canvas.drawRect(0f, 0f, boundsWidth.toFloat(), boundsHeight.toFloat(), paint)
            return
        }
        if (tool == "bezier") {
            val first = points.getJSONArray(0)
            val path = Path().apply {
                moveTo(first.getDouble(0).toFloat(), first.getDouble(1).toFloat())
                var index = 1
                while (index + 2 < points.length()) {
                    val control1 = points.getJSONArray(index)
                    val control2 = points.getJSONArray(index + 1)
                    val end = points.getJSONArray(index + 2)
                    cubicTo(control1.getDouble(0).toFloat(), control1.getDouble(1).toFloat(),
                        control2.getDouble(0).toFloat(), control2.getDouble(1).toFloat(),
                        end.getDouble(0).toFloat(), end.getDouble(1).toFloat())
                    index += 3
                }
                // Show any incomplete segment's handles without persisting them.
                while (index < points.length()) {
                    val handle = points.getJSONArray(index)
                    lineTo(handle.getDouble(0).toFloat(), handle.getDouble(1).toFloat())
                    index++
                }
            }
            paint.strokeWidth = width
            if (points.length() == 1)
                canvas.drawCircle(first.getDouble(0).toFloat(), first.getDouble(1).toFloat(),
                    (width / 2f).coerceAtLeast(1f), paint)
            else canvas.drawPath(path, paint)
            return
        }
        if (tool == "polyline" || tool == "polygon") {
            if (points.length() < 2) return
            val first = points.getJSONArray(0)
            val path = Path().apply {
                moveTo(first.getDouble(0).toFloat(), first.getDouble(1).toFloat())
                for (i in 1 until points.length()) {
                    val point = points.getJSONArray(i)
                    lineTo(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
                }
                if (tool == "polygon" && points.length() >= 3 && !stroke.optBoolean("previewOpen")) close()
            }
            paint.strokeWidth = width
            if (tool == "polygon" && points.length() >= 3 &&
                !stroke.optBoolean("previewOpen") && stroke.optBoolean("fillShape"))
                paint.style = Paint.Style.FILL_AND_STROKE
            canvas.drawPath(path, paint)
            return
        }
        if (tool in setOf("line", "rectangle", "ellipse")) {
            // Keep shapes as two endpoint events in the shared stroke history, so
            // previews, undo, export and the AI stroke.add capability render identically.
            val first = points.getJSONArray(0)
            val last = points.getJSONArray(points.length() - 1)
            val x0 = first.getDouble(0).toFloat()
            val y0 = first.getDouble(1).toFloat()
            val x1 = last.getDouble(0).toFloat()
            val y1 = last.getDouble(1).toFloat()
            paint.strokeWidth = width
            if (tool != "line" && stroke.optBoolean("fillShape"))
                paint.style = Paint.Style.FILL_AND_STROKE
            when (tool) {
                "line" -> canvas.drawLine(x0, y0, x1, y1, paint)
                "rectangle" -> canvas.drawRect(minOf(x0, x1), minOf(y0, y1),
                    maxOf(x0, x1), maxOf(y0, y1), paint)
                "ellipse" -> canvas.drawOval(minOf(x0, x1), minOf(y0, y1),
                    maxOf(x0, x1), maxOf(y0, y1), paint)
            }
            return
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
