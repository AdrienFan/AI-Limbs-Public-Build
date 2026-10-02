package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Frozen reference, multi-seed search, one soft mask and one pixel-history receipt per gesture. */
internal object ArtContiguousFill {
    const val MAX_POINTS=512
    const val MAX_SEEDS=8192
    const val MAX_SEARCH_WORK=32*1024*1024L
    val modes=linkedMapOf("connected" to "相连区域","boundary" to "边界色围住的区域","similar" to "全局相似色")
    val drags=linkedMapOf("off" to "单点","any" to "拖动填任意区域","similar" to "拖动填同色区域")
    fun defaults()=ArtColorSelection.defaults().apply {
        remove("mode");remove("boundaryMode");remove("limitToSelection")
        put("reference","current");put("tolerance",0);put("antialias",0)
        put("fillMode","connected");put("dragMode","off");put("fillType","solid");put("erase",false)
        put("opacity",1.0);put("useSelectionAsBoundary",true)
        put("pattern",ArtFigure.fill(JSONObject().put("mode","pattern"),"#FF161616").getJSONObject("pattern"))
    }
    fun options(p:JSONObject):JSONObject {
        val o=defaults();o.keys().forEach {if(p.has(it))o.put(it,p.get(it))}
        if(p.has("referenceAllLayers")) {
            require(p.get("referenceAllLayers") is Boolean)
            val reference=if(p.getBoolean("referenceAllLayers"))"visible" else "current"
            require(!p.has("reference") || p.getString("reference")==reference) {"reference与referenceAllLayers冲突"}
            o.put("reference",reference)
        }
        require(o.getString("fillMode") in modes && o.getString("dragMode") in drags && o.getString("fillType") in setOf("solid","pattern"))
        require(o.get("erase") is Boolean && o.get("useSelectionAsBoundary") is Boolean)
        val opacity=o.get("opacity");require(opacity is Number && opacity.toDouble().isFinite() && opacity.toDouble() in 0.0..1.0)
        require(o.getString("fillMode")!="similar" || o.getString("dragMode")=="off") {"全局相似色使用单点取样；拖动用于连续／边界区域"}
        require(o.getString("fillMode")!="similar" || o.getInt("gapClose")==0) {"全局相似色不使用封闭缺口"}
        o.put("boundaryMode",o.getString("fillMode")=="boundary")
        ArtColorSelection.options(o,32)
        val foreground=p.getString("color");require(foreground.matches(Regex("#[A-Fa-f0-9]{8}")))
        o.put("pattern",ArtFigure.fill(JSONObject().put("mode","pattern").put("pattern",o.getJSONObject("pattern")),foreground).getJSONObject("pattern"))
        o.put("color",foreground)
        require(o.getBoolean("erase") || o.getString("fillType")=="pattern" || Color.alpha(Color.parseColor(foreground))>0) {"填充色不能完全透明，请使用擦除模式"}
        return o
    }
    fun info()=JSONObject().put("defaults",defaults()).put("fillModes",JSONObject(modes)).put("dragModes",JSONObject(drags))
        .put("patterns",JSONObject(ArtFigure.patterns)).put("labels",ArtLayerLabels.info())
        .put("limits",JSONObject().put("points",MAX_POINTS).put("seeds",MAX_SEEDS).put("pixels",ArtRasterSelection.MAX_PIXELS)
            .put("searchWork",MAX_SEARCH_WORK).put("gapClose",32).put("feather",32).put("expand",64))
        .put("scope","文档像素；拖动沿折线逐像素取样，参考在整个手势中固定，松手只提交一次。similar拖动仅接受与首点相同的预乘RGBA颜色。图案以文档原点平铺，不按每个区域重置。目标仍为可见未锁定、无变换和分组的绘画/图像层。")
        .put("pipeline","fixed reference -> connected/boundary/global search -> close narrow gaps -> max union of drag regions -> expand/shrink -> feather or AA -> min with selection coverage -> solid/pattern RGBA × opacity × coverage -> persisted pixel overlay")
    fun seeds(p:JSONObject,w:Int,h:Int):List<Pair<Int,Int>> {
        val raw=if(p.has("points")) {
            require(!p.has("x") && !p.has("y")) {"points与x/y请择一提供"};p.getJSONArray("points")
        } else JSONArray().put(JSONArray().put(p.getInt("x")).put(p.getInt("y")))
        require(raw.length() in 1..MAX_POINTS) {"每次拖动最多512折线点，请分段填充"}
        val vertices=(0 until raw.length()).map {i->val q=raw.getJSONArray(i);require(q.length()==2)
            for(k in 0..1) {val v=q.get(k);require(v is Number && v.toDouble().isFinite() && v.toDouble()==v.toInt().toDouble())}
            (q.getInt(0) to q.getInt(1)).also {require(it.first in 0 until w && it.second in 0 until h) {"填充取样点需位于画布内"}}
        }
        require(p.getString("dragMode")!="off" || vertices.size==1) {"多个取样点需开启dragMode"}
        val result=linkedSetOf<Pair<Int,Int>>();result.add(vertices.first())
        vertices.zipWithNext().forEach {(a,b)->val steps=maxOf(abs(b.first-a.first),abs(b.second-a.second))
            for(i in 1..steps) {result.add((a.first+(b.first-a.first)*i.toDouble()/steps).roundToInt() to (a.second+(b.second-a.second)*i.toDouble()/steps).roundToInt())
                require(result.size<=MAX_SEEDS) {"拖动路径最多8192个像素取样点，请分段填充"}}
        }
        return result.toList()
    }
    fun mask(state:JSONObject,source:Bitmap,p:JSONObject):ArtColorSelection.Mask {
        val request=JSONObject(p.toString()).put("limitToSelection",p.getBoolean("useSelectionAsBoundary") && state.has("selection") && !state.isNull("selection"))
        val r=ArtColorSelection.bounds(state,request);val seeds=seeds(p,state.getInt("width"),state.getInt("height"))
        require(r.contains(seeds.first().first,seeds.first().second)) {"首点需在查找范围内"}
        val selected=state.optJSONObject("selection");val sampler=selected?.let {ArtSoftSelection.Sampler(it)}
        require(sampler==null || sampler.at(seeds.first().first+0.5,seeds.first().second+0.5)>0) {"首点需在现有选区内"}
        val initial=source.getPixel(seeds.first().first,seeds.first().second);val groups=linkedMapOf<Int,MutableList<Pair<Int,Int>>>()
        val boundary=p.getString("fillMode")=="boundary";val threshold=p.getInt("tolerance")*255/100
        for(seed in seeds) {
            if(!r.contains(seed.first,seed.second))continue
            if(sampler!=null && sampler.at(seed.first+0.5,seed.second+0.5)==0)continue
            val color=source.getPixel(seed.first,seed.second)
            if(p.getString("dragMode")=="similar" && ArtColorSelection.difference(color,initial)!=0)continue
            if(boundary && ArtColorSelection.difference(color,Color.parseColor(p.getString("boundaryColor")))<=threshold && p.getInt("opacitySpread")==100)continue
            val a=Color.alpha(color);val key=if(boundary)0 else Color.argb(a,Color.red(color)*a/255,Color.green(color)*a/255,Color.blue(color)*a/255)
            groups.getOrPut(key) {mutableListOf()}.add(seed)
        }
        ArtImagePolicy.requireBytes(r.width().toLong()*r.height()*64,"连续填充与软蒙版")
        val union=ByteArray(r.width()*r.height())
        var work=0L
        for(group in groups.values) {
            val pending=group.filter {seed->(union[(seed.second-r.top)*r.width()+seed.first-r.left].toInt() and 255)<255}
            if(pending.isEmpty())continue
            work+=union.size;require(work<=MAX_SEARCH_WORK) {"拖动颜色搜索超过33554432次像素比较，请缩小查找范围或分段填充"}
            val seed=pending.first();request.put("x",seed.first).put("y",seed.second)
            val mask=ArtColorSelection.mask(state,source,request,p.getString("fillMode")!="similar",pending,applyEffects=false,gapLimit=32)
            for(i in union.indices)union[i]=maxOf(union[i].toInt() and 255,mask.alpha[i].toInt() and 255).toByte()
        }
        val result=ArtColorSelection.finish(state,source,ArtColorSelection.Mask(union,r,initial),request,32)
        if(sampler!=null)for(i in result.alpha.indices)result.alpha[i]=minOf(result.alpha[i].toInt() and 255,sampler.at(r.left+i%r.width()+0.5,r.top+i/r.width()+0.5)).toByte()
        return result
    }
    fun crop(mask:ArtColorSelection.Mask):ArtColorSelection.Mask {
        val r=mask.bounds;var left=r.width();var top=r.height();var right=0;var bottom=0
        for(i in mask.alpha.indices)if(mask.alpha[i].toInt()!=0) {val x=i%r.width();val y=i/r.width()
            left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x+1);bottom=maxOf(bottom,y+1)}
        require(right>left && bottom>top)
        val w=right-left;val h=bottom-top;val alpha=ByteArray(w*h)
        for(y in 0 until h)System.arraycopy(mask.alpha,(top+y)*r.width()+left,alpha,y*w,w)
        return ArtColorSelection.Mask(alpha,Rect(r.left+left,r.top+top,r.left+right,r.top+bottom),mask.sampled)
    }
    fun paint(mask:ArtColorSelection.Mask,p:JSONObject,resources:((String)->Bitmap)?):Bitmap {
        val r=mask.bounds;val bitmap=Bitmap.createBitmap(r.width(),r.height(),Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap);canvas.translate(-r.left.toFloat(),-r.top.toFloat());val opacity=(p.getDouble("opacity")*255).roundToInt()
            if(p.getString("fillType")=="pattern")ArtPatternRenderer.withPaint(p.getJSONObject("pattern"),resources) {paint->paint.alpha=opacity;canvas.drawRect(RectF(r),paint)}
            else canvas.drawRect(RectF(r),Paint().apply {color=if(p.getBoolean("erase"))Color.WHITE else Color.parseColor(p.getString("color"));alpha=(Color.alpha(color)*p.getDouble("opacity")).roundToInt()})
            val row=IntArray(r.width())
            for(y in 0 until r.height()) {bitmap.getPixels(row,0,r.width(),0,y,r.width(),1)
                for(x in row.indices) {val c=row[x];val alpha=(Color.alpha(c)*(mask.alpha[y*r.width()+x].toInt() and 255)+127)/255
                    row[x]=(c and 0x00ffffff) or (alpha shl 24)}
                bitmap.setPixels(row,0,r.width(),0,y,r.width(),1)
            }
            return bitmap
        } catch(error:Throwable) {bitmap.recycle();throw error}
    }
}
