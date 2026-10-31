package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.cancelled
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.number
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.integer
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.choice

/** Derivatives, surface lighting and denoising in the plugin's linear floating-point working model. */
internal object ArtSurfaceFilters {
    val ids=setOf("filter.edge_detection","filter.height_to_normal_map","filter.phong_bumpmap",
        "filter.gaussian_noise_reduction","filter.wavelet_noise_reducer")
    private data class Light(val vector:DoubleArray,val color:DoubleArray)
    private fun normal(x:Double,y:Double,z:Double):DoubleArray {
        val length=sqrt(x*x+y*y+z*z);require(length>0 && length.isFinite()) {"法线不能是零向量或非有限向量"}
        return doubleArrayOf(x/length,y/length,z/length)
    }
    /** Sparse derivative kernels; integer radii are sample spacing, not Krita's variable kernel radius. */
    private fun gradient(r:ArtFilterRaster,x:Int,y:Int,channel:String,type:String,rx:Int,ry:Int):DoubleArray {
        if(type=="simple")return doubleArrayOf((r.heightAt(x+rx,y,channel)-r.heightAt(x-rx,y,channel))/(2*rx),
            (r.heightAt(x,y+ry,channel)-r.heightAt(x,y-ry,channel))/(2*ry))
        var gx=0.0;var gy=0.0
        val weights=if(type=="sobel")intArrayOf(1,2,1) else intArrayOf(1,1,1)
        val norm=2*weights.sum()
        for(d in -1..1) {
            gx+=(r.heightAt(x+rx,y+d*ry,channel)-r.heightAt(x-rx,y+d*ry,channel))*weights[d+1]
            gy+=(r.heightAt(x+d*rx,y+ry,channel)-r.heightAt(x+d*rx,y-ry,channel))*weights[d+1]
        }
        return doubleArrayOf(gx/(norm*rx),gy/(norm*ry))
    }
    fun process(action:String,source:FloatArray,w:Int,h:Int,p:JSONObject,budgetBytes:Long=256L*1024*1024):FloatArray {
        require(action in ids)
        val r=ArtFilterRaster(source,w,h,budgetBytes)
        if(action=="filter.wavelet_noise_reducer")return r.finish(wavelet(r,p,budgetBytes),p,false)
        if(action=="filter.gaussian_noise_reduction") {
            val sigma=number(p,"sigma",1.0,0.0,32.0)
            val threshold=number(p,"threshold",.06,0.0,16.0)
            val blurred=ArtNeighborhoodFilters.process("filter.gaussian_blur",source,w,h,
                JSONObject().put("sigmaX",sigma).put("sigmaY",sigma),budgetBytes)
            val out=source.copyOf()
            for(i in source.indices step 4) {
                if(i%16384==0)cancelled()
                if(source[i+3]==0f)continue
                var difference=0.0
                for(c in 0..2)difference=max(difference,abs(r.straight(i,c)-(if(blurred[i+3]>0)blurred[i+c].toDouble()/blurred[i+3] else 0.0)))
                if(difference>threshold)for(c in 0..2)out[i+c]=(if(blurred[i+3]>0)blurred[i+c].toDouble()/blurred[i+3]*source[i+3] else 0.0).toFloat()
            }
            return r.finish(out,p,false)
        }
        r.work(32)
        val type=choice(p,"type","sobel",setOf("sobel","prewitt","simple"))
        val rx=integer(p,"spacingX",1,1,16);val ry=integer(p,"spacingY",1,1,16)
        val channel=choice(p,"heightChannel","luminance",setOf("red","green","blue","alpha","luminance"))
        val strength=number(p,"strength",1.0,0.0,1024.0)
        val output=choice(p,"output","magnitude",setOf("magnitude","xGrowth","xFall","yGrowth","yFall","direction"))
        val swizzle=if(p.has("swizzle"))p.getJSONArray("swizzle") else JSONArray("[\"x+\",\"y+\",\"z+\"]")
        require(swizzle.length()==3)
        val axes=IntArray(3);val signs=DoubleArray(3)
        for(c in 0..2) {
            val v=swizzle.get(c);require(v is String && v in setOf("x+","x-","y+","y-","z+","z-"))
            axes[c]="xyz".indexOf(v[0]);signs[c]=if(v[1]=='+')1.0 else -1.0
        }
        val normalInput=p.optBoolean("normalInput",false)
        val material=choice(p,"material","white",setOf("white","source"))
        val ambient=number(p,"ambient",.1,0.0,1.0);val diffuse=number(p,"diffuse",.7,0.0,1.0)
        val specular=number(p,"specular",.2,0.0,1.0);val shininess=number(p,"shininess",24.0,1.0,256.0)
        val lights=mutableListOf<Light>()
        if(action=="filter.phong_bumpmap") {
            val a=if(p.has("lights"))p.getJSONArray("lights") else JSONArray("[{\"azimuth\":135,\"elevation\":45,\"color\":\"#FFFFFFFF\",\"intensity\":1}]")
            require(a.length()<=4) {"最多4盏定向灯"}
            for(i in 0 until a.length()) {
                val o=a.getJSONObject(i)
                require(o.keys().asSequence().all {it in setOf("azimuth","elevation","color","intensity")})
                val az=number(o,"azimuth",135.0,-360.0,360.0)*PI/180
                val el=number(o,"elevation",45.0,-90.0,90.0)*PI/180
                val color=o.optString("color","#FFFFFFFF");require(color.matches(Regex("#[0-9a-fA-F]{8}")))
                val bits=color.drop(1).toLong(16)
                val intensity=number(o,"intensity",1.0,0.0,16.0)*((bits shr 24) and 255)/255
                val rgb=DoubleArray(3) {c->ArtFilterColorSpace.decode(((bits shr (16-8*c)) and 255)/255.0)*intensity}
                lights.add(Light(doubleArrayOf(cos(az)*cos(el),sin(az)*cos(el),sin(el)),rgb))
            }
        }
        val out=source.copyOf()
        for(y in 0 until h) {cancelled();for(x in 0 until w) {
            val i=(y*w+x)*4;if(source[i+3]==0f)continue
            val g=gradient(r,x,y,channel,type,rx,ry)
            when(action) {
                "filter.edge_detection" -> {
                    val gx=g[0]*strength;val gy=g[1]*strength
                    val edge=when(output) {"xGrowth"->max(0.0,gx);"xFall"->max(0.0,-gx)
                        "yGrowth"->max(0.0,gy);"yFall"->max(0.0,-gy)
                        "direction"->if(gx==0.0 && gy==0.0)0.0 else (atan2(gy,gx)+PI)/(2*PI)
                        else->hypot(gx,gy)}
                    for(c in 0..2)out[i+c]=(edge*source[i+3]).toFloat()
                }
                "filter.height_to_normal_map" -> {
                    val n=normal(-g[0]*strength,-g[1]*strength,1.0)
                    for(c in 0..2)out[i+c]=((n[axes[c]]*signs[c]*.5+.5)*source[i+3]).toFloat()
                }
                "filter.phong_bumpmap" -> {
                    val n=if(normalInput) {
                        val v=DoubleArray(3) {r.straight(i,it)}
                        require(v.all {it in 0.0..1.0}) {"法线图输入RGB需要0–1线性数据"}
                        normal(v[0]*2-1,v[1]*2-1,v[2]*2-1)
                    } else normal(-g[0]*strength,-g[1]*strength,1.0)
                    val light=DoubleArray(3) {ambient}
                    for(l in lights) {
                        val dot=(0..2).sumOf {n[it]*l.vector[it]}
                        val spec=if(dot>0)max(0.0,2*dot*n[2]-l.vector[2]).pow(shininess)*specular else 0.0
                        for(c in 0..2)light[c]+=l.color[c]*(diffuse*max(0.0,dot)+spec)
                    }
                    for(c in 0..2)out[i+c]=(light[c]*(if(material=="source")r.straight(i,c) else 1.0)*source[i+3]).toFloat()
                }
            }
        }}
        return r.finish(out,p,false)
    }

    /** Rectangular, edge-padded orthonormal Haar transform. Threshold is in linear RGB coefficient units. */
    private fun wavelet(r:ArtFilterRaster,p:JSONObject,budget:Long):FloatArray {
        val threshold=number(p,"threshold",.03,0.0,16.0)
        val mode=choice(p,"thresholdMode","soft",setOf("soft","hard"))
        fun powerOfTwo(v:Int):Int {var n=1;while(n<v) {require(n<=(1 shl 28));n*=2};return n}
        val w=powerOfTwo(r.width);val h=powerOfTwo(r.height)
        require(w.toLong()*h<=Int.MAX_VALUE && r.width.toLong()*r.height*64+w.toLong()*h*16<=budget) {"小波补边缓冲超过预算"}
        r.work(64)
        val out=r.source.copyOf();if(threshold==0.0)return out
        val buffer=DoubleArray(w*h);val temp=DoubleArray(w*h);val scale=1/sqrt(2.0)
        for(c in 0..2) {
            if(!p.optBoolean(listOf("red","green","blue")[c],true))continue
            for(y in 0 until h) {cancelled();for(x in 0 until w)buffer[y*w+x]=r.straight(r.index(x,y),c)}
            var width=w;var height=h;val levels=mutableListOf<Pair<Int,Int>>()
            while(width>1 || height>1) {
                cancelled();levels.add(width to height)
                val nw=max(1,width/2);val nh=max(1,height/2)
                if(width>1)for(y in 0 until height) {
                    cancelled();for(x in 0 until nw) {
                        val a=buffer[y*w+2*x];val b=buffer[y*w+2*x+1]
                        temp[y*w+x]=(a+b)*scale;temp[y*w+nw+x]=(a-b)*scale
                    }
                    for(x in 0 until width)buffer[y*w+x]=temp[y*w+x]
                }
                if(height>1)for(x in 0 until width) {
                    if(x%1024==0)cancelled();for(y in 0 until nh) {
                        val a=buffer[2*y*w+x];val b=buffer[(2*y+1)*w+x]
                        temp[y*w+x]=(a+b)*scale;temp[(nh+y)*w+x]=(a-b)*scale
                    }
                    for(y in 0 until height)buffer[y*w+x]=temp[y*w+x]
                }
                for(y in 0 until height) {cancelled();for(x in 0 until width)if(x>=nw || y>=nh) {
                    val i=y*w+x;val v=buffer[i]
                    buffer[i]=if(abs(v)<=threshold)0.0 else if(mode=="soft")sign(v)*(abs(v)-threshold) else v
                }}
                width=nw;height=nh
            }
            for((ww,hh) in levels.asReversed()) {
                val nw=max(1,ww/2);val nh=max(1,hh/2)
                if(hh>1)for(x in 0 until ww) {
                    if(x%1024==0)cancelled();for(y in 0 until nh) {
                        val a=buffer[y*w+x];val b=buffer[(nh+y)*w+x]
                        temp[2*y*w+x]=(a+b)*scale;temp[(2*y+1)*w+x]=(a-b)*scale
                    }
                    for(y in 0 until hh)buffer[y*w+x]=temp[y*w+x]
                }
                if(ww>1)for(y in 0 until hh) {
                    cancelled();for(x in 0 until nw) {
                        val a=buffer[y*w+x];val b=buffer[y*w+nw+x]
                        temp[y*w+2*x]=(a+b)*scale;temp[y*w+2*x+1]=(a-b)*scale
                    }
                    for(x in 0 until ww)buffer[y*w+x]=temp[y*w+x]
                }
            }
            for(y in 0 until r.height) {cancelled();for(x in 0 until r.width) {
                val i=(y*r.width+x)*4;if(r.source[i+3]>0)out[i+c]=(buffer[y*w+x]*r.source[i+3]).toFloat()
            }}
        }
        return out
    }
}
