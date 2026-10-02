package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Region
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/** Plugin-owned menu model helpers. Operations carry immutable data for undo and archive replay. */
internal object ArtMenuOperations {
    fun layers(state: JSONObject): List<JSONObject> = state.getJSONArray("layers").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }
    fun active(state: JSONObject): JSONObject? = layers(state).firstOrNull {
        it.getString("id") == state.optString("selectedLayerId")
    }
    fun subtree(state: JSONObject, id: String): List<JSONObject> {
        val all = layers(state)
        val ids = mutableSetOf(id)
        require(all.any { it.getString("id") == id }) { "图层不存在" }
        repeat(all.size) { all.forEach { if (it.optString("parentId") in ids) ids.add(it.getString("id")) } }
        return all.filter { it.getString("id") in ids }
    }
    fun isLocked(state: JSONObject, layer: JSONObject): Boolean {
        val all = layers(state).associateBy { it.getString("id") }
        var node = layer
        repeat(all.size + 1) {
            if (node.getBoolean("locked")) return true
            val parent = node.optString("parentId")
            if (parent.isBlank()) return false
            node = all.getValue(parent)
        }
        error("图层组存在循环引用")
    }
    fun pixelsEditable(state: JSONObject): Boolean = active(state)?.let {
        it.getString("kind") in setOf("paint", "image") && it.optString("parentId").isBlank() &&
            it.getBoolean("visible") && !isLocked(state, it) &&
            it.getDouble("x") == 0.0 && it.getDouble("y") == 0.0 &&
            it.getDouble("rotation") == 0.0 && it.getDouble("scale") == 1.0
    } == true
    fun canUngroup(state: JSONObject): Boolean = active(state)?.let {
        it.getString("kind") == "group" && !isLocked(state, it) &&
            it.getBoolean("visible") && it.getDouble("opacity") == 1.0 &&
            it.getString("blend") == "normal" && it.getDouble("x") == 0.0 &&
            it.getDouble("y") == 0.0 && it.getDouble("rotation") == 0.0 && it.getDouble("scale") == 1.0
    } == true
    fun mergePair(state: JSONObject): List<JSONObject> {
        val top = active(state) ?: error("请先选择图层")
        require(top.optString("parentId").isBlank()) { "合并当前仅支持根图层" }
        val roots = layers(state).filter { it.optString("parentId").isBlank() }
        val index = roots.indexOf(top)
        require(index > 0) { "当前图层下方没有图层" }
        val pair = listOf(roots[index - 1], top)
        require(pair.all { it.getBoolean("visible") && it.getString("blend") == "normal" &&
            subtree(state, it.getString("id")).all { child -> !isLocked(state, child) && child.getBoolean("visible") } }) {
            "合并需要可见、未锁定且正常混合的相邻根图层（含组内图层）"
        }
        return pair
    }
    fun cloneTree(tree: List<JSONObject>): List<JSONObject> {
        val ids = tree.associate { it.getString("id") to UUID.randomUUID().toString() }
        return tree.map { original ->
            JSONObject(original.toString()).apply {
                put("id", ids.getValue(original.getString("id")))
                put("parentId", if (original.optString("parentId") in ids)
                    ids.getValue(original.getString("parentId")) else "")
                optJSONArray("shapes")?.let { shapes ->
                    for (n in 0 until shapes.length()) shapes.getJSONObject(n).put("id", UUID.randomUUID().toString())
                }
                val strokes = getJSONArray("strokes")
                val strokeIds = mutableMapOf<String, String>()
                for (n in 0 until strokes.length()) {
                    val stroke = strokes.getJSONObject(n)
                    val fresh = UUID.randomUUID().toString()
                    strokeIds[stroke.getString("id")] = fresh
                    stroke.put("id", fresh).put("layerId", getString("id"))
                }
                optJSONArray("contentOrder")?.let { order ->
                    for (n in 0 until order.length()) {
                        val event = order.getJSONObject(n)
                        if (event.getString("kind") == "stroke") event.put("id", strokeIds.getValue(event.getString("id")))
                    }
                }
            }
        }
    }
    fun change(state: JSONObject, remove: List<String>, insert: List<JSONObject>, index: Int,
               selected: String, label: String): JSONObject = JSONObject()
        .put("removeIds", JSONArray(remove)).put("layers", JSONArray(insert)).put("index", index)
        .put("selectedId", selected).put("label", label)
        .put("sourceIds", JSONArray(layers(state).map { it.getString("id") }))
        .put("sourceDigest", signature(state))

    private fun signature(state: JSONObject): String {
        fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
                JSONObject.quote(key) + ":" + if (key == "asset") "\"asset-reference\"" else canonical(value.opt(key))
            }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
            is String -> JSONObject.quote(value)
            is Number -> JSONObject.numberToString(value)
            else -> value.toString()
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(canonical(state).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun edit(state: JSONObject, p: JSONObject) {
        require(signature(state) == p.getString("sourceDigest")) {
            "此图层/滤镜操作依赖先前画面，不能单独撤销其来源；请按足迹顺序撤销"
        }
        val all = layers(state)
        val source = p.getJSONArray("sourceIds")
        require(all.map { it.getString("id") } == (0 until source.length()).map { source.getString(it) }) {
            "图层结构已改变；此操作与后续编辑存在依赖"
        }
        val remove = p.getJSONArray("removeIds").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        require(remove.all { id -> all.any { it.getString("id") == id } })
        val result = all.filterNot { it.getString("id") in remove }.toMutableList()
        val insert = p.getJSONArray("layers").let { a -> (0 until a.length()).map { JSONObject(a.getJSONObject(it).toString()) } }
        val index = p.getInt("index")
        require(index in 0..result.size)
        result.addAll(index, insert)
        val ids = result.map { it.getString("id") }
        require(ids.size == ids.toSet().size) { "重复图层 ID" }
        val byId = result.associateBy { it.getString("id") }
        result.forEach { layer ->
            val seen = mutableSetOf(layer.getString("id"))
            var parent = layer.optString("parentId")
            while (parent.isNotBlank()) {
                require(seen.add(parent)) { "循环图层组" }
                val group = byId[parent] ?: error("父图层组不存在")
                require(group.getString("kind") == "group")
                parent = group.optString("parentId")
            }
        }
        val selected = p.getString("selectedId")
        require(selected.isBlank() || selected in ids)
        state.put("layers", JSONArray(result)).put("selectedLayerId", selected)
        if (p.has("background")) state.put("background", p.getString("background"))
        if (p.has("filter")) state.put("lastFilter", JSONObject(p.getJSONObject("filter").toString()))
    }
    /** Visit only actual asset fields, including inactive history and stored layer trees. */
    fun assets(value: Any, visit: (JSONObject, String) -> Unit) {
        when (value) {
            is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                if (key == "asset" && value.optString(key).isNotBlank()) visit(value, key)
                else value.opt(key)?.let { assets(it, visit) }
            }
            is JSONArray -> for (n in 0 until value.length()) value.opt(n)?.let { assets(it, visit) }
        }
    }
    fun isolated(snapshot: JSONObject, ids: Set<String>, rawRoot: String? = null): JSONObject {
        val copy = JSONObject(snapshot.toString())
        val state = copy.getJSONObject("state")
        state.put("background", "#00000000")
        layers(state).forEach {
            if (it.getString("id") !in ids) it.put("visible", false)
            if (it.getString("id") == rawRoot) it.put("visible", true).put("opacity", 1.0).put("blend", "normal")
        }
        return copy
    }
    fun rasterLayer(id: String, name: String, asset: String): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", "paint").put("parentId", "")
        .put("visible", true).put("locked", false).put("opacity", 1.0).put("blend", "normal")
        .put("x", 0.0).put("y", 0.0).put("rotation", 0.0).put("scale", 1.0)
        .put("asset", "").put("strokes", JSONArray())
        .put("contentOrder", JSONArray().put(JSONObject().put("kind", "paste").put("asset", asset).put("x", 0).put("y", 0)))

    fun filter(bitmap: Bitmap, action: String, p: JSONObject, selection: JSONObject?) {
        val threshold = p.optInt("threshold", 128)
        val steps = p.optInt("steps", 16)
        val mode = p.optString("mode", "lightness")
        require(threshold in 0..255 && steps in 2..128)
        require(mode in setOf("luminosity", "luminosity601", "average", "lightness", "min", "max"))
        val coverage=selection?.takeIf {it.has("coverage")}?.let {ArtSoftSelection.Sampler(it)}
        val region = selection?.takeIf {!it.has("coverage")}?.let { Region().apply { setPath(ArtSelection.path(it), Region(0, 0, bitmap.width, bitmap.height)) } }
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
            for (x in row.indices) {
                if (region != null && !region.contains(x, y)) continue
                val amount=coverage?.at(x+0.5,y+0.5) ?: 255;if(amount==0)continue
                val c = row[x]; val a = Color.alpha(c)
                if (a == 0) { if (action == "filter.resettransparent") row[x] = 0; continue }
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                fun quant(v: Int): Int {
                    val step=65535/steps;val value=v*257;val remainder=value%step
                    val quantized=value + if(remainder>step/2) step-remainder else -remainder
                    return (quantized.coerceIn(0,65535)/257.0).roundToInt()
                }
                row[x] = when (action) {
                    "filter.invert" -> Color.argb(a, 255-r, 255-g, 255-b)
                    "filter.desaturate" -> {
                        val v = when (mode) { "average" -> (r+g+b)/3; "lightness" -> (maxOf(r,g,b)+minOf(r,g,b))/2
                            "min" -> minOf(r,g,b); "max" -> maxOf(r,g,b); "luminosity601" -> (r*0.299+g*0.587+b*0.114).roundToInt(); else -> luma(c) }
                        Color.argb(a,v,v,v)
                    }
                    "filter.threshold" -> { val v = if (luma(c) > threshold) 255 else 0; Color.argb(a,v,v,v) }
                    "filter.posterize" -> Color.argb(quant(a),quant(r),quant(g),quant(b))
                    "filter.maximize" -> { val v=maxOf(r,g,b); Color.argb(a,if(r==v)r else 0,if(g==v)g else 0,if(b==v)b else 0) }
                    "filter.minimize" -> { val v=minOf(r,g,b); Color.argb(a,if(r==v)r else 0,if(g==v)g else 0,if(b==v)b else 0) }
                    "filter.resettransparent" -> c
                    else -> error("尚未实现的滤镜")
                }
                row[x]=ArtSoftSelection.blend(c,row[x],amount)
            }
            bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
        }
    }
    fun luma(c: Int): Int = (Color.red(c)*0.2126 + Color.green(c)*0.7152 + Color.blue(c)*0.0722).roundToInt()
    fun histogram(bitmap: Bitmap): JSONObject {
        val channels = Array(4) { IntArray(256) }
        val row = IntArray(bitmap.width)
        var count = 0L
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row,0,row.size,0,y,row.size,1)
            for (c in row) if (Color.alpha(c)>0) {
                channels[0][Color.red(c)]++; channels[1][Color.green(c)]++
                channels[2][Color.blue(c)]++; channels[3][luma(c)]++; count++
            }
        }
        return JSONObject().put("nonTransparentPixels", count).put("width",bitmap.width).put("height",bitmap.height).apply {
            listOf("red","green","blue","luminance").forEachIndexed { n,key -> put(key,JSONArray(channels[n].toList())) }
        }
    }
}
