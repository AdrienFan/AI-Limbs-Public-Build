package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** A captured draft survives pointer-up only when explicitly held. Preview and commit share ArtLine geometry. */
internal class StudioLineInteraction {
    private var capture:JSONObject?=null
    private var style:JSONObject?=null
    private var state:JSONObject?=null
    private var viewMatrix=Matrix()
    private var inverseView=Matrix()
    private var layerMatrix=Matrix()
    private var inverseLayer=Matrix()
    private val samples=mutableListOf<ArtBrush.Sample>()
    private var pointer=AssistantPoint(0.0,0.0)
    private var started=0L
    private var altInitiallyHeld=false
    private var translating=false
    private var currentStep=0.0
    private var useGuides=true
    var density=1f
    var onDraft:(Boolean)->Unit={}
    val active get()=capture!=null
    fun cancel() {capture=null;style=null;state=null;samples.clear();translating=false;onDraft(false)}
    fun configure(step:Double,snap:Boolean) {
        if(!active)return
        currentStep=if(translating)0.0 else step;useGuides=snap
    }
    private fun mapped(s:ArtBrush.Sample,m:Matrix):ArtBrush.Sample {
        val xy=floatArrayOf(s.x.toFloat(),s.y.toFloat());m.mapPoints(xy);return s.copy(x=xy[0].toDouble(),y=xy[1].toDouble())
    }
    private fun same(m:Matrix):Boolean {
        val a=FloatArray(9);val b=FloatArray(9);m.getValues(a);viewMatrix.getValues(b)
        return a.indices.all {abs(a[it]-b[it])<0.001f}
    }
    private fun parameters():JSONObject {
        val context=requireNotNull(capture);val source=requireNotNull(state)
        val out=JSONObject(requireNotNull(style).toString()).put("documentId",context.getString("documentId"))
            .put("expectedRevision",context.getInt("expectedRevision")).put("layerId",context.getString("layerId"))
            .put("angleStep",currentStep).put("points",JSONArray(samples.map {mapped(it,inverseLayer).json()}))
        if(useGuides && !translating && currentStep==0.0 && samples.size>=2) {
            val a=samples.first();val b=samples.last();val values=FloatArray(9);viewMatrix.getValues(values)
            val scale=hypot(values[0],values[3]).toDouble();require(scale>0)
            val session=ArtAssistants.SnapSession(source,AssistantPoint(a.x,a.y),
                ArtAssistants.settings(source).getDouble("thresholdDp")*density/scale,ArtLine.guideTypes)
            session.complete(listOf(AssistantPoint(a.x,a.y),AssistantPoint(b.x,b.y)))
            session.active?.let {out.put("assistantId",it.id)}
        }
        return out
    }
    private fun nonzero()=samples.size>=2 && hypot(samples.last().x-samples.first().x,samples.last().y-samples.first().y)>0.00001
    fun command(command:String,documentId:String,revision:Int,layerId:String,busy:Boolean,onLine:(JSONObject)->Unit) {
        if(command=="cancel") {cancel();return}
        require(command=="finish" && !busy && active) {"没有可完成的直线"}
        val context=requireNotNull(capture)
        require(context.getString("documentId")==documentId && context.getInt("expectedRevision")==revision &&
            context.getString("layerId")==layerId) {"工程或图层已更新，请重新绘制"}
        require(nonzero()) {"直线起终点不能重合"}
        val p=parameters();ArtLine.geometry(p,requireNotNull(state))
        if(context.getString("kind")=="vector")p.remove("brush")
        cancel();onLine(p)
    }
    fun touch(event:MotionEvent,source:JSONObject,documentId:String,revision:Int,layerId:String,toScreen:Matrix,
        busy:Boolean,options:JSONObject,holdDraft:Boolean,moveStart:Boolean,onLine:(JSONObject)->Unit):Boolean {
        if(busy || event.actionMasked==MotionEvent.ACTION_CANCEL) {cancel();return true}
        if(event.actionMasked==MotionEvent.ACTION_DOWN && !active) {
            val layer=ArtMenuOperations.layers(source).first {it.getString("id")==layerId}
            require(layer.getString("kind") in setOf("paint","vector") && !ArtMenuOperations.isLocked(source,layer) && ArtShapes.visible(source,layer)) {"请选择可编辑的绘画或矢量图层"}
            layerMatrix=ArtShapes.layerMatrix(source,layer)
            require(toScreen.invert(inverseView) && layerMatrix.invert(inverseLayer))
            viewMatrix=Matrix(toScreen);state=source
            capture=JSONObject().put("documentId",documentId).put("expectedRevision",revision).put("layerId",layerId).put("kind",layer.getString("kind"))
            style=ArtLine.settings(options).put("brushSeed",java.util.Random().nextInt(Int.MAX_VALUE))
            started=event.eventTime;altInitiallyHeld=event.metaState and android.view.KeyEvent.META_ALT_ON!=0
            onDraft(true)
        }
        val context=capture ?: return true
        require(context.getString("documentId")==documentId && context.getInt("expectedRevision")==revision && context.getString("layerId")==layerId && same(toScreen)) {"工程、图层或视图已改变，请重新起笔"}
        val xy=floatArrayOf(event.x,event.y);inverseView.mapPoints(xy);val position=AssistantPoint(xy[0].toDouble(),xy[1].toDouble())
        val alt=event.metaState and android.view.KeyEvent.META_ALT_ON!=0
        if(!alt)altInitiallyHeld=false
        val moving=(moveStart || (alt && !altInitiallyHeld)) && nonzero()
        if(event.actionMasked==MotionEvent.ACTION_DOWN)pointer=position
        if(moving && !translating) {
            // Bake the displayed axis before translation, so entering Alt/move mode cannot jump away from a snapped line.
            val axis=ArtBrush.samples(ArtLine.geometry(parameters(),source).getJSONArray("points"))
            samples.clear();samples.addAll(axis.map {mapped(it,layerMatrix)})
        }
        translating=moving
        currentStep=if(moving)0.0 else if(event.metaState and android.view.KeyEvent.META_SHIFT_ON!=0)15.0 else options.optDouble("angleStep",0.0)
        useGuides=options.getBoolean("snapToAssistants")
        if(moving) {
            val delta=position-pointer
            for(i in samples.indices)samples[i]=samples[i].copy(x=samples[i].x+delta.x,y=samples[i].y+delta.y)
        } else {
            fun add(x:Float,y:Float,pressure:Float,time:Long,tilt:Float,orientation:Float) {
                require(samples.size<ArtBrush.MAX_SAMPLES) {"直线最多10000个采样"}
                val elapsed=max(time-started,samples.lastOrNull()?.time?.toLong() ?: 0L)
                require(elapsed<=180000) {"单次直线草稿最长3分钟"}
                val v=floatArrayOf(x,y);inverseView.mapPoints(v)
                samples.add(ArtBrush.Sample(v[0].toDouble(),v[1].toDouble(),pressure.coerceIn(0f,1f).toDouble(),elapsed.toDouble(),
                    (tilt/(PI/2)).coerceIn(0.0,1.0),((orientation+PI)/(2*PI)).coerceIn(0.0,1.0)))
            }
            if(event.actionMasked==MotionEvent.ACTION_MOVE)for(i in 0 until event.historySize)
                add(event.getHistoricalX(i),event.getHistoricalY(i),event.getHistoricalPressure(i),event.getHistoricalEventTime(i),
                    event.getHistoricalAxisValue(MotionEvent.AXIS_TILT,i),event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION,i))
            if(event.actionMasked in setOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP))
                add(event.x,event.y,event.pressure,event.eventTime,event.getAxisValue(MotionEvent.AXIS_TILT),event.getAxisValue(MotionEvent.AXIS_ORIENTATION))
        }
        pointer=position
        if(event.actionMasked==MotionEvent.ACTION_UP && !holdDraft) {
            if(nonzero())command("finish",documentId,revision,layerId,busy,onLine) else cancel()
        }
        return true
    }
    fun draw(canvas:Canvas,toScreen:Matrix,resources:((String)->Bitmap)?=null) {
        if(active && !same(toScreen)) {cancel();return}
        if(!active || !nonzero())return
        val line=ArtLine.geometry(parameters(),requireNotNull(state));canvas.save()
        try {
            canvas.concat(viewMatrix);canvas.concat(layerMatrix)
            if(requireNotNull(capture).getString("kind")=="paint") {
                val brush=ArtBrush.settings(line.getString("brushTool"),line.getJSONObject("brush"))
                ArtRenderer.drawStroke(canvas,ArtSoftSelection.bindStroke(ArtBrush.prepare(line,brush,line.getInt("brushSeed")),
                    requireNotNull(state).optJSONObject("selection"),inverseLayer),resources=resources)
            } else {
                val vector=JSONObject(line.toString()).put("points",line.getJSONArray("lineEndpoints"))
                vector.remove("brush");ArtRenderer.drawStroke(canvas,vector)
            }
            val ends=line.getJSONArray("lineEndpoints");val a=ends.getJSONArray(0);val b=ends.getJSONArray(1)
            val combined=Matrix(viewMatrix).apply {preConcat(layerMatrix)};val values=FloatArray(9);combined.getValues(values)
            val scale=hypot(values[0],values[3]);require(scale>0)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(80,160,240);style=Paint.Style.STROKE;strokeWidth=density/scale}
            canvas.drawLine(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),b.getDouble(0).toFloat(),b.getDouble(1).toFloat(),paint)
        } finally {canvas.restore()}
    }
}
