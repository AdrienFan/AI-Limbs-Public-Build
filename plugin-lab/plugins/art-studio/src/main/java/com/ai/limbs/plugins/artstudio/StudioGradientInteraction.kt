package com.ai.limbs.plugins.artstudio

import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/** A gradient gesture freezes layer, revision, mask, settings and seed; preview is bounded and throttled. */
internal class StudioGradientInteraction(private val view:View) {
    private var capture:JSONObject?=null
    private var source:JSONObject?=null
    private var frame=Matrix();private var layerFrame=Matrix();private var inverse=Matrix()
    private var start=FloatArray(2);private var end=FloatArray(2)
    private var preview:ArtGradient.Image?=null;private var lastPreview=0L;private var dirty=false
    var onDraft:(Boolean)->Unit={}
    val hasDraft get()=capture!=null
    fun cancel() {val existed=hasDraft;capture=null;source=null;preview?.bitmap?.recycle();preview=null;dirty=false
        if(existed)onDraft(false);view.invalidate()}
    private fun same(a:Matrix,b:Matrix):Boolean {
        val x=FloatArray(9);val y=FloatArray(9);a.getValues(x);b.getValues(y)
        return x.indices.all {abs(x[it]-y[it])<0.0001f}
    }
    private fun parameters()=JSONObject(requireNotNull(capture).toString()).put("points",JSONArray()
        .put(JSONArray().put(start[0]).put(start[1])).put(JSONArray().put(end[0]).put(end[1])))
    fun touch(e:MotionEvent,state:JSONObject,doc:String,revision:Int,layerId:String,m:Matrix,busy:Boolean,
        settings:JSONObject,color:String,opacity:Double,onComplete:(JSONObject)->Unit):Boolean {
        if(busy||e.actionMasked==MotionEvent.ACTION_CANCEL||e.buttonState and MotionEvent.BUTTON_SECONDARY!=0) {cancel();return true}
        val layer=ArtMenuOperations.layers(state).first {it.getString("id")==layerId}
        val transform=ArtShapes.layerMatrix(state,layer)
        if(e.actionMasked==MotionEvent.ACTION_DOWN) {
            cancel();require(layer.getString("kind")=="paint"&&ArtShapes.visible(state,layer)&&!ArtMenuOperations.isLocked(state,layer)) {"请选择可编辑的可见绘画图层"}
            frame=Matrix(m);layerFrame=Matrix(transform)
            require(Matrix(m).apply {preConcat(transform)}.invert(inverse))
            start=floatArrayOf(e.x,e.y);inverse.mapPoints(start);end=start.copyOf()
            val p=JSONObject(settings.toString()).put("color",color).put("opacity",opacity)
            val options=ArtGradient.settings(p);options.keys().forEach {p.put(it,options.get(it))}
            p.put("documentId",doc).put("expectedRevision",revision).put("layerId",layerId)
                .put("tool","gradient").put("width",1).put("id",java.util.UUID.randomUUID().toString())
            val toLayer=Matrix();require(transform.invert(toLayer))
            capture=ArtSoftSelection.bindStroke(p,state.optJSONObject("selection"),toLayer)
            require(p.getString("gradientMode")!="shape"||p.optJSONObject("selection")!=null) {"轮廓渐变请先建立选区"}
            source=JSONObject().put("width",state.getInt("width")).put("height",state.getInt("height"))
            onDraft(true)
        }
        val p=capture ?: return true
        require(p.getString("documentId")==doc&&p.getInt("expectedRevision")==revision&&p.getString("layerId")==layerId&&same(m,frame)&&same(transform,layerFrame)) {"工程、图层或视图变化，请重新拉渐变"}
        if(e.actionMasked in setOf(MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            end=floatArrayOf(e.x,e.y);inverse.mapPoints(end);require(end.all {it.isFinite()&&abs(it)<=1_000_000})
            dirty=true
        }
        if(e.actionMasked==MotionEvent.ACTION_UP) {
            if(hypot((end[0]-start[0]).toDouble(),(end[1]-start[1]).toDouble())<0.01) {cancel();return true}
            val result=parameters();ArtGradient.prepare(result,requireNotNull(source));cancel();onComplete(result)
        }
        view.invalidate();return true
    }
    fun draw(canvas:Canvas,m:Matrix) {
        if(!hasDraft)return
        if(!same(m,frame)) {cancel();return}
        val now=SystemClock.uptimeMillis()
        if(dirty&&now-lastPreview>=100&&hypot((end[0]-start[0]).toDouble(),(end[1]-start[1]).toDouble())>=0.01) {
            val p=ArtGradient.prepare(parameters(),requireNotNull(source))
            val result=ArtGradient.image(p,256)
            preview?.bitmap?.recycle();preview=result;lastPreview=now;dirty=false
        }
        canvas.save()
        try {
            canvas.concat(frame);canvas.concat(layerFrame)
            preview?.let {canvas.drawBitmap(it.bitmap,null,RectF(it.bounds),Paint(Paint.FILTER_BITMAP_FLAG))}
        } finally {canvas.restore()}
        val a=start.copyOf();val b=end.copyOf();val combined=Matrix(frame).apply {preConcat(layerFrame)};combined.mapPoints(a);combined.mapPoints(b)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(80,210,230);strokeWidth=2f*view.resources.displayMetrics.density}
        canvas.drawLine(a[0],a[1],b[0],b[1],paint);canvas.drawCircle(a[0],a[1],4f*view.resources.displayMetrics.density,paint)
        if(dirty)view.postInvalidateDelayed(100)
    }
}
