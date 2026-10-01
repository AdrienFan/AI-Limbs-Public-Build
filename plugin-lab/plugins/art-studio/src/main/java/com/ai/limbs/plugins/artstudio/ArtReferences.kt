package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject

/** References are document-owned view objects, excluded from ArtRenderer artwork output. */
internal object ArtReferences {
    const val LAYER = "00000000-0000-0000-0000-000000000001"
    const val MAX = 16
    const val PREVIEW_EDGE = 512
    fun items(state: JSONObject): List<JSONObject> {
        val a=state.optJSONArray("references") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    fun ids(state: JSONObject): List<String> =
        state.optJSONArray("referenceSelection")?.let { ArtShapes.ids(it) } ?: emptyList()
    fun normalize(source: JSONObject): JSONObject {
        val r=JSONObject(source.toString())
        for(key in listOf("id","asset")) require(r.getString(key).matches(Regex("[a-f0-9-]{36}")))
        require(r.getInt("width") in 1..16384 && r.getInt("height") in 1..16384)
        val m=ArtShapes.matrix(r.getJSONArray("matrix"))
        val corners=floatArrayOf(0f,0f,r.getInt("width").toFloat(),0f,
            r.getInt("width").toFloat(),r.getInt("height").toFloat(),0f,r.getInt("height").toFloat())
        m.mapPoints(corners)
        require(corners.all { it.isFinite() && kotlin.math.abs(it)<=1000000f }) { "参考图像超出可编辑范围" }
        for(key in listOf("opacity","saturation")) {
            val v=r.optDouble(key,1.0);require(v.isFinite()&&v in 0.0..1.0);r.put(key,v)
        }
        for(key in listOf("visible","keepAspect")) r.put(key,r.optBoolean(key,true))
        r.put("locked",r.optBoolean("locked",false)).put("name",r.optString("name","参考图像").take(100))
        return r
    }
    fun validate(state: JSONObject) {
        val list=items(state);require(list.size<=MAX) { "一个工程最多16张参考图像" }
        require(list.map { it.getString("id") }.distinct().size==list.size)
        list.forEach { normalize(it) }
        require(ids(state).all { id -> list.any { it.getString("id")==id } }) { "参考图像选择包含不存在的对象" }
    }
    fun edit(state: JSONObject,type: String,p: JSONObject) {
        if(type=="REFERENCE_SHOW") { state.put("referencesVisible",p.getBoolean("visible"));return }
        val refs=items(state).toMutableList()
        if(type=="REFERENCE_ADD") {
            require(refs.size<MAX) { "一个工程最多16张参考图像" }
            val r=normalize(p.getJSONObject("reference"));require(refs.none { it.getString("id")==r.getString("id") })
            refs.add(r);state.put("referenceSelection",JSONArray().put(r.getString("id")))
        } else {
            val selected=ArtShapes.ids(p.getJSONArray("ids")).distinct()
            require(selected.all { id -> refs.any { it.getString("id")==id } }) { "参考图像已删除，请刷新" }
            if(type=="REFERENCE_SELECT") state.put("referenceSelection",JSONArray(selected))
            else {
                val targets=refs.filter { it.getString("id") in selected }
                if(type!="REFERENCE_STYLE") require(targets.none { it.getBoolean("locked") }) { "参考图像已锁定" }
                when(type) {
                    "REFERENCE_DELETE" -> refs.removeAll(targets.toSet())
                    "REFERENCE_TRANSFORM" -> {
                        val delta=ArtShapes.matrix(p.getJSONArray("matrix"))
                        require(targets.all { it.getBoolean("visible") }) { "参考图像隐藏，不能变换" }
                        targets.forEach {
                            val m=ArtShapes.matrix(it.getJSONArray("matrix"));m.postConcat(delta)
                            it.put("matrix",ArtShapes.encode(m))
                        }
                        state.put("referenceSelection",JSONArray(selected))
                    }
                    "REFERENCE_STYLE" -> {
                        val style=p.getJSONObject("style")
                        val keys=style.keys().asSequence().toList()
                        require(keys.isNotEmpty() && keys.all { it in setOf("opacity","saturation","visible","locked","keepAspect","name") })
                        // Lock changes remain accessible; other writes cannot modify a locked reference.
                        require(targets.none { it.getBoolean("locked") } || keys==listOf("locked")) { "请先解锁参考图像" }
                        targets.forEach { r -> keys.forEach { r.put(it,style.get(it)) } }
                    }
                    else -> error("未知参考图像操作")
                }
                val kept=refs.map { it.getString("id") }.toSet()
                state.put("referenceSelection",JSONArray(ids(state).filter { it in kept }))
            }
        }
        state.put("references",JSONArray(refs.map(::normalize)));validate(state)
    }
    /** A transient geometric adapter reuses object selection handles, never saved as a layer. */
    fun selectionState(state: JSONObject): JSONObject {
        val shapes=items(state).filter { it.getBoolean("visible") }.map { r ->
            ArtShapes.normalize(JSONObject().put("id",r.getString("id")).put("kind","rectangle")
                .put("points",JSONArray().put(JSONArray().put(0).put(0))
                    .put(JSONArray().put(r.getInt("width")).put(r.getInt("height"))))
                .put("matrix",r.getJSONArray("matrix")).put("locked",r.getBoolean("locked"))
                .put("fill","#FFFFFFFF").put("stroke","#00000000"))
        }
        val layer=JSONObject().put("id",LAYER).put("kind","vector").put("shapes",JSONArray(shapes))
            .put("visible",true).put("locked",false).put("opacity",1.0).put("parentId","")
            .put("x",0.0).put("y",0.0).put("scale",1.0).put("rotation",0.0)
        return JSONObject().put("layers",JSONArray().put(layer)).put("shapeSelection",
            JSONObject().put("layerId",LAYER).put("ids",JSONArray(ids(state).filter { id -> shapes.any { it.getString("id")==id } })))
    }
    fun draw(canvas: Canvas,state: JSONObject,bitmaps: Map<String,Bitmap>,toScreen: Matrix,
        gesture: StudioShapeInteraction?=null) {
        if(!state.optBoolean("referencesVisible",true)) return
        for(r in items(state)) {
            if(!r.getBoolean("visible")) continue
            val bitmap=bitmaps[r.getString("asset")] ?: error("参考图像预览未加载")
            val transform=Matrix(toScreen)
            gesture?.previewMatrix(r.getString("id"))?.let { transform.preConcat(it) }
            transform.preConcat(ArtShapes.matrix(r.getJSONArray("matrix")))
            val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha=(r.getDouble("opacity")*255).toInt()
                colorFilter=ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(r.getDouble("saturation").toFloat()) })
            }
            canvas.save()
            try {
                canvas.concat(transform)
                canvas.drawBitmap(bitmap,null,RectF(0f,0f,r.getInt("width").toFloat(),r.getInt("height").toFloat()),paint)
            } finally { canvas.restore() }
        }
    }
}
