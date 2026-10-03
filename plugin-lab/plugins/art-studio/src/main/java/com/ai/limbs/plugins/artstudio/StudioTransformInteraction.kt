package com.ai.limbs.plugins.artstudio

import android.graphics.*
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Draft only. Explicit Apply owns the guarded write, never pointer release. */
internal class StudioTransformInteraction {
    var density=1f
    var request:JSONObject?=null
    var onDraft:(JSONObject)->Unit={}
    private var before:JSONObject?=null
    private var selected=-1
    private var start= floatArrayOf(0f,0f)
    fun cancel() {request=null;before=null;selected=-1}
    fun pause() {before?.let {request=it;onDraft(JSONObject(it.toString()))};before=null;selected=-1}
    fun touch(event:MotionEvent,matrix:Matrix,busy:Boolean):Boolean {
        if(busy)return true
        val p=request ?: return true
        val inverse=Matrix();require(matrix.invert(inverse));val q=floatArrayOf(event.x,event.y);inverse.mapPoints(q)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                before=JSONObject(p.toString());start=q;selected=-1
                p.optJSONArray("points")?.let {points->
                    var nearest=24*density
                    for(i in 0 until points.length()) {
                        val a=points.getJSONArray(i);val xy=floatArrayOf(a.getDouble(0).toFloat(),a.getDouble(1).toFloat());matrix.mapPoints(xy)
                        val d=hypot(event.x-xy[0],event.y-xy[1]);if(d<nearest){nearest=d;selected=i}
                    }
                }
            }
            MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP -> {
                val old=before ?: return true;val next=JSONObject(old.toString())
                when(p.getString("mode")) {
                    "affine" -> next.put("dx",old.optDouble("dx",0.0)+q[0]-start[0]).put("dy",old.optDouble("dy",0.0)+q[1]-start[1])
                    "liquify" -> if(event.actionMasked==MotionEvent.ACTION_UP) {
                        val dabs=next.optJSONArray("dabs") ?: JSONArray();require(dabs.length()<128)
                        val dab=JSONObject().put("kind",next.optString("liquifyKind","push")).put("x",start[0]).put("y",start[1])
                            .put("radius",next.optDouble("radius",64.0)).put("strength",next.optDouble("strength",0.2)).put("angle",next.optDouble("twirlAngle",30.0))
                            .put("dx",q[0]-start[0]).put("dy",q[1]-start[1])
                        dabs.put(dab);next.put("dabs",dabs)
                    }
                    else -> if(selected>=0)next.getJSONArray("points").put(selected,JSONArray(listOf(q[0].toDouble(),q[1].toDouble())))
                }
                request=next;onDraft(JSONObject(next.toString()))
                if(event.actionMasked==MotionEvent.ACTION_UP){before=null;selected=-1}
            }
            MotionEvent.ACTION_CANCEL -> pause()
        };return true
    }
    fun draw(canvas:Canvas,matrix:Matrix) {
        val p=request ?: return
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(255,205,85);strokeWidth=density;style=Paint.Style.STROKE}
        val a=p.optJSONArray("points")
        if(a!=null)for(i in 0 until a.length()) {
            val v=a.getJSONArray(i);val q=floatArrayOf(v.getDouble(0).toFloat(),v.getDouble(1).toFloat());matrix.mapPoints(q)
            canvas.drawCircle(q[0],q[1],6*density,paint)
        }
        p.optJSONArray("dabs")?.let {dabs->for(i in 0 until dabs.length()) {
            val d=dabs.getJSONObject(i);val q=floatArrayOf(d.getDouble("x").toFloat(),d.getDouble("y").toFloat());matrix.mapPoints(q)
            canvas.drawCircle(q[0],q[1],5*density,paint)
        }}
        val box=p.getJSONObject("sourceBounds")
        val r=Rect(floor(box.getDouble("x")).toInt(),floor(box.getDouble("y")).toInt(),ceil(box.getDouble("x")+box.getDouble("width")).toInt(),ceil(box.getDouble("y")+box.getDouble("height")).toInt())
        val vertices=floatArrayOf(r.left.toFloat(),r.top.toFloat(),r.right.toFloat(),r.top.toFloat(),r.right.toFloat(),r.bottom.toFloat(),r.left.toFloat(),r.bottom.toFloat());matrix.mapPoints(vertices)
        paint.pathEffect=DashPathEffect(floatArrayOf(5*density,4*density),0f)
        for(i in 0..3){val j=(i+1)%4;canvas.drawLine(vertices[i*2],vertices[i*2+1],vertices[j*2],vertices[j*2+1],paint)}
    }
}
