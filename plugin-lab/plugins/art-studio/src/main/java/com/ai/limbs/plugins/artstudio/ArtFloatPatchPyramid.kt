package com.ai.limbs.plugins.artstudio

import java.util.Random
import kotlin.math.*

/** Linear premultiplied float PatchMatch: donor search, pyramid and voting never quantize HDR to Int colors. */
internal object ArtFloatPatchPyramid {
    const val MAX_LEVELS=6
    const val MAX_WORK=512_000_000L
    data class Frame(val width:Int,val height:Int,val pixels:FloatArray,val hole:BooleanArray,val scale:Int=1)
    data class Result(val pixels:FloatArray,val work:Long,val levels:List<ArtPatchPyramid.Level>)
    private data class Solved(val frame:Frame,val target:FloatArray,val field:IntArray)
    private class Budget {var work=0L
        fun add() {if(++work>MAX_WORK)error("修补超过512000000次比较/投票预算；请降低精度、增大细化间距或缩小涂抹区域")}
    }
    private fun cancel() {if(Thread.currentThread().isInterrupted)error("智能修补已取消")}
    private fun copy(source:FloatArray,from:Int,destination:FloatArray,to:Int) {
        for(c in 0..3)destination[to*4+c]=source[from*4+c]
    }
    fun downsample(frame:Frame):Frame {
        val w=(frame.width+1)/2;val h=(frame.height+1)/2;val pixels=FloatArray(w*h*4);val hole=BooleanArray(w*h)
        for(y in 0 until h) {
            if(y%32==0)cancel()
            for(x in 0 until w) {
                var a=0.0;var r=0.0;var g=0.0;var b=0.0;var count=0
                for(dy in 0..1)for(dx in 0..1) {val sx=x*2+dx;val sy=y*2+dy
                    if(sx>=frame.width||sy>=frame.height)continue
                    val i=sy*frame.width+sx;hole[y*w+x]=hole[y*w+x]||frame.hole[i]
                    a+=frame.pixels[i*4+3];r+=frame.pixels[i*4];g+=frame.pixels[i*4+1];b+=frame.pixels[i*4+2];count++
                }
                // Any unknown child makes the parent unavailable as a donor; erased contents never taint the pyramid.
                if(!hole[y*w+x]) {val i=(y*w+x)*4;pixels[i]=(r/count).toFloat();pixels[i+1]=(g/count).toFloat();pixels[i+2]=(b/count).toFloat();pixels[i+3]=(a/count).toFloat()}
            }
        }
        return Frame(w,h,pixels,hole,frame.scale*2)
    }
    fun donors(frame:Frame,radius:Int):BooleanArray {
        val w=frame.width;val h=frame.height;val n=w*h;val out=BooleanArray(n)
        if(w<2*radius+1||h<2*radius+1)return out
        val integral=IntArray((w+1)*(h+1));val stride=w+1
        for(y in 0 until h) {if(y%64==0)cancel();var row=0
            for(x in 0 until w) {if(frame.hole[y*w+x])row++
                integral[(y+1)*stride+x+1]=integral[y*stride+x+1]+row}
        }
        for(y in radius until h-radius)for(x in radius until w-radius) {
            val x0=x-radius;val y0=y-radius;val x1=x+radius+1;val y1=y+radius+1
            out[y*w+x]=frame.pixels[(y*w+x)*4+3]>0f&&
                integral[y1*stride+x1]-integral[y0*stride+x1]-integral[y1*stride+x0]+integral[y0*stride+x0]==0
        }
        return out
    }
    fun spacing(w:Int,h:Int,accuracy:Int,requested:Int,scale:Int):Int {
        require(w>0&&h>0&&accuracy in 1..100&&requested in 0..64&&scale>0)
        return if(requested>0)maxOf(1,(requested+scale-1)/scale)
            else ceil(sqrt(w.toDouble()*h/(1024+accuracy*32))).toInt().coerceIn(1,64)
    }
    fun repair(original:FloatArray,hole:BooleanArray,w:Int,h:Int,radius:Int,search:Int,accuracy:Int,
        requestedLevels:Int=0,refinementStep:Int=0,seed:Int=0):Result {
        require(w>0&&h>0&&w.toLong()*h*4==original.size.toLong()&&hole.size.toLong()==w.toLong()*h)
        require(radius in 1..8&&search in 16..1024&&accuracy in 1..100&&requestedLevels in 0..MAX_LEVELS&&refinementStep in 0..64)
        require(original.all {it.isFinite() && abs(it)<=65504f} && hole.indices.all {original[it*4+3] in 0f..1f})
        require(seed>=0&&hole.any {it})
        val frames=mutableListOf(Frame(w,h,original,hole));val limit=if(requestedLevels==0)MAX_LEVELS else requestedLevels
        while(frames.size<limit) {
            val f=frames.last();if(requestedLevels==0&&f.hole.count {it}<=128)break
            val next=downsample(f);val r=maxOf(1,(radius+next.scale-1)/next.scale)
            // Automatic depth is a geometry plan: stop before the next level loses every clean patch.
            val usable=donors(next,r).any {it}
            if(requestedLevels==0&&!usable)break
            require(usable) {"指定层数的粗层没有有效纹理；请减少层数或扩大搜索外框"}
            frames.add(next)
        }
        val budget=Budget();val reports=mutableListOf<ArtPatchPyramid.Level>();var previous:Solved?=null
        for(f in frames.asReversed()) {
            val r=maxOf(1,(radius+f.scale-1)/f.scale);val s=maxOf(1,(search+f.scale-1)/f.scale)
            val step=spacing(f.width,f.height,accuracy,refinementStep,f.scale)
            previous=solve(f,r,s,accuracy,step,previous,seed,budget,reports)
        }
        return Result(requireNotNull(previous).target,budget.work,reports)
    }
    private fun solve(f:Frame,r:Int,search:Int,accuracy:Int,step:Int,coarse:Solved?,seed:Int,budget:Budget,reports:MutableList<ArtPatchPyramid.Level>):Solved {
        cancel();val w=f.width;val h=f.height;val n=w*h;val valid=donors(f,r)
        val nearest=IntArray(n) {-1};val queue=IntArray(n);var head=0;var tail=0
        for(i in 0 until n)if(valid[i]) {nearest[i]=i;queue[tail++]=i}
        require(tail>0) {"附近没有完整的未涂抹纹理补丁；请减小补丁半径或扩大搜索范围"}
        while(head<tail) {
            if(head%4096==0)cancel()
            val i=queue[head++];val x=i%w;val y=i/w
            for(dy in -1..1)for(dx in -1..1) {val nx=x+dx;val ny=y+dy
                if(nx !in 0 until w||ny !in 0 until h)continue
                val next=ny*w+nx;if(nearest[next]<0) {nearest[next]=nearest[i];queue[tail++]=next}
            }
        }
        val count=f.hole.count {it};val holes=IntArray(count);var k=0
        for(i in 0 until n)if(f.hole[i])holes[k++]=i
        val field=IntArray(n) {-1};val target=f.pixels.copyOf()
        fun candidate(i:Int,c:Int)=c in 0 until n&&valid[c]&&abs(c%w-i%w)<=search&&abs(c/w-i/w)<=search
        for(i in holes) {
            if(i%4096==0)cancel()
            val near=nearest[i];require(candidate(i,near)) {"修补内部离有效纹理太远；请增大搜索半径或减少涂抹范围"}
            field[i]=near
            coarse?.let {c->
                val x=i%w;val y=i/w;val p=minOf(c.frame.height-1,y/2)*c.frame.width+minOf(c.frame.width-1,x/2)
                val donor=c.field[p]
                if(donor>=0) {val sx=x+(donor%c.frame.width-p%c.frame.width)*2;val sy=y+(donor/c.frame.width-p/c.frame.width)*2
                    if(sx in 0 until w&&sy in 0 until h&&candidate(i,sy*w+sx))field[i]=sy*w+sx}
            }
            copy(f.pixels,field[i],target,i)
        }
        val cols=(w+step-1)/step;val rows=(h+step-1)/step;val anchors=IntArray(cols*rows) {-1}
        for(i in holes) {val tile=(i/w/step)*cols+(i%w/step);if(anchors[tile]<0)anchors[tile]=i}
        val centers=IntArray(anchors.count {it>=0});var centerIndex=0
        for(anchor in anchors)if(anchor>=0)centers[centerIndex++]=anchor
        reports.add(ArtPatchPyramid.Level(w,h,f.scale,count,step,centers.size))
        val taps=(-r..r).filter {it==0||abs(it)==r||abs(it)==maxOf(1,r/2)}
        fun diff(a:Int,b:Int):Double {
            var error=0.0
            for(c in 0..3) {val d=target[a*4+c].toDouble()-f.pixels[b*4+c];error+=d*d}
            return error
        }
        fun score(i:Int,c:Int,limit:Double):Double {
            if(!candidate(i,c))return Double.POSITIVE_INFINITY
            var error=0.0;val x=i%w;val y=i/w;val sx=c%w;val sy=c/w
            for(dy in taps)for(dx in taps) {
                if(x+dx !in 0 until w||y+dy !in 0 until h)continue
                budget.add();val t=(y+dy)*w+x+dx;val source=(sy+dy)*w+sx+dx
                error+=diff(t,source)*(if(f.hole[t])0.25 else 1.0)
                if(error>=limit)return error
            }
            return error
        }
        val random=Random(0x534d415254L+seed+f.scale);val passes=2+(accuracy-1)*6/99
        val sums=Array(4) {FloatArray(n)};val weights=FloatArray(n)
        fun reconstruct() {
            for(a in sums)a.fill(0f);weights.fill(0f)
            for(i in holes) {
                if(i%4096==0)cancel()
                val sx=field[i]%w;val sy=field[i]/w;val x=i%w;val y=i/w
                for(dy in taps)for(dx in taps) {
                    val tx=x+dx;val ty=y+dy;if(tx !in 0 until w||ty !in 0 until h)continue
                    val t=ty*w+tx;if(!f.hole[t])continue
                    budget.add();val p=((sy+dy)*w+sx+dx)*4;val weight=1f/(1+dx*dx+dy*dy)
                    weights[t]+=weight
                    for(c in 0..3)sums[c][t]+=f.pixels[p+c]*weight
                }
            }
            for(i in holes)for(c in 0..3)target[i*4+c]=sums[c][i]/weights[i]
        }
        for(pass in 0 until passes) {
            val side=if(pass%2==0)-1 else 1
            for(index in centers.indices) {
                if(index%128==0)cancel()
                val i=centers[if(side<0)index else centers.lastIndex-index];val x=i%w;val y=i/w
                var best=field[i];var distance=score(i,best,Double.POSITIVE_INFINITY)
                fun consider(sx:Int,sy:Int) {if(sx !in 0 until w||sy !in 0 until h)return
                    val c=sy*w+sx;val d=score(i,c,distance);if(d<distance) {best=c;distance=d}}
                val gx=x/step;val gy=y/step
                fun propagate(tile:Int) {val other=anchors[tile];if(other<0)return
                    val donor=field[other];consider(donor%w+x-other%w,donor/w+y-other/w)}
                if(gx+side in 0 until cols)propagate(gy*cols+gx+side)
                if(gy+side in 0 until rows)propagate((gy+side)*cols+gx)
                var range=search
                while(range>=1) {
                    repeat(1+accuracy/34) {consider((best%w+random.nextInt(2*range+1)-range).coerceIn(r,w-r-1),
                        (best/w+random.nextInt(2*range+1)-range).coerceIn(r,h-r-1))}
                    range/=2
                }
                field[i]=best;copy(f.pixels,best,target,i)
            }
            // Lift each anchor's displacement to its tile; every native masked pixel keeps a valid clean donor.
            for(i in holes) {
                val anchor=anchors[(i/w/step)*cols+i%w/step];val donor=field[anchor]
                val sx=donor%w+i%w-anchor%w;val sy=donor/w+i/w-anchor/w
                if(sx in 0 until w&&sy in 0 until h&&candidate(i,sy*w+sx))field[i]=sy*w+sx
                copy(f.pixels,field[i],target,i)
            }
            if(pass%2==1||pass==passes-1)reconstruct()
        }
        return Solved(f,target,field)
    }
}
