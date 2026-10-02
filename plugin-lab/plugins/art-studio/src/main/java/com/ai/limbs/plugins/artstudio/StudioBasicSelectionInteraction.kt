package com.ai.limbs.plugins.artstudio

import android.graphics.*
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Four basic tools capture document coordinates, revision and coverage options at first press. */
internal class StudioBasicSelectionInteraction {
    private var capture:JSONObject?=null
    private var tool=""
    private var toScreen=Matrix()
    private var inverse=Matrix()
    private val vertices=mutableListOf<PointF>()
    private var start=PointF()
    private var end=PointF()
    private var lastTap=0L
    var onDraft:(Boolean)->Unit={}
    val hasDraft get()=capture!=null
    fun cancel() {capture=null;vertices.clear();lastTap=0;onDraft(false)}
    private fun matches(id:String,revision:Int,m:Matrix):Boolean {
        val c=capture ?: return true;val a=FloatArray(9);val b=FloatArray(9);m.getValues(a);toScreen.getValues(b)
        return c.getString("documentId")==id&&c.getInt("expectedRevision")==revision&&a.indices.all {abs(a[it]-b[it])<0.001f}
    }
    private fun point(x:Float,y:Float)=floatArrayOf(x,y).let {inverse.mapPoints(it);PointF(it[0],it[1])}
    private fun append(p:PointF) {
        val last=vertices.lastOrNull()
        if(last!=null) {val a=floatArrayOf(last.x,last.y,p.x,p.y);toScreen.mapPoints(a);if(hypot(a[2]-a[0],a[3]-a[1])<2)return}
        require(vertices.size<2048) {"选区最多2048顶点，请分段添加选区"};vertices.add(p)
    }
    private fun parameters():JSONObject {
        val out=JSONObject(requireNotNull(capture).toString())
        return when(tool) {
            "select","select_ellipse"->out.put("shape",if(tool=="select")"rect" else "ellipse")
                .put("x",min(start.x,end.x)).put("y",min(start.y,end.y)).put("width",abs(end.x-start.x)).put("height",abs(end.y-start.y))
            else->out.put("shape",if(tool=="select_polygon")"polygon" else "freehand")
                .put("points",JSONArray().apply {vertices.forEach {put(JSONArray().put(it.x).put(it.y))}})
        }
    }
    fun command(action:String,id:String,revision:Int,m:Matrix,onCreate:(JSONObject)->Unit) {
        if(action=="cancel") {cancel();return}
        if(!hasDraft)return
        require(matches(id,revision,m)) {"工程或视图已改变，请重新建立选区"}
        when(action) {
            "back"->{if(vertices.isNotEmpty())vertices.removeAt(vertices.lastIndex);if(vertices.isEmpty())cancel()}
            "finish"->{val p=parameters()
                if(tool in setOf("select_polygon","select_freehand"))require(vertices.size>=3) {"请至少放置三个顶点"}
                else require(p.getDouble("width")>0&&p.getDouble("height")>0) {"选区面积为零"}
                cancel();onCreate(p)
            }
            else->error("选区命令无效")
        }
    }
    fun touch(e:MotionEvent,id:String,revision:Int,currentTool:String,m:Matrix,busy:Boolean,settings:JSONObject,onCreate:(JSONObject)->Unit):Boolean {
        if(busy||e.actionMasked==MotionEvent.ACTION_CANCEL||e.buttonState and MotionEvent.BUTTON_SECONDARY!=0) {cancel();return true}
        require(matches(id,revision,m)) {"工程或视图已改变，请重新建立选区"}
        if(e.actionMasked==MotionEvent.ACTION_DOWN) {
            if(tool!=currentTool&&hasDraft)cancel()
            if(!hasDraft) {
                tool=currentTool;toScreen=Matrix(m);require(m.invert(inverse))
                capture=ArtSoftSelection.options(settings).put("mode",ArtSoftSelection.mode(settings,e.metaState))
                    .put("documentId",id).put("expectedRevision",revision)
                start=point(e.x,e.y);end=start;onDraft(true)
                if(tool=="select_freehand")append(start)
            }
            end=point(e.x,e.y)
        }
        if(!hasDraft)return true
        end=point(e.x,e.y)
        if(tool=="select_freehand"&&e.actionMasked==MotionEvent.ACTION_MOVE) {
            for(i in 0 until e.historySize)append(point(e.getHistoricalX(i),e.getHistoricalY(i)));append(end)
        }
        if(e.actionMasked==MotionEvent.ACTION_UP) {
            if(tool=="select_polygon") {
                val first=vertices.firstOrNull();val a=first?.let {floatArrayOf(it.x,it.y).apply {toScreen.mapPoints(this)}}
                val close=a!=null&&hypot(e.x-a[0],e.y-a[1])<=20
                val last=vertices.lastOrNull();val b=last?.let {floatArrayOf(it.x,it.y).apply {toScreen.mapPoints(this)}}
                val doubleTap=e.eventTime-lastTap in 1L..350L&&b!=null&&hypot(e.x-b[0],e.y-b[1])<=20
                if(vertices.size>=3&&(close||doubleTap))command("finish",id,revision,m,onCreate)
                else {append(end);lastTap=e.eventTime}
            } else {
                if(tool=="select_freehand")append(end)
                if(tool=="select_freehand"&&vertices.size<3||tool!="select_freehand"&&(start.x==end.x||start.y==end.y))cancel()
                else command("finish",id,revision,m,onCreate)
            }
        };return true
    }
    fun draw(canvas:Canvas,id:String,revision:Int,m:Matrix) {
        if(!hasDraft)return
        if(!matches(id,revision,m)) {cancel();return}
        val path=Path()
        if(tool in setOf("select","select_ellipse")) {
            val box=RectF(min(start.x,end.x),min(start.y,end.y),max(start.x,end.x),max(start.y,end.y))
            if(tool=="select")path.addRect(box,Path.Direction.CW) else path.addOval(box,Path.Direction.CW)
        } else {
            vertices.forEachIndexed {i,p->if(i==0)path.moveTo(p.x,p.y) else path.lineTo(p.x,p.y)}
            if(vertices.isNotEmpty())path.lineTo(end.x,end.y)
            if(tool=="select_freehand")path.close()
        }
        path.transform(m)
        canvas.drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(52,150,255);style=Paint.Style.STROKE;strokeWidth=2f;pathEffect=DashPathEffect(floatArrayOf(8f,5f),0f)})
    }
}
