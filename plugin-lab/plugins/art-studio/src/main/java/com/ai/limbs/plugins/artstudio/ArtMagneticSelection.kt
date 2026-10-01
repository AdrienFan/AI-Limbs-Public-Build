package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.util.PriorityQueue
import java.util.concurrent.CancellationException
import kotlin.math.*

/** Live-wire search follows real RGBA contrast. Failed or excessive searches are explicit errors. */
internal object ArtMagneticSelection {
    const val MAX_ANCHORS=128
    const val MAX_SEARCH_PIXELS=262144
    const val MAX_PATH_POINTS=32768
    data class Point(val x: Double,val y: Double)
    data class Image(val documentId: String,val revision: Int,val layerId: String,val reference: String,
        val filterRadius: Int,val rect: Rect,val edges: FloatArray)
    fun defaults()=JSONObject().put("mode","replace").put("reference","visible").put("filterRadius",1)
        .put("searchRadius",16).put("threshold",24).put("strength",8.0).put("anchorGap",40)
        .put("precision",0.75).put("limitToSelection",false)
    val pending=listOf("共享抗锯齿与羽化软选区","完成后的磁性锚点重编辑","颜色标签参考","Krita全部高级滤波模型")
    fun options(p: JSONObject): JSONObject {
        val o=defaults();o.keys().forEach {if(p.has(it))o.put(it,p.get(it))}
        require(o.getString("mode") in ArtBezierSelection.modes && o.getString("reference") in setOf("current","visible"))
        require(o.getInt("filterRadius") in 1..4 && o.getInt("searchRadius") in 2..64 && o.getInt("threshold") in 0..255)
        require(o.getDouble("strength").isFinite() && o.getDouble("strength") in 1.0..20.0)
        require(o.getDouble("precision").isFinite() && o.getDouble("precision") in 0.25..4.0)
        require(o.getInt("anchorGap") in 8..128);o.getBoolean("limitToSelection")
        return o
    }
    fun info()=JSONObject().put("defaults",defaults()).put("pending",JSONArray(pending))
        .put("maxAnchors",MAX_ANCHORS).put("maxSearchPixels",MAX_SEARCH_PIXELS).put("maxPathPoints",MAX_PATH_POINTS)
        .put("maxReferencePixels",ArtRasterSelection.MAX_PIXELS).put("algorithm","RGBA Sobel contrast + eight-neighbor A* live wire")
    fun image(snapshot: JSONObject,bitmap: Bitmap,p: JSONObject): Image {
        val o=options(p);val state=snapshot.getJSONObject("state");val rect=ArtColorSelection.bounds(state,p)
        val w=rect.width();val h=rect.height();val pixels=IntArray(w*h);bitmap.getPixels(pixels,0,w,rect.left,rect.top,w,h)
        // Premultiplication removes invisible RGB noise; alpha is an edge channel in its own right.
        for(i in pixels.indices) {
            val c=pixels[i];val a=c ushr 24
            pixels[i]=(a shl 24) or (((c ushr 16 and 255)*a/255) shl 16) or
                (((c ushr 8 and 255)*a/255) shl 8) or ((c and 255)*a/255)
        }
        val edges=FloatArray(w*h);val r=o.getInt("filterRadius")
        fun at(x: Int,y: Int)=pixels[y.coerceIn(0,h-1)*w+x.coerceIn(0,w-1)]
        for(y in 0 until h)for(x in 0 until w) {
            val tl=at(x-r,y-r);val tc=at(x,y-r);val tr=at(x+r,y-r)
            val ml=at(x-r,y);val mr=at(x+r,y)
            val bl=at(x-r,y+r);val bc=at(x,y+r);val br=at(x+r,y+r)
            var magnitude=0.0
            for(channel in 0..3) {
                val shift=channel*8
                fun c(v: Int)=(v ushr shift) and 255
                val gx=c(tr)+2*c(mr)+c(br)-c(tl)-2*c(ml)-c(bl)
                val gy=c(bl)+2*c(bc)+c(br)-c(tl)-2*c(tc)-c(tr)
                magnitude=max(magnitude,hypot(gx.toDouble(),gy.toDouble())/(4*255.0))
            }
            edges[y*w+x]=magnitude.coerceIn(0.0,1.0).toFloat()
        }
        return Image(snapshot.getString("id"),snapshot.getInt("revision"),p.getString("layerId"),o.getString("reference"),r,Rect(rect),edges)
    }
    fun anchors(raw: JSONArray): List<Point> {
        require(raw.length() in 1..MAX_ANCHORS) {"磁性路径需要1–128锚点"}
        return (0 until raw.length()).map {i->val q=raw.getJSONArray(i);require(q.length()==2)
            Point(q.getDouble(0),q.getDouble(1)).also {require(it.x.isFinite() && it.y.isFinite())}}
    }
    fun json(points: List<Point>)=JSONArray().apply {points.forEach {put(JSONArray().put(it.x).put(it.y))}}
    private data class Visit(val index: Int,val cost: Float,val rank: Float)
    fun segment(image: Image,a: Point,b: Point,p: JSONObject,cancelled: ()->Boolean={false}): List<Point> {
        val o=options(p);val bounds=image.rect;val radius=o.getInt("searchRadius")
        fun local(q: Point): Pair<Int,Int> {
            val x=floor(q.x).toInt();val y=floor(q.y).toInt()
            require(bounds.contains(x,y)) {"锚点需位于参考范围内"}
            return (x-bounds.left) to (y-bounds.top)
        }
        val (ax,ay)=local(a);val (bx,by)=local(b)
        if(ax==bx && ay==by)return listOf(a,b)
        val left=(min(ax,bx)-radius).coerceAtLeast(0);val top=(min(ay,by)-radius).coerceAtLeast(0)
        val right=(max(ax,bx)+radius+1).coerceAtMost(bounds.width());val bottom=(max(ay,by)+radius+1).coerceAtMost(bounds.height())
        val w=right-left;val h=bottom-top;val n=w*h
        require(n<=MAX_SEARCH_PIXELS) {"单段搜索范围超过262144像素，请在轮廓中间增加锚点"}
        val start=(ay-top)*w+ax-left;val end=(by-top)*w+bx-left
        val distances=FloatArray(n) {Float.POSITIVE_INFINITY};val previous=IntArray(n) {-1};val done=BooleanArray(n)
        val queue=PriorityQueue(compareBy<Visit> {it.rank}.thenBy {it.index})
        fun heuristic(x: Int,y: Int)=hypot((x-bx).toDouble(),(y-by).toDouble()).toFloat()
        distances[start]=0f;queue.add(Visit(start,0f,heuristic(ax,ay)))
        val dx=(bx-ax).toDouble();val dy=(by-ay).toDouble();val length2=dx*dx+dy*dy
        fun inCorridor(x: Int,y: Int): Boolean {
            val t=(((x-ax)*dx+(y-ay)*dy)/length2).coerceIn(0.0,1.0)
            return hypot(x-ax-t*dx,y-ay-t*dy)<=radius
        }
        val threshold=o.getInt("threshold");val strength=o.getDouble("strength").toFloat()
        var expanded=0
        while(queue.isNotEmpty()) {
            if(expanded++%256==0 && cancelled())throw CancellationException()
            val current=queue.remove();val i=current.index
            if(done[i] || current.cost>distances[i])continue
            done[i]=true;if(i==end)break
            val x=left+i%w;val y=top+i/w
            for(oy in -1..1)for(ox in -1..1) {
                if(ox==0 && oy==0)continue
                val nx=x+ox;val ny=y+oy
                if(nx !in left until right || ny !in top until bottom || !inCorridor(nx,ny))continue
                val j=(ny-top)*w+nx-left;if(done[j])continue
                val contrast=image.edges[ny*bounds.width()+nx]
                val edge=if(contrast*255>=threshold)contrast else 0f
                val step=if(ox==0 || oy==0)1f else 1.4142136f
                val cost=current.cost+step*(1f+strength*(1f-edge)*(1f-edge))
                if(cost<distances[j]) {
                    distances[j]=cost;previous[j]=i
                    require(queue.size<MAX_SEARCH_PIXELS*2) {"磁性搜索队列超过预算，请减小搜索半径"}
                    queue.add(Visit(j,cost,cost+heuristic(nx,ny)))
                }
            }
        }
        require(done[end]) {"无法在当前搜索半径内连接锚点"}
        val reverse=mutableListOf<Point>();var i=end
        while(i!=start) {
            require(i>=0 && reverse.size<MAX_PATH_POINTS)
            reverse.add(Point(bounds.left+left+i%w+0.5,bounds.top+top+i/w+0.5));i=previous[i]
        }
        reverse.add(a);reverse.reverse();reverse[reverse.lastIndex]=b
        return reverse
    }
    private fun distance(q: Point,a: Point,b: Point): Double {
        val dx=b.x-a.x;val dy=b.y-a.y;val n=dx*dx+dy*dy
        val t=if(n==0.0)0.0 else (((q.x-a.x)*dx+(q.y-a.y)*dy)/n).coerceIn(0.0,1.0)
        return hypot(q.x-a.x-t*dx,q.y-a.y-t*dy)
    }
    fun simplify(raw: List<Point>,epsilon: Double): List<Point> {
        if(raw.size<=2)return raw
        val keep=BooleanArray(raw.size);keep[0]=true;keep[raw.lastIndex]=true
        val stack=java.util.ArrayDeque<Pair<Int,Int>>();stack.add(0 to raw.lastIndex)
        var work=0L
        while(stack.isNotEmpty()) {
            val (first,last)=stack.removeLast();var far=-1;var best=epsilon
            for(i in first+1 until last) {
                require(++work<=16000000) {"轮廓简化超过预算，请减少锚点或增大简化精度"}
                val d=distance(raw[i],raw[first],raw[last]);if(d>best){best=d;far=i}
            }
            if(far>=0){keep[far]=true;stack.add(first to far);stack.add(far to last)}
        }
        return raw.filterIndexed {i,_->keep[i]}
    }
    fun trace(image: Image,anchors: List<Point>,p: JSONObject,closed: Boolean,cancelled: ()->Boolean={false},cache: java.util.concurrent.ConcurrentHashMap<Pair<Point,Point>,List<Point>> = java.util.concurrent.ConcurrentHashMap()): List<Point> {
        require(anchors.size in (if(closed)3 else 1)..MAX_ANCHORS)
        if(anchors.size==1)return anchors
        val result=mutableListOf<Point>()
        val pairs=if(closed)anchors.size else anchors.size-1
        for(i in 0 until pairs) {
            if(cancelled())throw CancellationException()
            val a=anchors[i];val b=anchors[(i+1)%anchors.size]
            val part=cache.computeIfAbsent(a to b) {segment(image,a,b,p,cancelled)}
            val simplified=simplify(part,options(p).getDouble("precision"))
            if(result.isEmpty())result.add(simplified.first())
            result.addAll(simplified.drop(1))
            require(result.size<=2049) {"吸附轮廓超过2048顶点，请增大简化精度或分次选择"}
        }
        if(closed && result.last()==result.first())result.removeAt(result.lastIndex)
        require(result.size<=2048)
        return result
    }
}
