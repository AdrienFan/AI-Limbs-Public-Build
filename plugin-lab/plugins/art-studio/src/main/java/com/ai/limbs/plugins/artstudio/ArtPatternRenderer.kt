package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONObject
import kotlin.math.hypot

/** Shared repeating RGBA tiles; resource bitmaps are borrowed, generated tiles are owned here. */
internal object ArtPatternRenderer {
    fun withPaint(pattern:JSONObject,resources:((String)->Bitmap)?,draw:(Paint)->Unit) {
        val kind=pattern.getString("kind");var owned:Bitmap?=null
        val bitmap=if(kind=="image")requireNotNull(resources)(pattern.getString("asset")) else {
            val edge=pattern.getInt("tileSize");val foreground=Color.parseColor(pattern.getString("foreground"))
            val background=Color.parseColor(pattern.getString("background"));val values=IntArray(edge*edge)
            for(y in 0 until edge)for(x in 0 until edge) {
                val ink=when(kind) {"checker"->(x*2/edge+y*2/edge)%2==0;"stripes"->x<edge/2
                    "dots"->hypot(x+0.5-edge/2.0,y+0.5-edge/2.0)<=edge/4.0;else->error("图案类型无效")}
                values[y*edge+x]=if(ink)foreground else background
            }
            Bitmap.createBitmap(values,edge,edge,Bitmap.Config.ARGB_8888).also {owned=it}
        }
        try {
            val offset=pattern.getJSONArray("offset")
            val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                style=Paint.Style.FILL
                shader=BitmapShader(bitmap,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT).apply {
                    setLocalMatrix(Matrix().apply {setScale(pattern.getDouble("scale").toFloat(),pattern.getDouble("scale").toFloat())
                        postRotate(pattern.getDouble("angle").toFloat());postTranslate(offset.getDouble(0).toFloat(),offset.getDouble(1).toFloat())})
                }
            }
            draw(paint)
        } finally {owned?.recycle()}
    }
}
