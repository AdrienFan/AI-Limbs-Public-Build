package com.ai.limbs.plugins.artstudio

import android.graphics.Path
import org.json.JSONArray
import org.json.JSONObject

/** Shared geometry for the visible selection and its raster editing operations. */
internal object ArtSelection {
    /** Store normalized vertices so moving and scaling a selection also moves its geometry. */
    fun fromVertices(points: JSONArray): JSONObject {
        require(points.length() in 3..2048) { "多边形选区需要 3–2048 个顶点" }
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until points.length()) {
            val point = points.getJSONArray(i)
            val x = point.getDouble(0)
            val y = point.getDouble(1)
            require(x.isFinite() && y.isFinite()) { "选区顶点坐标无效" }
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        val width = maxX - minX
        val height = maxY - minY
        require(width > 0.0 && height > 0.0) { "多边形选区面积为零" }
        val vertices = JSONArray()
        for (i in 0 until points.length()) {
            val point = points.getJSONArray(i)
            vertices.put(JSONArray().put((point.getDouble(0) - minX) / width)
                .put((point.getDouble(1) - minY) / height))
        }
        return JSONObject().put("shape", "polygon").put("x", minX).put("y", minY)
            .put("width", width).put("height", height).put("vertices", vertices)
    }

    fun path(selection: JSONObject): Path {
        val x = selection.getDouble("x").toFloat()
        val y = selection.getDouble("y").toFloat()
        val right = (selection.getDouble("x") + selection.getDouble("width")).toFloat()
        val bottom = (selection.getDouble("y") + selection.getDouble("height")).toFloat()
        return Path().apply {
            when (selection.optString("shape", "rect")) {
                "ellipse" -> addOval(x, y, right, bottom, Path.Direction.CW)
                "rect" -> addRect(x, y, right, bottom, Path.Direction.CW)
                "polygon" -> {
                    fillType = Path.FillType.EVEN_ODD
                    val vertices = selection.getJSONArray("vertices")
                    for (i in 0 until vertices.length()) {
                        val vertex = vertices.getJSONArray(i)
                        val vx = x + vertex.getDouble(0).toFloat() * (right - x)
                        val vy = y + vertex.getDouble(1).toFloat() * (bottom - y)
                        if (i == 0) moveTo(vx, vy) else lineTo(vx, vy)
                    }
                    close()
                }
                else -> error("未知选区形状")
            }
        }
    }

    fun contains(selection: JSONObject, x: Double, y: Double): Boolean {
        val left = selection.getDouble("x")
        val top = selection.getDouble("y")
        val width = selection.getDouble("width")
        val height = selection.getDouble("height")
        if (width <= 0.0 || height <= 0.0) return false
        return when (selection.optString("shape", "rect")) {
            "ellipse" -> {
                val dx = (2.0 * (x - left) / width) - 1.0
                val dy = (2.0 * (y - top) / height) - 1.0
                dx * dx + dy * dy <= 1.0
            }
            "rect" -> x in left..(left + width) && y in top..(top + height)
            "polygon" -> {
                val vertices = selection.getJSONArray("vertices")
                var inside = false
                for (i in 0 until vertices.length()) {
                    val j = (i + vertices.length() - 1) % vertices.length()
                    val current = vertices.getJSONArray(i)
                    val previous = vertices.getJSONArray(j)
                    val ax = left + current.getDouble(0) * width
                    val ay = top + current.getDouble(1) * height
                    val bx = left + previous.getDouble(0) * width
                    val by = top + previous.getDouble(1) * height
                    if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax)
                        inside = !inside
                }
                inside
            }
            else -> error("未知选区形状")
        }
    }

    fun intersectsSegment(selection: JSONObject, x0: Double, y0: Double,
                          x1: Double, y1: Double): Boolean {
        if (contains(selection, x0, y0) || contains(selection, x1, y1)) return true
        val width = selection.getDouble("width")
        val height = selection.getDouble("height")
        if (width <= 0.0 || height <= 0.0) return false
        if (selection.optString("shape", "rect") == "polygon") {
            val vertices = selection.getJSONArray("vertices")
            val left = selection.getDouble("x")
            val top = selection.getDouble("y")
            for (i in 0 until vertices.length()) {
                val j = (i + 1) % vertices.length()
                val a = vertices.getJSONArray(i)
                val b = vertices.getJSONArray(j)
                val ax = left + a.getDouble(0) * width
                val ay = top + a.getDouble(1) * height
                val bx = left + b.getDouble(0) * width
                val by = top + b.getDouble(1) * height
                val den = (x1 - x0) * (by - ay) - (y1 - y0) * (bx - ax)
                if (den == 0.0) continue
                val t = ((ax - x0) * (by - ay) - (ay - y0) * (bx - ax)) / den
                val u = ((ax - x0) * (y1 - y0) - (ay - y0) * (x1 - x0)) / den
                if (t in 0.0..1.0 && u in 0.0..1.0) return true
            }
            return false
        }
        if (selection.optString("shape", "rect") == "ellipse") {
            val left = selection.getDouble("x")
            val top = selection.getDouble("y")
            val ax = 2.0 * (x0 - left) / width - 1.0
            val ay = 2.0 * (y0 - top) / height - 1.0
            val bx = 2.0 * (x1 - left) / width - 1.0
            val by = 2.0 * (y1 - top) / height - 1.0
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) 0.0
                else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val nearestX = ax + t * dx
            val nearestY = ay + t * dy
            return nearestX * nearestX + nearestY * nearestY <= 1.0
        }
        val left = selection.getDouble("x")
        val top = selection.getDouble("y")
        val right = left + width
        val bottom = top + height
        fun crosses(ax: Double, ay: Double, bx: Double, by: Double): Boolean {
            val denominator = (x1 - x0) * (by - ay) - (y1 - y0) * (bx - ax)
            if (denominator == 0.0) return false
            val t = ((ax - x0) * (by - ay) - (ay - y0) * (bx - ax)) / denominator
            val u = ((ax - x0) * (y1 - y0) - (ay - y0) * (x1 - x0)) / denominator
            return t in 0.0..1.0 && u in 0.0..1.0
        }
        return crosses(left, top, right, top) || crosses(right, top, right, bottom) ||
            crosses(right, bottom, left, bottom) || crosses(left, bottom, left, top)
    }
}
