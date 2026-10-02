package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Guide projection and free brushes share capture, sensors, preview and final stroke normalization. */
internal class StudioAssistedBrushInteraction(private val view:View?=null) {
    private var capture:JSONObject?=null
    private var style:JSONObject?=null
    private var viewMatrix=Matrix()
    private var inverseView=Matrix()
    private var inverseLayer=Matrix()
    private var session:ArtAssistants.SnapSession?=null
    private val samples=mutableListOf<AssistantPoint>()
    private val sensors=mutableListOf<JSONArray>()
    private var started=0L
    val active get()=capture!=null
    var density=1f
    private val tick=object:Runnable {
        override fun run() {
            if(!active || samples.isEmpty())return
            try {
                require(samples.size<ArtBrush.MAX_SAMPLES)
                val t=(SystemClock.uptimeMillis()-started).toDouble();require(t<=180000)
                samples.add(samples.last());val sensor=JSONArray(sensors.last().toString()).put(1,t);sensors.add(sensor)
                session?.project(samples.last());view?.postInvalidateOnAnimation();view?.postDelayed(this,32)
            } catch(error:Exception) { cancel();android.widget.Toast.makeText(view?.context,error.message,android.widget.Toast.LENGTH_SHORT).show() }
        }
    }
    fun cancel() {view?.removeCallbacks(tick);capture=null;style=null;session=null;samples.clear();sensors.clear()}
    private fun same(m:Matrix):Boolean {
        val a=FloatArray(9);val b=FloatArray(9);m.getValues(a);viewMatrix.getValues(b)
        return a.indices.all {abs(a[it]-b[it])<0.001f}
    }
    fun touch(event:MotionEvent,state:JSONObject,documentId:String,revision:Int,layerId:String,
        toScreen:Matrix,busy:Boolean,options:JSONObject,onStroke:(JSONObject)->Unit,snap:Boolean=true):Boolean {
        if(busy || event.actionMasked==MotionEvent.ACTION_CANCEL){cancel();return true}
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            cancel();val layer=ArtMenuOperations.layers(state).first {it.getString("id")==layerId}
            require(layer.getString("kind")=="paint" && !layer.getBoolean("locked")) {"请使用未锁定绘画图层"}
            require(toScreen.invert(inverseView) && ArtShapes.layerMatrix(state,layer).invert(inverseLayer))
            capture=JSONObject().put("documentId",documentId).put("expectedRevision",revision).put("layerId",layerId)
            style=JSONObject(options.toString()).put("id",java.util.UUID.randomUUID().toString())
            if(options.has("brush"))style!!.put("brushSeed",java.util.Random().nextInt(Int.MAX_VALUE))
            viewMatrix=Matrix(toScreen);started=event.eventTime
            if(snap) {
                val start=floatArrayOf(event.x,event.y);inverseView.mapPoints(start)
                val values=FloatArray(9);toScreen.getValues(values);val scale=hypot(values[0],values[3]).toDouble();require(scale>0)
                session=ArtAssistants.SnapSession(state,AssistantPoint(start[0].toDouble(),start[1].toDouble()),
                    ArtAssistants.settings(state).getDouble("thresholdDp")*density/scale)
            }
        }
        val context=capture ?: return true
        require(context.getString("documentId")==documentId && context.getInt("expectedRevision")==revision &&
            context.getString("layerId")==layerId && same(toScreen)) {"工程、图层或视图已改变，请重新起笔"}
        fun add(x:Float,y:Float,pressure:Float,time:Long,tilt:Float,orientation:Float) {
            require(samples.size<ArtBrush.MAX_SAMPLES) {"单笔超过10000点，请分段绘制"}
            val elapsed=time-started;require(elapsed in 0..180000) {"单笔最长3分钟，请分段绘制"}
            val p=floatArrayOf(x,y);inverseView.mapPoints(p);val point=AssistantPoint(p[0].toDouble(),p[1].toDouble())
            samples.add(point);session?.project(point)
            sensors.add(JSONArray().put(pressure.coerceIn(if(style!!.has("brush"))0f else 0.1f,1f).toDouble()).put(elapsed)
                .put((tilt/(PI/2)).coerceIn(0.0,1.0)).put(((orientation+PI)/(2*PI)).coerceIn(0.0,1.0)))
        }
        if(event.actionMasked in setOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            // Historical samples can precede a stationary airbrush tick. Preserve chronological order.
            val lastTime=sensors.lastOrNull()?.getDouble(1) ?: -1.0
            if(event.actionMasked==MotionEvent.ACTION_MOVE)for(i in 0 until event.historySize)
                if(event.getHistoricalEventTime(i)-started>=lastTime)add(event.getHistoricalX(i),event.getHistoricalY(i),event.getHistoricalPressure(i),
                    event.getHistoricalEventTime(i),event.getHistoricalAxisValue(MotionEvent.AXIS_TILT,i),event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION,i))
            add(event.x,event.y,event.pressure,max(event.eventTime,started+lastTime.toLong()),
                event.getAxisValue(MotionEvent.AXIS_TILT),event.getAxisValue(MotionEvent.AXIS_ORIENTATION))
        }
        if(event.actionMasked==MotionEvent.ACTION_DOWN && style!!.has("brush") &&
            style!!.getJSONObject("brush").getDouble("airbrushRate")>0)view?.postDelayed(tick,32)
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            val stroke=parameters().put("documentId",context.getString("documentId")).put("expectedRevision",context.getInt("expectedRevision"))
            cancel();onStroke(stroke)
        };return true
    }
    private fun parameters():JSONObject {
        val points=JSONArray();val projected=session?.complete(samples) ?: samples
        projected.forEachIndexed {i,p ->
            val v=floatArrayOf(p.x.toFloat(),p.y.toFloat());inverseLayer.mapPoints(v)
            val point=JSONArray().put(v[0]).put(v[1]).put(sensors[i].getDouble(0))
            if(style!!.has("brush"))for(n in 1..3)point.put(sensors[i].getDouble(n))
            points.put(point)
        }
        val out=JSONObject(style!!.toString()).put("layerId",capture!!.getString("layerId")).put("points",points)
        session?.active?.let {out.put("assistantId",it.id)};return out
    }
    fun draw(canvas:Canvas,resources:((String)->Bitmap)?=null) {
        if(capture==null || samples.isEmpty())return
        val layer=Matrix();require(inverseLayer.invert(layer));canvas.save()
        try {
            canvas.concat(viewMatrix);canvas.concat(layer);val raw=parameters()
            val stroke=if(raw.has("brush"))ArtBrush.prepare(raw,raw.getJSONObject("brush"),raw.getInt("brushSeed"),false) else raw
            ArtRenderer.drawStroke(canvas,stroke,resources=resources)
        } finally {canvas.restore()}
    }
}
