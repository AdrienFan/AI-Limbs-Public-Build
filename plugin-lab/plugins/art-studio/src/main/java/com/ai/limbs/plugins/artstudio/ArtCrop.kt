package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Original integer crop geometry. A plan has no side effects; applying it binds document/revision. */
internal object ArtCrop {
    const val MAX_EDGE = 16384
    val guides = listOf("none", "thirds", "fifths", "golden", "diagonal", "cross")
    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right get() = x + width
        val bottom get() = y + height
        fun json() = JSONObject().put("x", x).put("y", y).put("width", width).put("height", height)
    }
    fun defaults() = JSONObject().put("target", "canvas").put("allowGrow", true)
        .put("lockWidth", false).put("lockHeight", false).put("lockRatio", false)
        .put("fixedWidth", 512).put("fixedHeight", 512).put("ratio", 1.0)
        .put("fromCenter", false).put("guides", "thirds")
    fun settings(p: JSONObject): JSONObject {
        val out = defaults()
        for (key in out.keys().asSequence().toList()) if (p.has(key)) out.put(key, p.get(key))
        require(out.getString("target") in setOf("canvas", "layer", "frame")) { "裁剪目标须为canvas/layer/frame" }
        require(out.getString("target") != "frame") { "当前画室没有动画帧数据与时间轴，无法裁剪当前帧" }
        for (key in listOf("allowGrow", "lockWidth", "lockHeight", "lockRatio", "fromCenter")) require(out.get(key) is Boolean)
        for (key in listOf("fixedWidth", "fixedHeight")) {
            val value = out.get(key); require(value is Number && value.toDouble() % 1.0 == 0.0 && value.toDouble() in 1.0..MAX_EDGE.toDouble())
        }
        val ratio = out.getDouble("ratio"); require(ratio.isFinite() && ratio in 1.0 / 128..128.0) { "比例width/height须为1/128–128" }
        require(!out.getBoolean("lockRatio") || (!out.getBoolean("lockWidth") && !out.getBoolean("lockHeight"))) { "比例锁定与宽高锁定不能同时启用" }
        require(out.getString("guides") in guides)
        return out
    }
    fun resolve(p: JSONObject, canvasWidth: Int, canvasHeight: Int): JSONObject {
        require(canvasWidth in 1..MAX_EDGE && canvasHeight in 1..MAX_EDGE)
        val options = settings(p)
        fun integer(key: String, default: Int? = null): Int {
            val value = if (p.has(key)) p.get(key) else default ?: error("缺少裁剪尺寸")
            require(value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0)
            require(abs(value.toDouble()) <= MAX_EDGE) { "裁剪坐标／尺寸绝对值最多16384" }
            return value.toInt()
        }
        var x = integer("x", 0); var y = integer("y", 0)
        val requestedWidth = integer("width"); val requestedHeight = integer("height")
        require(requestedWidth in 1..MAX_EDGE && requestedHeight in 1..MAX_EDGE)
        var width = if (options.getBoolean("lockWidth")) options.getInt("fixedWidth") else requestedWidth
        var height = if (options.getBoolean("lockHeight")) options.getInt("fixedHeight") else requestedHeight
        if (options.getBoolean("lockRatio")) {
            val ratio = options.getDouble("ratio")
            if (abs(width / ratio - height) > 0.5000001 && abs(height * ratio - width) > 0.5000001) {
                if (width >= height * ratio) height = (width / ratio).roundToInt().coerceAtLeast(1)
                else width = (height * ratio).roundToInt().coerceAtLeast(1)
            }
        }
        require(width in 1..MAX_EDGE && height in 1..MAX_EDGE) { "锁定后的宽高超出1–16384像素" }
        if (options.getBoolean("fromCenter")) {
            x = (x + (requestedWidth - width) / 2.0).roundToInt()
            y = (y + (requestedHeight - height) / 2.0).roundToInt()
        }
        require(abs(x) <= MAX_EDGE && abs(y) <= MAX_EDGE)
        if (!options.getBoolean("allowGrow")) require(x >= 0 && y >= 0 && x + width <= canvasWidth && y + height <= canvasHeight) {
            "裁剪框在画布外；请启用边界外扩展或调整框的位置／尺寸"
        }
        return options.put("x", x).put("y", y).put("width", width).put("height", height)
    }
    fun rect(p: JSONObject) = Rect(p.getInt("x"), p.getInt("y"), p.getInt("width"), p.getInt("height"))

    /** Drag constraints preserve the opposite edge (or center), and fit without changing locked sizes. */
    fun drag(base: Rect?, handle: String, startX: Double, startY: Double, x: Double, y: Double,
        options: JSONObject, canvasWidth: Int, canvasHeight: Int): Rect {
        require(listOf(startX, startY, x, y).all { it.isFinite() && abs(it) <= 1000000 })
        val o = settings(options)
        var left: Double; var top: Double; var width: Double; var height: Double
        if (handle == "move") {
            val b = requireNotNull(base)
            left = b.x + x - startX; top = b.y + y - startY; width = b.width.toDouble(); height = b.height.toDouble()
        } else {
            val b = base
            var anchorX = if (b == null) startX else if (handle.contains('w')) b.right.toDouble() else b.x.toDouble()
            var anchorY = if (b == null) startY else if (handle.contains('n')) b.bottom.toDouble() else b.y.toDouble()
            val horizontal = b == null || handle.contains('w') || handle.contains('e')
            val vertical = b == null || handle.contains('n') || handle.contains('s')
            val centered = o.getBoolean("fromCenter")
            if (centered && b != null) { anchorX = b.x + b.width / 2.0; anchorY = b.y + b.height / 2.0 }
            width = if (horizontal) abs(x - anchorX) * (if (centered) 2 else 1) else b!!.width.toDouble()
            height = if (vertical) abs(y - anchorY) * (if (centered) 2 else 1) else b!!.height.toDouble()
            if (o.getBoolean("lockWidth")) width = o.getInt("fixedWidth").toDouble()
            if (o.getBoolean("lockHeight")) height = o.getInt("fixedHeight").toDouble()
            if (o.getBoolean("lockRatio")) {
                val ratio = o.getDouble("ratio")
                if (!vertical || (horizontal && width >= height * ratio)) height = width / ratio else width = height * ratio
            }
            width = width.coerceAtLeast(1.0); height = height.coerceAtLeast(1.0)
            if (o.getBoolean("lockRatio")) {
                val maxWidth = if(o.getBoolean("allowGrow")) MAX_EDGE else canvasWidth
                val maxHeight = if(o.getBoolean("allowGrow")) MAX_EDGE else canvasHeight
                val factor = minOf(1.0, maxWidth / width, maxHeight / height)
                width *= factor; height *= factor
            }
            left = if (centered) anchorX - width / 2 else if (horizontal && x < anchorX) anchorX - width else anchorX
            top = if (centered) anchorY - height / 2 else if (vertical && y < anchorY) anchorY - height else anchorY
        }
        var w = width.roundToInt().coerceIn(1, MAX_EDGE); var h = height.roundToInt().coerceIn(1, MAX_EDGE)
        var px = left.roundToInt().coerceIn(-MAX_EDGE, MAX_EDGE); var py = top.roundToInt().coerceIn(-MAX_EDGE, MAX_EDGE)
        if (!o.getBoolean("allowGrow")) {
            require(!o.getBoolean("lockWidth") || w <= canvasWidth) { "锁定宽度大于画布" }
            require(!o.getBoolean("lockHeight") || h <= canvasHeight) { "锁定高度大于画布" }
            w = minOf(w, canvasWidth); h = minOf(h, canvasHeight)
            px = px.coerceIn(0, canvasWidth - w); py = py.coerceIn(0, canvasHeight - h)
        }
        return Rect(px, py, w, h)
    }
    fun lines(rect: Rect, guide: String): List<DoubleArray> {
        require(guide in guides)
        val x = rect.x.toDouble(); val y = rect.y.toDouble(); val w = rect.width.toDouble(); val h = rect.height.toDouble()
        if (guide == "none") return emptyList()
        if (guide == "diagonal") return listOf(doubleArrayOf(x,y,x+w,y+h), doubleArrayOf(x+w,y,x,y+h))
        val stops = when (guide) {
            "thirds" -> listOf(1.0/3, 2.0/3)
            "fifths" -> listOf(0.2,0.4,0.6,0.8)
            "golden" -> listOf((3-sqrt(5.0))/2, (sqrt(5.0)-1)/2)
            else -> listOf(0.5)
        }
        return stops.flatMap { listOf(doubleArrayOf(x+w*it,y,x+w*it,y+h), doubleArrayOf(x,y+h*it,x+w,y+h*it)) }
    }
    fun info() = JSONObject().put("targets", JSONArray(listOf("canvas", "layer"))).put("frameAvailable", false)
        .put("frameReason", "No animation frame data or timeline in this plugin")
        .put("defaults", defaults()).put("guides", JSONArray(guides)).put("coordinateSpace", "document integer pixels")
        .put("canvasMode", "resize viewport, shift root layers/references/assistants; retain editable source outside canvas")
        .put("layerMode", "editable convex content boundary; preserves layer transform and source; future drawing obeys this boundary")
        .put("limits", "edges 1–16384, origins ±16384, image.limits budget, up to 128 clip vertices; no automatic flatten or frame substitution")

    /** Intersect two convex local-space polygons, preserving an empty cropped layer. */
    fun intersect(subject: List<Pair<Double,Double>>, clip: List<Pair<Double,Double>>): List<Pair<Double,Double>> {
        if (subject.isEmpty()) return emptyList()
        require(subject.size in 3..128 && clip.size in 3..128)
        var out = subject
        for (i in clip.indices) {
            val a = clip[i]; val b = clip[(i+1)%clip.size]
            fun side(p: Pair<Double,Double>) = (b.first-a.first)*(p.second-a.second)-(b.second-a.second)*(p.first-a.first)
            val input = out; val next = mutableListOf<Pair<Double,Double>>()
            if (input.isEmpty()) break
            var previous = input.last(); var ps = side(previous)
            for (point in input) {
                val s = side(point)
                if ((ps >= 0) != (s >= 0)) {
                    val t = ps / (ps - s)
                    next.add((previous.first+(point.first-previous.first)*t) to (previous.second+(point.second-previous.second)*t))
                }
                if (s >= 0) next.add(point)
                previous=point; ps=s
            }
            out = next.distinct(); require(out.size <= 128) { "裁剪边界过于复杂，请撤销或使用更简单的裁剪" }
        }
        return if (out.size < 3) emptyList() else out
    }
}
