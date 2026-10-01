package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal class StudioColorizeInteraction {
    private var context: JSONObject?=null
    private var style: JSONObject?=null
    private var screen=Matrix()
    private var inverse=Matrix()
    private var points=JSONArray()
    fun cancel() {context=null;style=null;points=JSONArray()}
    private fun same(matrix: Matrix): Boolean {
        val a=FloatArray(9);val b=FloatArray(9);matrix.getValues(a);screen.getValues(b)
        return a.indices.all {abs(a[it]-b[it])<0.001f}
    }
    fun touch(event: MotionEvent,state: JSONObject,doc: String,revision: Int,layerId: String,
        matrix: Matrix,busy: Boolean,color: String,width: Float,erase: Boolean,
        onCreate: (JSONObject)->Unit,onStroke: (JSONObject)->Unit): Boolean {
        if(busy || event.actionMasked==MotionEvent.ACTION_CANCEL) {cancel();return true}
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            cancel()
            val layer=ArtMenuOperations.layers(state).first {it.getString("id")==layerId}
            ArtColorize.root(layer)
            val create=layer.getString("kind")!="colorize"
            if(create)ArtColorize.source(state,layerId)
            else require(layer.getBoolean("visible") && !layer.getBoolean("locked") &&
                layer.getJSONObject("colorize").getJSONObject("settings").getBoolean("editKeys")) {
                "请显示蒙版、解除锁定并开启编辑线索"
            }
            require(matrix.invert(inverse));screen=Matrix(matrix)
            context=JSONObject().put("documentId",doc).put("expectedRevision",revision)
                .put("layerId",layerId).put("create",create)
            style=JSONObject().put("color",color).put("width",width.toDouble()).put("erase",erase)
            state.optJSONObject("selection")?.let {style!!.put("selection",JSONObject(it.toString()))}
        }
        val capture=context ?: return true
        require(capture.getString("documentId")==doc && capture.getInt("expectedRevision")==revision &&
            capture.getString("layerId")==layerId && same(matrix)) {"工程、图层或视图已改变，请重新画颜色线索"}
        if(!capture.getBoolean("create")) {
            fun add(x: Float,y: Float) {
                val p=floatArrayOf(x,y);inverse.mapPoints(p)
                require(p.all {it.isFinite() && abs(it)<=1000000f})
                val last=if(points.length()>0)points.getJSONArray(points.length()-1) else null
                if(last!=null && last.getDouble(0)==p[0].toDouble() && last.getDouble(1)==p[1].toDouble())return
                require(points.length()<ArtColorize.MAX_POINTS) {"单笔线索超过4096点，请分笔"}
                points.put(JSONArray().put(p[0]).put(p[1]))
            }
            if(event.actionMasked in setOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
                if(event.actionMasked==MotionEvent.ACTION_MOVE)for(i in 0 until event.historySize)
                    add(event.getHistoricalX(i),event.getHistoricalY(i))
                add(event.x,event.y)
            }
        }
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            val create=capture.getBoolean("create")
            val p=if(create)JSONObject() else JSONObject(style!!.toString()).put("points",JSONArray(points.toString()))
            p.put("documentId",doc).put("expectedRevision",revision)
                .put(if(create)"sourceLayerId" else "maskId",layerId)
            cancel();if(create)onCreate(p) else onStroke(p)
        }
        return true
    }
    fun draw(canvas: Canvas) {
        if(context==null || context!!.getBoolean("create") || points.length()==0)return
        val key=JSONObject(style!!.toString()).put("points",points)
        if(key.getBoolean("erase"))key.put("erase",false).put("color","#99808080")
        canvas.save()
        try {
            canvas.concat(screen)
            canvas.saveLayer(null,null)
            try {ArtColorize.drawKey(canvas,key)} finally {canvas.restore()}
        } finally {canvas.restore()}
    }
}
