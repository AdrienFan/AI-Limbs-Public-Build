package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONArray
import kotlin.math.*

/** Premultiplied linear extended sRGB. RGB is signed and may exceed alpha; alpha remains 0..1. */
internal object ArtFloatPixels {
    const val LIMIT=65504f
    val space:ColorSpace get()=ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
    fun bitmap(w:Int,h:Int)=Bitmap.createBitmap(w,h,Bitmap.Config.RGBA_F16,true,space).apply {density=Bitmap.DENSITY_NONE}
    fun linear(v:Float):Float=if(abs(v)<=0.04045f)v/12.92f else sign(v)*((abs(v)+0.055f)/1.055f).pow(2.4f)
    fun srgb(v:Float):Float=if(abs(v)<=0.0031308f)v*12.92f else sign(v)*(1.055f*abs(v).pow(1f/2.4f)-0.055f)
    fun color(argb:Int):FloatArray {
        val a=Color.alpha(argb)/255f
        return floatArrayOf(linear(Color.red(argb)/255f)*a,linear(Color.green(argb)/255f)*a,linear(Color.blue(argb)/255f)*a,a)
    }
    fun color(rgba:JSONArray):FloatArray {
        require(rgba.length()==4)
        val p=FloatArray(4) {rgba.getDouble(it).toFloat()}
        require(p.all {it.isFinite() && abs(it)<=LIMIT} && p[3] in 0f..1f) {"线性RGBA需要有限RGB±65504、alpha 0–1"}
        for(c in 0..2)p[c]*=p[3]
        return p
    }
    fun preview(p:FloatArray,i:Int=0):Int {
        val a=p[i+3];fun byte(v:Float)=(v*255).roundToInt().coerceIn(0,255)
        return if(a<=0)0 else Color.argb(byte(a),byte(srgb(p[i]/a)),byte(srgb(p[i+1]/a)),byte(srgb(p[i+2]/a)))
    }
    fun read(input:Bitmap,area:Rect=Rect(0,0,input.width,input.height)):FloatArray {
        require(area.left>=0 && area.top>=0 && area.right<=input.width && area.bottom<=input.height && !area.isEmpty)
        val converted=input.config!=Bitmap.Config.RGBA_F16 || input.colorSpace!=space
        val image=if(converted)bitmap(input.width,input.height).also {Canvas(it).drawBitmap(input,0f,0f,Paint())} else input
        try {
            return ArtNativePixels.read(image,area.left,area.top,area.width(),area.height())
        } finally {if(converted)image.recycle()}
    }
    fun write(image:Bitmap,pixels:FloatArray) {
        require(image.config==Bitmap.Config.RGBA_F16 && image.colorSpace==space && pixels.size==image.width*image.height*4)
        ArtNativePixels.write(image,pixels)
    }
    fun from(w:Int,h:Int,p:FloatArray):Bitmap=bitmap(w,h).also {try {write(it,p)} catch(e:Throwable) {it.recycle();throw e}}
    fun mix(a:FloatArray,b:FloatArray,amount:Float,out:FloatArray,index:Int) {
        for(c in 0..3)out[index+c]=a[index+c]*(1-amount)+b[index+c]*amount
    }
    fun darkness(p:FloatArray,index:Int):Int = ((p[index+3]-(p[index]*0.2126f+p[index+1]*0.7152f+p[index+2]*0.0722f)).coerceIn(0f,1f)*255).roundToInt()
    /** Relative linear distance is bounded for masks without clamping HDR channels to white. */
    fun difference(a:FloatArray,ai:Int,b:FloatArray,bi:Int=0):Int {
        var delta=abs(a[ai+3]-b[bi+3])
        for(c in 0..2)delta=maxOf(delta,abs(a[ai+c]-b[bi+c])/maxOf(1f,abs(a[ai+c]),abs(b[bi+c])))
        return (delta*255).roundToInt().coerceIn(0,255)
    }
}

internal object ArtNativePixels {
    private var directory:java.io.File?=null
    private var loaded=false
    @Synchronized fun configure(root:java.io.File) {if(!loaded)directory=root}
    @Synchronized private fun load() {if(!loaded) {ArtNativeLibrary.load(requireNotNull(directory) {"高位深模块尚未绑定画室目录"},"artstudio_pixels_0299");loaded=true}}
    fun read(bitmap:Bitmap,x:Int,y:Int,width:Int,height:Int):FloatArray {load();return readNative(bitmap,x,y,width,height)}
    fun write(bitmap:Bitmap,pixels:FloatArray) {load();writeNative(bitmap,pixels)}
    private external fun readNative(bitmap:Bitmap,x:Int,y:Int,width:Int,height:Int):FloatArray
    private external fun writeNative(bitmap:Bitmap,pixels:FloatArray)
}

/** IEEE 754 binary16, round-to-nearest-even; codecs reject nonfinite samples before conversion. */
internal object ArtHalf {
    fun float(value:Short):Float {
        val h=value.toInt() and 65535;val sign=(h and 0x8000) shl 16;var exponent=(h ushr 10) and 31;var fraction=h and 1023
        if(exponent==0) {
            if(fraction==0)return Float.fromBits(sign)
            exponent=113
            while(fraction and 1024==0) {fraction=fraction shl 1;exponent--}
            return Float.fromBits(sign or (exponent shl 23) or ((fraction and 1023) shl 13))
        }
        return Float.fromBits(sign or ((if(exponent==31)255 else exponent+112) shl 23) or (fraction shl 13))
    }
    fun half(value:Float):Short {
        val bits=value.toRawBits();val sign=(bits ushr 16) and 0x8000;val abs=bits and 0x7fffffff
        require(value.isFinite() && kotlin.math.abs(value)<=65504f)
        if(abs<0x38800000) {
            if(abs<0x33000000)return sign.toShort()
            val shift=126-(abs ushr 23);val mantissa=(abs and 0x7fffff) or 0x800000
            val top=mantissa ushr shift;val remainder=mantissa and ((1 shl shift)-1);val halfway=1 shl (shift-1)
            return (sign or (top+if(remainder>halfway || remainder==halfway && top and 1!=0)1 else 0)).toShort()
        }
        val top=abs ushr 13;val remainder=abs and 8191
        return (sign or (top-0x1c000+if(remainder>4096 || remainder==4096 && top and 1!=0)1 else 0)).toShort()
    }
}
