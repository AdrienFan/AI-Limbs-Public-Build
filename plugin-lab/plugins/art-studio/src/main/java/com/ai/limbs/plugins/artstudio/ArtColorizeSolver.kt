package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONObject
import kotlin.math.*

/** Versioned settings drive line prefiltering and seeded geodesic fill; output remains an explicit cached asset. */
internal object ArtColorizeSolver {
    data class Result(val bitmap: Bitmap,val x: Int,val y: Int,val filled: Int,val seeds: Int,val cleanup: ArtColorizeFilters.Cleanup)
    fun workingBytes(mask:JSONObject,pixels:Long):Long {
        val options=ArtColorize.normalizeSettings(mask.getJSONObject("colorize").getJSONObject("settings"),JSONObject())
        val prefilter=(options.getBoolean("useEdgeDetection")&&options.getDouble("edgeDetectionSize")>0)||options.getDouble("fuzzyRadius")>0
        return pixels*(64+(if(prefilter)24 else 0)+(if(options.getDouble("cleanUpAmount")>0)24 else 0))
    }
    private fun strength(pixel: Int): Int {
        val light=(Color.red(pixel)*54+Color.green(pixel)*183+Color.blue(pixel)*19)/256
        return (255-light)*Color.alpha(pixel)/255
    }
    fun region(state: JSONObject,source: Bitmap,mask: JSONObject): Rect {
        val w=source.width;val h=source.height
        val selection=state.optJSONObject("selection")
        var bounds=Rect(0,0,w,h)
        if(selection!=null) {
            val x=floor(selection.getDouble("x")).toInt().coerceIn(0,w)
            val y=floor(selection.getDouble("y")).toInt().coerceIn(0,h)
            val r=ceil(selection.getDouble("x")+selection.getDouble("width")).toInt().coerceIn(0,w)
            val b=ceil(selection.getDouble("y")+selection.getDouble("height")).toInt().coerceIn(0,h)
            require(r>x && b>y) {"选区没有覆盖画布"}
            bounds=Rect(x,y,r,b)
        }
        if(mask.getJSONObject("colorize").getJSONObject("settings").getBoolean("limitBounds")) {
            val content=Rect();var found=false
            fun include(x: Int,y: Int) {if(!found) {content.set(x,y,x+1,y+1);found=true} else content.union(x,y,x+1,y+1)}
            val row=IntArray(w)
            for(y in bounds.top until bounds.bottom) {
                source.getPixels(row,0,w,0,y,w,1)
                for(x in bounds.left until bounds.right) if(strength(row[x])>=8)include(x,y)
            }
            for(key in ArtColorize.items(mask).filterNot {it.getBoolean("erase")}) {
                val points=key.getJSONArray("points");val half=key.getDouble("width")/2
                for(i in 0 until points.length()) {
                    val p=points.getJSONArray(i)
                    val x=floor(p.getDouble(0)-half).toInt().coerceIn(bounds.left,bounds.right-1)
                    val y=floor(p.getDouble(1)-half).toInt().coerceIn(bounds.top,bounds.bottom-1)
                    val r=ceil(p.getDouble(0)+half).toInt().coerceIn(bounds.left+1,bounds.right)
                    val b=ceil(p.getDouble(1)+half).toInt().coerceIn(bounds.top+1,bounds.bottom)
                    include(x,y);include(r-1,b-1)
                }
            }
            if(found) {
                val options=ArtColorize.normalizeSettings(mask.getJSONObject("colorize").getJSONObject("settings"),JSONObject())
                val pad=options.getInt("gapClose")+ceil(3*(options.getDouble("fuzzyRadius")+
                    if(options.getBoolean("useEdgeDetection"))1.5*options.getDouble("edgeDetectionSize") else 0.0)).toInt()+8
                content.inset(-pad,-pad);content.intersect(bounds);bounds=content
            }
        }
        require(bounds.width().toLong()*bounds.height()<=ArtColorize.MAX_PIXELS) {
            "填色区域超过4194304像素，请启用图层边界限制或选择较小区域"
        }
        return bounds
    }
    /** Replicated edges and separable sliding extrema avoid radius*area scanning. */
    private fun morph(input: ByteArray,w: Int,h: Int,radius: Int,maximum: Boolean,horizontal: Boolean): ByteArray {
        val output=ByteArray(input.size);val length=if(horizontal)w else h;val lines=if(horizontal)h else w
        val deque=IntArray(length+2*radius)
        fun index(line: Int,position: Int): Int = if(horizontal)line*w+position else position*w+line
        for(line in 0 until lines) {
            var head=0;var tail=0
            fun value(position: Int): Int=input[index(line,position.coerceIn(0,length-1))].toInt()
            for(t in -radius until length+radius) {
                val v=value(t)
                while(head<tail && (if(maximum)value(deque[tail-1])<=v else value(deque[tail-1])>=v))tail--
                deque[tail++]=t
                while(deque[head]<t-2*radius)head++
                val center=t-radius
                if(center in 0 until length)output[index(line,center)]=value(deque[head]).toByte()
            }
        }
        return output
    }
    fun solve(state: JSONObject,source: Bitmap,mask: JSONObject,area: Rect): Result {
        val w=area.width();val h=area.height();val n=w*h
        ArtImagePolicy.requireBytes(workingBytes(mask,n.toLong()),"上色蒙版求解")
        val data=mask.getJSONObject("colorize");val settings=ArtColorize.normalizeSettings(data.getJSONObject("settings"),JSONObject())
        val pixels=IntArray(n);source.getPixels(pixels,0,w,area.left,area.top,w,h)
        val rawHeight=ByteArray(n) {strength(pixels[it]).toByte()}
        val height=ArtColorizeFilters.height(rawHeight,w,h,settings.getBoolean("useEdgeDetection"),
            settings.getDouble("edgeDetectionSize"),settings.getDouble("fuzzyRadius"))
        val threshold=settings.getInt("threshold")
        var barrier=ByteArray(n) {if((height[it].toInt() and 255)>=threshold)1 else 0}
        val r=settings.getInt("gapClose")
        if(r>0) {
            barrier=morph(morph(barrier,w,h,r,true,true),w,h,r,true,false)
            barrier=morph(morph(barrier,w,h,r,false,true),w,h,r,false,false)
        }
        val selection=state.optJSONObject("selection")?.let {
            Region().apply {setPath(ArtSelection.path(it),Region(0,0,state.getInt("width"),state.getInt("height")))}
        }
        val allowed=BooleanArray(n) {i -> barrier[i].toInt()==0 &&
            (selection==null || selection.contains(area.left+i%w,area.top+i/w))}
        val labels=IntArray(n) {-1};val seeds=IntArray(n) {-1};val distances=IntArray(n) {Int.MAX_VALUE}
        val keyPixels=IntArray(n);val keys=ArtColorize.keyBitmap(mask,area.left,area.top,w,h)
        try {keys.getPixels(keyPixels,0,w,0,0,w,h)} finally {keys.recycle()}
        val colors=ArtColorize.palette(mask)
        var seedCount=0
        for(i in 0 until n) {
            val label=Color.blue(keyPixels[i])-1
            if(allowed[i] && Color.alpha(keyPixels[i])==255 && label in colors.indices) {
                seeds[i]=label;labels[i]=label;distances[i]=0;seedCount++
            }
        }
        // An indexed heap contains each pixel once; updates change its key rather than allocating duplicates.
        val heap=IntArray(n);val positions=IntArray(n) {-1};var size=0
        fun less(a: Int,b: Int): Boolean = distances[a]<distances[b] ||
            (distances[a]==distances[b] && (labels[a]<labels[b] || (labels[a]==labels[b] && a<b)))
        fun swap(a: Int,b: Int) {val t=heap[a];heap[a]=heap[b];heap[b]=t;positions[heap[a]]=a;positions[heap[b]]=b}
        fun push(pixel: Int) {
            var p=positions[pixel]
            if(p<0) {p=size++;heap[p]=pixel;positions[pixel]=p}
            while(p>0) {val parent=(p-1)/2;if(!less(heap[p],heap[parent]))break;swap(p,parent);p=parent}
        }
        for(i in 0 until n)if(seeds[i]>=0)push(i)
        while(size>0) {
            if(Thread.currentThread().isInterrupted)error("上色蒙版求解已取消")
            val i=heap[0];positions[i]=-2;size--
            if(size>0) {
                heap[0]=heap[size];positions[heap[0]]=0;var p=0
                while(p*2+1<size) {
                    var child=p*2+1;if(child+1<size && less(heap[child+1],heap[child]))child++
                    if(!less(heap[child],heap[p]))break;swap(p,child);p=child
                }
            }
            val x=i%w;val y=i/w
            fun visit(next: Int) {
                if(!allowed[next] || positions[next]==-2 || seeds[next]>=0)return
                val distance=distances[i]+1+(height[next].toInt() and 255)/8
                val label=labels[i]
                if(distance<distances[next] || (distance==distances[next] && label<labels[next])) {
                    distances[next]=distance;labels[next]=label;push(next)
                }
            }
            if(x>0)visit(i-1);if(x+1<w)visit(i+1);if(y>0)visit(i-w);if(y+1<h)visit(i+w)
        }
        val cleanup=ArtColorizeFilters.cleanup(labels,seeds,w,h,settings.getDouble("cleanUpAmount"))
        val output=IntArray(n);var filled=0
        for(i in 0 until n) {
            val label=labels[i];if(label<0)continue
            val c=colors[label];if(c.getBoolean("transparent"))continue
            val pixel=Color.parseColor(c.getString("color"))
            // Tint bright/transparent areas above the source. Dark line pixels remain visible.
            val alpha=Color.alpha(pixel)*(255-(rawHeight[i].toInt() and 255))/255
            output[i]=Color.argb(alpha,Color.red(pixel),Color.green(pixel),Color.blue(pixel));if(alpha>0)filled++
        }
        val result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        try {result.setPixels(output,0,w,0,0,w,h);return Result(result,area.left,area.top,filled,seedCount,cleanup)}
        catch(error:Throwable) {result.recycle();throw error}
    }
}
