package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Paint a temporary mask. One complete pointer gesture submits one document-bound repair. */
internal class StudioSmartPatchInteraction {
    private var capture: JSONObject?=null
    private var options: JSONObject?=null
    private var toScreen=Matrix()
    private var inverse=Matrix()
    private var selection: JSONObject?=null
    private var canvasWidth=0f
    private var canvasHeight=0f
    private val samples=mutableListOf<Pair<Float,Float>>()
    fun cancel() { capture=null;options=null;selection=null;samples.clear() }
    private fun same(matrix: Matrix): Boolean {
        val a=FloatArray(9);val b=FloatArray(9);matrix.getValues(a);toScreen.getValues(b)
        return a.indices.all { abs(a[it]-b[it])<0.001f }
    }
    fun touch(event: MotionEvent,state: JSONObject,documentId: String,revision: Int,layerId: String,
        matrix: Matrix,busy: Boolean,settings: JSONObject,onRepair: (JSONObject)->Unit): Boolean {
        if(busy || event.actionMasked==MotionEvent.ACTION_CANCEL) {cancel();return true}
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            cancel()
            val layer=ArtMenuOperations.layers(state).first { it.getString("id")==layerId }
            require(layer.getString("kind") in setOf("paint","image") &&
                layer.getBoolean("visible") && !layer.getBoolean("locked")) { "请选择未锁定的可见绘画或图像图层" }
            require(layer.optString("parentId").isBlank() && layer.getDouble("x")==0.0 &&
                layer.getDouble("y")==0.0 && layer.getDouble("scale")==1.0 && !layer.has("affine") && layer.getDouble("rotation")==0.0) {
                "基础智能修补需要未变换的根图层"
            }
            ArtSmartPatch.options(settings)
            require(matrix.invert(inverse)) { "当前视图无法转换修补坐标" }
            toScreen=Matrix(matrix);options=JSONObject(settings.toString())
            canvasWidth=state.getInt("width").toFloat();canvasHeight=state.getInt("height").toFloat()
            selection=state.optJSONObject("selection")?.let { JSONObject(it.toString()) }
            capture=JSONObject().put("documentId",documentId).put("expectedRevision",revision).put("layerId",layerId)
        }
        val context=capture ?: return true
        require(context.getString("documentId")==documentId && context.getInt("expectedRevision")==revision &&
            context.getString("layerId")==layerId && same(matrix)) { "工程、图层或视图已改变，请重新涂抹" }
        fun add(x: Float,y: Float) {
            val point=floatArrayOf(x,y);inverse.mapPoints(point)
            require(point.all { it.isFinite() }) { "修补坐标无效" }
            val next=point[0] to point[1]
            if(samples.lastOrNull()==next)return
            require(samples.size<ArtSmartPatch.MAX_POINTS) { "修补路径超过4096点，请分笔修补" }
            samples.add(next)
        }
        if(event.actionMasked in setOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            if(event.actionMasked==MotionEvent.ACTION_MOVE) for(i in 0 until event.historySize)
                add(event.getHistoricalX(i),event.getHistoricalY(i))
            add(event.x,event.y)
        }
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            val p=JSONObject(options!!.toString())
            for(key in listOf("documentId","expectedRevision","layerId"))p.put(key,context.get(key))
            p.put("points",JSONArray().apply { samples.forEach { put(JSONArray().put(it.first).put(it.second)) } })
            cancel();onRepair(p)
        }
        return true
    }
    fun draw(canvas: Canvas) {
        if(capture==null || samples.isEmpty())return
        canvas.save()
        try {
            canvas.concat(toScreen)
            canvas.clipRect(0f,0f,canvasWidth,canvasHeight)
            selection?.let {canvas.clipPath(ArtSelection.path(it))}
            ArtSmartPatch.drawMask(canvas,samples,options!!.getDouble("width").toFloat(),Color.argb(115,255,70,165))
        } finally {canvas.restore()}
    }
}
