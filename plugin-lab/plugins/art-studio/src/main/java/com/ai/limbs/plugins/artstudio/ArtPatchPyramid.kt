package com.ai.limbs.plugins.artstudio

import java.util.Random
import kotlin.math.*

/** Independent coarse-to-fine masked PatchMatch. Native pixels are reconstructed from native donors. */
internal object ArtPatchPyramid {
    const val MAX_LEVELS=6
    const val MAX_WORK=512_000_000L
    data class Frame(val width:Int,val height:Int,val pixels:IntArray,val hole:BooleanArray,val scale:Int=1)
    data class Level(val width:Int,val height:Int,val scale:Int,val maskPixels:Int,val refinementStep:Int,val centers:Int)
    data class Result(val pixels:IntArray,val work:Long,val levels:List<Level>)
    private data class Solved(val frame:Frame,val target:IntArray,val field:IntArray)
    private class Budget {var work=0L
        fun add() {if(++work>MAX_WORK)error("修补超过512000000次比较/投票预算；请降低精度、增大细化间距或缩小涂抹区域")}
    }
    private fun cancel() {if(Thread.currentThread().isInterrupted)error("智能修补已取消")}
    private fun alpha(pixel:Int)=pixel ushr 24
    private fun channel(pixel:Int,shift:Int)=(pixel ushr shift) and 255
    private fun color(a:Double,r:Double,g:Double,b:Double):Int {
        val aa=a.roundToInt().coerceIn(0,255)
        if(aa==0||a<=0)return 0
        fun c(v:Double)=(v/a).roundToInt().coerceIn(0,255)
        return (aa shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
    fun downsample(frame:Frame):Frame {
        val w=(frame.width+1)/2;val h=(frame.height+1)/2;val pixels=IntArray(w*h);val hole=BooleanArray(w*h)
        for(y in 0 until h) {
            if(y%32==0)cancel()
            for(x in 0 until w) {
                var a=0.0;var r=0.0;var g=0.0;var b=0.0;var count=0
                for(dy in 0..1)for(dx in 0..1) {val sx=x*2+dx;val sy=y*2+dy
                    if(sx>=frame.width||sy>=frame.height)continue
                    val i=sy*frame.width+sx;hole[y*w+x]=hole[y*w+x]||frame.hole[i]
                    val p=frame.pixels[i];val aa=alpha(p).toDouble();a+=aa
                    r+=channel(p,16)*aa;g+=channel(p,8)*aa;b+=channel(p,0)*aa;count++
                }
                // Any unknown child makes the parent unavailable as a donor; erased contents never taint the pyramid.
                pixels[y*w+x]=if(hole[y*w+x])0 else color(a/count,r/count,g/count,b/count)
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
            out[y*w+x]=alpha(frame.pixels[y*w+x])>0&&
                integral[y1*stride+x1]-integral[y0*stride+x1]-integral[y1*stride+x0]+integral[y0*stride+x0]==0
        }
        return out
    }
    fun spacing(w:Int,h:Int,accuracy:Int,requested:Int,scale:Int):Int {
        require(w>0&&h>0&&accuracy in 1..100&&requested in 0..64&&scale>0)
        return if(requested>0)maxOf(1,(requested+scale-1)/scale)
            else ceil(sqrt(w.toDouble()*h/(1024+accuracy*32))).toInt().coerceIn(1,64)
    }
    fun repair(original:IntArray,hole:BooleanArray,w:Int,h:Int,radius:Int,search:Int,accuracy:Int,
        requestedLevels:Int=0,refinementStep:Int=0,seed:Int=0):Result {
        require(w>0&&h>0&&w.toLong()*h==original.size.toLong()&&hole.size==original.size)
        require(radius in 1..8&&search in 16..1024&&accuracy in 1..100&&requestedLevels in 0..MAX_LEVELS&&refinementStep in 0..64)
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
        val budget=Budget();val reports=mutableListOf<Level>();var previous:Solved?=null
        for(f in frames.asReversed()) {
            val r=maxOf(1,(radius+f.scale-1)/f.scale);val s=maxOf(1,(search+f.scale-1)/f.scale)
            val step=spacing(f.width,f.height,accuracy,refinementStep,f.scale)
            previous=solve(f,r,s,accuracy,step,previous,seed,budget,reports)
        }
        return Result(requireNotNull(previous).target,budget.work,reports)
    }
    private fun solve(f:Frame,r:Int,search:Int,accuracy:Int,step:Int,coarse:Solved?,seed:Int,budget:Budget,reports:MutableList<Level>):Solved {
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
            target[i]=f.pixels[field[i]]
        }
        val cols=(w+step-1)/step;val rows=(h+step-1)/step;val anchors=IntArray(cols*rows) {-1}
        for(i in holes) {val tile=(i/w/step)*cols+(i%w/step);if(anchors[tile]<0)anchors[tile]=i}
        val centers=IntArray(anchors.count {it>=0});var centerIndex=0
        for(anchor in anchors)if(anchor>=0)centers[centerIndex++]=anchor
        reports.add(Level(w,h,f.scale,count,step,centers.size))
        val taps=(-r..r).filter {it==0||abs(it)==r||abs(it)==maxOf(1,r/2)}
        fun diff(a:Int,b:Int):Double {
            val aa=alpha(a).toDouble();val ba=alpha(b).toDouble();val da=aa-ba
            val dr=(channel(a,16)*aa-channel(b,16)*ba)/255
            val dg=(channel(a,8)*aa-channel(b,8)*ba)/255
            val db=(channel(a,0)*aa-channel(b,0)*ba)/255
            return da*da+dr*dr+dg*dg+db*db
        }
        fun score(i:Int,c:Int,limit:Double):Double {
            if(!candidate(i,c))return Double.POSITIVE_INFINITY
            var error=0.0;val x=i%w;val y=i/w;val sx=c%w;val sy=c/w
            for(dy in taps)for(dx in taps) {
                if(x+dx !in 0 until w||y+dy !in 0 until h)continue
                budget.add();val t=(y+dy)*w+x+dx;val source=(sy+dy)*w+sx+dx
                error+=diff(target[t],f.pixels[source])*(if(f.hole[t])0.25 else 1.0)
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
                    budget.add();val p=f.pixels[(sy+dy)*w+sx+dx];val weight=1f/(1+dx*dx+dy*dy);val a=alpha(p).toFloat()
                    weights[t]+=weight;sums[0][t]+=a*weight;sums[1][t]+=channel(p,16)*a*weight
                    sums[2][t]+=channel(p,8)*a*weight;sums[3][t]+=channel(p,0)*a*weight
                }
            }
            for(i in holes)target[i]=color((sums[0][i]/weights[i]).toDouble(),(sums[1][i]/weights[i]).toDouble(),
                (sums[2][i]/weights[i]).toDouble(),(sums[3][i]/weights[i]).toDouble())
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
                field[i]=best;target[i]=f.pixels[best]
            }
            // Lift each anchor's displacement to its tile; every native masked pixel keeps a valid clean donor.
            for(i in holes) {
                val anchor=anchors[(i/w/step)*cols+i%w/step];val donor=field[anchor]
                val sx=donor%w+i%w-anchor%w;val sy=donor/w+i/w-anchor/w
                if(sx in 0 until w&&sy in 0 until h&&candidate(i,sy*w+sx))field[i]=sy*w+sx
                target[i]=f.pixels[field[i]]
            }
            if(pass%2==1||pass==passes-1)reconstruct()
        }
        return Solved(f,target,field)
    }
}
