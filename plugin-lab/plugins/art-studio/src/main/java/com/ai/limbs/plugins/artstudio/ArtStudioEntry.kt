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
        val viewChannel = ArtStudioViewChannel()
        val assistantView = ArtStudioAssistantView()

        fun viewTarget(p: JSONObject): String = p.optString("target", "assistant").also {
            require(it in setOf("assistant", "phone")) { "target 必须是 assistant 或 phone" }
        }
        host.registerProvider(ART_VIEW_CONTROL, viewChannel,
            mapOf("kind" to "ui_state"))
        host.registerProvider(ART_PAGE, ArtStudioPage(host),
            mapOf("kind" to "plugin_page", "screen_id" to ART_SCREEN))
        host.registerScreen(InProcessScreen(ART_SCREEN, "画室", "阿伟和兰儿共同编辑的画布",
            "ai_limbs.plugin_center.ui.v1",
            JSONObject().put("schema", 1).put("layout", "edge_to_edge")
                .put("blocks", JSONArray().put(JSONObject().put("type", "plugin_page")
                    .put("provider_id", ART_PAGE))).toString()))
        host.registerHomeTile(InProcessHomeTile("$ART_ID.tile", "画室", "共同编辑的结构化画布", ART_SCREEN))

        fun registerCapability(name: String, title: String, effect: InProcessCapabilityEffect,
                       block: suspend (JSONObject) -> JSONObject) {
            val fields = parametersFor(name)
            val properties = JSONObject()
            val required = JSONArray()
            for (field in fields) {
                properties.put(field.name, ArtCapabilityHelp.property(name, field))
                if (field.required) required.put(field.name)
            }
            host.registerCapability(InProcessCapabilitySpec(
                id = "$ART_ID.$name", displayName = title, description = ArtCapabilityHelp.description(name),
                suggestedParamsJson = ArtCapabilityHelp.example(name).toString(),
                invokeAliases = listOf("plugin.art.$name"),
                parameters = fields,
                inputSchema = JSONObject().put("type", "object").put("properties", properties)
                    .put("required", required).put("additionalProperties", false).toString(),
                effect = effect, domain = InProcessCapabilityDomain.PLUGIN,
                executor = InProcessCapabilityExecutor { json -> block(JSONObject(json)).toString() }
            ))
        }
        fun capability(name: String, title: String, effect: InProcessCapabilityEffect,
                       block: (JSONObject) -> JSONObject) {
            registerCapability(name, title, effect) { parameters ->
                try {
                    if (ArtCanvasFeedback.affectsCanvas(name, parameters))
                        store.withCanvasFeedback {
                            val result=block(parameters)
                            if(name.startsWith("reference.")) result.put("referenceFeedback",true)
                            if(name.startsWith("assistant.")) result.put("assistantFeedback",true)
                            if(name.startsWith("selection."))result.put("selectionFeedback",true)
                            if(name.startsWith("colorize.")) {
                                result.put("colorizeFeedback",true)
                                if(parameters.has("maskId"))result.put("colorizeMaskId",parameters.getString("maskId"))
                            }
                            result
                        }
                    else block(parameters)
                } catch (request: ArtImageResizeRequired) {
                    // Consent is a no-op result, not a successful edit or an image receipt.
                    request.response()
                }
            }
        }
        val read = InProcessCapabilityEffect.READ_ONLY
        val write = InProcessCapabilityEffect.PERSISTENT_WRITE
        capability("menu.catalog", "读取画室完整菜单清单", read) {
            ArtStudioMenuCatalog.describe(store.menuContext())
        }
        capability("menu.execute", "执行画室菜单操作", write) { p ->
            val arguments = p.optJSONObject("parameters")?.let { JSONObject(it.toString()) } ?: JSONObject()
            if (p.has("documentId")) arguments.put("documentId",p.getString("documentId"))
            if (p.has("expectedRevision")) arguments.put("expectedRevision",p.getInt("expectedRevision"))
            store.executeMenu("LANER",p.getString("action"),arguments)
        }
        capability("dock.state", "读取画室停靠面板状态", read) {
            store.dockPanelState()
        }
        capability("dock.command", "操作画室停靠面板", write) { p ->
            store.changeDockPanels(p.getString("command"), p.optString("panel").takeIf { it.isNotBlank() },
                if (p.has("enabled")) p.getBoolean("enabled") else null)
        }
        capability("text.fonts", "读取基础文字可用字体", read) {
            ArtText.fonts()
        }
        capability("text.create", "添加基础可编辑文字", write) { p ->
            store.writeText("LANER", p, false)
        }
        capability("text.update", "修改可编辑文字", write) { p ->
            store.writeText("LANER", p, true)
        }
        capability("image.set_background", "设置图像背景色与透明度", write) { p ->
            store.apply("LANER", "IMAGE_BACKGROUND", JSONObject().put("color", p.getString("color")))
        }
        capability("image.crop_to_selection", "裁切图像到选区边界", write) {
            store.cropToSelection("LANER")
        }
        capability("image.resize_canvas", "更改画室画布大小", write) { p ->
            store.apply("LANER", "CANVAS_RESIZE", JSONObject()
                .put("width", p.getInt("width")).put("height", p.getInt("height"))
                .put("x", -p.optInt("offsetX", 0)).put("y", -p.optInt("offsetY", 0)))
        }
        capability("view.state", "读取画室视图状态", read) { p ->
            when (viewTarget(p)) {
                "assistant" -> store.withViewSnapshot { assistantView.describe(it) }
                else -> viewChannel.describe().put("target", "phone")
            }
        }
        registerCapability("view.set", "设置画室视图选项", InProcessCapabilityEffect.UI_INTERACTION) { p ->
            viewChannel.execute("set", p)
        }
        registerCapability("view.tool_options", "操作工具参数浮窗", InProcessCapabilityEffect.UI_INTERACTION) { p ->
            viewChannel.execute("tool_options", p)
        }
        registerCapability("view.zoom_tool", "设置缩放工具方向", InProcessCapabilityEffect.UI_INTERACTION) { p ->
            viewChannel.execute("zoom_tool", p)
        }
        registerCapability("view.zoom", "设置画布显示比例", InProcessCapabilityEffect.UI_INTERACTION) { p ->
            when (viewTarget(p)) {
                "assistant" -> store.withViewSnapshot { assistantView.execute(store, it, "zoom", p) }
                else -> viewChannel.execute("zoom", p)
            }
        }
        registerCapability("view.command", "操作画室视图", InProcessCapabilityEffect.UI_INTERACTION) { p ->
            when (viewTarget(p)) {
                "assistant" -> store.withViewSnapshot { assistantView.execute(store, it, "command", p) }
                else -> viewChannel.execute("command", p)
            }
        }
        registerCapability("view.presentation", "切换画室页面模式", InProcessCapabilityEffect.UI_INTERACTION) { p ->
            val mode = p.getString("mode")
            require(mode in setOf("normal", "fullscreen_portrait", "fullscreen_landscape"))
            val response = JSONObject(host.invokeHostCapability("host.ui.presentation@1",
                JSONObject().put("operation", "set_mode").put("screen_id", ART_SCREEN)
                    .put("mode", mode).toString()))
            check(response.optBoolean("ok") && response.optString("mode") == mode) {
                response.optString("error").ifBlank { "宿主未确认画室显示模式：$response" }
            }
            viewChannel.execute("presentation", p)
            response
        }

        capability("assistant.list","读取辅助尺规",read) { p -> store.assistantList(p) }
        capability("assistant.create","创建辅助尺规",write) { p ->
            val a=JSONObject().put("id",UUID.randomUUID().toString()).put("type",p.getString("type"))
                .put("points",p.getJSONArray("points"))
            if(p.has("name"))a.put("name",p.getString("name"))
            store.apply("LANER","ASSISTANT_CREATE",JSONObject(p.toString()).put("assistant",a))
        }
        capability("assistant.select","选择辅助尺规",write) { p -> store.apply("LANER","ASSISTANT_SELECT",p) }
        capability("assistant.update","编辑辅助尺规",write) { p -> store.apply("LANER","ASSISTANT_UPDATE",p) }
        capability("assistant.delete","删除辅助尺规",write) { p -> store.apply("LANER","ASSISTANT_DELETE",p) }
        capability("assistant.settings","设置尺规显示与画笔吸附",write) { p -> store.apply("LANER","ASSISTANT_SETTINGS",p) }
        capability("assistant.project","计算沿尺规坐标",read) { p -> store.assistantProject(p) }
        capability("assistant.stroke","沿尺规绘画",write) { p -> store.assistantStroke("LANER",p) }
        capability("assistant.preview","检查尺规与画布",read) { p -> store.assistantPreview(p) }
        capability("toolbox.catalog", "读取画室工具清单", read) {
            ArtToolCatalog.describe(viewChannel.describe())
        }
        capability("document.create", "新建画室工程", write) { p ->
            store.create(p.getInt("width"), p.getInt("height"),
                p.optString("background", "#FFFFFFFF"), p.optString("name", "未命名工程"), "LANER")
        }
        capability("document.open", "打开画室工程", write) { p -> store.open(p.getString("id")) }
        capability("document.import", "导入画室工程文件", write) { p ->
            val encoded = p.getString("base64")
            require(encoded.length <= 90 * 1024 * 1024) { "工程文件超过 64 MB" }
            store.importArchive(Base64.decode(encoded, Base64.DEFAULT), "LANER")
        }
        capability("storage.settings", "读取画室默认保存目录", read) {
            store.saveDirectorySettings()
        }
        capability("storage.set_directory", "更改画室默认保存目录", write) { p ->
            store.setSaveDirectory(p.getString("directory"))
        }
        capability("document.save", "保存画室工程", write) { store.save() }
        capability("document.recent", "列出最近打开的画室工程", read) {
            JSONObject().put("documents", store.recent())
        }
        capability("document.open_image", "将图片打开为新工程", write) { p ->
            store.openImage(p.getString("base64"), p.optString("name", "未命名图像"), "LANER", p.optJSONObject("confirmResize"))
        }
        capability("document.save_as", "另存为并切换画室工程", write) { p ->
            store.saveAs(p.getString("name"), actor = "LANER")
        }
        capability("document.duplicate", "复制当前图像为新工程", write) { p ->
            store.duplicate(p.getString("name"), actor = "LANER")
        }
        capability("document.close", "关闭当前画室工程", write) {
            store.close()
        }
        capability("document.discard_and_close", "舍弃修改并关闭工程", write) {
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
        capability("canvas.region", "查看画布局部放大图", read) { p ->
            store.canvasRegion(p.getInt("x"), p.getInt("y"), p.getInt("width"),
                p.getInt("height"), p.optInt("maxEdge", 512),
                if (p.has("documentId")) p.getString("documentId") else null,
                if (p.has("expectedRevision")) p.getInt("expectedRevision") else null,p.optBoolean("selectionOutline",false))
        }
        capability("canvas.measure", "测量画布两点", read) { p ->
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
        capability("colorize.list","读取上色蒙版与颜色线索",read) {p->store.colorizeList(p)}
        capability("colorize.create","从线稿创建上色蒙版",write) {p->store.colorizeCreate("LANER",p)}
        capability("colorize.stroke","编辑蒙版颜色线索",write) {p->store.colorizeStroke("LANER",p)}
        capability("colorize.remove_stroke","删除一笔颜色线索",write) {p->store.apply("LANER","COLORIZE_REMOVE_STROKE",p)}
        capability("colorize.palette","管理线索调色板",write) {p->store.apply("LANER","COLORIZE_PALETTE",p)}
        capability("colorize.settings","设置上色蒙版",write) {p->store.apply("LANER","COLORIZE_SETTINGS",p)}
        capability("colorize.clear","清空蒙版颜色线索",write) {p->store.apply("LANER","COLORIZE_CLEAR",p)}
        capability("colorize.update","重新计算蒙版填色",write) {p->store.colorizeUpdate("LANER",p)}
        capability("colorize.convert","蒙版转换为绘画图层",write) {p->store.apply("LANER","COLORIZE_CONVERT",p)}
        capability("colorize.preview","检查填色与颜色线索",read) {p->store.colorizePreview(p)}
        capability("selection.color_info","读取颜色区域选区参数",read) {ArtColorSelection.info()}
        capability("selection.contiguous","创建连续区域选区",write) {p->store.colorSelection("LANER",p,true)}
        capability("selection.similar","创建相似色选区",write) {p->store.colorSelection("LANER",p,false)}
        capability("selection.magnetic_info","读取磁性套索参数与范围",read) {ArtMagneticSelection.info()}
        capability("selection.magnetic_trace","预览磁性吸附轮廓",read) {p->store.magneticTrace(p)}
        capability("selection.magnetic_create","创建磁性套索选区",write) {p->store.magneticCreate("LANER",p)}
        capability("selection.bezier_info","读取贝塞尔曲线选区范围",read) {ArtBezierSelection.info()}
        capability("selection.bezier_create","建立贝塞尔曲线选区",write) {p->store.bezierSelectionCreate("LANER",p)}
        capability("selection.bezier_nodes","读取曲线选区节点",read) {p->store.bezierSelectionNodes(p)}
        capability("selection.bezier_edit","编辑曲线选区节点",write) {p->store.bezierSelectionEdit("LANER",p)}
        capability("selection.preview","检查当前选区轮廓",read) {p->store.selectionPreview(p)}
        capability("gradient.info","读取渐变形状、色标、重复与抖动参数",read) {ArtGradient.info()}
        capability("gradient.draw","绘制多色标及轮廓渐变",write) {p->store.apply("LANER","STROKE_ADD",
            JSONObject(p.toString()).put("id",UUID.randomUUID().toString()).put("tool","gradient").put("width",1))}
        capability("enclose.info","读取围合填充范围与默认参数",read) {ArtEncloseFill.info()}
        capability("enclose.apply","围合填充当前图层",write) {p->store.encloseFill("LANER",p)}
        capability("patch.info","读取智能修补范围",read) { ArtSmartPatch.info() }
        capability("patch.apply","智能修补当前图层",write) { p -> store.smartPatch("LANER",p) }
        capability("fill.info","读取连续填充模式、图案和软覆盖参数",read) {ArtContiguousFill.info()}
        capability("fill.contiguous","填充相连、边界或全局相似色区域",write) {p->store.fillContiguous("LANER",p)}
        capability("color.sample", "从画布合成结果取色", read) { p ->
            val snapshot = store.current()
            val merged = p.optBoolean("sampleMerged", true)
            val sourceId = if (merged) null else p.getString("layerId")
            val bitmap = ArtColorSampler.renderSource(store, snapshot, sourceId)
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
                require(android.graphics.Color.alpha(color) > 0) { "透明区域没有可取的颜色" }
                JSONObject().put("color", String.format(java.util.Locale.ROOT,
                    "#%08X", color)).put("x", x).put("y", y).put("radius", radius)
                    .put("blend", blend).put("sampleMerged", merged)
            } finally {
                bitmap.recycle()
            }
        }
        capability("layer.list", "列出画室图层", read) {
            val snapshot = store.current()
            snapshot.getJSONObject("state").put("revision", snapshot.getJSONArray("operations").length())
        }
        capability("layer.search", "按名称搜索画室图层", read) { p ->
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
        capability("history.timeline", "读取足迹状态列表", read) {
            val snapshot = store.current()
            JSONObject().put("timeline", snapshot.getJSONArray("timeline"))
                .put("position", snapshot.getInt("timelinePosition"))
                .put("revision", snapshot.getInt("revision"))
        }
        capability("history.goto", "切换到指定足迹状态", write) { p ->
            store.historyJump("LANER", p.getString("id"), p.getInt("expectedRevision"))
        }
        capability("layer.create", "创建画室绘画图层", write) { p ->
            p.put("id", UUID.randomUUID().toString()); store.apply("LANER", "LAYER_CREATE", p)
        }
        capability("layer.vector", "创建基础矢量图层", write) { p ->
            p.put("id", UUID.randomUUID().toString()); store.apply("LANER", "VECTOR_LAYER_CREATE", p)
        }
        capability("reference.list","读取参考图像",read) { p -> store.referenceList(p) }
        capability("reference.preview","查看参考与画布",read) { p -> store.referencePreview(p) }
        capability("reference.region","查看参考图像局部细节",read) { p -> store.referenceRegion(p) }
        capability("reference.add","添加参考图像",write) { p -> store.referenceAdd("LANER",p) }
        capability("reference.select","选择参考图像",write) { p -> store.apply("LANER","REFERENCE_SELECT",p) }
        capability("reference.transform","变换参考图像",write) { p -> store.apply("LANER","REFERENCE_TRANSFORM",p) }
        capability("reference.style","设置参考图像样式",write) { p -> store.apply("LANER","REFERENCE_STYLE",p) }
        capability("reference.delete","删除参考图像",write) { p -> store.apply("LANER","REFERENCE_DELETE",p) }
        capability("reference.show","整体显示或隐藏参考",write) { p -> store.apply("LANER","REFERENCE_SHOW",p) }
        capability("shape.list", "读取矢量形状和选择", read) { p -> store.shapes(p) }
        capability("shape.hit", "命中矢量形状", read) { p -> store.shapes(p, true) }
        capability("shape.box", "查询框内矢量对象", read) { p -> store.shapes(p, box = true) }
        capability("comic.info","读取漫画分格范围与默认参数",read) {ArtComicPanels.info()}
        capability("comic.frame","创建漫画分格框",write) {p->store.comicFrame("LANER",p)}
        capability("comic.cut","切分漫画矢量分格",write) {p->store.comicEdit("LANER","cut",p)}
        capability("comic.merge","合并相邻漫画分格",write) {p->store.comicEdit("LANER","merge",p)}
        capability("path.topology_info","读取子路径和多节点编辑范围",read) {ArtPathTopology.info()}
        capability("path.topology","断开路径、连接子路径或合并端点",write) {p->store.apply("LANER","SHAPE_PATH_TOPOLOGY",p)}
        capability("path.convert","将基础形状转换为可编辑路径",write) {p->store.apply("LANER","SHAPE_PATH_CONVERT",p)}
        capability("path.combine","将多个路径合成一个子路径对象",write) {p->store.apply("LANER","SHAPE_PATH_COMBINE",p)}
        capability("shape.style_info","读取高级对象样式范围",read) {ArtObjectStyle.info()}
        capability("path.create", "创建可编辑贝塞尔路径", write) { p -> store.pathCreate("LANER",p) }
        capability("path.nodes", "读取路径节点与控制柄", read) { p -> store.pathNodes(p) }
        capability("path.edit", "编辑贝塞尔路径节点", write) { p -> store.apply("LANER","SHAPE_PATH_EDIT",p) }
        capability("calligraphy.info","读取矢量书法笔参数和跟随规则",read) {ArtCalligraphy.info()}
        capability("calligraphy.profiles","列出完整书法配置档",read) {store.calligraphyProfiles()}
        capability("calligraphy.profile.get","读取书法配置档",read) {p->store.calligraphyProfile(p.getString("id"))}
        capability("calligraphy.profile.save","保存或更新书法配置档",write) {p->store.saveCalligraphyProfile(p)}
        capability("calligraphy.profile.delete","删除书法配置档",write) {p->store.deleteCalligraphyProfile(p.getString("id"))}
        capability("shape.calligraphy", "绘制矢量书法笔画", write) { p -> store.calligraphy("LANER",p) }
        capability("shape.freehand_info","读取徒手路径接续与分模式优化",read) {ArtFreehand.info()}
        capability("shape.freehand", "绘制矢量徒手路径", write) { p -> store.freehand("LANER", p) }
        capability("shape.create", "创建可编辑矢量形状", write) { p ->
            val shape = JSONObject(p.getJSONObject("shape").toString()).put("id", UUID.randomUUID().toString())
            p.put("shape", shape); store.apply("LANER", "SHAPE_CREATE", p)
        }
        capability("shape.layout_info","读取矢量形状布局和剪切范围",read) {ArtShapeLayout.info()}
        capability("shape.align","对齐所选矢量形状",write) {p->store.apply("LANER","SHAPE_ALIGN",p)}
        capability("shape.distribute","分布所选矢量形状",write) {p->store.apply("LANER","SHAPE_DISTRIBUTE",p)}
        capability("shape.shear","剪切所选矢量形状",write) {p->store.apply("LANER","SHAPE_SHEAR",p)}
        capability("shape.select", "选择矢量对象", write) { p ->
            store.apply("LANER", "SHAPE_SELECT", p)
        }
        capability("shape.transform", "变换选定形状", write) { p ->
            store.apply("LANER", "SHAPE_TRANSFORM", p)
        }
        capability("shape.style", "设置基础形状样式", write) { p ->
            store.apply("LANER", "SHAPE_STYLE", p)
        }
        capability("shape.delete", "删除矢量对象", write) { p ->
            store.apply("LANER", "SHAPE_DELETE", p)
        }
        capability("layer.group", "创建画室图层组", write) { p ->
            p.put("id", UUID.randomUUID().toString()); store.apply("LANER", "GROUP_CREATE", p)
        }
        capability("selection.basic_info","读取基本软选区参数",read) {ArtSoftSelection.info()}
        capability("selection.adjust","羽化或扩展收缩当前选区",write) {p->store.adjustSelection("LANER",p)}
        capability("selection.coverage","读取选区坐标的覆盖率",read) {p->store.selectionCoverage(p)}
        capability("selection.create","创建矩形软选区",write) {p->store.basicSelection("LANER",p.put("shape","rect"))}
        capability("selection.freehand","创建自由套索软选区",write) {p->store.basicSelection("LANER",p.put("shape","freehand"))}
        capability("selection.polygon","创建多边形软选区",write) {p->store.basicSelection("LANER",p.put("shape","polygon"))}
        capability("selection.ellipse","创建椭圆软选区",write) {p->store.basicSelection("LANER",p.put("shape","ellipse"))}
        mapOf("layer.select" to "LAYER_SELECT", "layer.rename" to "LAYER_RENAME",
            "layer.move" to "LAYER_MOVE", "layer.delete" to "LAYER_DELETE",
            "layer.set_visibility" to "LAYER_VISIBLE", "layer.set_opacity" to "LAYER_OPACITY",
            "layer.set_lock" to "LAYER_LOCK", "layer.set_blend" to "LAYER_BLEND",
            "layer.properties" to "LAYER_PROPERTIES",
            "selection.clear" to "SELECTION_CLEAR",
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
            capability(name, label, write) { p -> store.apply("LANER", type, p) }
        }
        capability("layer.copy", "复制画室图层", write) { p ->
            p.put("newId", UUID.randomUUID().toString()); store.apply("LANER", "LAYER_COPY", p)
        }
        capability("layer.move_up", "上移同级图层", write) { p ->
            store.apply("LANER", "LAYER_MOVE_STEP", p.put("direction", "up"))
        }
        capability("layer.move_down", "下移同级图层", write) { p ->
            store.apply("LANER", "LAYER_MOVE_STEP", p.put("direction", "down"))
        }
        capability("path.info","读取栅格路径笔刷和多边形填充说明",read) {ArtRasterPath.info()}
        capability("path.geometry","计算路径控制点及笔刷轮廓采样",read) {p->store.rasterPathGeometry(p)}
        capability("path.draw","当前笔刷描栅格路径与多边形图案填充",write) {p->store.rasterPathDraw("LANER",p)}
        capability("figure.info","读取矩形椭圆约束与描边填充",read) {ArtFigure.info()}
        capability("figure.geometry","计算矩形椭圆边界及有效圆角",read) {p->store.figureGeometry(p)}
        capability("figure.draw","绘制矩形椭圆或可编辑矢量形状",write) {p->store.figureDraw("LANER",p)}
        capability("line.info","读取直线笔刷与交互参数",read) {ArtLine.info()}
        capability("line.geometry","计算直线端点与传感器轨迹",read) {p->store.lineGeometry(p)}
        capability("line.draw","以当前笔刷或矢量描边绘制直线",write) {p->store.lineDraw("LANER",p)}
        capability("dyna.info","读取动态画笔参数与处理顺序",read) {ArtDyna.info()}
        capability("dyna.stroke","使用共享笔刷绘制动态笔触",write) {p ->
            p.put("tool","dyna").put("id",UUID.randomUUID().toString());store.apply("LANER","STROKE_ADD",p)
        }
        capability("mirror.info","读取多重画笔参数与链路",read) {ArtMirror.info()}
        capability("mirror.stroke","使用笔刷引擎绘制多重笔触",write) {p ->
            p.put("tool","mirror").put("id",UUID.randomUUID().toString());store.apply("LANER","STROKE_ADD",p)
        }
        capability("brush.info","读取栅格笔刷引擎参数",read) {p -> ArtBrush.info(p.optString("tool","ink"))}
        capability("brush.presets","列出笔刷预设",read) {p -> store.brushPresets(p.optString("tool").takeIf {it.isNotBlank()})}
        capability("brush.preset.get","读取完整笔刷预设",read) {p -> store.brushPreset(p.getString("id"))}
        capability("brush.preset.save","保存自定义笔刷预设",write) {p -> store.saveBrushPreset(p)}
        capability("brush.preset.delete","删除自定义笔刷预设",write) {p -> store.deleteBrushPreset(p.getString("id"))}
        capability("brush.resources","列出图像笔尖和纹理",read) {p -> store.brushResources(p.optString("kind").takeIf {it.isNotBlank()})}
        capability("brush.resource.import","导入笔尖或纹理图像",write) {p -> store.importBrushResource(p)}
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
        capability("history.revert_actor_operations", "撤销兰儿指定操作", write) { p ->
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
        capability("edit.paste_new", "从剪贴板创建新工程", write) { p ->
            store.pasteAsNew("LANER", p.optJSONObject("confirmResize"))
        }
        capability("edit.clear", "清除选区像素", write) { store.editPixels("LANER", "CLEAR") }
        capability("edit.fill_foreground", "用前景色填充选区", write) { p ->
            store.editPixels("LANER", "FILL", p.getString("color"))
        }
        capability("edit.fill_background", "用指定背景色填充选区", write) { p ->
            store.editPixels("LANER", "FILL", p.getString("color"))
        }
        capability("image.formats", "读取画室图片格式支持", read) {
            ArtImageFormats.describe()
        }
        capability("image.limits", "读取图片尺寸与内存预算", read) {
            ArtImagePolicy.describe()
        }
        capability("image.import", "导入常用格式图片", write) { p ->
            store.importImage("LANER", p.getString("base64"), p.optJSONObject("confirmResize"))
        }
        capability("export.png", "导出 PNG", write) { p ->
            ArtRenderer.export(store, store.current(), "png", p.optString("name", ""), p)
        }
        capability("export.jpeg", "导出 JPEG", write) { p ->
            ArtRenderer.export(store, store.current(), "jpeg", p.optString("name", ""), p)
        }
        host.logger.i("ArtStudio", "Art Studio mounted")
        return InProcessPluginHandle { viewChannel.close(); host.logger.i("ArtStudio", "Art Studio stopped") }
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

internal fun parametersFor(name: String): List<InProcessCapabilityParameterSpec> {
    fun p(key: String, type: String = "string", optional: Boolean = false): InProcessCapabilityParameterSpec {
        return InProcessCapabilityParameterSpec(key, type, ArtCapabilityHelp.parameterDescription(name, key), !optional)
    }
    val id = p("id")
    fun gradientFields()=listOf(p("gradientMode",optional=true),p("gradientReverse","boolean",true),p("gradientEndColor",optional=true),
        p("gradientStops","array",true),p("gradientRepeat",optional=true),p("gradientDither","boolean",true),p("gradientSeed","integer",true),
        p("gradientInterpolation",optional=true),p("gradientAntialias","number",true))
    fun softSelectionFields()=listOf(p("antialias","number",true),p("feather","integer",true),p("expand","integer",true))
    fun colorSelectionFields()=listOf(p("opacitySpread","integer",true),p("antialias","number",true),p("feather","integer",true),p("stopAtDarkest","boolean",true),p("colorLabels","array",true))
    fun basicSelectionFields()=listOf(p("documentId",optional=true),p("expectedRevision","integer",true),p("mode",optional=true),p("antialias","number",true),p("feather","integer",true),p("expand","integer",true))
    fun figureFields()=listOf(p("fixedWidth","number",true),p("fixedHeight","number",true),p("fixedRatio","number",true),
        p("drawFromCenter","boolean",true),p("cornerRadius","number",true),p("outline",optional=true),p("figureFill","object",true))
    fun lineFields()=listOf(p("useSensors","boolean",true),p("angleStep","number",true),p("lineOffset","array",true))
    fun dynaFields()=listOf(p("mass","number",true),p("drag","number",true))
    fun mirrorFields()=listOf(p("brushTool",optional=true),p("mirrorAngle","number",true),p("mirrorDirection",optional=true),
        p("mirrorCount","integer",true),p("mirrorRadius","number",true),p("mirrorSeed","integer",true),
        p("mirrorCenters","array",true),p("mirrorIntervalX","integer",true),p("mirrorIntervalY","integer",true),
        p("axisX","number",true),p("axisY","number",true))
    return when (name) {
        "path.geometry" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("tool"),p("points","array"),p("outline",optional=true),p("figureFill","object",true),p("color",optional=true),p("brushTool",optional=true))
        "path.draw" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("tool"),p("points","array"),p("color"),p("width","number"),
            p("opacity","number",true),p("brushTool",optional=true),p("brush","object",true),p("brushPresetId",optional=true),p("brushSeed","integer",true),p("outline",optional=true),p("figureFill","object",true))
        "figure.geometry" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("tool"),p("points","array"))+figureFields()
        "figure.draw" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("tool"),p("points","array"),p("color"),p("width","number"),
            p("opacity","number",true),p("brushTool",optional=true),p("brush","object",true),p("brushPresetId",optional=true),p("brushSeed","integer",true))+figureFields()
        "line.geometry" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("points","array"),p("assistantId",optional=true))+lineFields()
        "line.draw" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("points","array"),p("color"),p("width","number"),
            p("opacity","number",true),p("brushTool",optional=true),p("brush","object",true),p("brushPresetId",optional=true),p("brushSeed","integer",true),p("assistantId",optional=true))+lineFields()
        "dyna.stroke" -> listOf(p("layerId"),p("points","array"),p("color"),p("width","number"),
            p("opacity","number",true),p("brushTool",optional=true),p("brush","object",true),p("brushPresetId",optional=true),
            p("brushSeed","integer",true),p("documentId",optional=true),p("expectedRevision","integer",true),p("assistantId",optional=true))+dynaFields()
        "mirror.stroke" -> listOf(p("layerId"),p("points","array"),p("color"),p("width","number"),
            p("opacity","number",true),p("brush","object",true),p("brushPresetId",optional=true),p("brushSeed","integer",true),
            p("documentId",optional=true),p("expectedRevision","integer",true))+mirrorFields()
        "brush.info","brush.presets" -> listOf(p("tool",optional=true))
        "brush.preset.get","brush.preset.delete" -> listOf(id)
        "brush.preset.save" -> listOf(p("id",optional=true),p("name"),p("tool"),p("brush","object"),
            p("width","number",true),p("opacity","number",true))
        "brush.resources" -> listOf(p("kind",optional=true))
        "brush.resource.import" -> listOf(p("kind"),p("base64"),p("name",optional=true))
        "layer.vector" -> listOf(p("documentId"), p("expectedRevision", "integer"),
            p("name", optional = true), p("parentId", optional = true), p("select", "boolean", true))
        "reference.list" -> listOf(p("documentId"))
        "reference.preview" -> listOf(p("documentId"),p("expectedRevision","integer",true))
        "reference.region" -> listOf(p("documentId"),id,p("x","integer"),p("y","integer"),
            p("width","integer"),p("height","integer"),p("maxEdge","integer",true),p("expectedRevision","integer",true))
        "reference.add" -> listOf(p("documentId"),p("expectedRevision","integer"),p("base64"),
            p("name",optional=true),p("matrix","array",true),p("confirmResize","object",true))
        "reference.select","reference.delete" -> listOf(p("documentId"),p("expectedRevision","integer"),p("ids","array"))
        "reference.transform" -> listOf(p("documentId"),p("expectedRevision","integer"),p("ids","array"),p("matrix","array"))
        "reference.style" -> listOf(p("documentId"),p("expectedRevision","integer"),p("ids","array"),p("style","object"))
        "reference.show" -> listOf(p("documentId"),p("expectedRevision","integer"),p("visible","boolean"))
        "selection.contiguous" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("x","integer"),p("y","integer"),
            p("mode",optional=true),p("reference",optional=true),p("tolerance","integer",true),p("expand","integer",true),
            p("gapClose","integer",true),p("boundaryMode","boolean",true),p("boundaryColor",optional=true),p("limitToSelection","boolean",true),p("bounds","object",true))+colorSelectionFields()
        "selection.similar" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("x","integer"),p("y","integer"),
            p("mode",optional=true),p("reference",optional=true),p("tolerance","integer",true),p("expand","integer",true),p("limitToSelection","boolean",true),p("bounds","object",true))+colorSelectionFields()
        "selection.magnetic_trace", "selection.magnetic_create" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("anchors","array"),
            p("mode",optional=true),p("reference",optional=true),p("filterRadius","integer",true),p("searchRadius","integer",true),
            p("threshold","integer",true),p("strength","number",true),p("precision","number",true),p("limitToSelection","boolean",true),p("bounds","object",true)) +
            if(name=="selection.magnetic_trace")listOf(p("closed","boolean",true)) else softSelectionFields()
        "comic.frame" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),
            p("x","number"),p("y","number"),p("width","number"),p("height","number"),p("style","object",true))
        "comic.cut" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("start","array"),p("end","array"),
            p("ids","array",true),p("automatic","boolean",true),p("preset",optional=true),
            p("thick","number",true),p("thin","number",true),p("special","number",true),
            p("horizontal",optional=true),p("vertical",optional=true),p("diagonal",optional=true),p("angle","number",true))
        "comic.merge" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("start","array"),p("end","array"),p("ids","array",true))
        "shape.list" -> listOf(p("documentId"), p("layerId"))
        "shape.hit" -> listOf(p("documentId"), p("layerId"), p("x", "number"), p("y", "number"), p("tolerance", "number", true))
        "shape.box" -> listOf(p("documentId"), p("layerId"), p("x", "number"), p("y", "number"),
            p("width", "number"), p("height", "number"), p("contained", "boolean", true))
        "path.topology" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),id,p("action"),p("at","array",true),p("first","array",true),p("second","array",true))
        "path.convert", "path.combine" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("ids","array"))
        "path.create" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),
            p("nodes","array"),p("closed","boolean",true),p("style","object",true))
        "path.nodes" -> listOf(p("documentId"),p("layerId"),id)
        "path.edit" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),id,p("edits","array"))
        "shape.calligraphy" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),
            p("samples","array"),p("width","number",true),p("angle","number",true),
            p("fixation","number",true),p("thinning","number",true),p("smoothing","number",true),
            p("usePressure","boolean",true),p("cap",optional=true),p("color",optional=true),p("opacity","number",true),
            p("mass","number",true),p("drag","number",true),p("useTilt","boolean",true),p("followPath","boolean",true),
            p("followPathId",optional=true),p("followSubpath","integer",true),p("followReverse","boolean",true),p("profileId",optional=true))
        "calligraphy.profile.get", "calligraphy.profile.delete" -> listOf(id)
        "calligraphy.profile.save" -> listOf(p("id",optional=true),p("name"),p("settings","object"))
        "shape.freehand" -> listOf(p("documentId"), p("expectedRevision", "integer"), p("layerId"),
            p("points", "array"), p("mode", optional = true), p("precision", "number", true),
            p("closed", "boolean", true), p("style", "object", true),p("optimizeRaw","boolean",true),p("rawPrecision","number",true),
            p("optimizeCurve","boolean",true),p("curvePrecision","number",true),p("combineAngle","number",true),p("startEndpoint","object",true),p("endEndpoint","object",true))
        "shape.create" -> listOf(p("documentId"), p("expectedRevision", "integer"), p("layerId"), p("shape", "object"))
        "shape.select", "shape.delete" -> listOf(p("documentId"), p("expectedRevision", "integer"), p("layerId"), p("ids", "array"))
        "shape.align" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("ids","array"),p("mode"),p("reference",optional=true))
        "shape.distribute" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("ids","array"),p("mode"))
        "shape.shear" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("ids","array"),p("shearX","number"),p("shearY","number"),p("pivot","array",true))
        "shape.transform" -> listOf(p("documentId"), p("expectedRevision", "integer"), p("layerId"), p("ids", "array"), p("matrix", "array"))
        "shape.style" -> listOf(p("documentId"), p("expectedRevision", "integer"), p("layerId"), p("ids", "array"), p("style", "object"))
        "text.create", "text.update" -> listOf(p("documentId"), p("expectedRevision", "integer"),
            p("content"), p("fontId", optional = true), p("fontSize", "number", true),
            p("boxWidth", "integer", true), p("lineSpacing", "number", true), p("align", optional = true),
            p("color", optional = true), p("x", "number", true), p("y", "number", true)) +
            if (name == "text.update") listOf(id) else emptyList()
        "menu.execute" -> listOf(p("action"), p("parameters", "object", true),
            p("documentId", optional = true), p("expectedRevision", "integer", true))
        "image.set_background" -> listOf(p("color"))
        "image.resize_canvas" -> listOf(p("width", "integer"), p("height", "integer"),
            p("offsetX", "integer", true), p("offsetY", "integer", true))
        "dock.command" -> listOf(p("command"), p("panel", optional = true), p("enabled", "boolean", true))
        "view.set" -> listOf(p("option"), p("enabled", "boolean"))
        "view.state" -> listOf(p("target", optional = true))
        "view.command" -> listOf(p("command"), p("target", optional = true))
        "colorize.list" -> listOf(p("documentId"),p("expectedRevision","integer",true),p("maskId",optional=true),p("includeKeys","boolean",true))
        "colorize.preview" -> listOf(p("documentId"),p("expectedRevision","integer",true),p("maskId",optional=true))
        "colorize.create" -> listOf(p("documentId"),p("expectedRevision","integer"),p("sourceLayerId"),p("name",optional=true))
        "colorize.stroke" -> listOf(p("documentId"),p("expectedRevision","integer"),p("maskId"),
            p("points","array"),p("width","number"),p("color",optional=true),p("erase","boolean",true))
        "colorize.remove_stroke" -> listOf(p("documentId"),p("expectedRevision","integer"),p("maskId"),p("strokeId"))
        "colorize.palette" -> listOf(p("documentId"),p("expectedRevision","integer"),p("maskId"),
            p("color"),p("action"),p("transparent","boolean",true))
        "colorize.settings" -> listOf(p("documentId"),p("expectedRevision","integer"),p("maskId"),p("settings","object"))
        "colorize.clear","colorize.update","colorize.convert" -> listOf(p("documentId"),p("expectedRevision","integer"),p("maskId"))
        "selection.basic_info", "selection.bezier_info" -> emptyList()
        "selection.adjust" -> listOf(p("documentId"),p("expectedRevision","integer"),p("expand","integer",true),p("feather","integer",true))
        "selection.coverage" -> listOf(p("documentId"),p("expectedRevision","integer"),p("x","number"),p("y","number"))
        "selection.bezier_create" -> listOf(p("documentId"),p("expectedRevision","integer"),p("nodes","array"),p("mode",optional=true))+softSelectionFields()
        "selection.bezier_nodes" -> listOf(p("documentId"),p("expectedRevision","integer",true),p("componentIndex","integer",true))
        "selection.bezier_edit" -> listOf(p("documentId"),p("expectedRevision","integer"),p("edits","array"),p("componentIndex","integer",true))
        "selection.preview" -> listOf(p("documentId"),p("expectedRevision","integer",true))
        "gradient.info" -> emptyList()
        "gradient.draw" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("points","array"),p("color"),p("opacity","number",true))+gradientFields()
        "enclose.info" -> emptyList()
        "enclose.apply" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("shape"),
            p("points","array",true),p("nodes","array",true),p("color",optional=true),p("mode",optional=true),p("regionColor",optional=true),
            p("tolerance","integer",true),p("includeContour","boolean",true),p("invert","boolean",true),
            p("reference",optional=true),p("width","integer",true),p("opacity","number",true),
            p("erase","boolean",true),p("expand","integer",true),p("gapClose","integer",true),
            p("fillType",optional=true),p("pattern","object",true),p("blend",optional=true))+colorSelectionFields()
        "patch.info" -> emptyList()
        "patch.apply" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),
            p("points","array"),p("width","number"),p("patchRadius","integer",true),
            p("accuracy","integer",true),p("searchRadius","integer",true),p("feather","integer",true),
            p("levels","integer",true),p("refinementStep","integer",true),p("seed","integer",true))
        "assistant.list", "assistant.preview" -> listOf(p("documentId"),p("expectedRevision","integer",true))
        "assistant.create" -> listOf(p("documentId"),p("expectedRevision","integer"),p("type"),p("points","array"),p("name",optional=true))
        "assistant.select", "assistant.delete" -> listOf(p("documentId"),p("expectedRevision","integer"),id)
        "assistant.update" -> listOf(p("documentId"),p("expectedRevision","integer"),id,p("changes","object"))
        "assistant.settings" -> listOf(p("documentId"),p("expectedRevision","integer"),p("settings","object"))
        "assistant.project" -> listOf(p("documentId"),p("expectedRevision","integer"),id,p("points","array"))
        "assistant.stroke" -> listOf(p("documentId"),p("expectedRevision","integer"),id,p("layerId"),p("points","array"),p("width","number"),
            p("tool",optional=true),p("color",optional=true),p("opacity","number",true),p("nibAngle","number",true),
            p("brush","object",true),p("brushPresetId",optional=true),p("brushSeed","integer",true))+mirrorFields()+dynaFields()+lineFields()
        "view.tool_options" -> listOf(p("action"), p("toolId", optional = true),
            p("xDp", "number", true), p("yDp", "number", true))
        "view.zoom_tool" -> listOf(p("mode"))
        "view.zoom" -> listOf(p("documentId"), p("percent", "number"), p("target", optional = true))
        "view.presentation" -> listOf(p("mode"))
        "document.create" -> listOf(p("width", "integer"), p("height", "integer"),
            p("background", optional = true), p("name", optional = true))
        "document.import" -> listOf(p("base64"))
        "document.open_image" -> listOf(p("base64"), p("name", optional = true), p("confirmResize", "object", true))
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
            p("baseColor", optional = true), p("sampleMerged", "boolean", true),
            p("layerId", optional = true))
        "canvas.measure" -> listOf(p("x0", "number"), p("y0", "number"),
            p("x1", "number"), p("y1", "number"))
        "fill.info" -> emptyList()
        "fill.contiguous" -> listOf(p("documentId",optional=true),p("expectedRevision","integer",true),p("layerId",optional=true),
            p("x","integer",true),p("y","integer",true),p("points","array",true),p("color"),p("tolerance","integer",true),
            p("referenceAllLayers","boolean",true),p("reference",optional=true),p("erase","boolean",true),p("opacity","number",true),
            p("fillMode",optional=true),p("dragMode",optional=true),p("fillType",optional=true),p("pattern","object",true),
            p("boundaryColor",optional=true),p("gapClose","integer",true),p("useSelectionAsBoundary","boolean",true),p("bounds","object",true),
            p("expand","integer",true))+colorSelectionFields()

        "layer.rename" -> listOf(id, p("name"))
        "layer.move" -> listOf(id, p("index", "integer"))
        "layer.set_visibility" -> listOf(id, p("visible", "boolean"))
        "layer.set_opacity" -> listOf(id, p("opacity", "number"))
        "layer.set_blend" -> listOf(id, p("blend"))
        "layer.properties" -> listOf(id, p("name", optional = true),
            p("opacity", "number", true), p("blend", optional = true),
            p("visible", "boolean", true), p("locked", "boolean", true),p("colorLabel","integer",true))
        "stroke.add" -> listOf(p("layerId"), p("points", "array"), p("color"), p("width", "number"),
            p("opacity", "number", true), p("tool", optional = true),
            p("brush","object",true),p("brushPresetId",optional=true),p("brushSeed","integer",true),
            p("fillShape", "boolean", true), p("gradientMode", optional = true),
            p("gradientReverse", "boolean", true),
            p("gradientEndColor", optional = true),p("gradientStops","array",true),p("gradientRepeat",optional=true),
            p("gradientDither","boolean",true),p("gradientSeed","integer",true),p("gradientInterpolation",optional=true),p("gradientAntialias","number",true),
            p("mirrorDirection", optional = true), p("mirrorCount", "integer", true),
            p("mirrorRadius", "number", true), p("mirrorSeed", "integer", true),
            p("mirrorCenters", "array", true),
            p("mirrorIntervalX", "integer", true), p("mirrorIntervalY", "integer", true),
            p("axisX", "number", true),
            p("axisY", "number", true), p("mass", "number", true),
            p("drag", "number", true), p("nibAngle", "number", true),p("assistantId",optional=true))+mirrorFields()+dynaFields()+lineFields()+figureFields()
        "stroke.erase" -> listOf(p("layerId"), p("strokeId"))
        "selection.create", "selection.ellipse" -> listOf(p("x", "number"), p("y", "number"),
            p("width", "number"), p("height", "number"))+basicSelectionFields()
        "selection.polygon", "selection.freehand" -> listOf(p("points", "array"))+basicSelectionFields()
        "selection.edit" -> listOf(p("layerId"), p("action"), p("dx", "number", true),
            p("dy", "number", true), p("factor", "number", true), p("degrees", "number", true),
            p("copyId", optional = true))
        "canvas.crop" -> listOf(p("width", "integer"), p("height", "integer"),
            p("x", "number", true), p("y", "number", true))
        "transform.move" -> listOf(id, p("x", "number"), p("y", "number"))
        "transform.scale" -> listOf(id, p("scale", "number"))
        "transform.rotate" -> listOf(id, p("rotation", "number"))
        "history.goto" -> listOf(id, p("expectedRevision", "integer"))
        "history.revert_actor_operations" -> listOf(id)
        "storage.set_directory" -> listOf(p("directory"))
        "canvas.region" -> listOf(p("x", "integer"), p("y", "integer"), p("width", "integer"),
            p("height", "integer"), p("maxEdge", "integer", true),p("selectionOutline","boolean",true),
            p("documentId", optional = true), p("expectedRevision", "integer", true))
        "edit.paste_new" -> listOf(p("confirmResize", "object", true))
        "image.import" -> listOf(p("base64"), p("confirmResize", "object", true))
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
    }.distinctBy { it.name }
}
