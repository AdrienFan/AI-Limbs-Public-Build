package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.*

/** A real editable mask layer: key strokes are metadata, output is an explicit cached asset. */
internal object ArtColorize {
    const val MAX_STROKES=256
    const val MAX_POINTS=4096
    const val MAX_TOTAL_POINTS=32768
    const val MAX_COLORS=32
    const val MAX_PIXELS=4194304
    val pending=listOf("图层组与变换线稿","多尺度大画幅求解","动画与HDR")
    fun settings()=JSONObject().put("threshold",180).put("gapClose",0)
        .put("limitBounds",false).put("editKeys",true).put("showOutput",true)
        .put("useEdgeDetection",false).put("edgeDetectionSize",4.0).put("fuzzyRadius",0.0).put("cleanUpAmount",0.0)
    fun defaults()=JSONObject().put("settings",settings()).put("maxStrokes",MAX_STROKES)
        .put("maxPoints",MAX_POINTS).put("maxTotalPoints",MAX_TOTAL_POINTS).put("maxColors",MAX_COLORS)
        .put("maxPixels",MAX_PIXELS).put("pending",JSONArray(pending))
        .put("algorithm","prefiltered-seeded-geodesic-fill")
        .put("filtering","LoG-style shadow edges + three-box Gaussian approximation; fuzzy screen union; foreign-perimeter region cleanup")
        .put("ranges",JSONObject().put("useEdgeDetection","boolean").put("edgeDetectionSize","0–100 px")
            .put("fuzzyRadius","0–500 px (blur sigma); gap hint about 2×radius, not a guaranteed closure width")
            .put("cleanUpAmount","0–1; zero disables, higher removes more small competing spill regions")).put("coordinateSpace","mask-local; untransformed root only for editing")
    fun items(layer: JSONObject): List<JSONObject> {
        val a=layer.getJSONObject("colorize").getJSONArray("keys")
        return (0 until a.length()).map {a.getJSONObject(it)}
    }
    fun palette(layer: JSONObject): List<JSONObject> {
        val a=layer.getJSONObject("colorize").getJSONArray("palette")
        return (0 until a.length()).map {a.getJSONObject(it)}
    }
    fun layer(state: JSONObject,id: String): JSONObject = ArtMenuOperations.layers(state)
        .firstOrNull {it.getString("id")==id && it.getString("kind")=="colorize"} ?: error("上色蒙版不存在")
    fun rooted(layer: JSONObject): Boolean = layer.optString("parentId").isBlank() &&
        layer.getDouble("x")==0.0 && layer.getDouble("y")==0.0 &&
        layer.getDouble("rotation")==0.0 && layer.getDouble("scale")==1.0
    fun canUpdate(state: JSONObject,mask: JSONObject): Boolean {
        val source=ArtMenuOperations.layers(state).firstOrNull {
            it.getString("id")==mask.getJSONObject("colorize").getString("sourceLayerId")
        } ?: return false
        return rooted(mask) && rooted(source) && source.getString("kind") in setOf("paint","image") &&
            source.getBoolean("visible") && mask.getBoolean("visible")
    }
    fun root(layer: JSONObject) {
        require(layer.optString("parentId").isBlank() && layer.getDouble("x")==0.0 && layer.getDouble("y")==0.0 &&
            layer.getDouble("rotation")==0.0 && layer.getDouble("scale")==1.0) { "请使用未变换的根图层" }
    }
    fun source(state: JSONObject,id: String): JSONObject {
        val source=ArtMenuOperations.layers(state).firstOrNull {it.getString("id")==id} ?: error("关联线稿源已删除")
        require(source.getString("kind") in setOf("paint","image") && source.getBoolean("visible")) { "基础上色蒙版需要可见绘画或图像线稿" }
        root(source);return source
    }
    fun signature(state: JSONObject,source: JSONObject): String =
        MessageDigest.getInstance("SHA-256").digest((source.toString()+":"+state.getInt("width")+":"+state.getInt("height"))
            .toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
    fun dirty(state: JSONObject,layer: JSONObject): Boolean {
        val data=layer.getJSONObject("colorize")
        if(data.getInt("generation")!=data.getInt("outputGeneration"))return true
        val source=ArtMenuOperations.layers(state).firstOrNull {it.getString("id")==data.getString("sourceLayerId")} ?: return true
        return data.getString("sourceSignature")!=signature(state,source)
    }
    fun color(value: String): String {
        require(value.matches(Regex("#[A-Fa-f0-9]{8}")) && Color.alpha(Color.parseColor(value))>0) {
            "线索颜色需要非透明的 #AARRGGBB；保持透明请设置调色板的透明标记"
        }
        return value.uppercase()
    }
    fun stroke(p: JSONObject,id: String): JSONObject {
        val pts=p.getJSONArray("points");val width=p.getDouble("width")
        require(width.isFinite() && width in 0.1..256.0 && pts.length() in 1..MAX_POINTS)
        for(i in 0 until pts.length()) {
            val v=pts.getJSONArray(i)
            require(v.length()==2 && (0..1).all {v.getDouble(it).isFinite() && abs(v.getDouble(it))<=1000000})
        }
        if(p.has("erase"))require(p.get("erase") is Boolean)
        val erase=if(p.has("erase"))p.getBoolean("erase") else false
        val out=JSONObject().put("id",id).put("points",JSONArray(pts.toString())).put("width",width)
            .put("erase",erase).put("color",if(erase)"" else color(p.getString("color")))
        p.optJSONObject("selection")?.let {out.put("selection",JSONObject(it.toString()))}
        return out
    }
    fun normalizeSettings(old: JSONObject,patch: JSONObject): JSONObject {
        val out=settings();val allowed=out.keys().asSequence().toSet()
        for(input in listOf(old,patch))input.keys().asSequence().forEach {key ->
            require(key in allowed) {"未知蒙版参数：$key"}
            when(key) {
                "threshold","gapClose" -> {
                    val value=input.get(key);require(value is Number)
                    val n=value.toDouble()
                    require(n.isFinite()&&n==floor(n)&&n in (if(key=="threshold")1.0..254.0 else 0.0..8.0))
                    out.put(key,n.toInt())
                }
                "edgeDetectionSize","fuzzyRadius","cleanUpAmount" -> {
                    val value=input.get(key);require(value is Number)
                    val n=value.toDouble();val max=when(key) {"edgeDetectionSize"->100.0;"fuzzyRadius"->500.0;else->1.0}
                    require(n.isFinite()&&n in 0.0..max);out.put(key,n)
                }
                else -> {require(input.get(key) is Boolean);out.put(key,input.getBoolean(key))}
            }
        }
        return out
    }
    fun edit(state: JSONObject,type: String,p: JSONObject) {
        val all=ArtMenuOperations.layers(state)
        if(type=="COLORIZE_CREATE") {
            val source=source(state,p.getString("sourceLayerId"));val id=p.getString("id")
            require(id.matches(Regex("[a-f0-9-]{36}")) && all.none {it.getString("id")==id})
            val name=p.optString("name","上色蒙版").trim().take(100);require(name.isNotBlank())
            val data=JSONObject().put("sourceLayerId",source.getString("id")).put("keys",JSONArray())
                .put("palette",JSONArray()).put("settings",settings()).put("generation",0).put("outputGeneration",-1)
                .put("sourceSignature","").put("outputX",0).put("outputY",0).put("outputWidth",0).put("outputHeight",0)
            val mask=JSONObject().put("id",id).put("name",name).put("kind","colorize").put("colorize",data)
                .put("parentId","").put("visible",true).put("locked",false).put("opacity",1.0).put("blend","normal")
                .put("x",0.0).put("y",0.0).put("scale",1.0).put("rotation",0.0).put("asset","").put("strokes",JSONArray())
            val result=all.toMutableList();result.add(all.indexOf(source)+1,mask)
            state.put("layers",JSONArray(result)).put("selectedLayerId",id);return
        }
        val mask=layer(state,p.getString("maskId"));require(!mask.getBoolean("locked")) {"蒙版已锁定"}
        if(type in setOf("COLORIZE_STROKE","COLORIZE_OUTPUT"))root(mask)
        val data=mask.getJSONObject("colorize")
        fun changed() {data.put("generation",data.getInt("generation")+1)}
        when(type) {
            "COLORIZE_STROKE" -> {
                require(mask.getBoolean("visible") && data.getJSONObject("settings").getBoolean("editKeys")) {"请显示蒙版并开启编辑线索"}
                val key=p.getJSONObject("stroke");val normalized=stroke(key,key.getString("id"))
                require(normalized.getString("id").matches(Regex("[a-f0-9-]{36}")))
                val keys=items(mask);require(keys.size<MAX_STROKES && keys.none {it.getString("id")==key.getString("id")})
                require(keys.sumOf {it.getJSONArray("points").length()}+normalized.getJSONArray("points").length()<=MAX_TOTAL_POINTS)
                val colors=data.getJSONArray("palette");val c=normalized.getString("color")
                if(!normalized.getBoolean("erase") && palette(mask).none {it.getString("color")==c}) {
                    require(colors.length()<MAX_COLORS);colors.put(JSONObject().put("color",c).put("transparent",false))
                }
                data.getJSONArray("keys").put(normalized);changed()
            }
            "COLORIZE_REMOVE_STROKE" -> {
                val a=data.getJSONArray("keys");val i=(0 until a.length()).firstOrNull {a.getJSONObject(it).getString("id")==p.getString("strokeId")}
                    ?: error("颜色线索不存在")
                a.remove(i);changed()
            }
            "COLORIZE_CLEAR" -> {data.put("keys",JSONArray()).put("palette",JSONArray());changed()}
            "COLORIZE_PALETTE" -> {
                val c=color(p.getString("color"));val colors=data.getJSONArray("palette")
                val index=(0 until colors.length()).firstOrNull {colors.getJSONObject(it).getString("color")==c} ?: error("调色板颜色不存在")
                when(p.getString("action")) {
                    "transparent" -> colors.getJSONObject(index).put("transparent",p.getBoolean("transparent"))
                    "remove" -> {
                        colors.remove(index)
                        data.put("keys",JSONArray(items(mask).filter {it.getBoolean("erase") || it.getString("color")!=c}))
                    }
                    else -> error("未知调色板操作")
                }
                changed()
            }
            "COLORIZE_SETTINGS" -> {
                val old=normalizeSettings(data.getJSONObject("settings"),JSONObject());val next=normalizeSettings(old,p.getJSONObject("settings"))
                if(listOf("threshold","gapClose","limitBounds","useEdgeDetection","edgeDetectionSize","fuzzyRadius","cleanUpAmount").any {old.get(it)!=next.get(it)})changed()
                data.put("settings",next)
            }
            "COLORIZE_OUTPUT" -> {
                val output=p.getJSONObject("output");val asset=output.getString("asset")
                require(asset.matches(Regex("[a-f0-9-]{36}")) && p.getInt("generation")==data.getInt("generation"))
                val x=output.getInt("x");val y=output.getInt("y");val w=output.getInt("width");val h=output.getInt("height")
                require(x>=0 && y>=0 && w>0 && h>0 && x.toLong()+w<=state.getInt("width") &&
                    y.toLong()+h<=state.getInt("height") && w.toLong()*h<=MAX_PIXELS)
                mask.put("asset",asset);data.put("outputX",x).put("outputY",y).put("outputWidth",w).put("outputHeight",h)
                    .put("outputGeneration",data.getInt("generation")).put("sourceSignature",p.getString("sourceSignature"))
            }
            "COLORIZE_CONVERT" -> {
                require(mask.getString("asset").isNotBlank()) {"请先更新一次填色结果"}
                val order=JSONArray().put(JSONObject().put("kind","paste").put("asset",mask.getString("asset"))
                    .put("x",data.getInt("outputX")).put("y",data.getInt("outputY")))
                mask.put("kind","paint").put("asset","").put("contentOrder",order);mask.remove("colorize")
            }
            else -> error("未知上色蒙版操作")
        }
    }
    fun validate(state: JSONObject) {
        ArtMenuOperations.layers(state).filter {it.getString("kind")=="colorize"}.forEach {mask ->
            val d=mask.getJSONObject("colorize")
            require(d.getString("sourceLayerId").matches(Regex("[a-f0-9-]{36}")))
            require(d.getInt("generation")>=0 && d.getInt("outputGeneration") in -1..d.getInt("generation"))
            val asset=mask.getString("asset")
            require(asset.isBlank() || asset.matches(Regex("[a-f0-9-]{36}")))
            require(d.getInt("outputX")>=0 && d.getInt("outputY")>=0 && d.getInt("outputWidth")>=0 && d.getInt("outputHeight")>=0)
            require(d.getInt("outputWidth").toLong()*d.getInt("outputHeight")<=MAX_PIXELS)
            if(asset.isNotBlank())require(d.getInt("outputGeneration")>=0 && d.getString("sourceSignature").matches(Regex("[a-f0-9]{64}")))
            normalizeSettings(d.getJSONObject("settings"),d.getJSONObject("settings"))
            val keys=items(mask);require(keys.size<=MAX_STROKES && keys.map {it.getString("id")}.toSet().size==keys.size)
            require(keys.sumOf {it.getJSONArray("points").length()}<=MAX_TOTAL_POINTS)
            keys.forEach {stroke(it,it.getString("id"));require(it.getString("id").matches(Regex("[a-f0-9-]{36}")))}
            val colors=palette(mask);require(colors.size<=MAX_COLORS && colors.map {it.getString("color")}.toSet().size==colors.size)
            colors.forEach {color(it.getString("color"));require(it.get("transparent") is Boolean)}
            require(keys.filterNot {it.getBoolean("erase")}.all {key -> colors.any {it.getString("color")==key.getString("color")}})
        }
    }
    fun drawKey(canvas: Canvas,key: JSONObject,encodedColor: Int?=null) {
        val saved=canvas.save()
        try {
        key.optJSONObject("selection")?.let {canvas.clipPath(ArtSelection.path(it))}
        val points=key.getJSONArray("points");val path=Path()
        for(i in 0 until points.length()) {
            val p=points.getJSONArray(i)
            if(i==0)path.moveTo(p.getDouble(0).toFloat(),p.getDouble(1).toFloat())
            else path.lineTo(p.getDouble(0).toFloat(),p.getDouble(1).toFloat())
        }
        val paint=Paint(if(encodedColor==null)Paint.ANTI_ALIAS_FLAG else 0).apply {
            xfermode=PorterDuffXfermode(if(key.getBoolean("erase"))PorterDuff.Mode.CLEAR else PorterDuff.Mode.SRC)
            color=encodedColor ?: if(key.getBoolean("erase"))Color.GRAY else Color.parseColor(key.getString("color"))
            strokeWidth=key.getDouble("width").toFloat();strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;style=Paint.Style.STROKE
        }
        if(points.length()>1)canvas.drawPath(path,paint)
        paint.style=Paint.Style.FILL;val first=points.getJSONArray(0);val last=points.getJSONArray(points.length()-1)
        canvas.drawCircle(first.getDouble(0).toFloat(),first.getDouble(1).toFloat(),paint.strokeWidth/2,paint)
        canvas.drawCircle(last.getDouble(0).toFloat(),last.getDouble(1).toFloat(),paint.strokeWidth/2,paint)
        } finally {canvas.restoreToCount(saved)}
    }
    fun keyBitmap(mask: JSONObject,x: Int,y: Int,w: Int,h: Int): Bitmap {
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap);canvas.translate(-x.toFloat(),-y.toFloat())
            val palette=palette(mask)
            for(key in items(mask)) {
                // Opaque encoded labels use SRC; CLEAR removes labels instead of painting an eraser color.
                drawKey(canvas,key,if(key.getBoolean("erase"))Color.WHITE else
                    Color.rgb(0,0,palette.indexOfFirst {it.getString("color")==key.getString("color")}+1))
            }
            return bitmap
        } catch(error:Throwable) {bitmap.recycle();throw error}
    }
}
