package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/** Touch preview is raw geometry. Curve fitting happens once on the Store worker at release. */
internal class StudioFreehandInteraction(private val view: View) {
    private var captured: JSONObject? = null
    private var samples = JSONArray()
    private var preview = Path()
    private var lastX = 0.0
    private var lastY = 0.0
    private var closedPreview = false
    private var screenMatrix = Matrix()

    fun cancel() {
        if (captured == null && samples.length() == 0) return
        captured = null; samples = JSONArray(); preview = Path()
        view.invalidate()
    }

    private fun sameContext(document: String, revision: Int, layer: String): Boolean {
        val p = captured ?: return false
        return p.getString("documentId") == document && p.getInt("expectedRevision") == revision &&
            p.getString("layerId") == layer
    }

    fun draw(canvas: Canvas, toScreen: Matrix, document: String, revision: Int, layer: String) {
        val p = captured ?: return
        if (!sameContext(document, revision, layer)) return
        val style = p.getJSONObject("style")
        val path = Path(preview)
        if (closedPreview) path.close()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = style.getDouble("strokeWidth").toFloat()
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        fun color(key: String) {
            val c = Color.parseColor(style.getString(key))
            paint.color = c; paint.alpha = (Color.alpha(c) * style.getDouble("opacity")).toInt()
        }
        canvas.save()
        try {
            canvas.concat(toScreen)
            if (closedPreview) {
                color("fill"); paint.style = Paint.Style.FILL; canvas.drawPath(path, paint)
            }
            color("stroke"); paint.style = Paint.Style.STROKE; canvas.drawPath(path, paint)
        } finally { canvas.restore() }
    }

    private fun add(x: Float, y: Float, inverse: Matrix) {
        val local = floatArrayOf(x, y); inverse.mapPoints(local)
        val px = local[0].toDouble(); val py = local[1].toDouble()
        require(px.isFinite() && py.isFinite() && kotlin.math.abs(px) <= 1000000.0 &&
            kotlin.math.abs(py) <= 1000000.0) { "路径采样超出可编辑范围" }
        if (samples.length() > 0 && kotlin.math.hypot(px - lastX, py - lastY) <= 0.000001) return
        require(samples.length() < ArtFreehand.MAX_SAMPLES) { "轨迹超过2048点，请将长路径分段绘制" }
        if (samples.length() == 0) preview.moveTo(local[0], local[1]) else preview.lineTo(local[0], local[1])
        samples.put(JSONArray().put(px).put(py))
        lastX = px; lastY = py
    }

    fun touch(event: MotionEvent, toScreen: Matrix, state: JSONObject, document: String, revision: Int,
        layerId: String, busy: Boolean, mode: String, precision: Float, closed: Boolean,
        fill: Boolean, color: String, width: Float, opacity: Float, commit: (JSONObject) -> Unit): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || busy) { cancel(); return true }
        if (captured != null && !sameContext(document, revision, layerId)) {
            cancel(); error("工程已更新，请重新绘制路径")
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            val layer = ArtShapes.layer(state, layerId)
            require(layer.getString("kind") == "vector") { "徒手路径需要矢量图层，请在工具选项中新建" }
            require(ArtShapes.visible(state, layer) && !ArtMenuOperations.isLocked(state, layer)) {
                "矢量图层或父组隐藏、锁定，不能绘制路径"
            }
            captured = JSONObject().put("documentId", document).put("expectedRevision", revision)
                .put("layerId", layerId).put("mode", mode).put("precision", precision.toDouble())
                .put("closed", closed).put("style", JSONObject()
                    .put("fill", if (fill) color else "#00000000").put("stroke", color)
                    .put("strokeWidth", width.toDouble()).put("opacity", opacity.toDouble()))
            screenMatrix = Matrix(toScreen)
        }
        val p = captured ?: return true
        // A wheel zoom during a stroke must not mix two coordinate frames in one path.
        val first = FloatArray(9); val current = FloatArray(9)
        screenMatrix.getValues(first); toScreen.getValues(current)
        require(first.indices.all { kotlin.math.abs(first[it] - current[it]) <= 0.0001f }) {
            "视图已改变，请重新绘制路径"
        }
        val inverse = Matrix(); check(screenMatrix.invert(inverse))
        closedPreview = p.getBoolean("closed") ||
            (event.metaState and android.view.KeyEvent.META_SHIFT_MASK != 0)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> add(event.x, event.y, inverse)
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                for (i in 0 until event.historySize)
                    add(event.getHistoricalX(i), event.getHistoricalY(i), inverse)
                add(event.x, event.y, inverse)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val result = JSONObject(p.toString()).put("points", JSONArray(samples.toString()))
                        .put("closed", closedPreview)
                    cancel()
                    commit(result)
                }
            }
        }
        view.invalidate()
        return true
    }
}
