package com.ai.limbs.plugins.artstudio

import android.graphics.*
import android.view.MotionEvent
import org.json.JSONObject
import kotlin.math.*

/** Pointer input captures a revision. Picking/projection and writes run on the caller's worker thread. */
internal class StudioMoveInteraction {
    var density=1f
    private var request:JSONObject?=null
    private var startX=0.0;private var startY=0.0
    private var dx=0.0;private var dy=0.0
    private var pixelMode=false
    private var options=ArtMove.defaults()
    fun cancel() {request=null;dx=0.0;dy=0.0}
    fun touch(event:MotionEvent,state:JSONObject,documentId:String,revision:Int,layerId:String,matrix:Matrix,
        busy:Boolean,settings:JSONObject,submit:(JSONObject)->Unit):Boolean {
        if(busy)return true
        val inverse=Matrix();require(matrix.invert(inverse));val point=floatArrayOf(event.x,event.y);inverse.mapPoints(point)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                options=ArtMove.validateSettings(JSONObject(settings.toString()))
                pixelMode=ArtMove.selectionMode(state,options.getString("moveScope"))
                startX=point[0].toDouble();startY=point[1].toDouble();dx=0.0;dy=0.0
                request=JSONObject(options.toString()).apply {
                    remove("revision");put("documentId",documentId);put("expectedRevision",revision);put("unit","px")
                    if(pixelMode || options.getString("layerMode")=="current")put("layerId",layerId)
                    else {put("pickX",floor(startX).toInt());put("pickY",floor(startY).toInt())}
                }
            }
            MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP -> {
                val p=request ?: return true
                require(p.getString("documentId")==documentId && p.getInt("expectedRevision")==revision) {"工程已更新，请重新开始移动"}
                dx=point[0]-startX;dy=point[1]-startY
                if((event.metaState and android.view.KeyEvent.META_SHIFT_ON)!=0) {if(abs(dx)>=abs(dy))dy=0.0 else dx=0.0}
                if((event.metaState and android.view.KeyEvent.META_ALT_ON)!=0) {dx*=0.2;dy*=0.2}
                if(pixelMode) {dx=dx.roundToInt().toDouble();dy=dy.roundToInt().toDouble()}
                if(event.actionMasked==MotionEvent.ACTION_UP) {
                    val payload=JSONObject(p.toString()).put("dx",dx).put("dy",dy)
                    cancel();submit(payload)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }
    fun draw(canvas:Canvas,matrix:Matrix) {
        if(request==null)return
        val line=floatArrayOf(startX.toFloat(),startY.toFloat(),(startX+dx).toFloat(),(startY+dy).toFloat());matrix.mapPoints(line)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(255,205,85);strokeWidth=2*density}
        canvas.drawLine(line[0],line[1],line[2],line[3],paint);canvas.drawCircle(line[2],line[3],5*density,paint)
        val unit=options.getString("unit");val ppi=options.getDouble("ppi")
        val text=String.format(java.util.Locale.ROOT,"Δ %.3f, %.3f %s",ArtMove.fromPixels(dx,unit,ppi),ArtMove.fromPixels(dy,unit,ppi),unit)
        paint.color=Color.WHITE;paint.textSize=14*density
        canvas.drawText(text,line[2]+8*density,line[3]-10*density,paint)
    }
}
