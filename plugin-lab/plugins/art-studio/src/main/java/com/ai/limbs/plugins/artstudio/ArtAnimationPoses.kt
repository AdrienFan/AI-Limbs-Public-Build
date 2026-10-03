package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** One captured native cel plus small absolute pose recipes; never a sequence of SVG documents. */
internal object ArtAnimationPoses {
    private val layerFields = setOf("x", "y", "scale", "rotation", "opacity", "affine")
    private val shapeFields = setOf("matrix", "opacity", "visible")

    private fun matrix(value: JSONArray) {
        require(value.length() == 6) { "姿态矩阵需要六个数字" }
        require((0 until 6).all { value.get(it) is Number }) { "矩阵元素须为数字" }
        val v = (0 until 6).map { value.getDouble(it) }
        require(v.all { it.isFinite() && abs(it) <= 1000000 }) { "姿态矩阵元素超出范围" }
        require(abs(v[0] * v[3] - v[1] * v[2]) >= 1e-8) { "姿态矩阵不能退化" }
    }

    private fun patch(target: JSONObject, change: JSONObject, fields: Set<String>) {
        require(change.keys().asSequence().all { it in fields }) { "未知姿态属性" }
        change.keys().forEach { key ->
            when (key) {
                "matrix", "affine" -> matrix(change.getJSONArray(key))
                "visible" -> require(change.get(key) is Boolean) { "visible须为布尔值" }
                else -> {
                    require(change.get(key) is Number) { "姿态属性须为数字" }
                    val value = change.getDouble(key)
                    require(value.isFinite()) { "姿态数字须为有限值" }
                    require(when (key) {
                        "opacity" -> value in 0.0..1.0
                        "scale" -> value in 0.01..100.0
                        else -> abs(value) <= 1000000
                    }) { "姿态属性超出范围" }
                }
            }
            target.put(key, change.get(key))
        }
    }

    fun cel(base: JSONObject, pose: JSONObject): JSONObject {
        val cel = JSONObject(base.toString())
        if (pose.has("layer")) patch(cel, pose.getJSONObject("layer"), layerFields)
        if (pose.has("shapes")) pose.getJSONArray("shapes").let { changes ->
            val shapes = cel.getJSONArray("shapes")
            val byId = (0 until shapes.length()).associate { shapes.getJSONObject(it).let { shape -> shape.getString("id") to shape } }
            val seen = mutableSetOf<String>()
            for (index in 0 until changes.length()) {
                val change = changes.getJSONObject(index)
                val id = change.getString("id")
                require(seen.add(id)) { "同一姿态中形状编号重复" }
                val shape = requireNotNull(byId[id]) { "来源帧中没有该形状" }
                require(!shape.getBoolean("locked")) { "姿态不能编辑锁定的形状" }
                val properties = JSONObject(change.toString()).apply { remove("id") }
                patch(shape, properties, shapeFields)
            }
        }
        return cel
    }

    fun prepare(layer: JSONObject, parameters: JSONObject): JSONObject {
        require(parameters.toString().toByteArray(Charsets.UTF_8).size <= 192 * 1024) { "姿态请求超过192 KiB，请分段提交" }
        require(parameters.keys().asSequence().all { it in setOf("documentId", "expectedRevision", "layerId", "sourceFrame", "poses", "overwrite") }) { "未知姿态请求参数" }
        val sourceFrame = parameters.getInt("sourceFrame")
        require(parameters.get("sourceFrame") is Number && parameters.getDouble("sourceFrame") == sourceFrame.toDouble()) { "来源帧号须为整数" }
        if (parameters.has("overwrite")) require(parameters.get("overwrite") is Boolean) { "overwrite须为布尔值" }
        require(sourceFrame in 0..ArtAnimation.MAX_TIME) { "来源帧号无效" }
        val source = if (ArtAnimation.keys(layer) == null) ArtAnimation.content(layer)
            else JSONObject(requireNotNull(ArtAnimation.active(layer, sourceFrame)).getJSONObject("content").toString())
        val poses = parameters.getJSONArray("poses")
        require(poses.length() in 1..32) { "单次提交1–32个姿态帧" }
        val seen = mutableSetOf<Int>()
        var changes = 0
        for (index in 0 until poses.length()) {
            val pose = poses.getJSONObject(index)
            require(pose.keys().asSequence().all { it in setOf("frame", "layer", "shapes") }) { "未知姿态帧参数" }
            require(pose.get("frame") is Number && pose.getDouble("frame") == pose.getInt("frame").toDouble()) { "帧号须为整数" }
            val frame = pose.getInt("frame")
            require(frame in 0..ArtAnimation.MAX_TIME && seen.add(frame)) { "目标帧号无效或重复" }
            changes += pose.optJSONArray("shapes")?.length() ?: 0
            require(changes <= 1024) { "单次最多1024个对象姿态修改" }
            cel(source, pose) // Reject every invalid recipe before any draft write.
        }
        return JSONObject(parameters.toString()).put("baseCel", source)
    }

    fun install(layer: JSONObject, parameters: JSONObject) {
        val existing = ArtAnimation.keys(layer)
        val rows = linkedMapOf<Int, JSONObject>()
        if (existing == null) rows[0] = JSONObject().put("time", 0).put("content", ArtAnimation.content(layer))
        else for (index in 0 until existing.length()) {
            val key = existing.getJSONObject(index)
            rows[key.getInt("time")] = JSONObject(key.toString())
        }
        val poses = parameters.getJSONArray("poses")
        for (index in 0 until poses.length()) {
            val pose = poses.getJSONObject(index)
            val frame = pose.getInt("frame")
            require(existing == null || frame !in rows || parameters.optBoolean("overwrite", false)) { "目标已有关键帧；覆盖须显式overwrite:true" }
            rows[frame] = JSONObject().put("time", frame).put("content", cel(parameters.getJSONObject("baseCel"), pose))
        }
        require(rows.size <= 128) { "每个动画图层最多128个关键帧" }
        layer.put("animationKeys", JSONArray(rows.toSortedMap().values.toList()))
    }

    fun describe(snapshot: JSONObject, layer: JSONObject, frame: Int): JSONObject {
        require(frame in 0..ArtAnimation.MAX_TIME) { "来源帧号无效" }
        val base = if (ArtAnimation.keys(layer) == null) ArtAnimation.content(layer)
            else requireNotNull(ArtAnimation.active(layer, frame)).getJSONObject("content")
        val settings = JSONObject()
        for (key in layerFields) if (base.has(key)) settings.put(key, base.get(key))
        val shapes = JSONArray()
        base.optJSONArray("shapes")?.let { list -> for (index in 0 until list.length()) {
            val shape = list.getJSONObject(index)
            val row = JSONObject().put("id", shape.getString("id")).put("kind", shape.getString("kind"))
                .put("locked", shape.getBoolean("locked"))
            for (key in shapeFields) if (shape.has(key)) row.put(key, shape.get(key))
            shapes.put(row)
        } }
        return JSONObject().put("documentId", snapshot.getString("id")).put("revision", snapshot.getInt("revision"))
            .put("layerId", layer.getString("id")).put("sourceFrame", frame).put("layer", settings).put("shapes", shapes)
    }
}
