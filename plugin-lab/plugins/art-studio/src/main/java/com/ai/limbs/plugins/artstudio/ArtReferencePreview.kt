package com.ai.limbs.plugins.artstudio

import android.graphics.*
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal data class StudioRenderFrame(val first:JSONObject,val second:Bitmap,val third:String,
    val fourth:Map<String,Bitmap>) {
    companion object {
        // Hold the document lock across snapshot, pixels and revision marker. Otherwise an external
        // SVG edit can make an old bitmap look current and stop the page from refreshing it.
        fun current(store:ArtStore):StudioRenderFrame? = store.readViewSnapshot { snapshot ->
            snapshot?.let { create(store,it) }
        }
        fun create(store:ArtStore,snapshot:JSONObject):StudioRenderFrame {
            val refs=store.referenceBitmaps(snapshot)
            var image:Bitmap?=null
            try {
                image=ArtRenderer.render(store,snapshot,colorizeKeys=true)
                return StudioRenderFrame(snapshot,image,store.revision(),refs)
            } catch(error:Throwable) { image?.recycle();refs.values.forEach { it.recycle() };throw error }
        }
    }
}

/** A view receipt includes the artwork and references outside its bounds, unlike exports. */
internal object ArtReferencePreview {
    fun overview(store:ArtStore,snapshot:JSONObject,includeAssistants:Boolean=false):JSONObject {
        val state=snapshot.getJSONObject("state")
        val bounds=RectF(0f,0f,state.getInt("width").toFloat(),state.getInt("height").toFloat())
        val synthetic=ArtReferences.selectionState(state)
        val layer=ArtShapes.layer(synthetic,ArtReferences.LAYER)
        if(state.optBoolean("referencesVisible",true)) ArtShapes.items(layer).forEach { bounds.union(ArtShapes.bounds(it)) }
        if(includeAssistants && ArtAssistants.settings(state).getBoolean("visible")) {
            for(a in ArtAssistants.items(state).filter { it.getBoolean("visible") }) {
                for(p in ArtAssistants.points(a)) bounds.union(p.x.toFloat(),p.y.toFloat())
                val path=ArtAssistants.path(a)
                if(!path.isEmpty) {val r=RectF();path.computeBounds(r,true);bounds.union(r)}
                for(p in ArtAssistants.localCorners(a))bounds.union(p.x.toFloat(),p.y.toFloat())
            }
        }
        val edge=ArtCanvasFeedback.THUMBNAIL_EDGE
        val scale=minOf(edge/bounds.width(),edge/bounds.height())
        val w=maxOf(1,kotlin.math.ceil(bounds.width()*scale).toInt()).coerceAtMost(edge)
        val h=maxOf(1,kotlin.math.ceil(bounds.height()*scale).toInt()).coerceAtMost(edge)
        val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(image);canvas.drawColor(Color.rgb(38,38,42))
            val matrix=Matrix().apply { setRectToRect(bounds,RectF(0f,0f,w.toFloat(),h.toFloat()),Matrix.ScaleToFit.CENTER) }
            val frame=StudioRenderFrame.create(store,snapshot)
            try {
                canvas.drawBitmap(frame.second,matrix,Paint(Paint.FILTER_BITMAP_FLAG))
                ArtReferences.draw(canvas,state,frame.fourth,matrix)
                if(includeAssistants) ArtAssistants.draw(canvas,state,matrix)
            } finally { frame.second.recycle();frame.fourth.values.forEach { it.recycle() } }
            val bytes=ArtImagePolicy.encodePng(image,1024*1024)
            val metadata=JSONObject().put("kind",if(includeAssistants) "assistant-view" else "reference-view").put("width",w).put("height",h)
                .put("documentId",snapshot.getString("id")).put("revision",snapshot.getInt("revision"))
                .put("documentBounds",JSONArray().put(bounds.left.toDouble()).put(bounds.top.toDouble())
                    .put(bounds.right.toDouble()).put(bounds.bottom.toDouble()))
                .put("referenceCount",ArtReferences.items(state).size)
                .put("assistantCount",if(includeAssistants)ArtAssistants.items(state).size else 0)
            return JSONObject().put("thumbnail",metadata).put("mcp_content",JSONArray().put(
                JSONObject().put("type","image").put("mimeType","image/png")
                    .put("data",Base64.encodeToString(bytes,Base64.NO_WRAP))))
        } finally { image.recycle() }
    }
}
