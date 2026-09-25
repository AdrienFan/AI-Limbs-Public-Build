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
            store.create(p.getInt("width"), p.getInt("height"),
                p.optString("background", "#FFFFFFFF"), p.optString("name", "未命名工程"), "LANER")
        }
        capability("document.open", "打开画室工程", write) { p -> store.open(p.getString("id")) }
        capability("document.import", "导入画室工程文件", write,
            "导入 .ailart 工程的 base64 内容，创建独立工程并切换为当前工程。") { p ->
            val encoded = p.getString("base64")
            require(encoded.length <= 90 * 1024 * 1024) { "工程文件超过 64 MB" }
            store.importArchive(Base64.decode(encoded, Base64.DEFAULT), "LANER")
        }
        capability("document.save", "保存画室工程", write) { store.save() }
        capability("document.recent", "列出最近打开的画室工程", read) {
            JSONObject().put("documents", store.recent())
        }
        capability("document.open_image", "将图片打开为新工程", write,
            "使用 PNG 或 JPEG 的 base64 数据创建独立工程并切换至它，原工程保持可打开。") { p ->
            store.openImage(p.getString("base64"), p.optString("name", "未命名图像"), "LANER")
        }
        capability("document.save_as", "另存为并切换画室工程", write,
            "将当前画布复制成新 ID 的 .ailart 工程，保存到画室私有路径并将新工程设为当前；返回路径。") { p ->
            store.saveAs(p.getString("name"), actor = "LANER")
        }
        capability("document.duplicate", "复制当前图像为新工程", write) { p ->
            store.duplicate(p.getString("name"), actor = "LANER")
        }
        capability("document.close", "关闭当前画室工程", write,
            "保留草稿与已保存档案，清除当前工程指针；未保存修改请先保存或明确调用 discard_and_close。") {
            store.close()
        }
        capability("document.discard_and_close", "舍弃修改并关闭工程", write,
            "明确舍弃当前未保存的修改：有存档时恢复已保存状态，否则删除草稿，然后关闭。") {
            store.discardCurrent()
        }
        capability("document.incremental_version", "另存工程增量版本", write) {
            store.saveIncrementalVersion("LANER")
        }
        capability("document.incremental_backup", "保存工程增量备份", write) {
            store.saveIncrementalBackup()
        }
        capability("template.create", "从当前图像创建模板", write) { p ->
            store.createTemplate(p.getString("name"))
        }
        capability("template.list", "列出画室模板", read) {
            JSONObject().put("templates", store.templates())
        }
        capability("template.open", "从模板创建新工程", write) { p ->
            store.fromTemplate(p.getString("id"), "LANER")
        }
        capability("session.save", "保存画室会话", write) { p ->
            store.saveSession(p.getString("name"))
        }
        capability("session.list", "列出画室会话", read) {
            JSONObject().put("sessions", store.sessions())
        }
        capability("session.open", "恢复画室会话", write) { p ->
            store.openSession(p.getString("name"))
        }
        capability("session.delete", "删除画室会话", write) { p ->
            store.deleteSession(p.getString("name"))
        }
        capability("document.rename", "重命名画室工程", write) { p -> store.apply("LANER", "DOCUMENT_RENAME", p) }
        capability("document.info", "读取画室工程", read) { store.current() }
        capability("document.list", "列出画室工程", read) { JSONObject().put("documents", store.list()) }
        capability("canvas.inspect", "查看画布结构", read) { store.current() }
        capability("canvas.measure", "测量画布两点", read,
            "以画布像素坐标返回距离与屏幕坐标系顺时针角度。") { p ->
            val x0 = p.getDouble("x0"); val y0 = p.getDouble("y0")
            val x1 = p.getDouble("x1"); val y1 = p.getDouble("y1")
            require(listOf(x0, y0, x1, y1).all { it.isFinite() })
            val snapshot = store.current().getJSONObject("state")
            require(x0 in 0.0..snapshot.getDouble("width") &&
                x1 in 0.0..snapshot.getDouble("width") &&
                y0 in 0.0..snapshot.getDouble("height") &&
                y1 in 0.0..snapshot.getDouble("height")) { "测量坐标不在画布内" }
            JSONObject().put("distancePx", kotlin.math.hypot(x1 - x0, y1 - y0))
                .put("degrees", Math.toDegrees(kotlin.math.atan2(y1 - y0, x1 - x0)))
        }
        capability("fill.contiguous", "填充当前图层连通区域", write,
            "连通填色，遵循当前选区；tolerance 为每个 RGBA 通道允许的最大差值百分比（0–100），referenceAllLayers 决定从所有可见层取参考色。目标仍是当前可编辑根图层。") { p ->
            store.fillContiguous("LANER", p.getInt("x"), p.getInt("y"), p.getString("color"),
                if (p.has("expectedRevision")) p.getInt("expectedRevision") else null,
                p.optInt("tolerance", 0), p.optBoolean("referenceAllLayers", false))
        }
        capability("color.sample", "从画布合成结果取色", read,
            "输入画布像素坐标；可选 radius=0–32、blend=0–100。blend<100 时需传当前 baseColor（#AARRGGBB），返回与左侧取色工具相同的颜色。") { p ->
            val snapshot = store.current()
            val bitmap = ArtRenderer.render(store, snapshot)
            try {
                val x = p.getInt("x"); val y = p.getInt("y")
                val radius = p.optInt("radius", 0)
                val sampled = ArtColorSampler.sample(bitmap, x, y, radius)
                val blend = p.optInt("blend", 100)
                require(blend in 0..100) { "取色混合必须在 0–100% 之间" }
                val color = if (blend == 100) sampled else {
                    val baseColor = p.getString("baseColor")
                    require(baseColor.matches(Regex("#[A-Fa-f0-9]{8}"))) { "当前颜色必须是 #AARRGGBB" }
                    ArtColorSampler.blend(android.graphics.Color.parseColor(baseColor), sampled, blend)
                }
                JSONObject().put("color", String.format(java.util.Locale.ROOT,
                    "#%08X", color)).put("x", x).put("y", y).put("radius", radius)
                    .put("blend", blend)
            } finally {
                bitmap.recycle()
            }
        }
        capability("layer.list", "列出画室图层", read,
            "读取当前工程图层、选中图层 ID、画布尺寸、背景和 revision。layers 数组按从底到顶排序；parentId 为空表示根图层，非空表示所属图层组。") {
            val snapshot = store.current()
            snapshot.getJSONObject("state").put("revision", snapshot.getJSONArray("operations").length())
        }
        capability("layer.search", "按名称搜索画室图层", read,
            "在当前工程中按名称忽略大小写查找图层，返回底到顶排列的匹配图层、活动图层 ID 和 revision。") { p ->
            val query = p.getString("query").trim()
            require(query.isNotBlank()) { "搜索词不能为空" }
            val snapshot = store.current()
            val state = snapshot.getJSONObject("state")
            val layers = state.getJSONArray("layers")
            val found = JSONArray()
            for (i in 0 until layers.length()) {
                val layer = layers.getJSONObject(i)
                if (layer.getString("name").contains(query, ignoreCase = true)) found.put(layer)
            }
            JSONObject().put("layers", found)
                .put("selectedLayerId", state.getString("selectedLayerId"))
                .put("revision", snapshot.getInt("revision"))
        }
        capability("history.list", "列出画室操作历史", read) {
            JSONObject().put("operations", store.current().getJSONArray("operations"))
        }
        capability("layer.create", "创建画室绘画图层", write,
            "创建可绘画图层；可选 parentId 指定已有图层组，可选 select=true 立即设为活动图层。") { p ->
            p.put("id", UUID.randomUUID().toString()); store.apply("LANER", "LAYER_CREATE", p)
        }
        capability("layer.group", "创建画室图层组", write,
            "创建图层组；可选 parentId 指定父组，可选 select=true 立即设为活动组。") { p ->
            p.put("id", UUID.randomUUID().toString()); store.apply("LANER", "GROUP_CREATE", p)
        }
        capability("selection.freehand", "创建自由套索选区", write,
            "提供自由描画得到的画布坐标点，使用真实闭合边界。") { p ->
            val params = ArtSelection.fromVertices(p.getJSONArray("points"))
            if (p.has("expectedRevision")) params.put("expectedRevision", p.getInt("expectedRevision"))
            store.apply("LANER", "SELECTION_CREATE", params)
        }
        capability("selection.polygon", "创建多边形选区", write,
            "提供画布坐标下的 3–2048 个顶点；复制、清除和填充沿多边形边界执行。") { p ->
            val params = ArtSelection.fromVertices(p.getJSONArray("points"))
            if (p.has("expectedRevision")) params.put("expectedRevision", p.getInt("expectedRevision"))
            store.apply("LANER", "SELECTION_CREATE", params)
        }
        capability("selection.ellipse", "创建椭圆形选区", write,
            "选区为真实椭圆边界；复制、剪切、填充和连通填充使用相同形状。") { p ->
            p.put("shape", "ellipse")
            store.apply("LANER", "SELECTION_CREATE", p)
        }
        mapOf("layer.select" to "LAYER_SELECT", "layer.rename" to "LAYER_RENAME",
            "layer.move" to "LAYER_MOVE", "layer.delete" to "LAYER_DELETE",
            "layer.set_visibility" to "LAYER_VISIBLE", "layer.set_opacity" to "LAYER_OPACITY",
            "layer.set_lock" to "LAYER_LOCK", "layer.set_blend" to "LAYER_BLEND",
            "layer.properties" to "LAYER_PROPERTIES",
            "selection.create" to "SELECTION_CREATE", "selection.clear" to "SELECTION_CLEAR",
            "selection.edit" to "SELECTION_EDIT",
            "canvas.crop" to "CROP").forEach { (name, type) ->
            val label = when (name) {
                "layer.select" -> "选择活动图层"
                "layer.rename" -> "重命名图层"
                "layer.move" -> "按索引移动图层"
                "layer.delete" -> "删除图层"
                "layer.set_visibility" -> "设置图层可见性"
                "layer.set_opacity" -> "设置图层不透明度"
                "layer.set_lock" -> "锁定或解锁图层"
                "layer.set_blend" -> "设置图层混合模式"
                "layer.properties" -> "批量修改图层属性"
                else -> "画室 " + name.substringAfter('.')
            }
            val detail = when (name) {
                "layer.move" -> "index 为 layer.list 返回的 layers 数组中的底到顶绝对索引；只上下挪一格请用 layer.move_up 或 layer.move_down。"
                "layer.delete" -> "删除指定图层；锁定的图层和含有子层的图层组不可直接删除。"
                "layer.properties" -> "一次原子操作修改图层名称、0–1 不透明度、混合模式、可见性和锁定状态；只提交需要改变的字段。"
                "layer.set_blend" -> "支持 normal、multiply、screen、add 四种混合模式。"
                "layer.set_opacity" -> "图层不透明度范围为 0.0–1.0。"
                else -> label
            }
            capability(name, label, write, detail) { p -> store.apply("LANER", type, p) }
        }
        capability("layer.copy", "复制画室图层", write,
            "复制指定图层，图层组会连子层一起复制；可选 select=true 自动选中副本。") { p ->
            p.put("newId", UUID.randomUUID().toString()); store.apply("LANER", "LAYER_COPY", p)
        }
        capability("layer.move_up", "上移同级图层", write,
            "把指定图层向合成栈顶移动一格；只与同一父组中的相邻图层交换，返回更新后的工程状态。") { p ->
            store.apply("LANER", "LAYER_MOVE_STEP", p.put("direction", "up"))
        }
        capability("layer.move_down", "下移同级图层", write,
            "把指定图层向合成栈底移动一格；只与同一父组中的相邻图层交换，返回更新后的工程状态。") { p ->
            store.apply("LANER", "LAYER_MOVE_STEP", p.put("direction", "down"))
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
        capability("edit.clipboard_info", "读取画室剪贴板", read) { store.clipboardInfo() }
        capability("edit.cut", "剪切选区像素", write) { store.copyPixels("LANER", cut = true) }
        capability("edit.copy", "复制选区像素", write) { store.copyPixels("LANER") }
        capability("edit.copy_merged", "合并复制可见画布", write) {
            store.copyPixels("LANER", merged = true)
        }
        capability("edit.paste", "粘贴剪贴板为新图层", write) { store.pastePixels("LANER") }
        capability("edit.paste_at", "粘贴到画布坐标", write) { p ->
            store.pastePixels("LANER", atX = p.getInt("x"), atY = p.getInt("y"))
        }
        capability("edit.paste_into", "粘贴进活动图层", write) {
            store.pastePixels("LANER", intoActive = true)
        }
        capability("edit.paste_new", "从剪贴板创建新工程", write) { store.pasteAsNew("LANER") }
        capability("edit.clear", "清除选区像素", write) { store.editPixels("LANER", "CLEAR") }
        capability("edit.fill_foreground", "用前景色填充选区", write) { p ->
            store.editPixels("LANER", "FILL", p.getString("color"))
        }
        capability("edit.fill_background", "用指定背景色填充选区", write) { p ->
            store.editPixels("LANER", "FILL", p.getString("color"))
        }
        capability("image.import", "导入 PNG 或 JPEG", write) { p ->
            store.importImage("LANER", p.getString("base64"))
        }
        capability("export.png", "导出 PNG", write) { p ->
            ArtRenderer.export(host.dataDir, store, store.current(), "png", p.optString("name", ""), p)
        }
        capability("export.jpeg", "导出 JPEG", write) { p ->
            ArtRenderer.export(host.dataDir, store, store.current(), "jpeg", p.optString("name", ""), p)
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
    fun p(key: String, type: String = "string", optional: Boolean = false): InProcessCapabilityParameterSpec {
        val description = when (key) {
            "id" -> "图层或工程 ID；先读取 layer.list 或 document.list 确定真实 ID。"
            "parentId" -> "可选的父图层组 ID，空字符串表示根层级。"
            "select" -> "可选；true 表示创建或复制后立即选中该图层。"
            "index" -> "layer.list 返回的 layers 数组中的位置，从 0 开始，方向为底到顶。"
            "opacity" -> "不透明度，0.0 表示透明，1.0 表示完全不透明。"
            "blend" -> "混合模式：normal、multiply、screen 或 add。"
            "query" -> "图层名称中的文字；按名称筛选且忽略大小写。"
            "visible" -> "true 显示，false 隐藏。"
            "locked" -> "true 锁定，false 解锁。"
            "expectedRevision" -> "可选的操作历史条数；用于拒绝在另一端改动后过期的操作。"
            else -> key
        }
        return InProcessCapabilityParameterSpec(key, type, description, !optional)
    }
    val id = p("id")
    return when (name) {
        "document.create" -> listOf(p("width", "integer"), p("height", "integer"),
            p("background", optional = true), p("name", optional = true))
        "document.import" -> listOf(p("base64"))
        "document.open_image" -> listOf(p("base64"), p("name", optional = true))
        "document.save_as", "document.duplicate", "template.create",
        "session.save", "session.open", "session.delete" -> listOf(p("name"))
        "template.open" -> listOf(id)
        "document.open", "layer.select", "layer.delete", "layer.copy",
        "layer.move_up", "layer.move_down", "layer.set_lock" ->
            listOf(id) + when (name) {
                "layer.set_lock" -> listOf(p("locked", "boolean"))
                "layer.copy" -> listOf(p("select", "boolean", true))
                else -> emptyList()
            }
        "layer.create", "layer.group" -> listOf(p("name", optional = true), p("parentId", optional = true),
            p("select", "boolean", true))
        "layer.search" -> listOf(p("query"))
        "color.sample" -> listOf(p("x", "integer"), p("y", "integer"),
            p("radius", "integer", true), p("blend", "integer", true),
            p("baseColor", optional = true))
        "canvas.measure" -> listOf(p("x0", "number"), p("y0", "number"),
            p("x1", "number"), p("y1", "number"))
        "fill.contiguous" -> listOf(p("x", "integer"), p("y", "integer"), p("color"),
            p("expectedRevision", "integer", true), p("tolerance", "integer", true),
            p("referenceAllLayers", "boolean", true))
        "layer.rename" -> listOf(id, p("name"))
        "layer.move" -> listOf(id, p("index", "integer"))
        "layer.set_visibility" -> listOf(id, p("visible", "boolean"))
        "layer.set_opacity" -> listOf(id, p("opacity", "number"))
        "layer.set_blend" -> listOf(id, p("blend"))
        "layer.properties" -> listOf(id, p("name", optional = true),
            p("opacity", "number", true), p("blend", optional = true),
            p("visible", "boolean", true), p("locked", "boolean", true))
        "stroke.add" -> listOf(p("layerId"), p("points", "array"), p("color"), p("width", "number"),
            p("opacity", "number", true), p("tool", optional = true),
            p("fillShape", "boolean", true), p("gradientMode", optional = true),
            p("gradientReverse", "boolean", true),
            p("mirrorDirection", optional = true), p("mirrorCount", "integer", true),
            p("mirrorRadius", "number", true), p("mirrorSeed", "integer", true),
            p("mirrorCenters", "array", true),
            p("mirrorIntervalX", "integer", true), p("mirrorIntervalY", "integer", true),
            p("axisX", "number", true),
            p("axisY", "number", true), p("mass", "number", true),
            p("drag", "number", true), p("nibAngle", "number", true))
        "stroke.erase" -> listOf(p("layerId"), p("strokeId"))
        "selection.create", "selection.ellipse" -> listOf(p("x", "number"), p("y", "number"),
            p("width", "number"), p("height", "number"))
        "selection.polygon", "selection.freehand" -> listOf(p("points", "array"))
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
        "export.png", "export.jpeg" -> listOf(p("name", optional = true),
            p("x", "integer", true), p("y", "integer", true),
            p("cropWidth", "integer", true), p("cropHeight", "integer", true),
            p("width", "integer", true), p("height", "integer", true))
        "document.rename" -> listOf(p("name"))
        "edit.fill_foreground", "edit.fill_background" -> listOf(p("color"))
        "edit.paste_at" -> listOf(p("x", "integer"), p("y", "integer"))
        else -> emptyList()
    }.let { fields ->
        if ((name.startsWith("layer.") && name !in setOf("layer.list", "layer.search")) ||
            name.startsWith("stroke.") || name.startsWith("selection.") ||
            name.startsWith("transform.") || name == "canvas.crop" || name == "document.rename") {
            fields + p("expectedRevision", "integer", true)
        } else fields
    }
}
