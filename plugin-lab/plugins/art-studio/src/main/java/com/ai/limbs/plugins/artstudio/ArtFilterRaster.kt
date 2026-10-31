package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import kotlin.math.*

/** Bounded immutable input, premultiplied sampling and channel masks for spatial operations. */
internal class ArtFilterRaster(val source:FloatArray,val width:Int,val height:Int,budgetBytes:Long) {
    init {
        require(width>0 && height>0 && width.toLong()*height*4==source.size.toLong())
        require(width.toLong()*height*96<=budgetBytes) {"空间滤镜工作内存超过预算"}
        for(i in source.indices) {
            if(i%16384==0)cancelled()
            require(source[i].isFinite() && if(i%4==3)source[i] in 0f..1f else abs(source[i])<=65504f)
        }
    }
    fun work(taps:Long) {require(width.toLong()*height*taps<=250_000_000L) {"空间滤镜运算量超过单次预算"}}
    fun index(x:Int,y:Int)=((y.coerceIn(0,height-1))*width+x.coerceIn(0,width-1))*4
    fun straight(i:Int,c:Int)=if(source[i+3]>0)source[i+c].toDouble()/source[i+3] else 0.0
    fun heightAt(x:Int,y:Int,channel:String):Double {
        val i=index(x,y)
        return when(channel) {"red"->straight(i,0);"green"->straight(i,1);"blue"->straight(i,2)
            "alpha"->source[i+3].toDouble();else->.2126*straight(i,0)+.7152*straight(i,1)+.0722*straight(i,2)}
    }
    fun sample(out:FloatArray,i:Int,x:Double,y:Double,border:String) {
        val sx=when(border) {"repeat"->x.coerceIn(0.0,width-1.0);"wrap"->((x%width)+width)%width;else->x}
        val sy=when(border) {"repeat"->y.coerceIn(0.0,height-1.0);"wrap"->((y%height)+height)%height;else->y}
        val bx=floor(sx).toInt();val by=floor(sy).toInt();val fx=sx-bx;val fy=sy-by
        for(c in 0..3) {
            var sum=0.0
            for(dy in 0..1)for(dx in 0..1) {
                var px=bx+dx;var py=by+dy
                if(border=="transparent" && (px !in 0 until width || py !in 0 until height))continue
                if(border=="wrap") {px=((px%width)+width)%width;py=((py%height)+height)%height}
                sum+=source[index(px,py)+c]*(if(dx==0)1-fx else fx)*(if(dy==0)1-fy else fy)
            }
            out[i+c]=sum.toFloat()
        }
    }
    fun finish(out:FloatArray,p:JSONObject,defaultAlpha:Boolean):FloatArray {
        val channels=booleanArrayOf(p.optBoolean("red",true),p.optBoolean("green",true),p.optBoolean("blue",true),p.optBoolean("alpha",defaultAlpha))
        require(channels.any {it}) {"至少选择一个通道"}
        for(i in source.indices step 4) {
            if(i%16384==0)cancelled()
            val old=source[i+3];val filtered=out[i+3]
            require(filtered.isFinite() && filtered in 0f..1f) {"空间滤镜产生非法透明度"}
            val alpha=if(channels[3])filtered else old
            for(c in 0..2) {
                val a=if(channels[c])filtered else old
                val v=if(channels[c])out[i+c] else source[i+c]
                val result=if(a==alpha)v else if(a>0)(v.toDouble()/a*alpha).toFloat() else 0f
                require(result.isFinite() && abs(result)<=65504f) {"空间滤镜结果超出有限浮点范围"}
                out[i+c]=result
            }
            out[i+3]=alpha
        }
        return out
    }
    companion object {
        fun cancelled() {if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("滤镜已取消")}
        fun number(p:JSONObject,key:String,default:Double,min:Double,max:Double):Double {
            val raw=if(p.has(key))p.get(key) else default;require(raw is Number) {"$key 需要数字"}
            val v=raw.toDouble();require(v.isFinite() && v in min..max) {"$key 需要有限值 $min–$max"};return v
        }
        fun integer(p:JSONObject,key:String,default:Int,min:Int,max:Int):Int {
            val v=number(p,key,default.toDouble(),min.toDouble(),max.toDouble());require(v==floor(v)) {"$key 需要整数"};return v.toInt()
        }
        fun choice(p:JSONObject,key:String,default:String,values:Set<String>):String {
            val v=if(p.has(key))p.get(key) else default;require(v is String && v in values) {"$key 选项无效"};return v
        }
        /** Stable counter-based noise: seed, canvas coordinates and stream identify every sample. */
        fun random(seed:Int,x:Int,y:Int,stream:Int):Double {
            var z=seed.toLong() xor (x.toLong()*-7046029254386353131L) xor (y.toLong()*-4658895280553007687L) xor (stream.toLong()*-7723592293110705685L)
            z=(z xor (z ushr 30))*-4658895280553007687L;z=(z xor (z ushr 27))*-7723592293110705685L
            return ((z xor (z ushr 31)) ushr 11).toDouble()/9007199254740992.0
        }
    }
}
