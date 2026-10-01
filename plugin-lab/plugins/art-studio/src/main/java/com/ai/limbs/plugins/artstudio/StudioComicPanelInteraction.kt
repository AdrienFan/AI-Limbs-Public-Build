package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal class StudioComicPanelInteraction {
    private var capture: JSONObject?=null
    private var toScreen=Matrix()
    private var inverse=Matrix()
    private var start: ArtComicPanels.Point?=null
    private var end: ArtComicPanels.Point?=null
    private var mode="cut"
    private var gap=0.0
    fun cancel() {capture=null;start=null;end=null}
    private fun same(m: Matrix): Boolean {
        val a=FloatArray(9);val b=FloatArray(9);m.getValues(a);toScreen.getValues(b)
        return a.indices.all {abs(a[it]-b[it])<0.001f}
    }
    fun touch(event: MotionEvent,state: JSONObject,documentId: String,revision: Int,layerId: String,
        matrix: Matrix,busy: Boolean,settings: JSONObject,onApply: (String,JSONObject)->Unit): Boolean {
        if(busy || event.actionMasked==MotionEvent.ACTION_CANCEL) {cancel();return true}
        if(event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) &&
            event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {cancel();return true}
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            cancel();ArtComicPanels.target(state,layerId)
            require(matrix.invert(inverse));toScreen=Matrix(matrix)
            capture=ArtComicPanels.options(settings).put("documentId",documentId)
                .put("expectedRevision",revision).put("layerId",layerId)
            if(capture!!.getBoolean("selectedOnly")) {
                val ids=ArtShapes.selected(state,layerId)
                require(ids.isNotEmpty()) {"请先用形状选择工具选中要处理的分格"}
                capture!!.put("ids",JSONArray(ids))
            }
            mode=capture!!.getString("mode")
        }
        val p=capture ?: return true
        require(p.getString("documentId")==documentId && p.getInt("expectedRevision")==revision &&
            p.getString("layerId")==layerId && same(matrix)) {"工程、图层或视图已变化，请重新拖动"}
        if(event.actionMasked in setOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            val v=floatArrayOf(event.x,event.y);inverse.mapPoints(v)
            require(v.all {it.isFinite() && abs(it)<=1000000})
            val q=ArtComicPanels.Point(v[0].toDouble(),v[1].toDouble())
            if(start==null)start=q
            end=q
            gap=ArtComicPanels.width(p,start!!,q)
        }
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            val a=start!!;val b=end!!
            val result=JSONObject(p.toString()).put("start",JSONArray().put(a.x).put(a.y))
                .put("end",JSONArray().put(b.x).put(b.y))
            val operation=mode;cancel();onApply(operation,result)
        }
        return true
    }
    fun draw(canvas: Canvas) {
        val a=start ?: return;val b=end ?: return
        val values=FloatArray(9);toScreen.getValues(values)
        val scale=kotlin.math.hypot(values[Matrix.MSCALE_X].toDouble(),values[Matrix.MSKEW_Y].toDouble()).toFloat().coerceAtLeast(0.001f)
        canvas.save()
        try {
            canvas.concat(toScreen)
            if(mode=="cut" && gap>0)canvas.drawLine(a.x.toFloat(),a.y.toFloat(),b.x.toFloat(),b.y.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.argb(65,80,210,230);strokeWidth=gap.toFloat()})
            canvas.drawLine(a.x.toFloat(),a.y.toFloat(),b.x.toFloat(),b.y.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {color=if(mode=="cut")Color.rgb(80,210,230) else Color.rgb(245,180,65);strokeWidth=2f/scale})
        } finally {canvas.restore()}
    }
}
