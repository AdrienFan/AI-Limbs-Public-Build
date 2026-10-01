package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

internal class StudioCalligraphyInteraction(private val view: View) {
    private var captured: JSONObject?=null
    private var samples=JSONArray()
    private var preview: JSONObject?=null
    private var frame=Matrix()
    private var started=0L

    fun cancel() {
        if(captured==null && samples.length()==0) return
        captured=null;samples=JSONArray();preview=null;view.invalidate()
    }
    private fun current(document: String,revision: Int,layer: String): Boolean {
        val p=captured ?: return false
        return p.getString("documentId")==document && p.getInt("expectedRevision")==revision &&
            p.getString("layerId")==layer
    }
    fun draw(canvas: Canvas,toScreen: Matrix,document: String,revision: Int,layer: String) {
        if(!current(document,revision,layer)) return
        val shape=preview
        canvas.save()
        try {
            canvas.concat(toScreen)
            if(shape!=null) {
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=Color.parseColor(shape.getString("fill"));style=Paint.Style.FILL
                    alpha=(Color.alpha(color)*shape.getDouble("opacity")).toInt()
                }
                canvas.drawPath(ArtShapes.path(shape),paint)
            }
            else if(samples.length()>0) {
                val p=captured!!;val s=samples.getJSONObject(0)
                val width=p.getDouble("width").toFloat()*
                    (if(p.getBoolean("usePressure")) s.getDouble("pressure").toFloat() else 1f)
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=Color.parseColor(p.getString("color"))
                    alpha=(Color.alpha(color)*p.getDouble("opacity")).toInt()
                }
                canvas.translate(s.getDouble("x").toFloat(),s.getDouble("y").toFloat())
                canvas.rotate(p.getDouble("angle").toFloat())
                canvas.drawOval(-width/2f,-kotlin.math.max(0.05f,width*0.025f),
                    width/2f,kotlin.math.max(0.05f,width*0.025f),paint)
            }
        } finally { canvas.restore() }
    }
    private fun add(x: Float,y: Float,time: Long,pressure: Float,inverse: Matrix) {
        val xy=floatArrayOf(x,y);inverse.mapPoints(xy)
        val t=(time-started).coerceAtLeast(0L).toDouble()
        val s=JSONObject().put("x",xy[0].toDouble()).put("y",xy[1].toDouble())
            .put("time",t).put("pressure",pressure.coerceIn(0f,1f).toDouble())
        if(samples.length()>0) {
            val last=samples.getJSONObject(samples.length()-1)
            if(kotlin.math.hypot(s.getDouble("x")-last.getDouble("x"),
                s.getDouble("y")-last.getDouble("y"))<=0.000001) {
                // Release pressure is often zero; a stationary release must not erase the end.
                return
            }
        }
        require(samples.length()<ArtCalligraphy.MAX_SAMPLES) { "书法轨迹超过1000点，请将长笔画分段绘制" }
        samples.put(s)
    }
    fun touch(event: MotionEvent,toScreen: Matrix,state: JSONObject,document: String,revision: Int,
        layer: String,busy: Boolean,options: JSONObject,commit: (JSONObject)->Unit): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_CANCEL || busy) { cancel();return true }
        if(captured!=null && !current(document,revision,layer)) {
            cancel();error("工程已更新，请重新绘制书法笔画")
        }
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            cancel()
            val active=ArtShapes.layer(state,layer)
            require(active.getString("kind")=="vector") { "矢量书法笔需要矢量图层" }
            require(ArtShapes.visible(state,active) && !ArtMenuOperations.isLocked(state,active)) {
                "矢量图层或父组隐藏、锁定，不能绘制"
            }
            captured=JSONObject(options.toString()).put("documentId",document)
                .put("expectedRevision",revision).put("layerId",layer)
            frame=Matrix(toScreen);started=event.eventTime
        }
        val p=captured ?: return true
        val old=FloatArray(9);val now=FloatArray(9);frame.getValues(old);toScreen.getValues(now)
        require(old.indices.all { kotlin.math.abs(old[it]-now[it])<=0.0001f }) {
            "视图已改变，请重新绘制书法笔画"
        }
        val inverse=Matrix();check(frame.invert(inverse))
        fun pressure(value: Float): Float =
            if(event.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS ||
                event.getToolType(0)==MotionEvent.TOOL_TYPE_ERASER) value else 1f
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> add(event.x,event.y,event.eventTime,pressure(event.pressure),inverse)
            MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP -> {
                for(i in 0 until event.historySize) add(event.getHistoricalX(i),event.getHistoricalY(i),
                    event.getHistoricalEventTime(i),pressure(event.getHistoricalPressure(i)),inverse)
                add(event.x,event.y,event.eventTime,pressure(event.pressure),inverse)
            }
        }
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            val result=JSONObject(p.toString()).put("samples",JSONArray(samples.toString()))
            val moved=samples.length()>=2
            cancel()
            if(moved) commit(result)
        } else if(samples.length()>=2) {
            preview=ArtCalligraphy.create(JSONObject(p.toString()).put("samples",samples))
        }
        view.invalidate();return true
    }
}
