package com.ai.limbs.plugins.artstudio

import android.graphics.*
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/** Collect a bounded document-space seed path; all writes occur after a valid pointer release. */
internal class StudioContiguousFillInteraction(private val view:View) {
    private var capture:JSONObject?=null
    private var frame=Matrix()
    private var inverse=Matrix()
    private val points=mutableListOf<Pair<Int,Int>>()
    var onDraft:(Boolean)->Unit={}
    val hasDraft get()=capture!=null
    fun cancel() {val existed=hasDraft;capture=null;points.clear();if(existed)onDraft(false);view.invalidate()}
    fun touch(event:MotionEvent,doc:String,revision:Int,layerId:String,m:Matrix,w:Int,h:Int,busy:Boolean,
        settings:JSONObject,color:String,opacity:Double,onFill:(JSONObject)->Unit):Boolean {
        if(busy || event.actionMasked==MotionEvent.ACTION_CANCEL || event.buttonState and MotionEvent.BUTTON_SECONDARY!=0) {cancel();return true}
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            cancel();require(m.invert(inverse));frame=Matrix(m)
            capture=ArtContiguousFill.options(JSONObject(settings.toString()).put("color",color).put("opacity",opacity))
                .put("documentId",doc).put("expectedRevision",revision).put("layerId",layerId)
            onDraft(true)
        }
        val p=capture ?: return true
        require(p.getString("documentId")==doc && p.getInt("expectedRevision")==revision && p.getString("layerId")==layerId) {"工程或目标层已改变，请重新填充"}
        val a=FloatArray(9);val b=FloatArray(9);m.getValues(a);frame.getValues(b)
        require(a.indices.all {abs(a[it]-b[it])<0.0001f}) {"视图已变化，请重新拖动填充"}
        fun append(x:Float,y:Float,force:Boolean) {
            val local=floatArrayOf(x,y);inverse.mapPoints(local)
            if(local[0]<0 || local[1]<0 || local[0]>=w || local[1]>=h)return
            val point=local[0].toInt() to local[1].toInt()
            if(points.isNotEmpty() && (p.getString("dragMode")=="off" || point==points.last()))return
            if(!force && points.isNotEmpty() && hypot((point.first-points.last().first).toDouble(),(point.second-points.last().second).toDouble())<2)return
            require(points.size<ArtContiguousFill.MAX_POINTS) {"一次拖动最多512折线点，请分段填充"}
            points.add(point)
        }
        if(event.actionMasked in setOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            for(i in 0 until event.historySize)append(event.getHistoricalX(i),event.getHistoricalY(i),false)
            append(event.x,event.y,event.actionMasked!=MotionEvent.ACTION_MOVE)
        }
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            if(points.isEmpty()) {cancel();return true}
            val result=JSONObject(p.toString()).put("points",JSONArray().apply {points.forEach {put(JSONArray().put(it.first).put(it.second))}})
            cancel();onFill(result)
        }
        view.invalidate();return true
    }
    fun draw(canvas:Canvas,m:Matrix) {
        if(!hasDraft || points.isEmpty())return
        val path=Path()
        points.forEachIndexed {i,p->val q=floatArrayOf(p.first+0.5f,p.second+0.5f);m.mapPoints(q)
            if(i==0)path.moveTo(q[0],q[1]) else path.lineTo(q[0],q[1])}
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(60,185,225);style=Paint.Style.STROKE;strokeWidth=2*view.resources.displayMetrics.density}
        canvas.drawPath(path,paint)
        val seed=floatArrayOf(points.first().first+0.5f,points.first().second+0.5f);m.mapPoints(seed)
        canvas.drawCircle(seed[0],seed[1],5*view.resources.displayMetrics.density,paint)
    }
}
