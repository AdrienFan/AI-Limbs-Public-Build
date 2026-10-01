package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import org.json.JSONObject
import kotlin.math.abs

internal object ArtColorSelection {
    val pending=listOf("共享抗锯齿与羽化软选区","颜色标签图层参考","颜色差异的软覆盖扩散","沿最暗像素停止扩展")
    fun defaults()=JSONObject().put("mode","replace").put("reference","visible").put("tolerance",15)
        .put("expand",0).put("gapClose",0).put("boundaryMode",false).put("boundaryColor","#FF161616").put("limitToSelection",false)
    fun options(p: JSONObject): JSONObject {
        val o=defaults();o.keys().forEach {if(p.has(it))o.put(it,p.get(it))}
        require(o.getString("mode") in ArtBezierSelection.modes)
        require(o.getString("reference") in setOf("current","visible"))
        require(o.getInt("tolerance") in 0..100 && o.getInt("expand") in -16..16 && o.getInt("gapClose") in 0..8)
        require(o.getString("boundaryColor").matches(Regex("#[A-Fa-f0-9]{8}")))
        o.getBoolean("boundaryMode");o.getBoolean("limitToSelection")
        return o
    }
    fun info()=JSONObject().put("defaults",defaults()).put("pending",org.json.JSONArray(pending))
        .put("maxPixels",ArtRasterSelection.MAX_PIXELS).put("maxRuns",ArtRasterSelection.MAX_RUNS)
        .put("algorithm","premultiplied RGBA binary selection; connected four-neighbor flood or global scan")
    fun bounds(state: JSONObject,p: JSONObject): Rect {
        val w=state.getInt("width");val h=state.getInt("height")
        val r=if(p.has("bounds")) {
            val b=p.getJSONObject("bounds")
            val x=b.getInt("x");val y=b.getInt("y");val bw=b.getInt("width");val bh=b.getInt("height")
            require(kotlin.math.abs(x.toLong())<=1000000 && kotlin.math.abs(y.toLong())<=1000000 && bw>0 && bh>0 &&
                x.toLong()+bw<=1000000 && y.toLong()+bh<=1000000) {"查找范围坐标无效"}
            Rect(x,y,x+bw,y+bh)
        } else if(p.optBoolean("limitToSelection")) {
            val s=state.optJSONObject("selection") ?: error("请先建立查找范围选区")
            Rect(kotlin.math.floor(s.getDouble("x")).toInt(),kotlin.math.floor(s.getDouble("y")).toInt(),
                kotlin.math.ceil(s.getDouble("x")+s.getDouble("width")).toInt(),kotlin.math.ceil(s.getDouble("y")+s.getDouble("height")).toInt())
        } else Rect(0,0,w,h)
        require(r.intersect(0,0,w,h) && r.width()>0 && r.height()>0 && r.width().toLong()*r.height()<=ArtRasterSelection.MAX_PIXELS) {
            "单次查找范围最多4194304像素；可先用矩形选区限定范围"
        }
        return r
    }
    private fun difference(a: Int,b: Int): Int {
        val aa=Color.alpha(a);val ba=Color.alpha(b)
        return maxOf(abs(aa-ba),abs(Color.red(a)*aa/255-Color.red(b)*ba/255),
            abs(Color.green(a)*aa/255-Color.green(b)*ba/255),abs(Color.blue(a)*aa/255-Color.blue(b)*ba/255))
    }
    // Exact square binary morphology in two linear sliding-window passes.
    fun morph(input: ByteArray,w: Int,h: Int,r: Int,grow: Boolean): ByteArray {
        if(r==0)return input.copyOf()
        fun pass(src: ByteArray,horizontal: Boolean): ByteArray {
            val out=ByteArray(src.size);val len=if(horizontal)w else h;val lines=if(horizontal)h else w
            val deque=IntArray(len+2*r);val values=IntArray(len+2*r)
            for(line in 0 until lines) {
                var head=0;var tail=0
                for(k in -r until len+r) {
                    val slot=k+r;val value=if(k !in 0 until len)0 else src[if(horizontal)line*w+k else k*w+line].toInt() and 255
                    values[slot]=value
                    while(tail>head && if(grow)values[deque[tail-1]]<=value else values[deque[tail-1]]>=value)tail--
                    deque[tail++]=slot;val center=k-r
                    while(head<tail && deque[head]<center)head++
                    if(center in 0 until len)out[if(horizontal)line*w+center else center*w+line]=values[deque[head]].toByte()
                }
            }
            return out
        }
        return pass(pass(input,true),false)
    }
    data class Result(val selection: JSONObject,val pixels: Int,val sampledColor: String)
    fun solve(state: JSONObject,source: Bitmap,p: JSONObject,connected: Boolean): Result {
        val o=options(p);val r=bounds(state,p);val w=r.width();val h=r.height();val n=w*h
        val x=p.getInt("x");val y=p.getInt("y");require(r.contains(x,y)) {"取样点不在查找范围内"}
        val pixels=IntArray(n);source.getPixels(pixels,0,w,r.left,r.top,w,h)
        val seed=(y-r.top)*w+x-r.left;val sampled=pixels[seed];val threshold=o.getInt("tolerance")*255/100
        val boundary=Color.parseColor(o.getString("boundaryColor"))
        val selection=if(o.getBoolean("limitToSelection"))state.optJSONObject("selection")?.let {s->
            Region().apply {setPath(ArtSelection.path(s),Region(r))}} else null
        require(selection==null || selection.contains(x,y)) {"取样点需位于现有选区内"}
        val candidate=ByteArray(n) {i->
            val hit=if(connected && o.getBoolean("boundaryMode"))difference(pixels[i],boundary)>threshold
                else difference(pixels[i],sampled)<=threshold
            if(hit && (selection==null || selection.contains(r.left+i%w,r.top+i/w)))255.toByte() else 0
        }
        var output=if(!connected)candidate else {
            val gap=o.getInt("gapClose")
            val core=if(gap==0)candidate else morph(candidate,w,h,gap,false)
            val queue=IntArray(n);val visited=ByteArray(n)
            // A closed-gap search requires a genuine surviving seed; never silently use a different seed.
            require(core[seed].toInt()!=0) {"取样点处在边界、现有选区外或缺口处理侵蚀区，请调整取样点／缺口半径"}
            var head=0;var tail=1;queue[0]=seed;visited[seed]=255.toByte()
            while(head<tail) {
                val i=queue[head++];val col=i%w;val row=i/w
                fun visit(j: Int) {if(core[j].toInt()!=0 && visited[j].toInt()==0) {visited[j]=255.toByte();queue[tail++]=j}}
                if(col>0)visit(i-1);if(col+1<w)visit(i+1);if(row>0)visit(i-w);if(row+1<h)visit(i+w)
            }
            if(gap==0)visited else morph(visited,w,h,gap,true).also {out->for(i in out.indices)if(candidate[i].toInt()==0)out[i]=0}
        }
        val expand=o.getInt("expand")
        if(expand!=0)output=morph(output,w,h,abs(expand),expand>0)
        if(selection!=null)for(i in output.indices)if(!selection.contains(r.left+i%w,r.top+i/w))output[i]=0
        val count=output.count {it.toInt()!=0}
        return Result(ArtRasterSelection.fromMask(output,w,h,r.left,r.top),count,String.format("#%08X",sampled))
    }
}
