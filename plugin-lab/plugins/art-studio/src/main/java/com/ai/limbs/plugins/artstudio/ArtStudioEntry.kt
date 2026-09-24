package com.ai.limbs.plugins.artstudio

import com.ai.limbs.plugin.runtime.*
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val ART_ID = "plugin.art.studio"
internal const val ART_PAGE = "$ART_ID.page"
internal const val ART_SCREEN = "$ART_ID.screen"

class ArtStudioEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        require(host.pluginId == ART_ID)
        val store = ArtStore(host.dataDir)
        host.registerProvider(ART_PAGE, ArtStudioPage(host),
            mapOf("kind" to "plugin_page", "screen_id" to ART_SCREEN))
        host.registerScreen(InProcessScreen(ART_SCREEN, "画室", "阿伟和兰儿共同编辑的画布",
            "ai_limbs.plugin_center.ui.v1",
            JSONObject().put("schema", 1).put("layout", "edge_to_edge")
                .put("blocks", JSONArray().put(JSONObject().put("type", "plugin_page")
                    .put("provider_id", ART_PAGE))).toString()))
        host.registerHomeTile(InProcessHomeTile("$ART_ID.tile", "画室", "共同编辑的结构化画布", ART_SCREEN))

        fun capability(name: String, title: String, effect: InProcessCapabilityEffect,
                       description: String = title, block: (JSONObject) -> JSONObject) {
            val fields = parametersFor(name)
            val properties = JSONObject()
            val required = JSONArray()
            for (field in fields) {
                properties.put(field.name, JSONObject().put("type", field.type)
                    .put("description", field.description))
                if (field.required) required.put(field.name)
            }
            host.registerCapability(InProcessCapabilitySpec(
                id = "$ART_ID.$name", displayName = title, description = description,
                invokeAliases = listOf("plugin.art.$name"),
                parameters = fields,
                inputSchema = JSONObject().put("type", "object").put("properties", properties)
                    .put("required", required).put("additionalProperties", false).toString(),
                effect = effect, domain = InProcessCapabilityDomain.PLUGIN,
                executor = InProcessCapabilityExecutor { json -> block(JSONObject(json)).toString() }
            ))
        }
        val read = InProcessCapabilityEffect.READ_ONLY
        val write = InProcessCapabilityEffect.PERSISTENT_WRITE
        capability("document.create", "新建画室工程", write) { p ->
            store.create(p.getInt("width"), p.getInt("height"), p.optString("background", "#FFFFFFFF"))
        }
        capability("document.open", "打开画室工程", write) { p -> store.open(p.getString("id")) }
        capability("document.import", "导入画室工程文件", write,
            "导入 .ailart 工程的 base64 内容，创建独立工程并切换为当前工程。") { p ->
            val encoded = p.getString("base64")
            require(encoded.length <= 90 * 1024 * 1024) { "工程文件超过 64 MB" }
            store.importArchive(Base64.decode(encoded, Base64.DEFAULT))
        }
        capability("document.save", "保存画室工程", write) { store.save() }
        capability("document.rename", "重命名画室工程", write) { p -> store.apply("LANER", "DOCUMENT_RENAME", p) }
        capability("document.info", "读取画室工程", read) { store.current() }
        capability("document.list", "列出画室工程", read) { JSONObject().put("documents", store.list()) }
        capability("canvas.inspect", "查看画布结构", read) { store.current() }
        capability("layer.list", "列出画室图层", read) { store.current().getJSONObject("state") }
        capability("history.list", "列出画室操作历史", read) {
            JSONObject().put("operations", store.current().getJSONArray("operations"))
        }
        capability("layer.create", "创建画室图层", write) { p ->
            p.put("id", UUID.randomUUID().toString()); store.apply("LANER", "LAYER_CREATE", p)
        }
        capability("layer.group", "创建画室图层组", write) { p ->
            p.put("id", UUID.randomUUID().toString()); store.apply("LANER", "GROUP_CREATE", p)
        }
        mapOf("layer.select" to "LAYER_SELECT", "layer.rename" to "LAYER_RENAME",
            "layer.move" to "LAYER_MOVE", "layer.delete" to "LAYER_DELETE",
            "layer.set_visibility" to "LAYER_VISIBLE", "layer.set_opacity" to "LAYER_OPACITY",
            "layer.set_lock" to "LAYER_LOCK", "layer.set_blend" to "LAYER_BLEND",
            "selection.create" to "SELECTION_CREATE", "selection.clear" to "SELECTION_CLEAR",
            "selection.edit" to "SELECTION_EDIT",
            "canvas.crop" to "CROP").forEach { (name, type) ->
            capability(name, "画室 ${name.substringAfter('.')}", write) { p -> store.apply("LANER", type, p) }
        }
        capability("layer.copy", "复制画室图层", write) { p ->
            p.put("newId", UUID.randomUUID().toString()); store.apply("LANER", "LAYER_COPY", p)
        }
        capability("stroke.add", "添加结构化笔画", write) { p ->
            p.put("id", UUID.randomUUID().toString()); store.apply("LANER", "STROKE_ADD", p)
        }
        capability("stroke.erase", "删除指定笔画", write) { p -> store.apply("LANER", "STROKE_ERASE", p) }
        for ((name, field) in mapOf("move" to "x", "scale" to "scale", "rotate" to "rotation")) {
            capability("transform.$name", "画室变换 $name", write) { p ->
                val value = p.getDouble(field)
                store.apply("LANER", "TRANSFORM", JSONObject().put("id", p.getString("id"))
                    .put(field, value).apply { if (name == "move") put("y", p.getDouble("y")) })
            }
        }
        capability("history.revert_actor_operations", "撤销兰儿指定操作", write,
            "只撤销指定的 LANER 操作，保留之后阿伟的操作；若后续操作依赖该操作则拒绝。") { p ->
            val id = p.getString("id")
            val ops = store.current().getJSONArray("operations")
            require((0 until ops.length()).any {
                val op = ops.getJSONObject(it)
                op.getString("id") == id && op.getString("actor") == "LANER" &&
                    op.getString("type") !in setOf("REVERT", "RESTORE")
            }) { "只能撤销已存在的兰儿操作" }
            store.apply("LANER", "REVERT", JSONObject().put("targetId", id))
        }
        capability("history.undo", "撤销最近一次画室操作", write) {
            store.history("LANER", redo = false)
        }
        capability("history.redo", "重做画室操作", write) {
            store.history("LANER", redo = true)
        }
        capability("image.import", "导入 PNG 或 JPEG", write) { p ->
            store.importImage("LANER", p.getString("base64"))
        }
        capability("export.png", "导出 PNG", write) { p ->
            ArtRenderer.export(host.dataDir, store, store.current(), "png", p.optString("name", ""))
        }
        capability("export.jpeg", "导出 JPEG", write) { p ->
            ArtRenderer.export(host.dataDir, store, store.current(), "jpeg", p.optString("name", ""))
        }
        host.logger.i("ArtStudio", "Art Studio mounted")
        return InProcessPluginHandle { host.logger.i("ArtStudio", "Art Studio stopped") }
    }
}

class ArtStudioPresentationEntry : InProcessPluginPresentationEntry {
    override suspend fun mount(host: InProcessPluginPresentationHost): InProcessPluginPresentationHandle {
        require(host.pluginId == ART_ID)
        val registration = host.registerPageProvider(ART_PAGE, ArtStudioPage(host),
            mapOf("kind" to "plugin_page", "screen_id" to ART_SCREEN))
        return InProcessPluginPresentationHandle { registration.close() }
    }
}

private fun parametersFor(name: String): List<InProcessCapabilityParameterSpec> {
    fun p(key: String, type: String = "string", optional: Boolean = false) =
        InProcessCapabilityParameterSpec(key, type, key, !optional)
    val id = p("id")
    return when (name) {
        "document.create" -> listOf(p("width", "integer"), p("height", "integer"), p("background", optional = true))
        "document.import" -> listOf(p("base64"))
        "document.open", "layer.select", "layer.delete", "layer.copy", "layer.set_lock" ->
            listOf(id) + if (name == "layer.set_lock") listOf(p("locked", "boolean")) else emptyList()
        "layer.create", "layer.group" -> listOf(p("name", optional = true), p("parentId", optional = true))
        "layer.rename" -> listOf(id, p("name"))
        "layer.move" -> listOf(id, p("index", "integer"))
        "layer.set_visibility" -> listOf(id, p("visible", "boolean"))
        "layer.set_opacity" -> listOf(id, p("opacity", "number"))
        "layer.set_blend" -> listOf(id, p("blend"))
        "stroke.add" -> listOf(p("layerId"), p("points", "array"), p("color"), p("width", "number"),
            p("opacity", "number", true), p("tool", optional = true))
        "stroke.erase" -> listOf(p("layerId"), p("strokeId"))
        "selection.create" -> listOf(p("x", "number"), p("y", "number"), p("width", "number"), p("height", "number"))
        "selection.edit" -> listOf(p("layerId"), p("action"), p("dx", "number", true),
            p("dy", "number", true), p("factor", "number", true), p("degrees", "number", true),
            p("copyId", optional = true))
        "canvas.crop" -> listOf(p("width", "integer"), p("height", "integer"),
            p("x", "number", true), p("y", "number", true))
        "transform.move" -> listOf(id, p("x", "number"), p("y", "number"))
        "transform.scale" -> listOf(id, p("scale", "number"))
        "transform.rotate" -> listOf(id, p("rotation", "number"))
        "history.revert_actor_operations" -> listOf(id)
        "image.import" -> listOf(p("base64"))
        "export.png", "export.jpeg", "document.rename" -> listOf(p("name", optional = name != "document.rename"))
        else -> emptyList()
    }.let { fields ->
        if ((name.startsWith("layer.") && name != "layer.list") || name.startsWith("stroke.") || name.startsWith("selection.") ||
            name.startsWith("transform.") || name == "canvas.crop" || name == "document.rename") {
            fields + p("expectedRevision", "integer", true)
        } else fields
    }
}
