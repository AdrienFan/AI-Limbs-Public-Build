package com.ai.limbs.plugins.artstudio

import kotlin.math.*

/** Plugin-owned RGBA8 prefilter and foreign-region cleanup; never edits saved key strokes. */
internal object ArtColorizeFilters {
    private fun cancel() { if(Thread.currentThread().isInterrupted)error("上色蒙版求解已取消") }
    private fun dimensions(n:Int,w:Int,h:Int) {require(w>0&&h>0&&w.toLong()*h==n.toLong()&&n<=ArtColorize.MAX_PIXELS)}
    /** Sliding box integrals use replicated borders, independent of radius and image area. */
    private fun box(input:FloatArray,w:Int,h:Int,r:Int,horizontal:Boolean):FloatArray {
        if(r==0)return input.copyOf()
        val out=FloatArray(input.size);val length=if(horizontal)w else h;val lines=if(horizontal)h else w
        val prefix=DoubleArray(length+1);val span=2*r+1.0
        fun index(line:Int,p:Int)=if(horizontal)line*w+p else p*w+line
        for(line in 0 until lines) {
            if(line%64==0)cancel()
            prefix[0]=0.0
            for(p in 0 until length)prefix[p+1]=prefix[p]+input[index(line,p)]
            val first=input[index(line,0)];val last=input[index(line,length-1)]
            for(p in 0 until length) {
                val left=p-r;val right=p+r
                val sum=prefix[minOf(length,right+1)]-prefix[maxOf(0,left)]+
                    maxOf(0,-left)*first.toDouble()+maxOf(0,right-length+1)*last.toDouble()
                out[index(line,p)]=(sum/span).toFloat()
            }
        }
        return out
    }
    /** Three separable boxes approximate a Gaussian with sigma=radius; fractional radii blend variances. */
    fun blur(input:FloatArray,w:Int,h:Int,radius:Double):FloatArray {
        dimensions(input.size,w,h);require(radius.isFinite()&&radius in 0.0..500.0)
        if(radius==0.0)return input.copyOf()
        val ideal=(sqrt(1+4*radius*radius)-1)/2;val low=floor(ideal).toInt();val high=low+1
        val mix=((radius*radius-low.toDouble()*(low+1))/(high.toDouble()*(high+1)-low.toDouble()*(low+1))).coerceIn(0.0,1.0)
        var current=input.copyOf()
        repeat(3) {
            for(horizontal in listOf(true,false)) {
                val a=box(current,w,h,low,horizontal);val b=box(current,w,h,high,horizontal)
                for(i in a.indices)a[i]=(a[i]*(1-mix)+b[i]*mix).toFloat()
                current=a
            }
        }
        return current
    }
    private fun normalize(input:FloatArray):FloatArray {
        var peak=0f;for(v in input)peak=maxOf(peak,v)
        if(peak>0f)for(i in input.indices)input[i]=(input[i]/peak).coerceIn(0f,1f)
        return input
    }
    fun height(strength:ByteArray,w:Int,h:Int,useEdges:Boolean,edgeSize:Double,fuzzyRadius:Double):ByteArray {
        dimensions(strength.size,w,h);require(edgeSize.isFinite()&&edgeSize in 0.0..100.0)
        require(fuzzyRadius.isFinite()&&fuzzyRadius in 0.0..500.0)
        if((!useEdges||edgeSize==0.0)&&fuzzyRadius==0.0)return strength.copyOf()
        var field=FloatArray(strength.size) {(strength[it].toInt() and 255)/255f}
        if(useEdges&&edgeSize>0.0) {
            val smooth=blur(field,w,h,edgeSize*0.5);val edges=FloatArray(field.size)
            for(y in 0 until h) {
                if(y%64==0)cancel()
                for(x in 0 until w) {val i=y*w+x
                    // Positive negative-Laplacian response outlines broad dark masses instead of blocking their interiors.
                    edges[i]=maxOf(0f,4*smooth[i]-smooth[y*w+maxOf(0,x-1)]-smooth[y*w+minOf(w-1,x+1)]-
                        smooth[maxOf(0,y-1)*w+x]-smooth[minOf(h-1,y+1)*w+x])
                }
            }
            field=normalize(blur(normalize(edges),w,h,edgeSize))
        }
        if(fuzzyRadius>0.0) {
            val fuzzy=blur(field,w,h,fuzzyRadius)
            // Screen union keeps thin lines while adding a soft ridge across short gaps.
            for(i in field.indices)field[i]=field[i]+fuzzy[i]*(1-field[i])
            normalize(field)
        }
        return ByteArray(field.size) {(field[it]*255).roundToInt().coerceIn(0,255).toByte()}
    }
    private inline fun adjacent(i:Int,w:Int,h:Int,visit:(Int)->Unit) {
        val x=i%w;val y=i/w
        if(x>0)visit(i-1);if(x+1<w)visit(i+1);if(y>0)visit(i-w);if(y+1<h)visit(i+w)
    }
    data class Cleanup(val pixels:Int,val seedPixels:Int,val regions:Int)
    /** Remove small foreign-boundary regions only when every competing region has a stronger perimeter. */
    fun cleanup(labels:IntArray,seeds:IntArray,w:Int,h:Int,amount:Double):Cleanup {
        dimensions(labels.size,w,h);require(seeds.size==labels.size&&amount.isFinite()&&amount in 0.0..1.0)
        if(amount==0.0)return Cleanup(0,0,0)
        val n=labels.size;val component=IntArray(n) {-1};val members=IntArray(n)
        val offsets=IntArray(n+1);val perimeter=IntArray(n);val foreign=IntArray(n)
        var tail=0;var count=0
        for(start in 0 until n) {
            if(start%16384==0)cancel()
            if(labels[start]<0||component[start]>=0)continue
            val id=count++;offsets[id]=tail;var head=tail;members[tail++]=start;component[start]=id
            while(head<tail) {
                if(head%16384==0)cancel()
                val i=members[head++];val x=i%w;val y=i/w
                if(x==0)perimeter[id]++;if(x==w-1)perimeter[id]++;if(y==0)perimeter[id]++;if(y==h-1)perimeter[id]++
                adjacent(i,w,h) {next->
                    if(labels[next]!=labels[i]) {perimeter[id]++;if(labels[next]>=0)foreign[id]++}
                    else if(component[next]<0) {component[next]=id;members[tail++]=next}
                }
            }
        }
        offsets[count]=tail
        val replacement=IntArray(count) {it};val threshold=0.05+0.45*(1-amount)
        for(id in 0 until count) {
            if(id%256==0)cancel()
            if(perimeter[id]==0||foreign[id].toDouble()/perimeter[id]<=threshold)continue
            var minimum=Int.MAX_VALUE;var sum=0L;var contacts=0;var winner=-1
            for(p in offsets[id] until offsets[id+1])adjacent(members[p],w,h) {next->
                val other=component[next]
                if(other>=0&&other!=id) {
                    minimum=minOf(minimum,perimeter[other]);sum+=perimeter[other];contacts++
                    if(winner<0||perimeter[other]>perimeter[winner]||
                        (perimeter[other]==perimeter[winner]&&labels[members[offsets[other]]]<labels[members[offsets[winner]]]))winner=other
                }
            }
            // Strictly increasing perimeter makes replacement acyclic; isolated regions and unseeded cells are preserved.
            if(winner>=0&&minimum>perimeter[id]&&sum.toDouble()/contacts>1.2*perimeter[id])replacement[id]=winner
        }
        var changed=0;var removedSeeds=0;var regions=0
        for(id in 0 until count) {
            if(id%256==0)cancel()
            var root=id;while(replacement[root]!=root)root=replacement[root]
            var current=id;while(replacement[current]!=current) {val next=replacement[current];replacement[current]=root;current=next}
            if(root==id)continue
            val label=labels[members[offsets[root]]];regions++
            for(p in offsets[id] until offsets[id+1]) {val i=members[p]
                if(labels[i]!=label) {labels[i]=label;changed++;if(seeds[i]>=0)removedSeeds++}
            }
        }
        return Cleanup(changed,removedSeeds,regions)
    }
}
