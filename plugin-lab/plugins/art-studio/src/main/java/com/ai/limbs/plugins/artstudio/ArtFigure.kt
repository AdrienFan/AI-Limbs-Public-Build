package com.ai.limbs.plugins.artstudio

import android.graphics.Path
import android.graphics.PathMeasure
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Two captured corners become immutable geometry; figures use the plugin's own brush and fill models. */
internal object ArtFigure {
    val tools=setOf("rectangle","ellipse")
    val fills=linkedMapOf("none" to "无","solid" to "纯色","pattern" to "图案")
    val outlines=linkedMapOf("brush" to "当前笔刷","basic" to "普通描边","none" to "无描边")
    val patterns=linkedMapOf("checker" to "棋盘","stripes" to "条纹","dots" to "圆点","image" to "图片平铺")
    fun defaults()=JSONObject().put("fixedWidth",0).put("fixedHeight",0).put("fixedRatio",0)
        .put("drawFromCenter",false).put("cornerRadius",0).put("outline","brush").put("brushTool","ink")
        .put("figureFill",JSONObject().put("mode","none"))
    private fun value(p:JSONObject,key:String,default:Double,max:Double):Double {
        val v=if(p.has(key))p.getDouble(key) else default;require(v.isFinite() && v in 0.0..max) {"$key 超出范围"};return v
    }
    private fun color(s:String):String {require(s.matches(Regex("#[A-Fa-f0-9]{8}"))) {"颜色须为#AARRGGBB"};return s}
    fun fill(p:JSONObject,foreground:String):JSONObject {
        require(p.keys().asSequence().all {it in setOf("mode","color","pattern")}) {"填充含未知字段"}
        val mode=p.optString("mode","none");require(mode in fills)
        val out=JSONObject().put("mode",mode)
        if(mode=="none") {require(!p.has("color")&&!p.has("pattern"));return out}
        if(mode=="solid") {require(!p.has("pattern"));return out.put("color",color(p.optString("color",foreground)))}
        require(!p.has("color"));val pattern=if(p.has("pattern"))p.getJSONObject("pattern") else JSONObject()
        require(pattern.keys().asSequence().all {it in setOf("kind","foreground","background","tileSize","scale","angle","offset","asset")})
        val kind=pattern.optString("kind","checker");require(kind in patterns)
        val scale=(if(pattern.has("scale"))pattern.getDouble("scale") else 1.0);require(scale.isFinite()&&scale in 0.1..16.0)
        val angle=(if(pattern.has("angle"))pattern.getDouble("angle") else 0.0);require(angle.isFinite()&&angle in -360.0..360.0)
        val offset=if(pattern.has("offset"))pattern.getJSONArray("offset") else JSONArray().put(0).put(0)
        require(offset.length()==2 && (0..1).all {offset.getDouble(it).isFinite()&&abs(offset.getDouble(it))<=1_000_000})
        val tile=(if(pattern.has("tileSize"))pattern.getDouble("tileSize") else 16.0);require(tile.isFinite()&&tile in 4.0..128.0&&tile==floor(tile))
        val config=JSONObject().put("kind",kind).put("scale",scale).put("angle",angle).put("offset",JSONArray(offset.toString()))
            .put("tileSize",tile.toInt()).put("foreground",color(pattern.optString("foreground",foreground)))
            .put("background",color(pattern.optString("background","#00000000")))
        if(kind=="image") {val id=pattern.getString("asset");require(id.matches(Regex("[a-f0-9-]{36}")));config.put("asset",id)}
        else require(!pattern.has("asset")) {"仅图片平铺使用asset"}
        return out.put("pattern",config)
    }
    fun settings(p:JSONObject):JSONObject {
        val tool=p.getString("tool");require(tool in tools)
        val out=JSONObject(p.toString())
        for(key in listOf("fixedWidth","fixedHeight","fixedRatio"))out.put(key,value(p,key,0.0,if(key=="fixedRatio")1000.0 else 32768.0))
        for(key in listOf("fixedWidth","fixedHeight","fixedRatio"))require(out.getDouble(key)==0.0 || out.getDouble(key)>=0.001)
        val radius=value(p,"cornerRadius",0.0,16384.0);require(tool=="rectangle" || radius==0.0) {"椭圆不使用圆角半径"}
        out.put("cornerRadius",radius).put("drawFromCenter",if(p.has("drawFromCenter"))p.getBoolean("drawFromCenter") else false)
        val outline=p.optString("outline","brush");require(outline in outlines);out.put("outline",outline)
        val brush=p.optString("brushTool","ink");require(brush in ArtBrush.tools);out.put("brushTool",brush)
        val foreground=color(p.optString("color","#FF161616"))
        val config=if(p.has("figureFill"))p.getJSONObject("figureFill") else JSONObject().put("mode",if(p.optBoolean("fillShape",false))"solid" else "none")
        out.put("figureFill",fill(config,foreground));require(outline!="none" || out.getJSONObject("figureFill").getString("mode")!="none") {"描边与填充不能同时为空"}
        out.remove("fillShape");return out
    }
    private fun size(out:JSONObject,raw:List<ArtBrush.Sample>):Pair<Double,Double> {
        val a=raw[0];val b=raw[1];val centered=out.getBoolean("drawFromCenter")
        var width=abs(b.x-a.x)*(if(centered)2 else 1);var height=abs(b.y-a.y)*(if(centered)2 else 1)
        val fw=out.getDouble("fixedWidth");val fh=out.getDouble("fixedHeight");val ratio=out.getDouble("fixedRatio")
        if(fw>0)width=fw;if(fh>0)height=fh
        if(ratio>0 && !(fw>0 && fh>0)) {if(fw>0)height=width/ratio else width=height*ratio}
        return width to height
    }
    fun hasArea(p:JSONObject):Boolean {
        val out=settings(p);val raw=ArtBrush.samples(p.getJSONArray("points"));require(raw.size==2)
        val (w,h)=size(out,raw);return w>0&&h>0
    }
    fun geometry(p:JSONObject):JSONObject {
        val out=settings(p);val raw=ArtBrush.samples(p.getJSONArray("points"));require(raw.size==2) {"矩形/椭圆需要两个原始端点"}
        val a=raw[0];val b=raw[1];val centered=out.getBoolean("drawFromCenter")
        val (width,height)=size(out,raw)
        require(width.isFinite()&&height.isFinite()&&width in 0.001..32768.0&&height in 0.001..32768.0) {"请画出非零矩形/椭圆，宽高最多32768像素"}
        val left=if(centered)a.x-width/2 else if(b.x<a.x)a.x-width else a.x
        val top=if(centered)a.y-height/2 else if(b.y<a.y)a.y-height else a.y
        val bounds=JSONArray().put(left).put(top).put(left+width).put(top+height)
        require((0..3).all {abs(bounds.getDouble(it))<=1_000_000})
        val radius=min(out.getDouble("cornerRadius"),min(width,height)/2)
        val corners=JSONArray().put(JSONArray().put(left).put(top)).put(JSONArray().put(left+width).put(top+height))
        return out.put("figureVersion",1).put("figureInput",JSONArray(p.getJSONArray("points").toString()))
            .put("figureBounds",bounds).put("effectiveRadius",radius).put("figureCorners",corners).put("points",JSONArray(corners.toString()))
    }
    fun path(p:JSONObject):Path {
        val b=p.getJSONArray("figureBounds");val l=b.getDouble(0).toFloat();val t=b.getDouble(1).toFloat()
        val r=b.getDouble(2).toFloat();val bottom=b.getDouble(3).toFloat()
        return Path().apply {
            if(p.getString("tool")=="ellipse")addOval(l,t,r,bottom,Path.Direction.CW)
            else {val radius=p.getDouble("effectiveRadius").toFloat();addRoundRect(l,t,r,bottom,radius,radius,Path.Direction.CW)}
        }
    }
    fun outlinePoints(p:JSONObject):JSONArray {
        val measure=PathMeasure(path(p),true);val length=measure.length.toDouble();require(length.isFinite()&&length>0)
        val count=ceil(length/0.75).toInt().coerceAtLeast(4);require(count<ArtBrush.MAX_SAMPLES) {"笔刷轮廓超过10000采样，请缩小形状或选择普通描边"}
        val distances=(0..count).map {length*it/count}.toMutableSet()
        if(p.getString("tool")=="rectangle" && p.getDouble("effectiveRadius")==0.0) {
            val bounds=p.getJSONArray("figureBounds");val w=bounds.getDouble(2)-bounds.getDouble(0);val h=bounds.getDouble(3)-bounds.getDouble(1)
            // Build unrounded rectangles segment by segment so every sharp corner is sampled exactly.
            val corners=listOf(bounds.getDouble(0) to bounds.getDouble(1),bounds.getDouble(2) to bounds.getDouble(1),
                bounds.getDouble(2) to bounds.getDouble(3),bounds.getDouble(0) to bounds.getDouble(3),bounds.getDouble(0) to bounds.getDouble(1))
            val samples=JSONArray();var time=0
            for(i in 1 until corners.size) {
                val a=corners[i-1];val b=corners[i];val n=ceil((if(i%2==1)w else h)/0.75).toInt().coerceAtLeast(1)
                for(j in (if(i==1)0 else 1)..n) {
                    require(samples.length()<ArtBrush.MAX_SAMPLES)
                    samples.put(JSONArray().put(a.first+(b.first-a.first)*j/n).put(a.second+(b.second-a.second)*j/n).put(1).put(time++*16).put(0).put(0))
                }
            };return samples
        }
        val out=JSONArray();val position=FloatArray(2)
        distances.sorted().forEachIndexed {i,distance ->
            require(measure.getPosTan(distance.toFloat(),position,null))
            out.put(JSONArray().put(position[0]).put(position[1]).put(1).put(i*16).put(0).put(0))
        }
        // Android path-measure endpoints can differ by floating precision; explicitly close to the first sample.
        val first=out.getJSONArray(0);out.getJSONArray(out.length()-1).put(0,first.getDouble(0)).put(1,first.getDouble(1))
        return out
    }
    fun validateStored(p:JSONObject) {
        require(p.getInt("figureVersion")==1);settings(p)
        require(ArtBrush.samples(p.getJSONArray("figureInput")).size==2)
        val bounds=p.getJSONArray("figureBounds");require(bounds.length()==4&&(0..3).all {bounds.getDouble(it).isFinite()&&abs(bounds.getDouble(it))<=1_000_000})
        val width=bounds.getDouble(2)-bounds.getDouble(0);val height=bounds.getDouble(3)-bounds.getDouble(1)
        require(width in 0.001..32768.0&&height in 0.001..32768.0)
        val radius=p.getDouble("effectiveRadius");require(radius.isFinite()&&radius in 0.0..min(width,height)/2)
        val corners=ArtBrush.samples(p.getJSONArray("figureCorners"));require(corners.size==2)
        require(corners[0].x==bounds.getDouble(0)&&corners[0].y==bounds.getDouble(1)&&corners[1].x==bounds.getDouble(2)&&corners[1].y==bounds.getDouble(3))
        require((p.getString("outline")=="brush")==p.has("brush"))
    }
    fun assetIds(p:JSONObject):Set<String> {
        val fill=p.getJSONObject("figureFill");return if(fill.getString("mode")=="pattern"&&fill.getJSONObject("pattern").getString("kind")=="image")setOf(fill.getJSONObject("pattern").getString("asset")) else emptySet()
    }
    fun info()=JSONObject().put("tools",JSONArray(tools.toList())).put("defaults",defaults()).put("outlines",JSONObject(outlines)).put("fills",JSONObject(fills)).put("patterns",JSONObject(patterns))
        .put("coordinates","All corners, dimensions, radius and pattern placement are layer-local pixels; zero dimensions/ratio mean free")
        .put("constraints","Both fixed dimensions take priority over ratio; width+ratio derives height, otherwise ratio derives width from height; center input is center+edge")
        .put("raster","Shared dab-v1 brush; constant pressure=1 on closed geometry; weighted/stabilizer/timed airbrush disabled, pixel_perfect allowed")
        .put("vector","Editable rectangle/ellipse and rounded rectangle with basic color stroke/solid fill; pattern fill and raster brush styles require a paint layer")
        .put("images","brush.resource.import(kind=texture) imports RGBA tiles (1–512px); use asset from brush.resources(kind=texture), not a filesystem path")
}
