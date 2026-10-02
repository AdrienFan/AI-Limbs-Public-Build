package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONObject
import kotlin.math.*

/** Fill and outline are composited once, including patterned erase masks, in preview and export alike. */
internal object ArtFigureRenderer {
    fun draw(canvas:Canvas,p:JSONObject,resources:((String)->Bitmap)?) {
        if(p.has("pathVersion"))ArtRasterPath.validateStored(p) else ArtFigure.validateStored(p)
        val path=if(p.has("pathVersion"))ArtRasterPath.path(p) else ArtFigure.path(p);val fill=p.getJSONObject("figureFill")
        val composite=Paint().apply {alpha=(255*p.optDouble("opacity",1.0)).roundToInt().coerceIn(0,255)
            if(p.getString("brushTool")=="eraser" && !p.optBoolean("selectionCoveragePass"))xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT)}
        val saved=canvas.saveLayer(null,composite)
        try {
            if(fill.getString("mode")!="none") {
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.FILL}
                if(fill.getString("mode")=="solid")paint.color=Color.parseColor(fill.getString("color"))
                else {
                    ArtPatternRenderer.withPaint(fill.getJSONObject("pattern"),resources) {patternPaint->canvas.drawPath(path,patternPaint)}
                }
                if(fill.getString("mode")=="solid")canvas.drawPath(path,paint)
            }
            when(p.getString("outline")) {
                "brush"->{
                    val tool=ArtBrush.engineTool(p)
                    val outline=JSONObject(p.toString()).put("opacity",1).put("tool",if(tool=="eraser")"ink" else tool)
                    ArtBrushRenderer.draw(canvas,outline,resources)
                }
                "basic"->canvas.drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=Color.parseColor(p.getString("color"));style=Paint.Style.STROKE;strokeWidth=p.getDouble("width").toFloat()
                })
                "none"->Unit
            }
        } finally {canvas.restoreToCount(saved)}
    }
}
