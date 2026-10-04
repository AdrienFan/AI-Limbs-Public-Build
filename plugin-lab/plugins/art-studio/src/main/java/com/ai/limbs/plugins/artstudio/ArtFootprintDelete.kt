package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject

/** Delete a retained drawing object, never jump backwards or omit a dependency from replay. */
internal object ArtFootprintDelete {
    private class LayerView(val layer:JSONObject,state:JSONObject) {
        val locked=ArtMenuOperations.isLocked(state,layer)
        val visible=if(layer.getString("kind")=="vector")ArtShapes.visible(state,layer) else true
        val key=ArtAnimation.active(layer,ArtAnimation.settings(state).getInt("current"))
        val strokeIds=layer.getJSONArray("strokes").let {a->
            (0 until a.length()).map {a.getJSONObject(it).getString("id")}.toSet()
        }
        val shapes=layer.optJSONArray("shapes")?.let {a->
            (0 until a.length()).map {a.getJSONObject(it)}.associateBy {it.getString("id")}
        }
        val bakedStrokes=mutableSetOf<String>()
        init {
            val preceding=mutableSetOf<String>()
            layer.optJSONArray("contentOrder")?.let {order->
                for(i in 0 until order.length()) {
                    val item=order.getJSONObject(i)
                    when(item.getString("kind")) {
                        "stroke"->preceding.add(item.getString("id"))
                        "move_pixels","transform_pixels"->bakedStrokes.addAll(preceding)
                    }
                }
            }
        }
    }
    private class Context(doc: JSONObject, val state: JSONObject) {
        val operations = doc.getJSONArray("operations")
        val byId = (0 until operations.length()).map { operations.getJSONObject(it) }
            .associateBy { it.getString("id") }
        val active = ArtHistory.stacks(operations).first.toSet()
        val layers = ArtMenuOperations.layers(state).associateBy { it.getString("id") }
        val views=mutableMapOf<String,LayerView>()
        val joined = mutableSetOf<Pair<String, String>>()
        init {
            byId.values.filter { it.getString("id") in active }.forEach { op ->
                val p = op.getJSONObject("parameters")
                when (op.getString("type")) {
                    "SHAPE_FREEHAND" -> for (key in listOf("startEndpoint", "endEndpoint")) {
                        p.optJSONObject(key)?.let { joined.add(p.getString("layerId") to it.getString("id")) }
                    }
                    "SHAPE_PATH_COMBINE" -> {
                        val ids = p.getJSONArray("ids")
                        for (i in 0 until ids.length()) joined.add(p.getString("layerId") to ids.getString(i))
                    }
                }
            }
        }
        fun status(id: String): JSONObject {
            fun blocked(reason: String) = JSONObject().put("allowed", false).put("reason", reason)
            if (id.isEmpty()) return blocked("初始画布不能删除")
            val op = byId[id] ?: return blocked("足迹不存在")
            if (id !in active) return blocked("这一步当前未应用到作品")
            val type = op.getString("type")
            if (type !in setOf("STROKE_ADD", "SHAPE_CREATE", "SHAPE_FREEHAND"))
                return blocked("这一步不是独立笔画或绘制对象")
            val p = op.getJSONObject("parameters")
            if (type == "SHAPE_FREEHAND" && (p.has("startEndpoint") || p.has("endEndpoint")))
                return blocked("这笔已接续到其他路径，不能单独删除")
            val layerId = p.getString("layerId")
            val layer = layers[layerId] ?: return blocked("原图层已删除或合并")
            val view=views.getOrPut(layerId) {LayerView(layer,state)}
            if (view.locked) return blocked("图层或父组已锁定")
            val key = view.key
            if (key != null) {
                val originalKey = op.optJSONObject("animationFrameKeys")?.optInt(layerId, 0) ?: 0
                if (key.getInt("time") != originalKey)
                    return blocked("请先定位到这笔所在的动画关键帧")
            }
            if (type == "STROKE_ADD") {
                val strokeId = p.getString("id")
                if (strokeId !in view.strokeIds)
                    return blocked("这笔已删除或合并为像素，不能单独删除")
                if(strokeId in view.bakedStrokes)
                    return blocked("后续像素移动或变形已引用这笔，不能单独删除")
                return JSONObject().put("allowed", true).put("reason", "")
                    .put("type", "STROKE_ERASE").put("parameters", JSONObject()
                        .put("layerId", layerId).put("strokeId", strokeId))
            }
            val shapeId = p.getJSONObject("shape").getString("id")
            if ((layerId to shapeId) in joined) return blocked("对象已与其他路径接续或合成，不能单独删除")
            val shapes = view.shapes ?: return blocked("对象已合并为像素")
            val shape = shapes[shapeId] ?: return blocked("对象已删除或合并")
            if (shape.getBoolean("locked")) return blocked("绘制对象已锁定")
            if (!view.visible) return blocked("请先显示矢量图层或父组")
            return JSONObject().put("allowed", true).put("reason", "")
                .put("type", "SHAPE_DELETE").put("parameters", JSONObject()
                    .put("layerId", layerId).put("ids", JSONArray().put(shapeId)))
        }
    }
    fun project(doc: JSONObject, state: JSONObject, history: JSONObject): JSONObject {
        val context = Context(doc, state)
        fun decorate(row:JSONObject) {
            val status=context.status(row.getString("id"))
            row.put("canDelete",status.getBoolean("allowed")).put("deleteReason",status.getString("reason"))
        }
        history.optJSONObject("entry")?.let {decorate(it)}
        for (key in listOf("timeline", "otherBranches")) {
            val entries = history.optJSONArray(key) ?: continue
            for (i in 0 until entries.length()) {
                // Only requested rows acquire layer/object indices and deletion eligibility.
                decorate(entries.getJSONObject(i))
            }
        }
        return history
    }
    fun resolve(doc: JSONObject, state: JSONObject, id: String): JSONObject {
        val status = Context(doc, state).status(id)
        require(status.getBoolean("allowed")) { status.getString("reason") }
        return status
    }
}
