package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONObject
import kotlin.math.*

/** Inverse sampling in document space, premultiplied alpha throughout every interpolation tap. */
internal object ArtTransformPixels {
    data class Result(val bytes:ByteArray,val bounds:Rect,val mask:JSONObject)
    private fun kernel(t:Double):Double {
        val x=abs(t);return when {x<1->1.5*x*x*x-2.5*x*x+1;x<2-> -0.5*x*x*x+2.5*x*x-4*x+2;else->0.0}
    }
    fun sample(data:IntArray,w:Int,h:Int,x:Double,y:Double,filter:String):Int {
        require(filter in ArtTransform.filters)
        fun pixel(i:Int,j:Int)=if(i in 0 until w&&j in 0 until h)data[j*w+i] else Color.TRANSPARENT
        if(filter=="nearest")return pixel(floor(x).toInt(),floor(y).toInt())
        val xx=x-0.5;val yy=y-0.5;val ix=floor(xx).toInt();val iy=floor(yy).toInt()
        var a=0.0;var r=0.0;var g=0.0;var b=0.0
        val taps=if(filter=="bicubic")-1..2 else 0..1
        for(j in taps)for(i in taps) {
            val weight=if(filter=="bicubic")kernel(xx-ix-i)*kernel(yy-iy-j) else
                (if(i==0)1-(xx-ix) else xx-ix)*(if(j==0)1-(yy-iy) else yy-iy)
            val p=pixel(ix+i,iy+j);val alpha=Color.alpha(p)*weight
            a+=alpha;r+=Color.red(p)*alpha;g+=Color.green(p)*alpha;b+=Color.blue(p)*alpha
        }
        if(a<=0)return Color.TRANSPARENT
        return Color.argb(a.roundToInt().coerceIn(0,255),(r/a).roundToInt().coerceIn(0,255),(g/a).roundToInt().coerceIn(0,255),(b/a).roundToInt().coerceIn(0,255))
    }
    fun render(capture:ArtMovePixels.Capture,plan:ArtTransform.Plan,p:JSONObject):Result {
        val src=capture.bounds;val dst=plan.bounds;val w=src.width();val h=src.height();val ow=dst.width();val oh=dst.height()
        ArtImagePolicy.requireBytes(w.toLong()*h*24+ow.toLong()*oh*24,"变换插值与选区覆盖")
        val image=BitmapFactory.decodeByteArray(capture.bytes,0,capture.bytes.size) ?: error("变换源PNG无效")
        val input=IntArray(w*h)
        try {require(image.width==w&&image.height==h);image.getPixels(input,0,w,0,0,w,h)} finally {image.recycle()}
        val sampler=ArtSoftSelection.Sampler(capture.mask)
        val maskInput=IntArray(w*h) {i->Color.argb(sampler.at(src.left+i%w+0.5,src.top+i/w+0.5),255,255,255)}
        val output=IntArray(ow*oh);val coverage=ByteArray(ow*oh)
        val filter=p.optString("interpolation","bilinear")
        fun put(x:Int,y:Int,sx:Double,sy:Double) {
            val i=y*ow+x
            output[i]=sample(input,w,h,sx-src.left,sy-src.top,filter)
            coverage[i]=Color.alpha(sample(maskInput,w,h,sx-src.left,sy-src.top,filter)).toByte()
        }
        if(plan.inverse!=null) {
            val row=FloatArray(ow*2)
            for(y in 0 until oh) {
                for(x in 0 until ow) {row[x*2]=(dst.left+x+0.5).toFloat();row[x*2+1]=(dst.top+y+0.5).toFloat()}
                plan.inverse.mapPoints(row)
                for(x in 0 until ow)put(x,y,row[x*2].toDouble(),row[x*2+1].toDouble())
            }
        } else {
            var work=0L
            for(triangle in plan.triangles) {
                val a=triangle.target[0];val b=triangle.target[1];val c=triangle.target[2]
                val left=floor(min(a.x,min(b.x,c.x))).toInt().coerceAtLeast(dst.left);val right=ceil(max(a.x,max(b.x,c.x))).toInt().coerceAtMost(dst.right)
                val top=floor(min(a.y,min(b.y,c.y))).toInt().coerceAtLeast(dst.top);val bottom=ceil(max(a.y,max(b.y,c.y))).toInt().coerceAtMost(dst.bottom)
                work+=(right-left).toLong()*(bottom-top);require(work<=128000000L) {"控制网格的像素运算量超限，请减少范围或细分"}
                val det=(b-a).cross(c-a)
                for(y in top until bottom)for(x in left until right) {
                    val v=ArtTransform.V(x+0.5,y+0.5)-a;val u=v.cross(c-a)/det;val t=(b-a).cross(v)/det
                    if(u>= -1e-9 && t>= -1e-9 && u+t<=1+1e-9) {
                        val mapped=triangle.source[0]*(1-u-t)+triangle.source[1]*u+triangle.source[2]*t
                        put(x-dst.left,y-dst.top,mapped.x,mapped.y)
                    }
                }
            }
        }
        require(coverage.any {it.toInt()!=0} && output.any {Color.alpha(it)>0}) {"变换后没有可见像素"}
        val mask=ArtSoftSelection.fromAlpha(coverage,dst)
        val bitmap=Bitmap.createBitmap(output,ow,oh,Bitmap.Config.ARGB_8888)
        val bytes=try {ArtImagePolicy.encodePng(bitmap,ArtStore.MAX_ASSET_BYTES)} finally {bitmap.recycle()}
        return Result(bytes,dst,mask)
    }
    fun validate(p:JSONObject) {
        val b=ArtMove.bounds(JSONObject().put("shape","rect").put("x",p.getInt("outputX")).put("y",p.getInt("outputY")).put("width",p.getInt("outputWidth")).put("height",p.getInt("outputHeight")))
        require(b.width()==p.getInt("outputWidth"))
        require(p.getString("scope") in setOf("selection","layer"))
        ArtSelection.validate(p.getJSONObject("sourceSelection"));require(p.getJSONObject("sourceSelection").has("coverage"))
        ArtSelection.validate(p.getJSONObject("selection"));require(p.getJSONObject("selection").has("coverage"))
        require(p.getString("interpolation") in ArtTransform.filters)
        val matrix=ArtShapes.matrix(p.getJSONArray("selectionToLayer"));require(matrix.invert(Matrix()))
    }
    fun draw(canvas:Canvas,event:JSONObject,store:ArtStore) {
        validate(event);val image=ArtImagePolicy.decodeAsset(store.assetFile(event.getString("asset")))
        try {
            require(image.width==event.getInt("outputWidth")&&image.height==event.getInt("outputHeight"))
            canvas.save()
            try {
                canvas.concat(ArtShapes.matrix(event.getJSONArray("selectionToLayer")))
                if(event.getString("scope")=="selection") {
                    val s=event.getJSONObject("sourceSelection")
                    ArtSoftSelection.draw(canvas,s,erase=true) {canvas.drawRect(s.getDouble("x").toFloat(),s.getDouble("y").toFloat(),(s.getDouble("x")+s.getDouble("width")).toFloat(),(s.getDouble("y")+s.getDouble("height")).toFloat(),Paint().apply {color=Color.WHITE})}
                }
                canvas.drawBitmap(image,event.getInt("outputX").toFloat(),event.getInt("outputY").toFloat(),Paint(Paint.FILTER_BITMAP_FLAG))
            } finally {canvas.restore()}
        } finally {image.recycle()}
    }
}
