package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/** Touch preview is raw geometry. Curve fitting happens once on the Store worker at release. */
internal class StudioFreehandInteraction(private val view: View) {
    private var captured: JSONObject? = null
    private var samples = JSONArray()
    private var lastX = 0.0
    private var lastY = 0.0
    private var closedPreview = false
    private var screenMatrix = Matrix()
    private var snapStart:ArtPathGeometry.Vec?=null

    fun cancel() {
        if (captured == null && samples.length() == 0) return
        captured = null; samples = JSONArray();snapStart=null
        view.invalidate()
    }

    private fun sameContext(document: String, revision: Int, layer: String): Boolean {
        val p = captured ?: return false
        return p.getString("documentId") == document && p.getInt("expectedRevision") == revision &&
            p.getString("layerId") == layer
    }

    fun draw(canvas: Canvas, toScreen: Matrix, document: String, revision: Int, layer: String) {
        val p = captured ?: return
        if (!sameContext(document, revision, layer)) {cancel();return}
        val first=FloatArray(9);val current=FloatArray(9);screenMatrix.getValues(first);toScreen.getValues(current)
        if(first.indices.any {kotlin.math.abs(first[it]-current[it])>0.0001f}) {cancel();return}
        if(samples.length()<2)return
        if(closedPreview&&(0 until samples.length()).map {samples.getJSONArray(it).toString()}.distinct().size<3)return
        val points=JSONArray(samples.toString())
        if(p.has("previewMatrix")) {
            val inverse=Matrix();require(ArtShapes.matrix(p.getJSONArray("previewMatrix")).invert(inverse))
            for(i in 0 until points.length()) {val a=points.getJSONArray(i);val xy=floatArrayOf(a.getDouble(0).toFloat(),a.getDouble(1).toFloat());inverse.mapPoints(xy);a.put(0,xy[0]).put(1,xy[1])}
        }
        var shape=ArtFreehand.create(JSONObject().put("points",points).put("mode","raw").put("closed",closedPreview).put("style",JSONObject(p.getJSONObject("style").toString())))
        if(p.has("previewMatrix"))shape=ArtShapes.normalize(shape.put("matrix",p.getJSONArray("previewMatrix")))
        canvas.save()
        try {canvas.concat(toScreen);ArtShapes.draw(canvas,JSONObject().put("kind","vector").put("shapes",JSONArray().put(shape)))} finally {canvas.restore()}
    }

    private fun endpointAt(event:MotionEvent,state:JSONObject,layer:String,matrix:Matrix,exclude:ArtFreehandConnect.Endpoint?):Pair<ArtFreehandConnect.Endpoint,ArtPathGeometry.Vec>? {
        return ArtFreehandConnect.candidates(state,layer).filter {it.first!=exclude}.filter {item->
            val p=floatArrayOf(item.second.x.toFloat(),item.second.y.toFloat());matrix.mapPoints(p)
            kotlin.math.hypot(event.x-p[0],event.y-p[1])<=12f*view.resources.displayMetrics.density
        }.minByOrNull {item->val p=floatArrayOf(item.second.x.toFloat(),item.second.y.toFloat());matrix.mapPoints(p);kotlin.math.hypot(event.x-p[0],event.y-p[1])}
    }
    private fun add(x: Float, y: Float, inverse: Matrix) {
        val local = floatArrayOf(x, y); inverse.mapPoints(local)
        if(samples.length()==0&&snapStart!=null) {local[0]=requireNotNull(snapStart).x.toFloat();local[1]=requireNotNull(snapStart).y.toFloat()}
        val px = local[0].toDouble(); val py = local[1].toDouble()
        require(px.isFinite() && py.isFinite() && kotlin.math.abs(px) <= 1000000.0 &&
            kotlin.math.abs(py) <= 1000000.0) { "路径采样超出可编辑范围" }
        if (samples.length() > 0 && kotlin.math.hypot(px - lastX, py - lastY) <= 0.000001) return
        require(samples.length() < ArtFreehand.MAX_SAMPLES) { "轨迹超过2048点，请将长路径分段绘制" }
        samples.put(JSONArray().put(px).put(py))
        lastX = px; lastY = py
    }

    fun touch(event: MotionEvent, toScreen: Matrix, state: JSONObject, document: String, revision: Int,
        layerId: String, busy: Boolean, mode: String, precision: Float, closed: Boolean,
        fill: Boolean, color: String, width: Float, opacity: Float, settings:JSONObject, commit: (JSONObject) -> Unit): Boolean {
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
            settings.keys().forEach {key->if(key!="objectStyle")requireNotNull(captured).put(key,settings.get(key))}
            requireNotNull(captured).getJSONObject("style").put("objectStyle",settings.getJSONObject("objectStyle"))
            if(settings.getBoolean("connectEndpoints")&&!closed)endpointAt(event,state,layerId,toScreen,null)?.let {e->requireNotNull(captured).put("startEndpoint",ArtFreehandConnect.json(e.first));snapStart=e.second
                val shape=ArtShapes.items(layer).first {it.getString("id")==e.first.id};val style=JSONObject()
                for(key in listOf("fill","stroke","strokeWidth","opacity","objectStyle"))if(shape.has(key))style.put(key,shape.get(key))
                requireNotNull(captured).put("style",style).put("previewMatrix",JSONArray(shape.getJSONArray("matrix").toString()))}
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
                    val result = JSONObject(p.toString()).put("points", JSONArray(samples.toString())).put("closed",closedPreview)
                    if(p.getBoolean("connectEndpoints")&&!closedPreview) {
                        val exclude=if(p.has("startEndpoint"))ArtFreehandConnect.endpoint(p.getJSONObject("startEndpoint")) else null
                        endpointAt(event,state,layerId,toScreen,exclude)?.let {item->
                            result.put("endEndpoint",ArtFreehandConnect.json(item.first))
                            result.getJSONArray("points").getJSONArray(samples.length()-1).put(0,item.second.x).put(1,item.second.y)
                        }
                    }
                    cancel()
                    commit(result)
                }
            }
        }
        view.invalidate()
        return true
    }
}
