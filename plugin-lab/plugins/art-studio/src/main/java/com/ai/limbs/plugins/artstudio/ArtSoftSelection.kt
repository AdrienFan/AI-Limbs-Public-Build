package com.ai.limbs.plugins.artstudio

import android.graphics.*
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.*

/** Eight-bit coverage is persisted alongside the existing nonzero scanline outline. */
internal object ArtSoftSelection {
    val modes=linkedMapOf("replace" to "替换","add" to "添加","subtract" to "减去","intersect" to "相交","xor" to "异或")
    val tools=setOf("select","select_ellipse","select_polygon","select_freehand")
    fun defaults()=JSONObject().put("mode","replace").put("antialias",1).put("feather",0).put("expand",0)
    fun options(p:JSONObject):JSONObject {
        val out=defaults();out.keys().forEach {if(p.has(it))out.put(it,p.get(it))}
        require(out.getString("mode") in modes)
        val aa=out.getDouble("antialias");require(aa.isFinite()&&aa in 0.0..1.0)
        for(key in listOf("feather","expand")) {val value=out.getDouble(key);require(value.isFinite()&&value==value.toInt().toDouble())}
        require(out.getInt("feather") in 0..32&&out.getInt("expand") in -64..64)
        return out
    }
    fun mode(settings:JSONObject,meta:Int):String {
        val shift=meta and android.view.KeyEvent.META_SHIFT_MASK!=0;val alt=meta and android.view.KeyEvent.META_ALT_MASK!=0
        return when {shift&&alt->"intersect";shift->"add";alt->"subtract";meta and android.view.KeyEvent.META_CTRL_MASK!=0->"replace";else->settings.getString("mode")}
    }
    fun decode(s:JSONObject):ByteArray {
        val w=s.getInt("maskWidth");val h=s.getInt("maskHeight");require(w>0&&h>0&&w.toLong()*h<=ArtRasterSelection.MAX_PIXELS)
        val coverage=s.getJSONObject("coverage");require(coverage.getString("encoding")=="deflate-alpha-v1")
        val text=coverage.getString("data");require(text.length<=6*1024*1024)
        val packed=Base64.decode(text,Base64.NO_WRAP);require(packed.size<=5*1024*1024)
        val result=ByteArray(w*h)
        InflaterInputStream(ByteArrayInputStream(packed)).use {input->var offset=0
            while(offset<result.size) {val count=input.read(result,offset,result.size-offset);require(count>0) {"软选区数据长度无效"};offset+=count}
            require(input.read()==-1) {"软选区数据超出声明尺寸"}
        };return result
    }
    class Sampler(private val s:JSONObject) {
        private val alpha=if(s.has("coverage"))decode(s) else null
        private val hard=if(alpha==null)Region().apply {setPath(ArtSelection.path(s),Region(-1000000,-1000000,1000000,1000000))} else null
        fun at(x:Double,y:Double):Int {
            val a=alpha ?: return if(requireNotNull(hard).contains(floor(x).toInt(),floor(y).toInt()))255 else 0
            val w=s.getInt("maskWidth");val h=s.getInt("maskHeight")
            val u=(x-s.getDouble("x"))*w/s.getDouble("width")-0.5;val v=(y-s.getDouble("y"))*h/s.getDouble("height")-0.5
            if(u< -1||v< -1||u>w||v>h)return 0
            val left=floor(u).toInt();val top=floor(v).toInt();val dx=u-left;val dy=v-top
            fun value(i:Int,j:Int)=if(i in 0 until w&&j in 0 until h)(a[j*w+i].toInt() and 255).toDouble() else 0.0
            return ((value(left,top)*(1-dx)+value(left+1,top)*dx)*(1-dy)+(value(left,top+1)*(1-dx)+value(left+1,top+1)*dx)*dy).roundToInt().coerceIn(0,255)
        }
    }
    private fun region(s:JSONObject,w:Int,h:Int,padding:Int=0):Rect {
        val x=s.getDouble("x");val y=s.getDouble("y");val width=s.getDouble("width");val height=s.getDouble("height")
        require(listOf(x,y,width,height,x+width,y+height).all {it.isFinite()&&abs(it)<=1000000})
        return Rect(floor(x-padding).toInt().coerceIn(0,w),floor(y-padding).toInt().coerceIn(0,h),
            ceil(x+width+padding).toInt().coerceIn(0,w),ceil(y+height+padding).toInt().coerceIn(0,h))
    }
    private fun budget(r:Rect) {
        require(!r.isEmpty&&r.width().toLong()*r.height()<=ArtRasterSelection.MAX_PIXELS) {"软选区处理范围最多4194304像素，请缩小选区"}
        ArtImagePolicy.requireBytes(r.width().toLong()*r.height()*28,"软选区处理")
    }
    private fun raster(s:JSONObject,r:Rect,aa:Boolean):ByteArray {
        budget(r);val w=r.width();val h=r.height();val result=ByteArray(w*h)
        if(s.has("coverage")) {val sampler=Sampler(s);for(y in 0 until h)for(x in 0 until w)result[y*w+x]=sampler.at(r.left+x+0.5,r.top+y+0.5).toByte();return result}
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        try {val canvas=Canvas(bitmap);canvas.translate(-r.left.toFloat(),-r.top.toFloat())
            canvas.drawPath(ArtSelection.path(s),Paint().apply {isAntiAlias=aa;color=Color.WHITE;style=Paint.Style.FILL})
            val row=IntArray(w);for(y in 0 until h) {bitmap.getPixels(row,0,w,0,y,w,1);for(x in 0 until w)result[y*w+x]=Color.alpha(row[x]).toByte()}
        } finally {bitmap.recycle()};return result
    }
    private fun blur(input:ByteArray,w:Int,h:Int,radius:Int):ByteArray {
        fun pass(src:ByteArray,r:Int,horizontal:Boolean):ByteArray {
            if(r==0)return src
            val out=ByteArray(src.size);val length=if(horizontal)w else h;val lines=if(horizontal)h else w
            fun index(line:Int,k:Int)=if(horizontal)line*w+k else k*w+line
            for(line in 0 until lines) {var sum=0
                for(k in -r..r)if(k in 0 until length)sum+=src[index(line,k)].toInt() and 255
                for(k in 0 until length) {
                    out[index(line,k)]=((sum+(2*r+1)/2)/(2*r+1)).toByte()
                    if(k-r in 0 until length)sum-=src[index(line,k-r)].toInt() and 255
                    if(k+r+1 in 0 until length)sum+=src[index(line,k+r+1)].toInt() and 255
                }
            };return out
        }
        var result=input
        // Three rolling box filters approximate a Gaussian with bounded linear work; supports sum to radius.
        for(i in 0..2) {val r=radius/3+if(i<radius%3)1 else 0;result=pass(pass(result,r,true),r,false)}
        return result
    }
    fun fromAlpha(alpha:ByteArray,r:Rect):JSONObject {
        val s=ArtRasterSelection.fromMask(alpha,r.width(),r.height(),r.left,r.top)
        if(s.optString("shape")!="raster")return s
        val w=s.getInt("maskWidth");val h=s.getInt("maskHeight");val left=s.getInt("x")-r.left;val top=s.getInt("y")-r.top
        val cropped=ByteArray(w*h);for(y in 0 until h)System.arraycopy(alpha,(top+y)*r.width()+left,cropped,y*w,w)
        val buffer=ByteArrayOutputStream();DeflaterOutputStream(buffer).use {it.write(cropped)}
        require(buffer.size()<=5*1024*1024)
        return s.put("coverage",JSONObject().put("encoding","deflate-alpha-v1").put("data",Base64.encodeToString(buffer.toByteArray(),Base64.NO_WRAP)))
    }
    fun process(s:JSONObject,p:JSONObject,w:Int,h:Int):JSONObject {
        ArtSelection.validate(s);val o=options(p)
        if(s.getDouble("width")==0.0||s.getDouble("height")==0.0)return ArtBezierSelection.empty()
        val r=region(s,w,h,abs(o.getInt("expand"))+o.getInt("feather"));if(r.isEmpty)return ArtBezierSelection.empty()
        budget(r);val aa=o.getDouble("antialias");var mask=raster(s,r,aa>0)
        if(!s.has("coverage")&&aa>0&&aa<1) {val hard=raster(s,r,false);for(i in mask.indices)mask[i]=((hard[i].toInt() and 255)*(1-aa)+(mask[i].toInt() and 255)*aa).roundToInt().toByte()}
        val expand=o.getInt("expand");if(expand!=0)mask=ArtColorSelection.morph(mask,r.width(),r.height(),abs(expand),expand>0)
        val feather=o.getInt("feather");if(feather>0)mask=blur(mask,r.width(),r.height(),feather)
        return fromAlpha(mask,r).put("creationOptions",o)
    }
    fun combine(current:JSONObject?,created:JSONObject,mode:String,w:Int,h:Int):JSONObject {
        require(mode in modes);if(mode=="replace")return created
        val empty=current==null||current.getDouble("width")==0.0||current.getDouble("height")==0.0
        if(empty)return if(mode in setOf("add","xor"))created else ArtBezierSelection.empty()
        if(created.getDouble("width")==0.0||created.getDouble("height")==0.0)return if(mode=="intersect")ArtBezierSelection.empty() else JSONObject(requireNotNull(current).toString())
        val a=region(requireNotNull(current),w,h);val b=region(created,w,h)
        val r=Rect(minOf(a.left,b.left),minOf(a.top,b.top),maxOf(a.right,b.right),maxOf(a.bottom,b.bottom))
        if(r.isEmpty)return ArtBezierSelection.empty();budget(r)
        val left=raster(current,r,false);val right=raster(created,r,false)
        for(i in left.indices) {val x=left[i].toInt() and 255;val y=right[i].toInt() and 255
            left[i]=when(mode) {"add"->minOf(255,x+y);"subtract"->maxOf(0,x-y);"intersect"->minOf(x,y);"xor"->abs(x-y);else->error("未知覆盖率组合")}.toByte()}
        return fromAlpha(left,r)
    }
    /** White alpha shader has a transparent border; drawPaint also clears outside the selected frame. */
    fun apply(canvas:Canvas,s:JSONObject,toLocal:Matrix=Matrix(),tint:Boolean=false) {
        val alpha=decode(s);val w=s.getInt("maskWidth");val h=s.getInt("maskHeight")
        ArtImagePolicy.requireBytes((w+2L)*(h+2)*2,"软选区覆盖")
        val bitmap=Bitmap.createBitmap(w+2,h+2,Bitmap.Config.ALPHA_8)
        try {val padded=ByteArray(bitmap.rowBytes*(h+2));for(y in 0 until h)System.arraycopy(alpha,y*w,padded,(y+1)*bitmap.rowBytes+1,w)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(padded))
            val sx=(s.getDouble("width")/w).toFloat();val sy=(s.getDouble("height")/h).toFloat()
            val shader=BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {setLocalMatrix(Matrix().apply {
                setScale(sx,sy);postTranslate(s.getDouble("x").toFloat()-sx,s.getDouble("y").toFloat()-sy);postConcat(toLocal)
            })}
            canvas.drawPaint(Paint(Paint.FILTER_BITMAP_FLAG).apply {this.shader=shader
                if(tint) {color=Color.rgb(52,150,255);this.alpha=45} else xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
        } finally {bitmap.recycle()}
    }
    fun draw(canvas:Canvas,s:JSONObject?,toLocal:Matrix=Matrix(),erase:Boolean=false,block:()->Unit) {
        if(s==null) {block();return}
        if(!s.has("coverage")) {val path=ArtSelection.path(s);path.transform(toLocal);val saved=canvas.save();try {canvas.clipPath(path);block()} finally {canvas.restoreToCount(saved)};return}
        val saved=canvas.saveLayer(null,if(erase)Paint().apply {xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT)} else null)
        try {block();apply(canvas,s,toLocal)} finally {canvas.restoreToCount(saved)}
    }
    fun bindStroke(stroke:JSONObject,selection:JSONObject?,toLayer:Matrix):JSONObject {
        if(selection!=null)stroke.put("selection",JSONObject(selection.toString())).put("selectionToLayer",ArtShapes.encode(toLayer))
        return stroke
    }
    fun maskBitmap(bitmap:Bitmap,s:JSONObject,x:Int=0,y:Int=0) {apply(Canvas(bitmap),s,Matrix().apply {setTranslate(-x.toFloat(),-y.toFloat())})}
    fun blend(a:Int,b:Int,amount:Int):Int {
        if(amount==0)return a;if(amount==255)return b
        val t=amount/255.0;val aa=Color.alpha(a);val ba=Color.alpha(b);val alpha=aa*(1-t)+ba*t
        if(alpha==0.0)return 0
        fun channel(x:Int,y:Int)=((x*aa*(1-t)+y*ba*t)/alpha).roundToInt().coerceIn(0,255)
        return Color.argb(alpha.roundToInt(),channel(Color.red(a),Color.red(b)),channel(Color.green(a),Color.green(b)),channel(Color.blue(a),Color.blue(b)))
    }
    fun info()=JSONObject().put("defaults",defaults()).put("modes",JSONObject(modes))
        .put("pipeline","rasterize geometry -> square max/min expand/shrink -> three-pass Gaussian approximation feather -> coverage combine. AA strength 0..1; expand -64..64 px; feather 0..32 px. Pixel coverage is 0..255; add=min(255,a+b), subtract=max(0,a-b), intersect=min(a,b), xor=abs(a-b).")
        .put("scope","Rectangle/ellipse/polygon/lasso creation in document pixels; mask clipped to canvas, <=4194304 pixels/32768 nonzero runs. No selection is empty for combination: add/xor create, subtract/intersect yield an explicit empty selection. New history stores final mask. Older geometry selections remain readable.")
}
