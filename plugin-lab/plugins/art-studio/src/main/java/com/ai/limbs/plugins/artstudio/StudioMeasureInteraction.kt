package com.ai.limbs.plugins.artstudio

import android.graphics.*
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject
import kotlin.math.*

internal class StudioMeasureInteraction {
    var density=1f
    var settings=ArtMeasure.defaults()
    var onBaseline:(Double)->Unit={}
    private var line:JSONObject?=null
    private var before:JSONObject?=null
    private var start= floatArrayOf(0f,0f)
    private var action="new"
    private var active=false
    fun cancel() {line=null;before=null;active=false}
    fun pause() {if(active)line=before;active=false;before=null}
    fun touch(event:MotionEvent,matrix:Matrix):Boolean {
        val inverse=Matrix();require(matrix.invert(inverse));val q=floatArrayOf(event.x,event.y);inverse.mapPoints(q)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                before=line?.let {JSONObject(it.toString())};start=q;active=true
                val alt=(event.metaState and KeyEvent.META_ALT_ON)!=0;val ctrl=(event.metaState and KeyEvent.META_CTRL_ON)!=0
                action=if(ctrl||settings.getString("dragMode")=="baseline")"baseline" else if(alt||settings.getString("dragMode")=="translate")"translate" else settings.getString("dragMode")
                if(action=="auto") {
                    action="new"
                    before?.let {l->val ends=floatArrayOf(l.getDouble("x0").toFloat(),l.getDouble("y0").toFloat(),l.getDouble("x1").toFloat(),l.getDouble("y1").toFloat());matrix.mapPoints(ends)
                        val a=hypot(event.x-ends[0],event.y-ends[1]);val b=hypot(event.x-ends[2],event.y-ends[3])
                        val dx=ends[2]-ends[0];val dy=ends[3]-ends[1];val len=dx*dx+dy*dy
                        val t=if(len>0)((event.x-ends[0])*dx+(event.y-ends[1])*dy)/len else 0f
                        val near=hypot(event.x-(ends[0]+t.coerceIn(0f,1f)*dx),event.y-(ends[1]+t.coerceIn(0f,1f)*dy))
                        action=when {a<14*density->"start";b<14*density->"end";near<10*density&&t in 0f..1f->"translate";else->"new"}
                    }
                }
                if(action=="translate")require(before!=null) {"请先画一条测量线"}
                if(action=="new")line=JSONObject().put("x0",q[0]).put("y0",q[1]).put("x1",q[0]).put("y1",q[1])
            }
            MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP -> {
                if(!active)return true
                if(action=="baseline") {
                    val origin=before?.let {floatArrayOf(it.getDouble("x0").toFloat(),it.getDouble("y0").toFloat())} ?: start
                    if(hypot(q[0]-origin[0],q[1]-origin[1])>1e-6 && event.actionMasked==MotionEvent.ACTION_UP)
                        onBaseline(Math.toDegrees(atan2((q[1]-origin[1]).toDouble(),(q[0]-origin[0]).toDouble())))
                } else {
                    val p=if(action=="new")requireNotNull(line).let {JSONObject(it.toString())} else JSONObject(requireNotNull(before).toString())
                    when(action) {
                        "translate" -> {p.put("dx",q[0]-start[0]).put("dy",q[1]-start[1])}
                        "start" -> {p.put("x0",q[0]).put("y0",q[1])}
                        else -> {p.put("x1",q[0]).put("y1",q[1])}
                    }
                    val options=JSONObject(settings.toString())
                    if(action=="translate")options.put("angleStep",0.0)
                    else if((event.metaState and KeyEvent.META_SHIFT_ON)!=0 && options.getDouble("angleStep")==0.0)options.put("angleStep",15.0)
                    val result=ArtMeasure.evaluate(p,options)
                    line=JSONObject().apply {for(k in listOf("x0","y0","x1","y1"))put(k,result.get(k))}
                }
                if(event.actionMasked==MotionEvent.ACTION_UP){active=false;before=null}
            }
            MotionEvent.ACTION_CANCEL -> pause()
        };return true
    }
    fun draw(canvas:Canvas,matrix:Matrix) {
        val l=line ?: return
        val result=ArtMeasure.evaluate(l,JSONObject(settings.toString()).put("angleStep",0.0))
        val q=floatArrayOf(l.getDouble("x0").toFloat(),l.getDouble("y0").toFloat(),l.getDouble("x1").toFloat(),l.getDouble("y1").toFloat());matrix.mapPoints(q)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;strokeWidth=2*density;style=Paint.Style.STROKE}
        canvas.drawLine(q[0],q[1],q[2],q[3],paint);canvas.drawCircle(q[0],q[1],5*density,paint);canvas.drawCircle(q[2],q[3],5*density,paint)
        val base=Math.toRadians(settings.getDouble("baseline"));val ray=floatArrayOf(l.getDouble("x0").toFloat(),l.getDouble("y0").toFloat(),(l.getDouble("x0")+cos(base)*80).toFloat(),(l.getDouble("y0")+sin(base)*80).toFloat());matrix.mapPoints(ray)
        val length=hypot(ray[2]-ray[0],ray[3]-ray[1]);if(length>0){paint.color=Color.rgb(255,205,85);canvas.drawLine(q[0],q[1],q[0]+(ray[2]-ray[0])/length*48*density,q[1]+(ray[3]-ray[1])/length*48*density,paint)}
        paint.color=Color.WHITE;paint.style=Paint.Style.FILL;paint.textSize=14*density;paint.setShadowLayer(3f,0f,0f,Color.BLACK)
        canvas.drawText(String.format(java.util.Locale.ROOT,"%.3f %s  ∠ %.1f°",result.getDouble("distance"),result.getString("unit"),result.getDouble("relativeDegrees")),q[2]+10*density,q[3]-12*density,paint)
    }
}
