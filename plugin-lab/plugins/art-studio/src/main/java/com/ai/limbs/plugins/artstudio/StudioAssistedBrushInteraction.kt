package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Matrix
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/** Capture guide, view, layer and style once; save the final coordinates, never re-snap history. */
internal class StudioAssistedBrushInteraction {
    private var capture: JSONObject?=null
    private var style: JSONObject?=null
    private var viewMatrix=Matrix()
    private var inverseView=Matrix()
    private var inverseLayer=Matrix()
    private var session: ArtAssistants.SnapSession?=null
    private val samples=mutableListOf<AssistantPoint>()
    private val pressures=mutableListOf<Double>()
    val active get()=capture!=null
    var density=1f
    fun cancel() { capture=null;style=null;session=null;samples.clear();pressures.clear() }
    private fun same(m: Matrix): Boolean {
        val a=FloatArray(9);val b=FloatArray(9);m.getValues(a);viewMatrix.getValues(b)
        return a.indices.all { abs(a[it]-b[it])<0.001f }
    }
    fun touch(event: MotionEvent,state: JSONObject,documentId: String,revision: Int,layerId: String,
        toScreen: Matrix,busy: Boolean,options: JSONObject,onStroke: (JSONObject)->Unit): Boolean {
        if(busy || event.actionMasked==MotionEvent.ACTION_CANCEL) { cancel();return true }
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            cancel()
            val layer=ArtMenuOperations.layers(state).first { it.getString("id")==layerId }
            require(layer.getString("kind")=="paint") { "尺规吸附需要绘画图层" }
            require(toScreen.invert(inverseView) && ArtShapes.layerMatrix(state,layer).invert(inverseLayer))
            capture=JSONObject().put("documentId",documentId).put("expectedRevision",revision).put("layerId",layerId)
            style=JSONObject(options.toString()).put("id",java.util.UUID.randomUUID().toString());viewMatrix=Matrix(toScreen)
            val start=floatArrayOf(event.x,event.y);inverseView.mapPoints(start)
            val scaleValues=FloatArray(9);toScreen.getValues(scaleValues)
            val scale=hypot(scaleValues[0],scaleValues[3]).toDouble();require(scale>0)
            session=ArtAssistants.SnapSession(state,AssistantPoint(start[0].toDouble(),start[1].toDouble()),
                ArtAssistants.settings(state).getDouble("thresholdDp")*density/scale)
        }
        val context=capture ?: return true
        require(context.getString("documentId")==documentId && context.getInt("expectedRevision")==revision &&
            context.getString("layerId")==layerId && same(toScreen)) { cancel();"工程、图层或视图已改变，请重新起笔" }
        fun add(x: Float,y: Float,pressure: Float) {
            require(samples.size<10000) { "单笔采样超过10000点，请分段绘制" }
            val p=floatArrayOf(x,y);inverseView.mapPoints(p);val point=AssistantPoint(p[0].toDouble(),p[1].toDouble())
            samples.add(point);pressures.add(pressure.coerceIn(0.1f,1f).toDouble());session!!.project(point)
        }
        if(event.actionMasked in setOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            if(event.actionMasked==MotionEvent.ACTION_MOVE) for(i in 0 until event.historySize)
                add(event.getHistoricalX(i),event.getHistoricalY(i),event.getHistoricalPressure(i))
            add(event.x,event.y,event.pressure)
        }
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            val stroke=parameters();stroke.put("documentId",context.getString("documentId"))
                .put("expectedRevision",context.getInt("expectedRevision"))
            cancel();onStroke(stroke)
        }
        return true
    }
    private fun parameters(): JSONObject {
        val points=JSONArray();val projected=session!!.complete(samples)
        projected.forEachIndexed { i,p ->
            val v=floatArrayOf(p.x.toFloat(),p.y.toFloat());inverseLayer.mapPoints(v)
            points.put(JSONArray().put(v[0]).put(v[1]).put(pressures[i]))
        }
        val out=JSONObject(style!!.toString())
            .put("layerId",capture!!.getString("layerId")).put("points",points)
        session!!.active?.let { out.put("assistantId",it.id) }
        return out
    }
    fun draw(canvas: Canvas) {
        if(capture==null || samples.isEmpty())return
        val layer=Matrix();require(inverseLayer.invert(layer))
        canvas.save()
        try { canvas.concat(viewMatrix);canvas.concat(layer);ArtRenderer.drawStroke(canvas,parameters()) }
        finally { canvas.restore() }
    }
}
