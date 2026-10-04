package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject

/** All requested pose frames, ordered by time. Never silently sample away a changed frame. */
internal class ArtAnimationFeedbackPlan private constructor(val frames:List<Int>) {
    val columns=minOf(4,frames.size)
    val rows=(frames.size+columns-1)/columns
    val width=columns*EDGE
    val height=rows*(EDGE+LABEL_HEIGHT)

    fun metadata(snapshot:JSONObject):JSONObject = JSONObject().put("kind","animation-contact-sheet")
        .put("status","ok").put("operationApplied",true).put("documentId",snapshot.getString("id"))
        .put("revision",snapshot.getInt("revision")).put("frames",JSONArray(frames))
        .put("frameCount",frames.size).put("columns",columns).put("rows",rows)
        .put("width",width).put("height",height).put("tileEdge",EDGE).put("labelHeight",LABEL_HEIGHT)
        .put("order","row-major").put("readFrameWith","plugin.art.studio.animation.preview")

    fun append(result:JSONObject,image:JSONObject):JSONObject {
        val content=JSONArray()
        result.optJSONArray("mcp_content")?.let {existing ->
            for(index in 0 until existing.length())content.put(existing.get(index))
        }
        val metadata=image.getJSONObject("metadata").put("imageContentIndex",content.length())
        content.put(image.getJSONObject("content"))
        return result.put("animationFeedback",metadata).put("mcp_content",content)
    }

    companion object {
        const val EDGE=128
        const val LABEL_HEIGHT=20
        fun create(frames:List<Int>):ArtAnimationFeedbackPlan {
            require(frames.size in 1..32 && frames.all {it in 0..ArtAnimation.MAX_TIME} &&
                frames.distinct().size==frames.size) {"缩图总览须包含1–32个不同的有效帧号"}
            return ArtAnimationFeedbackPlan(frames.sorted())
        }
    }
}
