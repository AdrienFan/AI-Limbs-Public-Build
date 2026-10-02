package com.ai.limbs.plugins.artstudio

import android.graphics.*
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** A multi-tap draft owns its original document, layer transform, style and random seed. */
internal class StudioRasterPathInteraction {
    private var style:JSONObject?=null
    private var selectedArea:JSONObject?=null
    private var vertices=JSONArray()
    private var view=Matrix();private var layer=Matrix();private var inverse=Matrix()
    private var hover:FloatArray?=null
    private var lastTap=0L;private var lastX=0f;private var lastY=0f
    var density=1f
    val hasDraft get()=style!=null
    fun cancel() {style=null;vertices=JSONArray();hover=null;lastTap=0L}
    private fun same(m:Matrix):Boolean {val a=FloatArray(9);val b=FloatArray(9);m.getValues(a);view.getValues(b);return a.indices.all {abs(a[it]-b[it])<0.001f}}
    private fun requireContext(doc:String,rev:Int,id:String) {val p=requireNotNull(style);require(p.getString("documentId")==doc&&p.getInt("expectedRevision")==rev&&p.getString("layerId")==id) {"工程或图层已改变，请重新绘制"}}
    private fun complete()=style?.let {if(it.getString("tool")=="bezier")vertices.length()>=4&&(vertices.length()-1)%3==0 else vertices.length()>=(if(it.getString("tool")=="polygon")3 else 2)} ?: false
    fun command(action:String,doc:String,rev:Int,id:String,busy:Boolean,onDraw:(JSONObject)->Unit) {
        if(action=="cancel") {cancel();return};if(style==null)return
        require(!busy);requireContext(doc,rev,id)
        when(action) {
            "back"->{if(vertices.length()>0)vertices.remove(vertices.length()-1);hover=null;lastTap=0;if(vertices.length()==0)cancel()}
            "finish"->{require(complete()) {"请先完成路径顶点或完整三次曲线段"};val p=JSONObject(requireNotNull(style).toString()).put("points",JSONArray(vertices.toString()));cancel();onDraw(p)}
            else->error("未知路径指令")
        }
    }
    fun touch(event:MotionEvent,state:JSONObject,doc:String,rev:Int,id:String,toScreen:Matrix,busy:Boolean,options:JSONObject,continuous:Boolean,onDraw:(JSONObject)->Unit):Boolean {
        if(busy||event.actionMasked==MotionEvent.ACTION_CANCEL) {cancel();return true}
        if(event.actionMasked==MotionEvent.ACTION_DOWN&&style==null) {
            val active=ArtMenuOperations.layers(state).first {it.getString("id")==id}
            require(active.getString("kind")=="paint"&&!ArtMenuOperations.isLocked(state,active)&&ArtShapes.visible(state,active)) {"请选择可见且未锁定的绘画图层"}
            selectedArea=state.optJSONObject("selection")?.let {JSONObject(it.toString())}
            view=Matrix(toScreen);layer=ArtShapes.layerMatrix(state,active);require(Matrix(toScreen).apply {preConcat(layer)}.invert(inverse))
            val p=ArtRasterPath.settings(options);if(p.getString("outline")!="brush")p.remove("brush")
            style=p.put("documentId",doc).put("expectedRevision",rev).put("layerId",id).put("brushSeed",java.util.Random().nextInt(Int.MAX_VALUE))
        }
        if(style==null)return true
        requireContext(doc,rev,id);require(same(toScreen)) {"视图已改变，请重新绘制"}
        val point=floatArrayOf(event.x,event.y);inverse.mapPoints(point);hover=point
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            val doubleTap=event.eventTime-lastTap in 1L..350L&&hypot(event.x-lastX,event.y-lastY)<32*density
            if(doubleTap&&complete()&&(requireNotNull(style).getString("tool")!="bezier"||continuous))command("finish",doc,rev,id,false,onDraw)
            else {
                val tool=requireNotNull(style).getString("tool");require(vertices.length()<(if(tool=="bezier")1024 else 2048)) {"路径控制点数量超限"}
                vertices.put(JSONArray().put(point[0]).put(point[1]));hover=null
                lastTap=event.eventTime;lastX=event.x;lastY=event.y
                if(tool=="bezier"&&!continuous&&vertices.length()==4)command("finish",doc,rev,id,false,onDraw)
            }
        };return true
    }
    fun draw(canvas:Canvas,toScreen:Matrix,resources:((String)->Bitmap)?) {
        val captured=style ?: return;if(!same(toScreen)) {cancel();return}
        val tool=captured.getString("tool");var controls=JSONArray(vertices.toString())
        hover?.let {controls.put(JSONArray().put(it[0]).put(it[1]))}
        if(controls.length()==0)return
        canvas.save()
        try {
            canvas.concat(view);canvas.concat(layer)
            val count=if(tool=="bezier")1+((controls.length()-1)/3)*3 else controls.length()
            val minimum=if(tool=="polygon")3 else if(tool=="bezier")4 else 2
            if(count>=minimum) {
                val raw=JSONArray();for(i in 0 until count)raw.put(controls.getJSONArray(i))
                val input=JSONObject(captured.toString()).put("points",raw)
                // A repeated tap or coincident controls can make a zero-length preview without a valid stroke.
                if(PathMeasure(ArtRasterPath.path(tool,raw),tool=="polygon").length>0) {
                    val geometry=ArtRasterPath.normalize(input)
                    val prepared=if(geometry.getString("outline")=="brush")ArtBrush.prepare(geometry.put("points",ArtRasterPath.outlinePoints(geometry)),geometry.getJSONObject("brush"),geometry.getInt("brushSeed")) else geometry
                    val inverseLayer=Matrix();require(layer.invert(inverseLayer))
                    ArtRenderer.drawStroke(canvas,ArtSoftSelection.bindStroke(prepared,selectedArea,inverseLayer),resources=resources)
                }
            }
            if(tool=="bezier"||count<minimum) {
                val guide=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(90,167,255);style=Paint.Style.STROKE;strokeWidth=1.2f;pathEffect=DashPathEffect(floatArrayOf(5f,4f),0f)}
                val path=Path();for(i in 0 until controls.length()) {val p=controls.getJSONArray(i);if(i==0)path.moveTo(p.getDouble(0).toFloat(),p.getDouble(1).toFloat()) else path.lineTo(p.getDouble(0).toFloat(),p.getDouble(1).toFloat())}
                canvas.drawPath(path,guide)
            }
        } finally {canvas.restore()}
    }
}
