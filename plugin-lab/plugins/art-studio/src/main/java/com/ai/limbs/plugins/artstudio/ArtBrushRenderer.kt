package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONObject
import kotlin.math.*

/** Shared dab renderer for live preview, replay, thumbnails and exports. */
internal object ArtBrushRenderer {
    fun draw(canvas:Canvas,stroke:JSONObject,resources:((String)->Bitmap)?,copies:List<Matrix>?=null) {
        val b=stroke.getJSONObject("brush");val tip=b.getJSONObject("tip");val texture=b.getJSONObject("texture")
        val pixel=b.getJSONObject("smoothing").getString("mode")=="pixel_perfect"
        val seed=stroke.getInt("brushSeed");val edge=128
        fun resource(part:JSONObject)=requireNotNull(resources) { "缺少笔刷资源读取器" }(part.getString("asset"))
        fun mask(image:Bitmap,tipMask:Boolean):Bitmap {
            val pixels=IntArray(image.width*image.height);image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
            pixels.indices.forEach { i->val c=pixels[i];val l=(Color.red(c)*0.2126+Color.green(c)*0.7152+Color.blue(c)*0.0722)/255
                val border=tipMask && (i%image.width==0 || i%image.width==image.width-1 || i/image.width==0 || i/image.width==image.height-1)
                val a=if(border && image.width>2 && image.height>2)0 else (Color.alpha(c)*(if(tipMask)1-l else l)).roundToInt().coerceIn(0,255)
                pixels[i]=Color.argb(a,255,255,255) }
            return Bitmap.createBitmap(pixels,image.width,image.height,Bitmap.Config.ARGB_8888)
        }
        val tipBitmap=if(tip.getString("shape")=="image")mask(resource(tip),true) else {
            val pixels=IntArray(edge*edge);val hardness=tip.getDouble("hardness")
            for(y in 0 until edge)for(x in 0 until edge) {
                val dx=(x+0.5-edge/2.0)/(edge/2.0-1);val dy=(y+0.5-edge/2.0)/(edge/2.0-1)
                val d=if(tip.getString("shape")=="square")max(abs(dx),abs(dy)) else hypot(dx,dy)
                val a=if(d>1)0.0 else if(d<=hardness || hardness==1.0)1.0 else ((1-d)/(1-hardness)).pow(2)
                pixels[y*edge+x]=Color.argb((255*a).roundToInt(),255,255,255)
            };Bitmap.createBitmap(pixels,edge,edge,Bitmap.Config.ARGB_8888)
        }
        var textureBitmap:Bitmap?=null
        val composite=Paint().apply {
            val c=Color.parseColor(stroke.optString("color","#FF000000"))
            alpha=(Color.alpha(c)*stroke.optDouble("opacity",1.0)).roundToInt().coerceIn(0,255)
            if(stroke.getString("tool")=="eraser")xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        try {
            val tipShader=BitmapShader(tipBitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
            val textureShader=if(texture.getString("kind")=="none")null else {
                val kind=texture.getString("kind")
                val source=if(kind=="image")mask(resource(texture),false) else null
                val tw=source?.width ?: 64;val th=source?.height ?: 64;val values=IntArray(tw*th)
                if(source!=null)source.getPixels(values,0,tw,0,0,tw,th)
                for(y in 0 until th)for(x in 0 until tw) {
                    var v=when(kind) {
                        "image"->Color.alpha(values[y*tw+x])/255.0
                        "grain"->ArtBrush.noise(seed,y*tw+x,71)
                        "canvas"->if(x%4==0 || y%4==0)0.25 else 1.0
                        "checker"->if((x/4+y/4)%2==0)1.0 else 0.0
                        else->error("纹理类型无效")
                    }
                    if(texture.getBoolean("invert"))v=1-v
                    val a=(255*(1-texture.getDouble("strength")+texture.getDouble("strength")*v)).roundToInt()
                    values[y*tw+x]=Color.argb(a,255,255,255)
                }
                source?.recycle()
                val bitmap=Bitmap.createBitmap(values,tw,th,Bitmap.Config.ARGB_8888);textureBitmap=bitmap
                BitmapShader(bitmap,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT).apply {
                    setLocalMatrix(Matrix().apply {setScale(texture.getDouble("scale").toFloat(),texture.getDouble("scale").toFloat())})
                }
            }
            val color=Color.parseColor(stroke.optString("color","#FF000000"))
            val paint=Paint().apply {
                isAntiAlias=!pixel;isFilterBitmap=!pixel
                shader=if(textureShader==null)tipShader else ComposeShader(tipShader,textureShader,PorterDuff.Mode.DST_IN)
                colorFilter=PorterDuffColorFilter(Color.rgb(Color.red(color),Color.green(color),Color.blue(color)),PorterDuff.Mode.SRC_IN)
            }
            val matrix=Matrix();val count=b.getInt("count");val scatter=b.getDouble("scatter")
            // Share masks and shaders across all hands instead of rebuilding imported tips per copy.
            fun drawOne() {
                val saved=canvas.saveLayer(null,composite)
                try {
                    ArtBrush.dabs(stroke) { dab->
                        for(i in 0 until count) {
                            val a=2*PI*ArtBrush.noise(seed,dab.ordinal*count+i,13)
                            val r=sqrt(ArtBrush.noise(seed,dab.ordinal*count+i,29))*scatter*dab.size
                            val x=(dab.sample.x+cos(a)*r).toFloat();val y=(dab.sample.y+sin(a)*r).toFloat()
                            val size=(if(stroke.getString("tool")=="spray")max(0.1,dab.size*0.08) else dab.size).toFloat()
                            val angle=dab.angle+(ArtBrush.noise(seed,dab.ordinal*count+i,41)-0.5)*b.getDouble("jitter")
                            matrix.reset();matrix.postTranslate(-tipBitmap.width/2f,-tipBitmap.height/2f)
                            matrix.postScale(size/tipBitmap.width,size*tip.getDouble("ratio").toFloat()/tipBitmap.height)
                            matrix.postRotate(angle.toFloat());matrix.postTranslate(x,y);tipShader.setLocalMatrix(matrix)
                            paint.alpha=(255*dab.flow).roundToInt().coerceIn(0,255)
                            val reach=size*0.75f+1
                            val clip=canvas.save()
                            try {
                                canvas.translate(x,y);canvas.rotate(angle.toFloat())
                                canvas.clipRect(-size/2,-size*tip.getDouble("ratio").toFloat()/2,size/2,size*tip.getDouble("ratio").toFloat()/2)
                                canvas.rotate(-angle.toFloat());canvas.translate(-x,-y)
                                canvas.drawRect(x-reach,y-reach,x+reach,y+reach,paint)
                            } finally {canvas.restoreToCount(clip)}
                        }
                    }
                } finally {canvas.restoreToCount(saved)}
            }
            if(copies==null)drawOne() else for(transform in copies) {
                val saved=canvas.save()
                try {canvas.concat(transform);drawOne()} finally {canvas.restoreToCount(saved)}
            }
        } finally {tipBitmap.recycle();textureBitmap?.recycle()}
    }
}
