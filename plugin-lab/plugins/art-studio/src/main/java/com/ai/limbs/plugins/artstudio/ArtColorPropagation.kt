package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import kotlin.math.*
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.cancelled
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.number
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.choice

/** Exact grid metrics. Hidden straight RGB is carried in an independent masked color plane. */
internal object ArtColorPropagation {
    fun process(r:ArtFilterRaster,p:JSONObject,hidden:FloatArray?):ArtResourceFilters.Result {
        val metric=choice(p,"distanceMetric","euclidean",setOf("euclidean","cityBlock","chessboard"))
        val mode=choice(p,"expansionMode","unbounded",setOf("bounded","unbounded"))
        val amount=number(p,"expansionAmount",10.0,0.0,8192.0)
        val expand=choice(p,"alphaMode","expand",setOf("preserve","expand"))=="expand"
        val useHidden=p.optBoolean("useHiddenSeeds",false)
        val w=r.width;val h=r.height;val n=w*h
        r.work(32)
        val owners=IntArray(n) {-1};val distance=DoubleArray(n) {Double.POSITIVE_INFINITY}
        for(j in 0 until n) {
            if(j%4096==0)cancelled()
            if(r.source[j*4+3]>0f || useHidden && hidden!=null && hidden[j*4+3]>0) {owners[j]=j;distance[j]=0.0}
        }
        if(metric=="euclidean") {
            val length=max(w,h);val f=DoubleArray(length);val d=DoubleArray(length)
            val arg=IntArray(length);val sites=IntArray(length);val z=DoubleArray(length+1)
            fun transform(count:Int) {
                var k=-1
                for(q in 0 until count)if(f[q].isFinite()) {
                    var cross=Double.NEGATIVE_INFINITY
                    while(k>=0) {
                        val v=sites[k]
                        cross=((f[q]+q.toDouble()*q)-(f[v]+v.toDouble()*v))/(2.0*(q-v))
                        if(cross>z[k])break
                        k--
                    }
                    k++;sites[k]=q;z[k]=if(k==0)Double.NEGATIVE_INFINITY else cross;z[k+1]=Double.POSITIVE_INFINITY
                }
                if(k<0) {for(x in 0 until count) {d[x]=Double.POSITIVE_INFINITY;arg[x]=-1};return}
                val last=k;k=0
                for(x in 0 until count) {
                    while(k<last && z[k+1]<x)k++
                    val v=sites[k];d[x]=(x-v).toDouble().pow(2)+f[v];arg[x]=v
                }
            }
            for(y in 0 until h) {
                cancelled();for(x in 0 until w)f[x]=distance[y*w+x]
                transform(w)
                for(x in 0 until w) {distance[y*w+x]=d[x];owners[y*w+x]=if(arg[x]<0)-1 else y*w+arg[x]}
            }
            val rowOwners=IntArray(h)
            for(x in 0 until w) {
                cancelled();for(y in 0 until h) {f[y]=distance[y*w+x];rowOwners[y]=owners[y*w+x]}
                transform(h)
                for(y in 0 until h) {distance[y*w+x]=sqrt(d[y]);owners[y*w+x]=if(arg[y]<0)-1 else rowOwners[arg[y]]}
            }
        } else {
            val queue=IntArray(n);var head=0;var tail=0
            for(i in 0 until n)if(owners[i]>=0)queue[tail++]=i
            while(head<tail) {
                if(head%4096==0)cancelled()
                val j=queue[head++];val x=j%w;val y=j/w
                for(dy in -1..1)for(dx in -1..1) {
                    if(dx==0 && dy==0 || metric=="cityBlock" && abs(dx)+abs(dy)!=1)continue
                    val px=x+dx;val py=y+dy;if(px !in 0 until w || py !in 0 until h)continue
                    val k=py*w+px;if(owners[k]>=0)continue
                    owners[k]=owners[j];distance[k]=distance[j]+1;queue[tail++]=k
                }
            }
        }
        val out=r.source.copyOf();val plane=hidden?.copyOf() ?: FloatArray(r.source.size)
        for(j in 0 until n) {
            if(j%4096==0)cancelled()
            val owner=owners[j];if(owner<0 || mode=="bounded" && distance[j]>amount)continue
            val i=j*4;val si=owner*4
            val rgb=DoubleArray(3) {c->if(r.source[si+3]>0)r.straight(si,c) else {
                val plane=requireNotNull(hidden);plane[si+c].toDouble()/plane[si+3]}}
            require(rgb.all {it.isFinite() && abs(it)<=65504}) {"扩散的直通RGB超出隐藏色支持范围±65504"}
            if(expand) {
                // Alpha-zero emissive RGB remains visible data, never reinterpreted as hidden straight color.
                if(r.source[i+3]==0f && (0..2).any {r.source[i+it]!=0f})continue
                for(c in 0..2)out[i+c]=rgb[c].toFloat();out[i+3]=1f
                for(c in 0..3)plane[i+c]=0f
            } else if(r.source[i+3]==0f) {
                for(c in 0..2)plane[i+c]=rgb[c].toFloat();plane[i+3]=1f
            }
        }
        return ArtResourceFilters.Result(out,plane)
    }
}
