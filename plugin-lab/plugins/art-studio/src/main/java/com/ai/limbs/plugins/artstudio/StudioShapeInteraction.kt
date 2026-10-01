package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.hypot

/** Gesture previews are transient. Only release commits one revision-bound operation. */
internal class StudioShapeInteraction(private val view: View) {
    private val density get() = view.resources.displayMetrics.density
    private var active = false
    private var document = ""
    private var revision = 0
    private var layerId = ""
    private var mode = ""
    private var add = false
    private var selected = emptyList<String>()
    private var hit: String? = null
    private var rect: RectF? = null
    private var delta = Matrix()
    private var down = PointF()
    private var downScreen = PointF()
    private var last = PointF()
    private var lastScreen = PointF()
    private var handle = -1
    private var moved = false
    private var editable = false
    var preserveAspect = false
    private var uniformResize = false
    fun previewMatrix(id:String):Matrix? = if(active && mode!="box" && id in selected) Matrix(delta) else null
    fun cancel() { active=false;rect=null;delta=Matrix();view.invalidate() }

    private fun handles(b:RectF):List<PointF> = listOf(
        PointF(b.left,b.top),PointF(b.centerX(),b.top),PointF(b.right,b.top),
        PointF(b.right,b.centerY()),PointF(b.right,b.bottom),PointF(b.centerX(),b.bottom),
        PointF(b.left,b.bottom),PointF(b.left,b.centerY()))
    private fun mapped(points:List<PointF>,m:Matrix):List<PointF> {
        val values=FloatArray(points.size*2)
        points.forEachIndexed { i,p -> values[i*2]=p.x;values[i*2+1]=p.y }
        m.mapPoints(values)
        return points.indices.map { PointF(values[it*2],values[it*2+1]) }
    }
    private fun rotationHandle(b:RectF,m:Matrix):PointF {
        val points=mapped(listOf(PointF(b.centerX(),b.centerY()),PointF(b.centerX(),b.top)),m)
        val dx=points[1].x-points[0].x;val dy=points[1].y-points[0].y
        val length=hypot(dx,dy)
        return if(length>0.001f) PointF(points[1].x+dx/length*28f*density,
            points[1].y+dy/length*28f*density)
        else PointF(points[1].x,points[1].y-28f*density)
    }
    fun draw(canvas:Canvas,layer:JSONObject,ids:List<String>,toScreen:Matrix,canEdit:Boolean) {
        val b=if(active&&mode!="box") rect else ArtShapes.bounds(layer,ids)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=if(canEdit) Color.rgb(120,190,255) else Color.GRAY
            style=Paint.Style.STROKE;strokeWidth=1.5f*density
        }
        if(active&&mode=="box") {
            val r=RectF(minOf(downScreen.x,lastScreen.x),minOf(downScreen.y,lastScreen.y),
                maxOf(downScreen.x,lastScreen.x),maxOf(downScreen.y,lastScreen.y))
            val p=Path().apply { addRect(r,Path.Direction.CW) }
            paint.color=if(lastScreen.x>=downScreen.x) Color.rgb(90,160,255) else Color.rgb(80,220,140)
            canvas.drawPath(p,paint)
            return
        }
        if(b==null) return
        val matrix=Matrix(toScreen)
        if(active) matrix.preConcat(delta)
        val corners=mapped(listOf(PointF(b.left,b.top),PointF(b.right,b.top),
            PointF(b.right,b.bottom),PointF(b.left,b.bottom)),matrix)
        val path=Path().apply { moveTo(corners[0].x,corners[0].y)
            corners.drop(1).forEach { lineTo(it.x,it.y) };close() }
        canvas.drawPath(path,paint)
        if(canEdit) {
            val centers=mapped(handles(b),matrix)
            val r=4f*density
            paint.style=Paint.Style.FILL
            for(p in centers) canvas.drawRect(p.x-r,p.y-r,p.x+r,p.y+r,paint)
            val rot=rotationHandle(b,matrix)
            canvas.drawCircle(rot.x,rot.y,5f*density,paint)
        }
    }
    private fun updateGesture(event:MotionEvent,local:FloatArray) {
        last=PointF(local[0],local[1]);lastScreen=PointF(event.x,event.y)
        moved=moved||hypot(event.x-downScreen.x,event.y-downScreen.y)>6f*density
        val b=rect
        if(editable&&moved&&b!=null) when(mode) {
            "move" -> delta=Matrix().apply { setTranslate(last.x-down.x,last.y-down.y) }
            "rotate" -> {
                val first=atan2(down.y-b.centerY(),down.x-b.centerX())
                val current=atan2(last.y-b.centerY(),last.x-b.centerX())
                var degrees=Math.toDegrees((current-first).toDouble()).toFloat()
                if((event.metaState and android.view.KeyEvent.META_CTRL_MASK != 0)) degrees=kotlin.math.round(degrees/45f)*45f
                delta=Matrix().apply { setRotate(degrees,b.centerX(),b.centerY()) }
            }
            "resize" -> {
                val h=handles(b)[handle]
                val pivot=PointF(if(h.x==b.centerX()) b.centerX() else if(h.x==b.left) b.right else b.left,
                    if(h.y==b.centerY()) b.centerY() else if(h.y==b.top) b.bottom else b.top)
                fun ratio(value:Float,denominator:Float):Float {
                    if(kotlin.math.abs(denominator)<0.001f) return 1f
                    val v=value/denominator
                    return if(v>=0f) v.coerceIn(0.01f,100f) else v.coerceIn(-100f,-0.01f)
                }
                var sx=ratio(last.x-pivot.x,h.x-pivot.x)
                var sy=ratio(last.y-pivot.y,h.y-pivot.y)
                if(uniformResize || ((event.metaState and android.view.KeyEvent.META_SHIFT_MASK != 0)&&handle in setOf(0,2,4,6))) {
                    val uniform=if(uniformResize && handle in setOf(1,5)) kotlin.math.abs(sy)
                        else if(uniformResize && handle in setOf(3,7)) kotlin.math.abs(sx)
                        else maxOf(kotlin.math.abs(sx),kotlin.math.abs(sy))
                    sx=if(sx<0f) -uniform else uniform;sy=if(sy<0f) -uniform else uniform
                }
                delta=Matrix().apply { setScale(sx,sy,pivot.x,pivot.y) }
            }
        }
    }
    fun touch(event:MotionEvent,local:FloatArray,toScreen:Matrix,state:JSONObject,
        documentId:String,currentRevision:Int,currentLayer:String,multiple:Boolean,busy:Boolean,
        commit:(String,JSONObject)->Unit):Boolean {
        if(busy) { cancel();return true }
        val layer=ArtShapes.layer(state,currentLayer)
        if(!ArtShapes.visible(state,layer)) { cancel();return true }
        if(event.actionMasked==MotionEvent.ACTION_CANCEL) { cancel();return true }
        if(active && (document!=documentId||revision!=currentRevision||layerId!=currentLayer)) {
            cancel()
            Toast.makeText(view.context,"画布已更新，请重新操作形状",Toast.LENGTH_SHORT).show()
            return true
        }
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active=true;uniformResize=preserveAspect;document=documentId;revision=currentRevision;layerId=currentLayer
                add=multiple||(event.metaState and android.view.KeyEvent.META_SHIFT_MASK != 0);selected=ArtShapes.selected(state,currentLayer)
                down=PointF(local[0],local[1]);last=PointF(down.x,down.y)
                downScreen=PointF(event.x,event.y);lastScreen=PointF(event.x,event.y);moved=false;delta=Matrix();handle=-1;hit=null
                rect=ArtShapes.bounds(layer,selected)
                val layerLocked=ArtMenuOperations.isLocked(state,layer)
                editable=!layerLocked&&ArtShapes.items(layer).filter { it.getString("id") in selected }
                    .none { it.getBoolean("locked") }
                val b=rect
                if(!add&&editable&&b!=null) {
                    val positions=mapped(handles(b),toScreen)+rotationHandle(b,toScreen)
                    handle=positions.indexOfFirst { hypot(it.x-event.x,it.y-event.y)<=12f*density }
                }
                if(handle>=0) mode=if(handle==8) "rotate" else "resize" else {
                    val unit=floatArrayOf(1f,0f);toScreen.mapVectors(unit)
                    val scale=hypot(unit[0],unit[1])
                    check(scale>0f&&scale.isFinite())
                    hit=ArtShapes.hit(layer,down.x,down.y,(6f*density/scale).coerceAtMost(1000000f))
                    if(!add && (hit!=null || b?.contains(down.x,down.y)==true)) {
                        val clicked=hit
                        if(clicked!=null && clicked !in selected) selected=listOf(clicked)
                        rect=ArtShapes.bounds(layer,selected)
                        editable=!layerLocked&&ArtShapes.items(layer).filter { it.getString("id") in selected }
                            .none { it.getBoolean("locked") }
                        mode="move"
                    } else mode="box"
                }
            }
            MotionEvent.ACTION_MOVE -> if(active) updateGesture(event,local)
            MotionEvent.ACTION_UP -> if(active) {
                updateGesture(event,local)
                // No movement: select only. No paint stroke or raster region is produced.
                val nextIds=if(!moved) {
                    val clicked=hit
                    if(handle>=0) selected else if(add) {
                        if(clicked==null) selected else if(clicked in selected) selected-clicked else selected+clicked
                    } else if(clicked!=null) listOf(clicked) else if(mode=="move") selected else emptyList()
                } else if(mode=="box") {
                    val r=RectF(minOf(downScreen.x,event.x),minOf(downScreen.y,event.y),
                        maxOf(downScreen.x,event.x),maxOf(downScreen.y,event.y))
                    val inverse=Matrix();check(toScreen.invert(inverse))
                    val clip=Path().apply { addRect(r,Path.Direction.CW);transform(inverse) }
                    val found=ArtShapes.box(layer,clip,event.x>=downScreen.x)
                    if(add) (selected+found).distinct() else found
                } else selected
                val p=JSONObject().put("documentId",document).put("expectedRevision",revision)
                    .put("layerId",layerId).put("ids",JSONArray(nextIds))
                val type=if(moved&&mode!="box"&&editable) {
                    p.put("matrix",ArtShapes.encode(delta));"SHAPE_TRANSFORM"
                } else "SHAPE_SELECT"
                active=false;rect=null;delta=Matrix()
                commit(type,p)
            }
        }
        view.invalidate()
        return true
    }
}
