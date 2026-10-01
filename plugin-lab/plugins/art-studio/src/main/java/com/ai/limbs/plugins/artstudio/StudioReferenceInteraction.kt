package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject

internal class StudioReferenceInteraction(private val view:View) {
    private val shapes=StudioShapeInteraction(view)
    private var frame:Matrix?=null
    fun cancel() { frame=null;shapes.cancel() }
    fun draw(canvas:Canvas,state:JSONObject,bitmaps:Map<String,Bitmap>,toScreen:Matrix,editing:Boolean) {
        ArtReferences.draw(canvas,state,bitmaps,toScreen,if(editing) shapes else null)
        if(!editing || !state.optBoolean("referencesVisible",true)) return
        val virtual=ArtReferences.selectionState(state);val layer=ArtShapes.layer(virtual,ArtReferences.LAYER)
        val selected=ArtShapes.selected(virtual,ArtReferences.LAYER)
        shapes.draw(canvas,layer,selected,toScreen,ArtShapes.items(layer)
            .filter { it.getString("id") in selected }.none { it.getBoolean("locked") })
    }
    fun touch(event:MotionEvent,state:JSONObject,document:String,revision:Int,toScreen:Matrix,
        multiple:Boolean,busy:Boolean,commit:(String,JSONObject)->Unit):Boolean {
        if(!state.optBoolean("referencesVisible",true)||busy||event.actionMasked==MotionEvent.ACTION_CANCEL) {
            cancel();return true
        }
        if(event.actionMasked==MotionEvent.ACTION_DOWN) frame=Matrix(toScreen)
        val initial=frame ?: return true
        val a=FloatArray(9);val b=FloatArray(9);initial.getValues(a);toScreen.getValues(b)
        require(a.indices.all { kotlin.math.abs(a[it]-b[it])<0.0001f }) { "视图已改变，请重新操作参考图像" }
        val inverse=Matrix();check(toScreen.invert(inverse))
        val xy=floatArrayOf(event.x,event.y);inverse.mapPoints(xy)
        val virtual=ArtReferences.selectionState(state)
        val layer=ArtShapes.layer(virtual,ArtReferences.LAYER)
        val hit=ArtShapes.hit(layer,xy[0],xy[1],0f)
        val selected=ArtReferences.ids(state)
        val resizeIds=if(hit!=null && hit !in selected) listOf(hit) else selected
        shapes.preserveAspect=ArtReferences.items(state).filter { it.getString("id") in resizeIds }
            .all { it.getBoolean("keepAspect") }
        val consumed=shapes.touch(event,xy,toScreen,virtual,document,revision,ArtReferences.LAYER,
            multiple,busy) { type,p ->
                p.remove("layerId")
                commit(if(type=="SHAPE_TRANSFORM") "REFERENCE_TRANSFORM" else "REFERENCE_SELECT",p)
            }
        if(event.actionMasked==MotionEvent.ACTION_UP) frame=null
        return consumed
    }
}
