package com.ai.limbs.plugins.artstudio

import android.graphics.*
import android.view.MotionEvent
import org.json.JSONObject
import kotlin.math.hypot

/** Pending crop is view-local. Pointer release never changes document history. */
internal class StudioCropInteraction {
    var density = 1f
    var onDraft: (JSONObject?) -> Unit = {}
    private var rectangle: ArtCrop.Rect? = null
    private var binding: JSONObject? = null
    private var gestureBase: ArtCrop.Rect? = null
    private var handle: String? = null
    private var downX = 0.0; private var downY = 0.0
    private var settings = ArtCrop.defaults()
    fun cancel() { rectangle=null; binding=null; handle=null; onDraft(null) }
    fun pause() { handle=null }
    fun configure(value: JSONObject, state: JSONObject?) {
        val next=ArtCrop.settings(value)
        if(next.toString()==settings.toString())return
        val adjusted=rectangle?.let {rect->
            val scene=requireNotNull(state)
            ArtCrop.rect(ArtCrop.resolve(JSONObject(next.toString()).apply {val p=rect.json();p.keys().forEach {put(it,p.get(it))}},scene.getInt("width"),scene.getInt("height")))
        }
        settings=next;rectangle=adjusted;handle=null;onDraft(draft())
    }
    private fun capture(documentId: String, revision: Int, layerId: String) = JSONObject()
        .put("documentId",documentId).put("expectedRevision",revision).put("layerId",layerId)
    private fun draft(): JSONObject? = rectangle?.let {
        JSONObject(settings.toString()).apply {
            val rect=it.json();rect.keys().forEach {key->put(key,rect.get(key))}
            binding?.let {bound->bound.keys().forEach {key->put(key,bound.get(key))}}
        }
    }
    fun set(p: JSONObject, state: JSONObject, documentId: String, revision: Int, layerId: String) {
        val plan=ArtCrop.resolve(JSONObject(settings.toString()).apply {p.keys().forEach {put(it,p.get(it))}},state.getInt("width"),state.getInt("height"))
        if(binding==null)binding=capture(documentId,revision,layerId)
        require(binding!!.getString("documentId")==documentId && binding!!.getInt("expectedRevision")==revision) {"工程已更新，请重建裁剪框"}
        rectangle=ArtCrop.rect(plan);onDraft(draft())
    }
    fun finish(documentId: String, revision: Int, layerId: String, submit: (JSONObject)->Unit) {
        val bound=binding ?: error("请先拖出裁剪框，或应用坐标")
        require(bound.getString("documentId")==documentId && bound.getInt("expectedRevision")==revision) {"工程已更新，请重建裁剪框"}
        if(settings.getString("target")=="layer")require(bound.getString("layerId")==layerId) {"活动图层已切换"}
        handle=null;submit(requireNotNull(draft()))
    }
    private fun handles(rect: ArtCrop.Rect): List<Pair<String,Pair<Float,Float>>> {
        val x=rect.x.toFloat();val y=rect.y.toFloat();val r=rect.right.toFloat();val b=rect.bottom.toFloat()
        return listOf("nw" to (x to y),"n" to ((x+r)/2 to y),"ne" to (r to y),"e" to (r to (y+b)/2),
            "se" to (r to b),"s" to ((x+r)/2 to b),"sw" to (x to b),"w" to (x to (y+b)/2))
    }
    fun touch(event: MotionEvent, state: JSONObject, documentId: String, revision: Int, layerId: String,
        matrix: Matrix, busy: Boolean): Boolean {
        if(busy)return true
        val inverse=Matrix();require(matrix.invert(inverse))
        val point=floatArrayOf(event.x,event.y);inverse.mapPoints(point)
        val x=point[0].toDouble();val y=point[1].toDouble()
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                binding?.let {if(it.getString("documentId")!=documentId || it.getInt("expectedRevision")!=revision)cancel()}
                downX=x;downY=y;gestureBase=rectangle
                var selected: String? = null
                rectangle?.let {rect->
                    selected=handles(rect).map { (id,p) ->
                        val screen=floatArrayOf(p.first,p.second);matrix.mapPoints(screen)
                        id to hypot(event.x-screen[0],event.y-screen[1])
                    }.filter {it.second<=18*density}.minByOrNull {it.second}?.first
                    if(selected==null && x>=rect.x && x<=rect.right && y>=rect.y && y<=rect.bottom)selected="move"
                }
                handle=selected ?: "create"
                if(handle=="create") {gestureBase=null;binding=capture(documentId,revision,layerId)}
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val selected=handle ?: return true
                rectangle=ArtCrop.drag(gestureBase,selected,downX,downY,x,y,settings,state.getInt("width"),state.getInt("height"))
                onDraft(draft())
                if(event.actionMasked==MotionEvent.ACTION_UP)handle=null
            }
            MotionEvent.ACTION_CANCEL -> {rectangle=gestureBase;handle=null;if(rectangle==null)binding=null;onDraft(draft())}
        }
        return true
    }
    fun draw(canvas: Canvas, matrix: Matrix) {
        val rect=rectangle ?: return
        fun screen(x:Float,y:Float):FloatArray=floatArrayOf(x,y).also {matrix.mapPoints(it)}
        val corners=listOf(screen(rect.x.toFloat(),rect.y.toFloat()),screen(rect.right.toFloat(),rect.y.toFloat()),
            screen(rect.right.toFloat(),rect.bottom.toFloat()),screen(rect.x.toFloat(),rect.bottom.toFloat()))
        val outline=Path().apply {corners.forEachIndexed {i,p->if(i==0)moveTo(p[0],p[1]) else lineTo(p[0],p[1])};close()}
        val shade=Path().apply {fillType=Path.FillType.EVEN_ODD;addRect(0f,0f,canvas.width.toFloat(),canvas.height.toFloat(),Path.Direction.CW);addPath(outline)}
        canvas.drawPath(shade,Paint().apply {color=Color.argb(75,0,0,0)})
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=2*density}
        canvas.drawPath(outline,paint)
        paint.color=Color.rgb(255,205,85);paint.strokeWidth=density
        ArtCrop.lines(rect,settings.getString("guides")).forEach {line->
            val points=floatArrayOf(line[0].toFloat(),line[1].toFloat(),line[2].toFloat(),line[3].toFloat());matrix.mapPoints(points)
            canvas.drawLine(points[0],points[1],points[2],points[3],paint)
        }
        handles(rect).forEach {(_,p)->val point=screen(p.first,p.second)
            canvas.drawCircle(point[0],point[1],5*density,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE})
            canvas.drawCircle(point[0],point[1],5*density,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;style=Paint.Style.STROKE;strokeWidth=density})
        }
        canvas.drawText("${rect.width} × ${rect.height}  (${rect.x}, ${rect.y})",corners[0][0],corners[0][1]-10*density,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;textSize=14*density})
    }
}
