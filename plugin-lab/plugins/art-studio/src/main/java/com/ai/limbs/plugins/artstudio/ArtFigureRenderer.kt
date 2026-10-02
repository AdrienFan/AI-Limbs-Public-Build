package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONObject
import kotlin.math.*

/** Fill and outline are composited once, including patterned erase masks, in preview and export alike. */
internal object ArtFigureRenderer {
    fun draw(canvas:Canvas,p:JSONObject,resources:((String)->Bitmap)?) {
        if(p.has("pathVersion"))ArtRasterPath.validateStored(p) else ArtFigure.validateStored(p)
        val path=if(p.has("pathVersion"))ArtRasterPath.path(p) else ArtFigure.path(p);val fill=p.getJSONObject("figureFill")
        var tile:Bitmap?=null
        val composite=Paint().apply {alpha=(255*p.optDouble("opacity",1.0)).roundToInt().coerceIn(0,255)
            if(p.getString("brushTool")=="eraser" && !p.optBoolean("selectionCoveragePass"))xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT)}
        val saved=canvas.saveLayer(null,composite)
        try {
            if(fill.getString("mode")!="none") {
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.FILL}
                if(fill.getString("mode")=="solid")paint.color=Color.parseColor(fill.getString("color"))
                else {
                    val pattern=fill.getJSONObject("pattern");val kind=pattern.getString("kind")
                    val bitmap=if(kind=="image")requireNotNull(resources)(pattern.getString("asset")) else {
                        val edge=pattern.getInt("tileSize");val foreground=Color.parseColor(pattern.getString("foreground"));val background=Color.parseColor(pattern.getString("background"))
                        val values=IntArray(edge*edge)
                        for(y in 0 until edge)for(x in 0 until edge) {
                            val ink=when(kind) {
                                "checker"->(x*2/edge+y*2/edge)%2==0
                                "stripes"->x<edge/2
                                "dots"->hypot(x+0.5-edge/2.0,y+0.5-edge/2.0)<=edge/4.0
                                else->error("图案类型无效")
                            };values[y*edge+x]=if(ink)foreground else background
                        }
                        Bitmap.createBitmap(values,edge,edge,Bitmap.Config.ARGB_8888).also {tile=it}
                    }
                    val offset=pattern.getJSONArray("offset")
                    paint.shader=BitmapShader(bitmap,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT).apply {
                        setLocalMatrix(Matrix().apply {
                            setScale(pattern.getDouble("scale").toFloat(),pattern.getDouble("scale").toFloat())
                            postRotate(pattern.getDouble("angle").toFloat());postTranslate(offset.getDouble(0).toFloat(),offset.getDouble(1).toFloat())
                        })
                    };paint.isFilterBitmap=true
                }
                canvas.drawPath(path,paint)
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
        } finally {canvas.restoreToCount(saved);tile?.recycle()}
    }
}
