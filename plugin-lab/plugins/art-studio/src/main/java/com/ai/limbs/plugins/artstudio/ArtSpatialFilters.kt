package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import kotlin.math.*
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.cancelled
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.number
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.integer
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.choice
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.random

/** Geometry and texture filters. Their own bounded working model is declared in menu metadata. */
internal object ArtSpatialFilters {
    val ids=setOf("filter.pixelize","filter.oilpaint","filter.wave","filter.small_tiles",
        "filter.round_corners","filter.random_noise","filter.random_pick","filter.raindrops")
    fun process(action:String,source:FloatArray,w:Int,h:Int,p:JSONObject,budgetBytes:Long=256L*1024*1024):FloatArray {
        require(action in ids)
        val raster=ArtFilterRaster(source,w,h,budgetBytes);raster.work(4)
        val out=source.copyOf()
        when(action) {
            "filter.pixelize" -> {
                val bw=integer(p,"pixelWidth",10,1,512);val bh=integer(p,"pixelHeight",10,1,512)
                for(y in 0 until h step bh) {cancelled();for(x in 0 until w step bw) {
                    val right=min(w,x+bw);val bottom=min(h,y+bh);val sum=DoubleArray(4)
                    for(py in y until bottom)for(px in x until right)for(c in 0..3)sum[c]+=source[(py*w+px)*4+c]
                    val count=(right-x)*(bottom-y)
                    for(py in y until bottom)for(px in x until right)for(c in 0..3)out[(py*w+px)*4+c]=(sum[c]/count).toFloat()
                }}
            }
            "filter.oilpaint" -> {
                val radius=integer(p,"brushSize",1,1,8);val bins=integer(p,"smooth",30,2,255)
                raster.work((2L*radius+1).powLong(2)+bins)
                val counts=IntArray(bins+1);val sums=DoubleArray((bins+1)*3)
                for(y in 0 until h) {cancelled();for(x in 0 until w) {
                    val i=(y*w+x)*4;if(source[i+3]==0f)continue
                    counts.fill(0);sums.fill(0.0)
                    for(dy in -radius..radius)for(dx in -radius..radius) {
                        val j=raster.index(x+dx,y+dy);if(source[j+3]==0f)continue
                        val l=.2126*raster.straight(j,0)+.7152*raster.straight(j,1)+.0722*raster.straight(j,2)
                        val bin=(l.coerceIn(0.0,1.0)*bins).toInt();counts[bin]++
                        for(c in 0..2)sums[bin*3+c]+=raster.straight(j,c)
                    }
                    var best=0;for(k in 1..bins)if(counts[k]>counts[best])best=k
                    require(counts[best]>0)
                    for(c in 0..2)out[i+c]=(sums[best*3+c]/counts[best]*source[i+3]).toFloat()
                }}
            }
            "filter.wave" -> {
                val ax=number(p,"amplitudeX",4.0,-4096.0,4096.0);val ay=number(p,"amplitudeY",4.0,-4096.0,4096.0)
                val lx=number(p,"periodX",50.0,.01,65536.0);val ly=number(p,"periodY",50.0,.01,65536.0)
                val px=number(p,"phaseX",0.0,-360.0,360.0)*PI/180;val py=number(p,"phaseY",0.0,-360.0,360.0)*PI/180
                val sx=choice(p,"shapeX","sine",setOf("sine","triangle"));val sy=choice(p,"shapeY","sine",setOf("sine","triangle"))
                val border=choice(p,"border","repeat",setOf("repeat","wrap","transparent"))
                fun wave(t:Double,shape:String)=if(shape=="sine")sin(t) else 2/PI*asin(sin(t))
                for(y in 0 until h) {cancelled();for(x in 0 until w)
                    raster.sample(out,(y*w+x)*4,x+ax*wave(2*PI*y/lx+px,sx),y+ay*wave(2*PI*x/ly+py,sy),border)}
            }
            "filter.small_tiles" -> {
                val n=integer(p,"numberOfTiles",2,1,16);val tw=w/n;val th=h/n
                require(tw>0 && th>0) {"每个瓷砖至少需要1×1像素"};raster.work(16)
                val tile=FloatArray(tw*th*4)
                // Exact pixel-area integration avoids aliasing when an entire image is reduced into a tile.
                for(y in 0 until th) {cancelled();for(x in 0 until tw) {
                    val left=x.toDouble()*w/tw;val right=(x+1.0)*w/tw
                    val top=y.toDouble()*h/th;val bottom=(y+1.0)*h/th;val sum=DoubleArray(4)
                    for(py in floor(top).toInt() until ceil(bottom).toInt())for(px in floor(left).toInt() until ceil(right).toInt()) {
                        val weight=(min(right,px+1.0)-max(left,px.toDouble()))*(min(bottom,py+1.0)-max(top,py.toDouble()))
                        for(c in 0..3)sum[c]+=source[raster.index(px,py)+c]*weight
                    }
                    for(c in 0..3)tile[(y*tw+x)*4+c]=(sum[c]/((right-left)*(bottom-top))).toFloat()
                }}
                out.fill(0f)
                for(y in 0 until th*n) {cancelled();for(x in 0 until tw*n)for(c in 0..3)out[(y*w+x)*4+c]=tile[((y%th)*tw+x%tw)*4+c]}
            }
            "filter.round_corners" -> {
                val r=min(number(p,"radius",30.0,0.0,8192.0),min(w,h)/2.0)
                val aa=p.optBoolean("antialias",true)
                if(r>0)for(y in 0 until h) {cancelled();for(x in 0 until w) {
                    val dx=max(abs(x+.5-w/2.0)-(w/2.0-r),0.0)
                    val dy=max(abs(y+.5-h/2.0)-(h/2.0-r),0.0)
                    val distance=hypot(dx,dy)-r
                    val coverage=if(aa)(.5-distance).coerceIn(0.0,1.0) else if(distance<=0)1.0 else 0.0
                    for(c in 0..3)out[(y*w+x)*4+c]=(source[(y*w+x)*4+c]*coverage).toFloat()
                }}
            }
            "filter.random_noise","filter.random_pick" -> {
                val level=number(p,"level",50.0,0.0,100.0)/100
                val opacity=number(p,"opacity",100.0,0.0,100.0)/100
                val seed=integer(p,"seed",0,Int.MIN_VALUE,Int.MAX_VALUE)
                val window=number(p,"windowsize",2.5,0.0,4096.0)
                val grayscale=p.optBoolean("grayscale",false)
                val border=choice(p,"border","repeat",setOf("repeat","wrap","transparent"))
                val sampled=FloatArray(4)
                for(y in 0 until h) {cancelled();for(x in 0 until w) {
                    if(random(seed,x,y,0)>=level || opacity==0.0)continue
                    val i=(y*w+x)*4
                    if(action=="filter.random_pick")raster.sample(sampled,0,x+window*(random(seed,x,y,1)-.5),y+window*(random(seed,x,y,2)-.5),border)
                    else {
                        for(c in 0..2)sampled[c]=ArtFilterColorSpace.decode(random(seed,x,y,if(grayscale)1 else c+1)).toFloat()
                        sampled[3]=1f
                    }
                    for(c in 0..3)out[i+c]=(source[i+c]*(1-opacity)+sampled[c]*opacity).toFloat()
                }}
            }
            "filter.raindrops" -> rain(raster,out,p)
        }
        return raster.finish(out,p,action!="filter.oilpaint")
    }
    private fun Long.powLong(n:Int):Long {var v=1L;repeat(n) {v*=this};return v}
    private data class Drop(val x:Double,val y:Double,val r:Double)
    private fun rain(r:ArtFilterRaster,out:FloatArray,p:JSONObject) {
        val size=integer(p,"dropSize",80,5,128);val count=integer(p,"number",80,0,256)
        val seed=integer(p,"seed",0,Int.MIN_VALUE,Int.MAX_VALUE)
        val fish=number(p,"fishEyes",30.0,1.0,100.0)/100
        val lighting=number(p,"lighting",1.0,0.0,4.0)
        val blur=p.optBoolean("blur",true)
        require(count.toLong()*(size+6)*(size+6)*10<=250_000_000L) {"雨滴运算量超过单次预算"}
        val drops=mutableListOf<Drop>()
        for(k in 0 until count) {
            cancelled();var found:Drop?=null
            for(attempt in 0 until 256) {
                val radius=(5+(size-5)*random(seed,k,attempt,1))/2
                val d=Drop(random(seed,k,attempt,2)*(r.width-1),random(seed,k,attempt,3)*(r.height-1),radius)
                if(drops.none {hypot(it.x-d.x,it.y-d.y)<it.r+d.r+2}) {found=d;break}
            }
            val d=found ?: break // Placement has an explicit maximum; count is a requested upper bound.
            drops.add(d)
            val left=max(0,floor(d.x-d.r-2).toInt());val right=min(r.width-1,ceil(d.x+d.r+2).toInt())
            val top=max(0,floor(d.y-d.r-2).toInt());val bottom=min(r.height-1,ceil(d.y+d.r+2).toInt())
            for(y in top..bottom) {cancelled();for(x in left..right) {
                val dx=x-d.x;val dy=y-d.y;val distance=hypot(dx,dy)
                if(distance>d.r)continue
                val scale=if(distance==0.0)1.0 else expm1(distance/d.r*ln1p(fish*d.r))/fish/distance
                val i=(y*r.width+x)*4;r.sample(out,i,d.x+dx*scale,d.y+dy*scale,"repeat")
                val rim=(distance/d.r).pow(4)
                val spec=exp(-((dx/d.r+.3).pow(2)+(dy/d.r+.35).pow(2))*35)
                val shade=lighting*((dy-dx)/d.r*.15*rim+spec*.35)
                for(c in 0..2)out[i+c]=(out[i+c]+shade*out[i+3]).toFloat()
            }}
            if(blur) {
                val pw=right-left+1;val patch=FloatArray(pw*(bottom-top+1)*4)
                for(y in top..bottom) {cancelled();for(x in left..right) {
                    val i=(y*r.width+x)*4;val j=((y-top)*pw+x-left)*4
                    if(hypot(x-d.x,y-d.y)>d.r+1) {for(c in 0..3)patch[j+c]=out[i+c];continue}
                    for(c in 0..3) {var sum=0.0;for(dy in -1..1)for(dx in -1..1)sum+=out[r.index(x+dx,y+dy)+c]
                        patch[j+c]=(sum/9).toFloat()}
                }}
                for(y in top..bottom)for(x in left..right)for(c in 0..3)out[(y*r.width+x)*4+c]=patch[((y-top)*pw+x-left)*4+c]
            }
        }
    }
}
