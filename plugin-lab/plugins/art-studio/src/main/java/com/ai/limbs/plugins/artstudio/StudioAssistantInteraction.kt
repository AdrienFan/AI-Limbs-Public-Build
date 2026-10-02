package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Matrix
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** One document/revision/matrix capture per assistant edit; commit only on release. */
internal class StudioAssistantInteraction {
    private var captured: JSONObject?=null
    private var screenMatrix=Matrix()
    private var original: JSONObject?=null
    private var handle=-1
    private var origin=AssistantPoint(0.0,0.0)
    private var draft=mutableListOf<AssistantPoint>()
    private var draftType=""
    private var selectionOnly=false
    var preview: JSONObject?=null;private set
    fun cancel() { captured=null;original=null;preview=null;draft.clear();draftType="";selectionOnly=false }
    private fun same(m: Matrix): Boolean {
        val a=FloatArray(9);val b=FloatArray(9);screenMatrix.getValues(a);m.getValues(b)
        return a.indices.all { kotlin.math.abs(a[it]-b[it])<0.001f }
    }
    fun touch(event: MotionEvent,state: JSONObject,documentId: String,revision: Int,toScreen: Matrix,
        adding: Boolean,type: String,busy: Boolean,onEdit: (String,JSONObject)->Unit,onCreated: ()->Unit): Boolean {
        if(busy) { cancel();return true }
        if(event.actionMasked==MotionEvent.ACTION_CANCEL) { cancel();return true }
        val inverse=Matrix();require(toScreen.invert(inverse))
        val v=floatArrayOf(event.x,event.y);inverse.mapPoints(v);val point=AssistantPoint(v[0].toDouble(),v[1].toDouble())
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            require(captured==null || (captured!!.getString("documentId")==documentId &&
                captured!!.getInt("expectedRevision")==revision && same(toScreen) && draftType==type)) {
                cancel();"工程、视图或类型已改变，请重新创建尺规"
            }
            if(captured==null) {
                captured=JSONObject().put("documentId",documentId).put("expectedRevision",revision)
                screenMatrix=Matrix(toScreen);draftType=type
            }
            if(adding) { require(ArtAssistants.items(state).size<ArtAssistants.MAX) { "一个工程最多32个辅助尺规" };return true }
            original=null;preview=null;handle=-1;selectionOnly=false;origin=point
            val radius=22.0*density
            val hit=ArtAssistants.items(state).asReversed().filter { it.getBoolean("visible") }.mapNotNull { a ->
                val handles=(ArtAssistants.points(a)+ArtAssistants.localCorners(a)).map { p ->
                    val t=floatArrayOf(p.x.toFloat(),p.y.toFloat());toScreen.mapPoints(t);t
                }
                val nearest=handles.indices.minByOrNull { hypot((handles[it][0]-event.x).toDouble(),(handles[it][1]-event.y).toDouble()) }
                val distance=nearest?.let { hypot((handles[it][0]-event.x).toDouble(),(handles[it][1]-event.y).toDouble()) } ?: Double.POSITIVE_INFINITY
                if(distance<=radius) Triple(a,nearest!!,distance) else if(ArtAssistants.settings(state).getBoolean("visible")) {
                    val d=ArtAssistants.hitDistance(a,point,toScreen)
                    if(d<=radius*0.55) Triple(a,-1,d) else null
                } else null
            }.minByOrNull { it.third }
            if(hit!=null) {
                original=JSONObject(hit.first.toString());preview=JSONObject(hit.first.toString());handle=hit.second
                selectionOnly=hit.first.getBoolean("locked")
            } else selectionOnly=true
            return true
        }
        val capture=captured ?: return true
        require(capture.getString("documentId")==documentId && capture.getInt("expectedRevision")==revision && same(toScreen)) {
            cancel();"工程或视图已改变，请重新操作尺规"
        }
        if(adding) {
            if(event.actionMasked==MotionEvent.ACTION_UP) {
                require(draftType==type) { "尺规类型已改变，请重新创建" }
                draft.add(point)
                if(draft.size==ArtAssistants.count(type)) {
                    val a=ArtAssistants.normalize(JSONObject().put("id",java.util.UUID.randomUUID().toString())
                        .put("type",type).put("points",JSONArray(draft.map { it.json() })))
                    val request=JSONObject(capture.toString()).put("assistant",a)
                    cancel();onEdit("ASSISTANT_CREATE",request);onCreated()
                }
            }
            return true
        }
        if(!selectionOnly && original!=null && event.actionMasked in setOf(MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            val a=JSONObject(original!!.toString());val old=ArtAssistants.points(a)
            if(handle>=old.size) {
                val opposite=ArtAssistants.localCorners(a)[1-(handle-old.size)]
                val x=minOf(point.x,opposite.x);val y=minOf(point.y,opposite.y)
                a.put("localBounds",JSONObject().put("x",x).put("y",y)
                    .put("width",maxOf(0.01,abs(point.x-opposite.x))).put("height",maxOf(0.01,abs(point.y-opposite.y))))
            } else {
                val next=if(handle>=0)old.mapIndexed {i,p->if(i==handle)point else p} else old.map {it+(point-origin)}
                a.put("points",JSONArray(next.map {it.json()}))
                if(handle<0&&a.has("localBounds")) {
                    val b=a.getJSONObject("localBounds");b.put("x",b.getDouble("x")+point.x-origin.x).put("y",b.getDouble("y")+point.y-origin.y)
                }
            }
            if(a.getString("type")=="ruler"&&a.optDouble("fixedLength",0.0)>0&&ArtAssistants.geometryValid(a))
                preview=ArtAssistants.normalize(a) else preview=a
        }
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            val a=preview
            val request=JSONObject(capture.toString())
            if(a!=null && !selectionOnly && (a.getJSONArray("points").toString()!=original!!.getJSONArray("points").toString() ||
                    a.optJSONObject("localBounds")?.toString()!=original!!.optJSONObject("localBounds")?.toString())) {
                val normalized=ArtAssistants.normalize(a)
                val changes=JSONObject().put("points",normalized.getJSONArray("points"))
                if(normalized.has("localBounds"))changes.put("localBounds",normalized.getJSONObject("localBounds"))
                request.put("id",a.getString("id")).put("changes",changes)
                cancel();onEdit("ASSISTANT_UPDATE",request)
            } else {
                request.put("id",original?.getString("id") ?: "")
                cancel();onEdit("ASSISTANT_SELECT",request)
            }
        }
        return true
    }
    // Hit radii are screen pixels; supplied externally for display density.
    var density: Float=1f
    fun drawDraft(canvas: Canvas,m: Matrix) {
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color=android.graphics.Color.rgb(80,184,238);style=android.graphics.Paint.Style.STROKE;strokeWidth=1.5f*density
        }
        var previous: FloatArray?=null
        for(p in draft) {
            val v=floatArrayOf(p.x.toFloat(),p.y.toFloat());m.mapPoints(v)
            canvas.drawCircle(v[0],v[1],7*density,paint)
            previous?.let { canvas.drawLine(it[0],it[1],v[0],v[1],paint) };previous=v
        }
    }
}
