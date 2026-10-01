package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A captured enclosing gesture never changes the stored selection or writes before ACTION_UP. */
internal class StudioEncloseFillInteraction {
    private var capture: JSONObject?=null
    private var settings: JSONObject?=null
    private var toScreen=Matrix()
    private var inverse=Matrix()
    private val points=mutableListOf<Pair<Float,Float>>()
    private var width=0f
    private var height=0f
    fun cancel() {capture=null;settings=null;points.clear()}
    private fun same(matrix: Matrix): Boolean {
        val a=FloatArray(9);val b=FloatArray(9);matrix.getValues(a);toScreen.getValues(b)
        return a.indices.all {abs(a[it]-b[it])<0.001f}
    }
    fun touch(event: MotionEvent,state: JSONObject,documentId: String,revision: Int,layerId: String,
        matrix: Matrix,busy: Boolean,options: JSONObject,color: String,onFill: (JSONObject)->Unit): Boolean {
        if(busy || event.actionMasked==MotionEvent.ACTION_CANCEL) {cancel();return true}
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            cancel()
            val layer=ArtMenuOperations.layers(state).first {it.getString("id")==layerId}
            ArtEncloseFill.target(layer)
            settings=ArtEncloseFill.options(options)
            require(matrix.invert(inverse)) {"无法转换围合坐标"}
            toScreen=Matrix(matrix);width=state.getInt("width").toFloat();height=state.getInt("height").toFloat()
            capture=JSONObject().put("documentId",documentId).put("expectedRevision",revision)
                .put("layerId",layerId).put("color",color)
        }
        val captured=capture ?: return true
        require(captured.getString("documentId")==documentId && captured.getInt("expectedRevision")==revision &&
            captured.getString("layerId")==layerId && same(matrix)) {"工程、图层或视图已变化，请重新围合"}
        val shape=settings!!.getString("shape")
        fun add(x: Float,y: Float) {
            val xy=floatArrayOf(x,y);inverse.mapPoints(xy)
            require(xy.all {it.isFinite() && abs(it)<=1_000_000}) {"围合坐标无效"}
            val point=xy[0] to xy[1]
            if(points.lastOrNull()==point)return
            if(shape in setOf("rect","ellipse") && points.size==2)points[1]=point
            else {require(points.size<ArtEncloseFill.MAX_POINTS) {"围合路径超过2048点，请缩短手势"};points.add(point)}
        }
        if(event.actionMasked in setOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            if(event.actionMasked==MotionEvent.ACTION_MOVE && shape !in setOf("rect","ellipse"))
                for(i in 0 until event.historySize)add(event.getHistoricalX(i),event.getHistoricalY(i))
            add(event.x,event.y)
        }
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            val p=JSONObject(settings!!.toString())
            captured.keys().forEach {p.put(it,captured.get(it))}
            p.put("points",JSONArray().apply {points.forEach {put(JSONArray().put(it.first).put(it.second))}})
            cancel();onFill(p)
        }
        return true
    }
    fun draw(canvas: Canvas) {
        if(capture==null || points.isEmpty())return
        val options=requireNotNull(settings);val shape=options.getString("shape")
        val ready=when(shape) {"lasso"->points.size>=3;"brush"->true;else->points.size==2 &&
            points[0].first!=points[1].first && points[0].second!=points[1].second}
        val path=if(ready)ArtEncloseFill.path(options,points) else Path().apply {
            points.forEachIndexed {i,q->if(i==0)moveTo(q.first,q.second) else lineTo(q.first,q.second)}
        }
        canvas.save()
        try {
            canvas.concat(toScreen);canvas.clipRect(0f,0f,width,height)
            if(ready)canvas.drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.argb(45,80,210,230);style=Paint.Style.FILL})
            val values=FloatArray(9);toScreen.getValues(values)
            val scale=kotlin.math.hypot(values[Matrix.MSCALE_X].toDouble(),values[Matrix.MSKEW_Y].toDouble()).toFloat().coerceAtLeast(0.001f)
            canvas.drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.rgb(80,210,230);style=Paint.Style.STROKE;strokeWidth=2f/scale})
        } finally {canvas.restore()}
    }
}
