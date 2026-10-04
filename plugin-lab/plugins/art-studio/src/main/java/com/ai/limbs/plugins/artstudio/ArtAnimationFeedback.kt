package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

/** One bounded sheet; render/recycle one frame at a time under the captured asset context. */
internal object ArtAnimationFeedback {
    fun attach(store:ArtStore,result:JSONObject,snapshot:JSONObject,frames:List<Int>):JSONObject {
        val plan=ArtAnimationFeedbackPlan.create(frames)
        ArtImagePolicy.requireDimensions(plan.width,plan.height)
        ArtImagePolicy.requireBytes(plan.width.toLong()*plan.height*16,"动画缩图总览")
        val sheet=Bitmap.createBitmap(plan.width,plan.height,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(sheet);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            for((index,frame) in plan.frames.withIndex()) {
                val left=(index%plan.columns)*ArtAnimationFeedbackPlan.EDGE
                val top=(index/plan.columns)*(ArtAnimationFeedbackPlan.EDGE+ArtAnimationFeedbackPlan.LABEL_HEIGHT)
                paint.color=Color.rgb(35,37,45)
                canvas.drawRect(left.toFloat(),top.toFloat(),(left+ArtAnimationFeedbackPlan.EDGE).toFloat(),
                    (top+ArtAnimationFeedbackPlan.LABEL_HEIGHT).toFloat(),paint)
                drawFrameNumber(canvas,frame,left.toFloat()+7,top.toFloat()+3)
                val image=ArtRenderer.render(store,ArtAnimation.frame(snapshot,frame),maxEdge=ArtAnimationFeedbackPlan.EDGE)
                try {
                    val x=left+(ArtAnimationFeedbackPlan.EDGE-image.width)/2f
                    val y=top+ArtAnimationFeedbackPlan.LABEL_HEIGHT+(ArtAnimationFeedbackPlan.EDGE-image.height)/2f
                    canvas.drawBitmap(image,null,RectF(x,y,x+image.width,y+image.height),paint)
                } finally {image.recycle()}
            }
            val encoded=ArtCanvasFeedback.encode(sheet,plan.metadata(snapshot))
            require(encoded.getJSONObject("metadata").getInt("bytes")<=512*1024) {"动画缩图总览超过512 KiB"}
            return plan.append(result,encoded)
        } finally {sheet.recycle()}
    }

    fun failure(result:JSONObject,snapshot:JSONObject?,frames:List<Int>,error:Exception):JSONObject {
        android.util.Log.e("ArtStudio","Pose batch committed but frame sheet failed",error)
        return result.put("animationFeedback",JSONObject().put("status","error").put("operationApplied",true)
            .put("documentId",snapshot?.getString("id") ?: JSONObject.NULL)
            .put("revision",snapshot?.getInt("revision") ?: JSONObject.NULL)
            .put("frames",JSONArray(frames.sorted())).put("error",error.message ?: error.javaClass.simpleName))
    }

    // Resident app_process can lack a default typeface: geometry labels avoid native font initialization.
    private fun drawFrameNumber(canvas:Canvas,frame:Int,x:Float,y:Float) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.rgb(207,222,255);strokeWidth=1.7f;strokeCap=Paint.Cap.ROUND
        }
        val segments=arrayOf(floatArrayOf(0f,0f,6f,0f),floatArrayOf(6f,0f,6f,6f),
            floatArrayOf(6f,6f,6f,12f),floatArrayOf(0f,12f,6f,12f),floatArrayOf(0f,6f,0f,12f),
            floatArrayOf(0f,0f,0f,6f),floatArrayOf(0f,6f,6f,6f))
        val digits=intArrayOf(0x3f,0x06,0x5b,0x4f,0x66,0x6d,0x7d,0x07,0x7f,0x6f)
        val masks=listOf(0x71)+frame.toString().map {digits[it-'0']} // F followed by the exact frame number.
        for((index,mask) in masks.withIndex())for(bit in segments.indices)if((mask and (1 shl bit))!=0) {
            val line=segments[bit];val start=x+index*11
            canvas.drawLine(start+line[0],y+line[1],start+line[2],y+line[3],paint)
        }
    }
}
