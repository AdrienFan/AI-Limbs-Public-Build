package com.ai.limbs.plugins.artstudio

import java.util.concurrent.CancellationException
import kotlin.math.*

/** Bounded source-specific lazy edge tiles. Halos sample the same frozen image, so adjacent
 * tiles have identical filter boundaries and no visible seams.
 */
internal class ArtMagneticEdges(private val pixels:FloatArray,private val width:Int,private val height:Int,
    private val radius:Int,private val model:String) {
    private val tiles=LinkedHashMap<Int,FloatArray>(32,0.75f,true)
    private val columns=(width+63)/64
    fun at(x:Int,y:Int,cancelled:()->Boolean):Float {
        require(x in 0 until width && y in 0 until height)
        val tx=x/64;val ty=y/64;val key=ty*columns+tx
        val cached=synchronized(tiles) {tiles[key]}
        val tile=if(cached!=null)cached else {
            val computed=compute(tx*64,ty*64,cancelled)
            synchronized(tiles) {
                tiles[key]=computed
                while(tiles.size>128)tiles.remove(tiles.keys.first())
            }
            computed
        }
        return tile[(y%64)*minOf(64,width-tx*64)+x%64]
    }
    private fun channel(x:Int,y:Int,c:Int):Double {
        val v=pixels[(y.coerceIn(0,height-1)*width+x.coerceIn(0,width-1))*4+c].toDouble()
        return 255*if(c==3)v else sign(v)*ln(1+abs(v))/ln(2.0)
    }
    private fun compute(left:Int,top:Int,cancelled:()->Boolean):FloatArray {
        val w=minOf(64,width-left);val h=minOf(64,height-top);val out=FloatArray(w*h)
        if(model=="sobel") {
            for(y in 0 until h) {
                if(cancelled())throw CancellationException()
                for(x in 0 until w)for(c in 0..3) {
                    fun p(dx:Int,dy:Int)=channel(left+x+dx,top+y+dy,c)
                    val gx=p(radius,-radius)+2*p(radius,0)+p(radius,radius)-p(-radius,-radius)-2*p(-radius,0)-p(-radius,radius)
                    val gy=p(-radius,radius)+2*p(0,radius)+p(radius,radius)-p(-radius,-radius)-2*p(0,-radius)-p(radius,-radius)
                    out[y*w+x]=maxOf(out[y*w+x],(hypot(gx.toDouble(),gy.toDouble())/1020.0).coerceIn(0.0,1.0).toFloat())
                }
            }
            return out
        }
        require(model=="log")
        val halo=ceil(3.0*radius).toInt();val kernel=DoubleArray(halo*2+1) {i->exp(-((i-halo).toDouble().pow(2))/(2.0*radius*radius))}
        val sum=kernel.sum();kernel.indices.forEach {kernel[it]/=sum}
        val sw=w+2;val sh=h+2;val horizontal=FloatArray(sw*(sh+halo*2));val smooth=FloatArray(sw*sh)
        for(c in 0..3) {
            for(y in 0 until sh+halo*2) {
                if(cancelled())throw CancellationException()
                for(x in 0 until sw) {
                    var value=0.0
                    for(k in -halo..halo)value+=channel(left+x-1+k,top+y-1-halo,c)*kernel[k+halo]
                    horizontal[y*sw+x]=value.toFloat()
                }
            }
            for(y in 0 until sh)for(x in 0 until sw) {
                var value=0.0
                for(k in kernel.indices)value+=horizontal[(y+k)*sw+x]*kernel[k]
                smooth[y*sw+x]=value.toFloat()
            }
            for(y in 0 until h)for(x in 0 until w) {
                val i=(y+1)*sw+x+1
                val laplacian=abs(smooth[i-1]+smooth[i+1]+smooth[i-sw]+smooth[i+sw]-4*smooth[i])
                out[y*w+x]=maxOf(out[y*w+x],(laplacian*radius*radius*4/255f).coerceIn(0f,1f))
            }
        }
        return out
    }
}
