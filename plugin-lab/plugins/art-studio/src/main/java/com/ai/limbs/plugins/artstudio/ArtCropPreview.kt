package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

internal object ArtCropPreview {
    fun create(store: ArtStore, snapshot: JSONObject, plan: JSONObject, maxEdge: Int): JSONObject {
        require(maxEdge in 64..1024)
        val state = snapshot.getJSONObject("state"); val rect = ArtCrop.rect(plan)
        val left = minOf(0, rect.x).toFloat(); val top = minOf(0, rect.y).toFloat()
        val right = maxOf(state.getInt("width"), rect.right).toFloat()
        val bottom = maxOf(state.getInt("height"), rect.bottom).toFloat()
        val padding = maxOf(right-left, bottom-top) * 0.04f
        val extent = RectF(left-padding, top-padding, right+padding, bottom+padding)
        val scale = maxEdge / maxOf(extent.width(), extent.height())
        val w = ceil(extent.width()*scale).toInt().coerceIn(1,maxEdge)
        val h = ceil(extent.height()*scale).toInt().coerceIn(1,maxEdge)
        ArtImagePolicy.requireBytes(ArtImagePolicy.renderBytes(store,state,maxEdge,maxEdge)+ArtBrush.renderOverhead(state,maxEdge,maxEdge)+w.toLong()*h*4,"裁剪预览")
        val bitmap = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(38,38,42))
            canvas.scale(scale,scale); canvas.translate(-extent.left,-extent.top)
            val rendered = if(plan.getString("target")=="layer") ArtRenderer.render(store,
                ArtColorSampler.isolate(snapshot, plan.getString("layerId")),maxEdge=maxEdge)
                else ArtRenderer.render(store,snapshot,maxEdge=maxEdge)
            try {
                canvas.drawBitmap(rendered,null,RectF(0f,0f,state.getInt("width").toFloat(),state.getInt("height").toFloat()),Paint(Paint.FILTER_BITMAP_FLAG))
            } finally { rendered.recycle() }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(255,205,85);style=Paint.Style.STROKE;strokeWidth=2/scale}
            canvas.drawRect(rect.x.toFloat(),rect.y.toFloat(),rect.right.toFloat(),rect.bottom.toFloat(),paint)
            paint.strokeWidth=1/scale
            ArtCrop.lines(rect,plan.getString("guides")).forEach {line->canvas.drawLine(line[0].toFloat(),line[1].toFloat(),line[2].toFloat(),line[3].toFloat(),paint)}
            val image=ArtCanvasFeedback.encode(bitmap,JSONObject().put("kind","crop-plan").put("documentId",snapshot.getString("id"))
                .put("revision",snapshot.getInt("revision")).put("width",w).put("height",h).put("plan",plan).put("operationApplied",false))
            return JSONObject().put("plan",plan).put("cropPreview",image.getJSONObject("metadata"))
                .put("mcp_content",JSONArray().put(image.getJSONObject("content")))
        } finally {bitmap.recycle()}
    }
}
