package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray

internal object ArtColorSelection {
    val pending=emptyList<String>()
    fun defaults()=JSONObject().put("mode","replace").put("reference","visible").put("tolerance",15)
        .put("opacitySpread",100).put("antialias",1).put("feather",0).put("stopAtDarkest",false).put("colorLabels",JSONArray().put(1))
        .put("expand",0).put("gapClose",0).put("boundaryMode",false).put("boundaryColor","#FF161616").put("limitToSelection",false)
    fun options(p:JSONObject,maxGap:Int=8):JSONObject {
        val o=defaults();o.keys().forEach {if(p.has(it))o.put(it,p.get(it))}
        require(o.getString("mode") in ArtSoftSelection.modes)
        require(o.getString("reference") in setOf("current","visible","labels"))
        for((key,range) in listOf("tolerance" to 0..100,"opacitySpread" to 0..100,"expand" to -64..64,"gapClose" to 0..maxGap,"feather" to 0..32)) {
            val v=o.get(key);require(v is Number&&v.toDouble().isFinite()&&v.toDouble()==v.toInt().toDouble()&&v.toInt() in range) {"颜色选区参数无效：$key"}
        }
        val aa=o.get("antialias");require(aa is Number&&aa.toDouble().isFinite()&&aa.toDouble() in 0.0..1.0)
        require(o.getString("boundaryColor").matches(Regex("#[A-Fa-f0-9]{8}")))
        for(key in listOf("boundaryMode","limitToSelection","stopAtDarkest"))require(o.get(key) is Boolean)
        ArtLayerLabels.parse(o.getJSONArray("colorLabels"))
        require(o.getInt("opacitySpread")==100 || o.getInt("tolerance")>0) {"颜色软覆盖需要容差大于0；精确匹配请将覆盖硬度设为100%"}
        return o
    }
    fun info()=JSONObject().put("defaults",defaults()).put("pending",JSONArray(pending))
        .put("maxPixels",ArtRasterSelection.MAX_PIXELS).put("maxRuns",ArtRasterSelection.MAX_RUNS).put("layerLabels",ArtLayerLabels.info())
        .put("algorithm","premultiplied RGBA maximum-channel difference; four-neighbor fixed-seed flood or global scan; eight-bit coverage. opacitySpread=100 is binary, otherwise clamp((T-d)*255*100/(T*(100-opacitySpread)),0,255). Boundary-color mode uses the inverse ramp.")
        .put("pipeline","search -> gap repair -> square max/min expand/shrink (positive grow optionally stops at darker/more-opaque ridge) -> feather, or adjustable one-pixel boundary antialias if feather=0 -> optional min with existing selection coverage -> replace/add/subtract/intersect/xor. All effects stay inside search bounds.")
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
    fun difference(a: Int,b: Int): Int {
        val aa=Color.alpha(a);val ba=Color.alpha(b)
        return maxOf(abs(aa-ba),abs(Color.red(a)*aa/255-Color.red(b)*ba/255),
            abs(Color.green(a)*aa/255-Color.green(b)*ba/255),abs(Color.blue(a)*aa/255-Color.blue(b)*ba/255))
    }
    // Exact square maximum/minimum coverage morphology in two linear sliding-window passes.
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
    /** Edge-only one-pixel smoothing, independent of the color-distance ramp. */
    private fun antialias(input:ByteArray,w:Int,h:Int,strength:Double):ByteArray {
        if(strength==0.0)return input
        val out=input.copyOf()
        fun value(x:Int,y:Int)=if(x in 0 until w&&y in 0 until h)input[y*w+x].toInt() and 255 else 0
        for(y in 0 until h)for(x in 0 until w) {
            val c=value(x,y);val n=value(x,y-1);val south=value(x,y+1);val west=value(x-1,y);val east=value(x+1,y)
            if(minOf(c,n,south,west,east)==0&&maxOf(c,n,south,west,east)>0) {
                val smooth=(4*c+n+south+west+east)/8.0
                out[y*w+x]=(c*(1-strength)+smooth*strength).roundToInt().coerceIn(0,255).toByte()
            }
        };return out
    }
    /** Grow coverage normally, then gate it by an eight-neighbor monotone darkness/opacity frontier. */
    private fun growAtDarkest(input:ByteArray,pixels:IntArray,w:Int,h:Int,radius:Int):ByteArray {
        val grown=morph(input,w,h,radius,true);val allowed=ByteArray(input.size);val queue=IntArray(input.size)
        var head=0;var tail=0
        for(i in input.indices)if(input[i].toInt()!=0) {allowed[i]=1;queue[tail++]=i}
        fun intensity(c:Int)=(Color.red(c)*0.2126+Color.green(c)*0.7152+Color.blue(c)*0.0722).roundToInt()
        while(head<tail) {
            val i=queue[head++];val x=i%w;val y=i/w;val previous=pixels[i];val opacity=Color.alpha(previous)
            for(dy in -1..1)for(dx in -1..1) {
                if(dx==0&&dy==0||x+dx !in 0 until w||y+dy !in 0 until h)continue
                val j=(y+dy)*w+x+dx
                if(allowed[j].toInt()!=0||grown[j].toInt()==0)continue
                val next=pixels[j]
                if(Color.alpha(next)>=opacity&&(opacity==0||intensity(next)<=intensity(previous))) {allowed[j]=1;queue[tail++]=j}
            }
        }
        for(i in grown.indices)if(allowed[i].toInt()==0)grown[i]=0
        return grown
    }
    data class Result(val selection:JSONObject,val pixels:Int,val sampledColor:String,val coverageSum:Long)
    data class Mask(val alpha:ByteArray,val bounds:Rect,val sampled:Int)
    fun mask(state:JSONObject,source:Bitmap,p:JSONObject,connected:Boolean,seedPoints:List<Pair<Int,Int>>?=null,applyEffects:Boolean=true,gapLimit:Int=8):Mask {
        val o=options(p,gapLimit);val r=bounds(state,p);val w=r.width();val h=r.height();val n=w*h
        ArtImagePolicy.requireBytes(n.toLong()*40,"颜色软选区搜索")
        val x=p.getInt("x");val y=p.getInt("y");require(r.contains(x,y)) {"取样点不在查找范围内"}
        val pixels=IntArray(n);source.getPixels(pixels,0,w,r.left,r.top,w,h)
        val seed=(y-r.top)*w+x-r.left;val sampled=pixels[seed];val threshold=o.getInt("tolerance")*255/100
        val boundaryMode=connected&&o.getBoolean("boundaryMode");val boundary=Color.parseColor(o.getString("boundaryColor"))
        val limit=if(o.getBoolean("limitToSelection")) {
            val selected=state.optJSONObject("selection") ?: error("请先建立查找范围选区")
            val sampler=ArtSoftSelection.Sampler(selected)
            ByteArray(n) {i->sampler.at(r.left+i%w+0.5,r.top+i/w+0.5).toByte()}
        } else null
        require(limit==null||limit[seed].toInt()!=0) {"取样点需位于现有选区内"}
        val spread=o.getInt("opacitySpread")
        fun coverage(d:Int):Int {
            if(spread==100)return if(if(boundaryMode)d>threshold else d<=threshold)255 else 0
            if(!boundaryMode)return if(d<threshold)((threshold-d)*255L*100/(threshold*(100-spread))).toInt().coerceIn(0,255) else 0
            return if(d<threshold)(255-(threshold-d)*255L*100/(threshold*(100-spread))).toInt().coerceIn(0,255) else 255
        }
        val candidate=ByteArray(n) {i->if(limit!=null&&limit[i].toInt()==0)0 else coverage(difference(pixels[i],if(boundaryMode)boundary else sampled)).toByte()}
        var output=if(!connected)candidate else {
            val gap=o.getInt("gapClose")
            val membership=ByteArray(n) {i->if(candidate[i].toInt()!=0)255.toByte() else 0}
            val core=if(gap==0)membership else morph(membership,w,h,gap,false)
            val queue=IntArray(n);val visited=ByteArray(n)
            val seeds=seedPoints ?: listOf(x to y)
            var head=0;var tail=0
            for((sx,sy) in seeds) {
                require(r.contains(sx,sy)) {"拖动取样点不在查找范围内"}
                val i=(sy-r.top)*w+sx-r.left
                if(core[i].toInt()!=0 && visited[i].toInt()==0) {visited[i]=255.toByte();queue[tail++]=i}
            }
            if(seedPoints==null)require(tail>0) {"取样点处在边界、现有选区外或缺口处理侵蚀区，请调整取样点／缺口半径"}
            while(head<tail) {
                val i=queue[head++];val col=i%w;val row=i/w
                fun visit(j:Int) {if(core[j].toInt()!=0&&visited[j].toInt()==0) {visited[j]=255.toByte();queue[tail++]=j}}
                if(col>0)visit(i-1);if(col+1<w)visit(i+1);if(row>0)visit(i-w);if(row+1<h)visit(i+w)
            }
            val reached=if(gap==0)visited else morph(visited,w,h,gap,true)
            ByteArray(n) {i->if(reached[i].toInt()!=0)candidate[i] else 0}
        }
        val raw=Mask(output,r,sampled)
        return if(applyEffects)finish(state,source,raw,p,gapLimit) else raw
    }
    fun finish(state:JSONObject,source:Bitmap,raw:Mask,p:JSONObject,gapLimit:Int=8):Mask {
        val o=options(p,gapLimit);val r=raw.bounds;val w=r.width();val h=r.height();val pixels=IntArray(w*h)
        source.getPixels(pixels,0,w,r.left,r.top,w,h);var output=raw.alpha
        val limit=if(o.getBoolean("limitToSelection")) {
            val selected=state.optJSONObject("selection") ?: error("请先建立查找范围选区")
            val sampler=ArtSoftSelection.Sampler(selected)
            ByteArray(w*h) {i->sampler.at(r.left+i%w+0.5,r.top+i/w+0.5).toByte()}
        } else null
        val expand=o.getInt("expand")
        if(expand!=0)output=if(expand>0&&o.getBoolean("stopAtDarkest"))growAtDarkest(output,pixels,w,h,expand) else morph(output,w,h,abs(expand),expand>0)
        val feather=o.getInt("feather")
        output=if(feather>0)ArtSoftSelection.blur(output,w,h,feather) else antialias(output,w,h,o.getDouble("antialias"))
        if(limit!=null)for(i in output.indices)output[i]=minOf(output[i].toInt() and 255,limit[i].toInt() and 255).toByte()
        return Mask(output,r,raw.sampled)
    }
    fun solve(state:JSONObject,source:Bitmap,p:JSONObject,connected:Boolean):Result {
        val o=options(p);val mask=mask(state,source,p,connected);val output=mask.alpha;val r=mask.bounds;val sampled=mask.sampled
        val count=output.count {it.toInt()!=0};val sum=output.sumOf {(it.toInt() and 255).toLong()}
        return Result(ArtSoftSelection.fromAlpha(output,r).put("creationOptions",o),count,String.format("#%08X",sampled),sum)
    }
}
