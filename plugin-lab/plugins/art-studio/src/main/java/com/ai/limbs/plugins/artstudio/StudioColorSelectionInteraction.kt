package com.ai.limbs.plugins.artstudio

import android.graphics.Matrix
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject
import kotlin.math.abs

internal class StudioColorSelectionInteraction {
    private var capture: JSONObject?=null
    private var screen=Matrix()
    private var startX=0f
    private var startY=0f
    fun cancel() {capture=null}
    fun touch(event: MotionEvent,state: JSONObject,documentId: String,revision: Int,layerId: String,
        matrix: Matrix,busy: Boolean,settings: JSONObject,onApply: (JSONObject)->Unit): Boolean {
        if(busy || event.actionMasked==MotionEvent.ACTION_CANCEL || event.buttonState and MotionEvent.BUTTON_SECONDARY!=0) {cancel();return true}
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            cancel();screen=Matrix(matrix);val inverse=Matrix();require(matrix.invert(inverse))
            val q=floatArrayOf(event.x,event.y);inverse.mapPoints(q)
            require(q.all {it.isFinite()})
            val x=kotlin.math.floor(q[0].toDouble()).toInt();val y=kotlin.math.floor(q[1].toDouble()).toInt()
            require(x in 0 until state.getInt("width") && y in 0 until state.getInt("height")) {"请点击画布内的颜色"}
            val o=ArtColorSelection.options(settings)
            // MotionEvent exposes modifier bits via metaState, not KeyEvent convenience properties.
            val shift=event.metaState and KeyEvent.META_SHIFT_MASK!=0
            val alt=event.metaState and KeyEvent.META_ALT_MASK!=0
            val ctrl=event.metaState and KeyEvent.META_CTRL_MASK!=0
            val mode=when {shift && alt->"intersect";ctrl->"replace"
                shift->"add";alt->"subtract";else->o.getString("mode")}
            capture=o.put("documentId",documentId).put("expectedRevision",revision).put("layerId",layerId).put("x",x).put("y",y).put("mode",mode)
            startX=event.x;startY=event.y
        }
        val p=capture ?: return true
        val a=FloatArray(9);val b=FloatArray(9);matrix.getValues(a);screen.getValues(b)
        require(p.getString("documentId")==documentId && p.getInt("expectedRevision")==revision &&
            p.getString("layerId")==layerId && a.indices.all {abs(a[it]-b[it])<0.001f}) {"工程或视图已变化，请重新取样"}
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            cancel()
            if(kotlin.math.hypot(event.x-startX,event.y-startY)<=20f)onApply(p)
        }
        return true
    }
}
