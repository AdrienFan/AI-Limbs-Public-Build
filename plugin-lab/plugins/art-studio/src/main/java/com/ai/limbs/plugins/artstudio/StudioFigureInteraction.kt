package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Captures two layer-local corners and style once; modifiers change only geometry constraints. */
internal class StudioFigureInteraction {
    private var capture:JSONObject?=null
    private var style:JSONObject?=null
    private var selectedArea:JSONObject?=null
    private var geometryOptions:JSONObject?=null
    private var viewMatrix=Matrix()
    private var layerMatrix=Matrix()
    private var inverse=Matrix()
    private var start=FloatArray(2)
    private var end=FloatArray(2)
    fun cancel() {capture=null;style=null;geometryOptions=null}
    private fun same(m:Matrix):Boolean {
        val a=FloatArray(9);val b=FloatArray(9);m.getValues(a);viewMatrix.getValues(b)
        return a.indices.all {abs(a[it]-b[it])<0.001f}
    }
    private fun parameters():JSONObject {
        val p=JSONObject(requireNotNull(style).toString());val options=requireNotNull(geometryOptions)
        for(key in listOf("fixedWidth","fixedHeight","fixedRatio","drawFromCenter"))p.put(key,options.get(key))
        return p.put("points",JSONArray().put(JSONArray().put(start[0]).put(start[1])).put(JSONArray().put(end[0]).put(end[1])))
    }
    fun touch(event:MotionEvent,state:JSONObject,documentId:String,revision:Int,layerId:String,toScreen:Matrix,
        busy:Boolean,options:JSONObject,onFigure:(JSONObject)->Unit):Boolean {
        if(busy || event.actionMasked==MotionEvent.ACTION_CANCEL) {cancel();return true}
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            cancel();val layer=ArtMenuOperations.layers(state).first {it.getString("id")==layerId}
            require(layer.getString("kind") in setOf("paint","vector") && !ArtMenuOperations.isLocked(state,layer) && ArtShapes.visible(state,layer)) {"请选择可编辑的绘画或矢量图层"}
            val normalized=ArtFigure.settings(options)
            if(layer.getString("kind")=="vector") {
                require(normalized.getJSONObject("figureFill").getString("mode")!="pattern") {"图案填充请使用绘画图层"}
                normalized.remove("brush")
            } else if(normalized.getString("outline")!="brush")normalized.remove("brush")
            selectedArea=state.optJSONObject("selection")?.let {JSONObject(it.toString())}
            viewMatrix=Matrix(toScreen);layerMatrix=ArtShapes.layerMatrix(state,layer)
            val combined=Matrix(toScreen).apply {preConcat(layerMatrix)};require(combined.invert(inverse))
            val point=floatArrayOf(event.x,event.y);inverse.mapPoints(point);start=point;end=point.copyOf()
            capture=JSONObject().put("documentId",documentId).put("expectedRevision",revision).put("layerId",layerId).put("kind",layer.getString("kind"))
            style=normalized.put("documentId",documentId).put("expectedRevision",revision).put("layerId",layerId)
                .put("brushSeed",java.util.Random().nextInt(Int.MAX_VALUE))
        }
        val context=capture ?: return true
        require(context.getString("documentId")==documentId && context.getInt("expectedRevision")==revision && context.getString("layerId")==layerId && same(toScreen)) {"工程、图层或视图已改变，请重新绘制"}
        if(event.actionMasked in setOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE)) {
            geometryOptions=JSONObject(requireNotNull(style).toString())
            val current=requireNotNull(geometryOptions)
            if(event.metaState and KeyEvent.META_SHIFT_ON!=0) {
                val constrained=listOf("fixedWidth","fixedHeight","fixedRatio").any {current.getDouble(it)>0}
                current.put("fixedWidth",0).put("fixedHeight",0).put("fixedRatio",if(constrained)0 else 1)
            }
            if(event.metaState and KeyEvent.META_CTRL_ON!=0)current.put("drawFromCenter",true)
        }
        if(event.actionMasked in setOf(MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            val point=floatArrayOf(event.x,event.y);inverse.mapPoints(point);end=point
        }
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            val p=parameters();val hasArea=ArtFigure.hasArea(p);cancel();if(hasArea)onFigure(p)
        };return true
    }
    fun draw(canvas:Canvas,toScreen:Matrix,resources:((String)->Bitmap)?) {
        if(capture==null)return
        if(!same(toScreen)) {cancel();return}
        val input=parameters();if(!ArtFigure.hasArea(input))return
        val geometry=ArtFigure.geometry(input)
        canvas.save()
        try {
            canvas.concat(viewMatrix);canvas.concat(layerMatrix)
            if(requireNotNull(capture).getString("kind")=="paint") {
                val stroke=if(geometry.getString("outline")=="brush")ArtBrush.prepare(geometry.put("points",ArtFigure.outlinePoints(geometry)),geometry.getJSONObject("brush"),geometry.getInt("brushSeed")) else geometry
                val inverseLayer=Matrix();require(layerMatrix.invert(inverseLayer))
                ArtRenderer.drawStroke(canvas,ArtSoftSelection.bindStroke(stroke,selectedArea,inverseLayer),resources=resources)
            } else {
                val shape=JSONObject().put("id","00000000-0000-0000-0000-000000000000").put("kind",geometry.getString("tool"))
                    .put("points",geometry.getJSONArray("figureCorners")).put("cornerRadius",geometry.getDouble("effectiveRadius"))
                    .put("stroke",if(geometry.getString("outline")=="none")"#00000000" else geometry.getString("color"))
                    .put("strokeWidth",geometry.getDouble("width")).put("opacity",geometry.optDouble("opacity",1.0))
                    .put("fill",if(geometry.getJSONObject("figureFill").getString("mode")=="solid")geometry.getJSONObject("figureFill").getString("color") else "#00000000")
                ArtShapes.draw(canvas,JSONObject().put("kind","vector").put("shapes",JSONArray().put(ArtShapes.normalize(shape))))
            }
        } finally {canvas.restore()}
    }
}
