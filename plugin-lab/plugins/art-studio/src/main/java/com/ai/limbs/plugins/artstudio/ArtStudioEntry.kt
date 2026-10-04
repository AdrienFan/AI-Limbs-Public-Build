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
        val interactions = ArtStudioInteractions(host)
        host.registerProvider(ART_INTERACTIONS, interactions, mapOf("kind" to "ui_state"))
        val extensionMenus = ArtStudioExtensionMenus(host.scope,
            reportError = { id, error -> host.logger.e("ArtStudio", "Invalid menu from extension $id", error) },
            recordUse = { id -> host.childExtensions.recordUse(id) }, bindInteractive = interactions::bind)
        host.registerProvider(ART_EXTENSION_MENUS, extensionMenus, mapOf("kind" to "ui_state"))

        fun viewTarget(p: JSONObject): String = p.optString("target", "assistant").also {
            require(it in setOf("assistant", "phone")) { "target 必须是 assistant 或 phone" }
        }
        host.registerProvider(ART_VIEW_CONTROL, viewChannel,
            mapOf("kind" to "ui_state"))
        val page = ArtStudioPage(host)
        host.registerProvider(ART_PAGE, page,
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
                // Validate response policy before applying an edit; format only after feedback.
                val responseMode = if (ArtCapabilityReply.supports(name)) ArtCapabilityReply.mode(parameters) else "full"
                val requestId = if (ArtCapabilityReply.tracksRequest(name) && parameters.has("requestId"))
                    ArtOperationReceipt.validate(parameters.getString("requestId")) else null
                val business = JSONObject(parameters.toString()).apply {
                    if (ArtCapabilityReply.supports(name)) remove("responseMode")
                    if (ArtCapabilityReply.tracksRequest(name)) remove("requestId")
                }
                fun execute(): JSONObject = if (requestId != null)
                    store.withCapabilityRequest(business.getString("documentId"), requestId) { block(business) }
                    else block(business)
                try {
                    fun executeWithFeedback():JSONObject = if (ArtCanvasFeedback.affectsCanvas(name, business)) {
                        val animationFrames=if(name=="animation.poses.apply") {
                            val poses=business.getJSONArray("poses")
                            (0 until poses.length()).map {poses.getJSONObject(it).getInt("frame")}
                        } else emptyList()
                        store.withCanvasFeedback(animationFrames) {
                            val result=execute()
                            if(name.startsWith("reference.")) result.put("referenceFeedback",true)
                            if(name.startsWith("assistant.")) result.put("assistantFeedback",true)
                            if(name.startsWith("selection."))result.put("selectionFeedback",true)
                            if(name.startsWith("colorize.")) {
                                result.put("colorizeFeedback",true)
                                if(parameters.has("maskId"))result.put("colorizeMaskId",parameters.getString("maskId"))
                            }
                            result
                        }
                    } else execute()
                    val result=if(responseMode=="receipt")store.withCompactSnapshots {executeWithFeedback()}
                        else executeWithFeedback()
                    if (requestId != null) result.put("requestId", requestId)
                    if(name=="svg.apply") ArtSvgReceipt.format(result,responseMode)
                    else ArtCapabilityReply.format(result,responseMode)
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
        capability("text.info", "读取文字排版与 SVG 支持范围", read) { ArtTextSpec.info() }
        capability("text.source", "读取文字原文与样式", read) { p -> store.textSource(p) }
        capability("text.geometry", "读取文字可引用的矢量形状快照", read) { p -> store.textGeometry(p) }
        capability("text.svg_validate", "检查 SVG 文字源码", read) { p ->
            val source=JSONObject(p.toString()).put("sourceMode","svg")
            JSONObject().put("valid",true).put("text",ArtText.prepare(source)).put("rendered",false)
        }
        capability("text.fonts", "读取文字可用字体", read) {
            ArtText.fonts()
        }
        capability("text.create", "添加可编辑富文本或 SVG 文字", write) { p ->
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
            for(key in ArtAssistants.editableFields-"points")if(p.has(key))a.put(key,p.get(key))
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
            ArtToolCatalog.describe(viewChannel.describe()).put("quickTools", store.quickToolsState())
        }
        capability("quick_tools.state", "读取快捷工具配置", read) { store.quickToolsState() }
        capability("quick_tools.configure", "配置快捷工具位置", write) { p -> store.configureQuickTools(p) }
        registerCapability("quick_tools.use", "使用已配置快捷工具", InProcessCapabilityEffect.UI_INTERACTION) { p ->
            val tool = store.quickToolTarget(p)
            viewChannel.execute("tool_select", JSONObject().put("toolId", tool))
                .put("slotId", p.getString("slotId")).put("configRevision", p.getLong("expectedConfigRevision"))
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
        capability("document.summary", "读取工程紧凑摘要", read) { store.summary() }
        capability("document.snapshot.read", "分页读取完整工程快照", read) { p ->
            store.snapshotPage(p.getString("documentId"), p.getInt("expectedRevision"),
                p.optInt("offset", 0), p.optInt("limit", 8000), p.optString("expectedSha256", ""))
        }
        capability("document.operation.status", "确认编辑请求是否已提交", read) { p ->
            store.operationStatus(p.getString("documentId"), p.getString("requestId"))
        }
        capability("document.list", "列出画室工程", read) { JSONObject().put("documents", store.list()) }
        capability("canvas.inspect", "查看画布结构", read) { store.current() }
        capability("canvas.region", "查看画布局部放大图", read) { p ->
            store.canvasRegion(p.getInt("x"), p.getInt("y"), p.getInt("width"),
                p.getInt("height"), p.optInt("maxEdge", 512),
                if (p.has("documentId")) p.getString("documentId") else null,
                if (p.has("expectedRevision")) p.getInt("expectedRevision") else null,p.optBoolean("selectionOutline",false))
        }
        capability("crop.info", "读取裁剪交互、边界及帧依赖", read) { ArtCrop.info() }
        capability("crop.geometry", "解析受约束裁剪框", read) { p -> store.cropGeometry(p) }
        capability("crop.preview", "查看裁剪框与构图线预览", read) { p -> store.cropGeometry(p,preview=true) }
        capability("crop.apply", "确认裁剪画布或图层边界", write) { p -> store.crop("LANER",p) }
        capability("canvas.crop", "裁切画布（兼容原入口）", write) { p -> store.apply("LANER","CROP",p) }
        capability("move.info", "读取移动范围、拾取和单位约束", read) { ArtMove.info().put("settings",store.moveSettings()) }
        capability("move.settings", "读取移动工具设置", read) { store.moveSettings() }
        capability("move.configure", "保存移动工具设置", write) { p -> store.configureMove(p) }
        capability("move.hit", "按可见像素拾取图层或所属组", read) { p -> store.moveHit(p) }
        capability("move.apply", "移动图层或真正搬移选区像素", write) { p -> store.move("LANER",p) }
        capability("move.nudge", "按移动工具键盘步进平移", write) { p -> store.moveNudge("LANER",p) }
        capability("svg.info","读取SVG场景格式与限制",read){ArtSceneSvg.info()}
        capability("svg.read","读取全图或指定对象SVG代码",read){p->store.svgRead(p)}
        capability("svg.validate","校验SVG代码与文档版本",read){p->store.svgValidate(p)}
        capability("svg.preview","预览SVG草稿，不写历史",read){p->store.svgApply("LANER",p,true)}
        capability("svg.apply","将SVG代码应用到原生画布",write){p->store.svgApply("LANER",p)}
        capability("svg.select","按稳定SVG对象ID选择画布对象",write){p->store.svgSelect("LANER",p)}
        capability("svg.hit","按画布像素定位SVG对象",read){p->store.svgHit(p)}
        capability("transform.info","读取图层和像素变形范围",read){ArtTransform.info()}
        capability("transform.geometry","解析变形输出框和网格",read){p->store.transformGeometry(p)}
        capability("transform.affine","无损非等比缩放、剪切及自由变换",write){p->store.transformAffine("LANER",p)}
        capability("transform.apply","确认透视、扭曲、笼形、液化、网格或选区像素变换",write){p->store.transformPixels("LANER",p)}
        capability("measure.info","读取测量单位、基线与交互说明",read){ArtMeasure.info()}
        capability("measure.settings","读取共享测量设置",read){store.measureSettings()}
        capability("measure.configure","保存测量单位与角度约束",write){p->store.configureMeasure(p)}
        capability("measure.line","换算、约束和整线平移测量线",read){p->ArtMeasure.evaluate(p,store.measureSettings())}
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
        capability("color.info", "读取取色目标、图层投影和调色板边界", read) { ArtColorSampler.info() }
        capability("color.state", "读取共享前景背景颜色及调色板", read) { store.colorState() }
        capability("color.set", "设置共享前景或背景颜色", write) { p -> store.setColor(p) }
        capability("color.palette.list", "列出指定取色调色板", read) { store.colorState() }
        capability("color.palette.save", "新建或编辑调色板", write) { p -> store.saveColorPalette(p) }
        capability("color.palette.delete", "删除取色调色板", write) { p -> store.deleteColorPalette(p.getString("id")) }
        capability("color.sample", "只读取合成或指定层颜色", read) { p -> store.sampleColor(p) }
        capability("color.pick", "取色到前景背景或指定调色板", write) { p -> store.sampleColor(p, pick = true) }

        capability("layer.list", "列出画室图层", read) {
            val snapshot = store.current()
            snapshot.getJSONObject("state").put("revision", snapshot.getInt("revision"))
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
        capability("animation.info","读取动画时间轴说明",read) {
            JSONObject().put("frameMin",0).put("frameMax",ArtAnimation.MAX_TIME).put("fpsMin",1).put("fpsMax",60)
                .put("maxPlaybackFrames",600).put("maxKeysPerLayer",128).put("maxKeysPerDocument",ArtAnimation.MAX_KEYS)
                .put("supportedLayers",JSONArray(listOf("paint","image","vector"))).put("holdEditsSourceKey",true)
                .put("gifMaxEdge",1024).put("gifPixelBudget",32L*1024*1024).put("gifPalette","adaptive-global-255")
                .put("gifCompression", "dictionary-lzw").put("gifCoalesceHolds", true).put("gifOpaqueDeltaRectangles", true)
                .put("gifAlphaThreshold",128).put("unimplemented",JSONArray(listOf("audio","curves","video","frame-sequence-import")))
        }
        capability("animation.timeline","读取动画时间轴",read) {store.animationTimeline()}
        capability("animation.configure","设置动画播放范围与帧率",write) {p->store.animationConfigure("LANER",p)}
        capability("animation.keyframe","编辑动画关键帧",write) {p->store.animationKey("LANER",p)}
        capability("animation.seek","定位当前动画帧",write) {p->store.animationSeek("LANER",p)}
        capability("animation.pose.read", "读取来源帧的紧凑姿态", read) { p -> store.animationPose(p) }
        capability("animation.poses.apply", "原子提交动画姿态序列", write) { p -> store.animationPoses("LANER", p) }
        capability("animation.preview","预览指定动画帧",read) {p->
            val frame=store.animationSnapshot(p)
            val image=ArtRenderer.render(store,frame,maxEdge=p.optInt("maxEdge",512))
            try {
                val bytes=ArtImagePolicy.encodePng(image,8*1024*1024)
                JSONObject().put("documentId",frame.getString("id")).put("revision",frame.getInt("revision"))
                    .put("frame",p.getInt("frame")).put("width",image.width).put("height",image.height)
                    .put("mcp_content",JSONArray().put(JSONObject().put("type","image").put("mimeType","image/png")
                        .put("data",Base64.encodeToString(bytes,Base64.NO_WRAP))))
            } finally {image.recycle()}
        }
        capability("animation.export","导出GIF动画",write) {p->store.animationExport(p)}
        capability("history.list", "列出画室操作历史", read) {
            store.historyOperations()
        }
        capability("history.timeline", "读取足迹状态列表", read) { p -> store.historyTimeline(p) }
        capability("history.entry", "读取指定一笔足迹", read) { p -> store.historyTimeline(p) }
        capability("history.goto", "切换到指定足迹状态", write) { p ->
            store.historyJump("LANER", p.getString("id"), p.getInt("expectedRevision"))
        }
        capability("history.delete", "仅删除足迹对应的独立绘制对象", write) {p->store.historyDelete("LANER",p)}
        capability("layer.create", "创建画室绘画图层", write) { p ->
            p.put("id", UUID.randomUUID().toString()); store.apply("LANER", "LAYER_CREATE", p)
        }
        capability("layer.vector", "创建基础矢量图层", write) { p ->
            p.put("id", UUID.randomUUID().toString()); store.apply("LANER", "VECTOR_LAYER_CREATE", p)
        }
        capability("reference.info","读取参考导入与集合规则",read) { ArtReferenceFiles.info() }
        capability("reference.paste","粘贴图片为参考",write) {p->store.referencePaste("LANER",p)}
        capability("reference.link","导入外部参考图片链接",write) {p->store.referenceLink("LANER",p)}
        capability("reference.refresh","刷新外部参考图片",write) {p->store.referenceRefresh("LANER",p)}
        capability("reference.embed","参考链接转为内嵌",write) {p->store.apply("LANER","REFERENCE_EMBED",p)}
        capability("reference.capture","从图层或画布生成参考",write) {p->store.referenceCapture("LANER",p)}
        capability("reference.collection_import","导入参考图集合",write) {p->store.referenceCollectionImport("LANER",p)}
        capability("reference.collection_export","导出参考图集合",write) {p->store.referenceCollectionExport(p)}
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
            "selection.edit" to "SELECTION_EDIT").forEach { (name, type) ->
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
        capability("stroke.batch", "原子批量落笔", write) { p -> store.strokeBatch("LANER", p) }
        capability("stroke.budget", "预检批量笔触数量", read) { p -> store.strokeBudget(p) }
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
            val ops = store.historyOperations().getJSONArray("operations")
            require((0 until ops.length()).any {
                val op = ops.getJSONObject(it)
                op.getString("id") == id && op.getString("actor") == "LANER" &&
                    op.getString("type") !in setOf("REVERT", "RESTORE", "ANIMATION_TIME")
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
            store.exportImage("png", p.optString("name", ""), p)
        }
        capability("export.jpeg", "导出 JPEG", write) { p ->
            store.exportImage("jpeg", p.optString("name", ""), p)
        }
        capability("export.image", "按格式导出当前帧", write) { p ->
            p.getString("documentId"); p.getInt("expectedRevision")
            store.exportImage(p.getString("format"), p.optString("name", ""), p)
        }
        val extensionPoint = host.childExtensions.publishPoint(ART_EXTENSION_POINT, ART_EXTENSION_API,
            "画室工具扩展", description = "提供画室工具菜单项及子插件自己的点击事件",
            allowedHostCapabilities = emptySet(), binder = ChildExtensionBinder(extensionMenus::bind))
        host.logger.i("ArtStudio", "Art Studio mounted")
        return InProcessPluginHandle {
            try { extensionPoint.close() } finally {
                try { extensionMenus.close() } finally {
                    try { interactions.close() } finally {
                        try { viewChannel.close() } finally { page.close() }
                    }
                }
            }
            host.logger.i("ArtStudio", "Art Studio stopped")
        }
    }
}

class ArtStudioPresentationEntry : InProcessPluginPresentationEntry {
    override suspend fun mount(host: InProcessPluginPresentationHost): InProcessPluginPresentationHandle {
        require(host.pluginId == ART_ID)
        val page = ArtStudioPage(host)
        val registration = host.registerPageProvider(ART_PAGE, page,
            mapOf("kind" to "plugin_page", "screen_id" to ART_SCREEN))
        return InProcessPluginPresentationHandle {
            try { registration.close() } finally { page.close() }
        }
    }
}

internal fun parametersFor(name: String): List<InProcessCapabilityParameterSpec> {
    fun p(key: String, type: String = "string", optional: Boolean = false): InProcessCapabilityParameterSpec {
        return InProcessCapabilityParameterSpec(key, type, ArtCapabilityHelp.parameterDescription(name, key), !optional)
    }
    fun transformAffineFields()=listOf(p("scaleX","number",true),p("scaleY","number",true),p("shearX","number",true),p("shearY","number",true),p("rotation","number",true),p("pivotX","number",true),p("pivotY","number",true),p("dx","number",true),p("dy","number",true))
    fun measureFields()=listOf(p("unit",optional=true),p("ppi","number",true),p("baseline","number",true),p("angleStep","number",true),p("dragMode",optional=true))
    fun moveSettingsFields()=listOf(
        p("layerMode",optional=true),p("moveScope",optional=true),p("unit",optional=true),p("ppi","number",true),
        p("step","number",true),p("largeMultiplier","number",true),p("alphaThreshold","integer",true),p("ignoreLocked","boolean",true))
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
        "reference.info" -> emptyList()
        "reference.paste" -> listOf(p("documentId"),p("expectedRevision","integer"),p("base64",optional=true),
            p("name",optional=true),p("matrix","array",true),p("confirmResize","object",true))
        "reference.link" -> listOf(p("documentId"),p("expectedRevision","integer"),p("location"),p("embedded","boolean",true),
            p("base64",optional=true),p("name",optional=true),p("matrix","array",true),p("confirmResize","object",true))
        "reference.refresh" -> listOf(p("documentId"),p("expectedRevision","integer"),id,p("base64",optional=true),p("confirmResize","object",true))
        "reference.embed" -> listOf(p("documentId"),p("expectedRevision","integer"),p("ids","array"))
        "reference.capture" -> listOf(p("documentId"),p("expectedRevision","integer"),p("source"),p("layerId",optional=true),
            p("name",optional=true),p("matrix","array",true),p("maxEdge","integer",true),p("confirmResize","object",true))
        "reference.collection_import" -> listOf(p("documentId"),p("expectedRevision","integer"),p("base64"),p("keepLinks","boolean",true))
        "reference.collection_export" -> listOf(p("documentId"),p("expectedRevision","integer"),p("ids","array",true),
            p("keepLinks","boolean",true),p("fileName",optional=true))
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
        "text.info" -> emptyList()
        "text.source" -> listOf(p("documentId"),id)
        "text.geometry" -> listOf(p("documentId"))
        "text.svg_validate" -> listOf(p("svgSource"),p("fontId",optional=true),p("fontSize","number",true),p("boxWidth","integer",true))
        "text.create", "text.update" -> listOf(p("documentId"), p("expectedRevision", "integer"),
            p("content",optional=true),p("sourceMode",optional=true),p("svgSource",optional=true),p("spans","array",true),
            p("fontId", optional = true), p("fontSize", "number", true),
            p("boxWidth", "integer", true), p("lineSpacing", "number", true), p("align", optional = true),
            p("color", optional = true),p("strokeColor",optional=true),p("strokeWidth","number",true),
            p("letterSpacing","number",true),p("wordSpacing","number",true),p("baselineShift","number",true),
            p("underline","boolean",true),p("strike","boolean",true),p("language",optional=true),p("fontFeatures",optional=true),
            p("writingMode",optional=true),p("textOrientation",optional=true),p("direction",optional=true),
            p("textPath","object",true),p("shapeInside","object",true),p("clearGeometry","boolean",true),
            p("x", "number", true), p("y", "number", true)) + if (name == "text.update") listOf(id) else emptyList()
        "menu.execute" -> listOf(p("action"), p("parameters", "object", true),
            p("documentId", optional = true), p("expectedRevision", "integer", true))
        "image.set_background" -> listOf(p("color"))
        "image.resize_canvas" -> listOf(p("width", "integer"), p("height", "integer"),
            p("offsetX", "integer", true), p("offsetY", "integer", true))
        "quick_tools.state" -> emptyList()
        "quick_tools.configure" -> listOf(p("action"), p("expectedConfigRevision", "integer"),
            p("slotId", optional = true), p("toolId", optional = true))
        "quick_tools.use" -> listOf(p("slotId"), p("expectedConfigRevision", "integer"))
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
        "assistant.create" -> listOf(p("documentId"),p("expectedRevision","integer"),p("type"),p("points","array"),p("name",optional=true),
            p("visible","boolean",true),p("enabled","boolean",true),p("locked","boolean",true),p("subdivisions","integer",true),p("rays","integer",true),
            p("localEnabled","boolean",true),p("localBounds","object",true),p("fixedLength","number",true),p("lengthUnit",optional=true),
            p("unitDpi","number",true),p("useVertical","boolean",true))
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
        "color.info", "color.state", "color.palette.list" -> emptyList()
        "color.set" -> listOf(p("target"), p("color"))
        "color.palette.save" -> listOf(p("id", optional = true), p("name"), p("colors", "array", true))
        "color.palette.delete" -> listOf(p("id"))
        "color.sample", "color.pick" -> listOf(p("x", "integer"), p("y", "integer"),
            p("radius", "integer", true), p("blend", "integer", true),
            p("baseColor", optional = true), p("sampleMerged", "boolean", true),
            p("layerId", optional = true), p("documentId", optional = true), p("expectedRevision", "integer", true)) +
            if (name == "color.pick") listOf(p("target", optional = true), p("paletteId", optional = true)) else emptyList()
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
        "stroke.batch", "stroke.budget" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),
            p("strokes","array"),p("defaults","object",true))
        "history.timeline" -> listOf(p("documentId",optional=true),p("expectedRevision","integer",true),
            p("offset","integer",true),p("limit","integer",true),p("branch",optional=true),p("compact","boolean",true))
        "history.entry" -> listOf(p("documentId"),p("expectedRevision","integer"),p("id"),p("compact","boolean",true))
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
        "svg.info" -> emptyList()
        "svg.read" -> listOf(p("scope",optional=true),p("objectIds","array",true),p("documentId",optional=true),p("expectedRevision","integer",true),p("offset","integer",true),p("limit","integer",true),p("includeIndex","boolean",true))
        "svg.validate","svg.preview","svg.apply" -> listOf(p("source"),p("documentId"),p("expectedRevision","integer"),p("scope",optional=true),p("objectIds","array",true),p("newLayerName",optional=true))+if(name=="svg.apply")listOf(p("responseMode",optional=true)) else emptyList()
        "svg.select" -> listOf(p("documentId"),p("expectedRevision","integer"),p("objectIds","array"))
        "svg.hit" -> listOf(p("x","integer"),p("y","integer"),p("documentId",optional=true),p("expectedRevision","integer",true))
        "transform.info","measure.info","measure.settings" -> emptyList()
        "transform.affine" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"))+transformAffineFields()
        "transform.geometry","transform.apply" -> listOf(p("mode"),p("scope"),p("sourceBounds","object",true),p("points","array",true),p("sourcePoints","array",true),p("dabs","array",true),
            p("columns","integer",true),p("rows","integer",true),p("subdivisions","integer",true),p("gridResolution","integer",true),p("alpha","number",true),p("interpolation",optional=true))+transformAffineFields()+
            if(name=="transform.apply")listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("bake","boolean",true)) else listOf(p("documentId",optional=true),p("expectedRevision","integer",true))
        "measure.configure" -> measureFields()+p("expectedSettingsRevision","integer")
        "measure.line" -> listOf(p("x0","number"),p("y0","number"),p("x1","number"),p("y1","number"),p("dx","number",true),p("dy","number",true))+measureFields()
        "move.info", "move.settings" -> emptyList()
        "move.configure" -> moveSettingsFields()+p("expectedSettingsRevision","integer")
        "move.hit" -> listOf(p("x","integer"),p("y","integer"),p("layerMode",optional=true),p("alphaThreshold","integer",true),p("ignoreLocked","boolean",true),p("documentId",optional=true),p("expectedRevision","integer",true))
        "move.apply" -> listOf(p("dx","number"),p("dy","number"),p("documentId"),p("expectedRevision","integer"),p("layerId",optional=true),p("pickX","integer",true),p("pickY","integer",true))+moveSettingsFields()
        "move.nudge" -> listOf(p("direction"),p("large","boolean",true),p("documentId"),p("expectedRevision","integer"),p("layerId",optional=true))+moveSettingsFields()
        "crop.info" -> emptyList()
        "canvas.crop" -> listOf(p("width","integer"),p("height","integer"),p("x","number",true),p("y","number",true))
        "crop.geometry", "crop.preview", "crop.apply" -> listOf(p("width", "integer"),p("height", "integer"),
            p("x","integer",true),p("y","integer",true),p("target",optional=true),p("allowGrow","boolean",true),
            p("lockWidth","boolean",true),p("lockHeight","boolean",true),p("fixedWidth","integer",true),p("fixedHeight","integer",true),
            p("lockRatio","boolean",true),p("ratio","number",true),p("fromCenter","boolean",true),p("guides",optional=true),
            p("layerId",optional=true),p("documentId",optional=name!="crop.apply"),p("expectedRevision","integer",optional=name!="crop.apply")) +
            if(name=="crop.preview")listOf(p("maxEdge","integer",true)) else emptyList()
        "transform.move" -> listOf(id, p("x", "number"), p("y", "number"))
        "transform.scale" -> listOf(id, p("scale", "number"))
        "transform.rotate" -> listOf(id, p("rotation", "number"))
        "document.snapshot.read" -> listOf(p("documentId"), p("expectedRevision", "integer"),
            p("offset", "integer", true), p("limit", "integer", true), p("expectedSha256", optional = true))
        "document.operation.status" -> listOf(p("documentId"), p("requestId"))
        "animation.info", "animation.timeline" -> emptyList()
        "animation.configure" -> listOf(p("documentId"),p("expectedRevision","integer"),
            p("fps","integer",true),p("start","integer",true),p("end","integer",true),p("loop","boolean",true),p("onion","boolean",true))
        "animation.keyframe" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("frame","integer"),p("action"),
            p("sourceFrame","integer",true),p("targetFrame","integer",true))
        "animation.seek" -> listOf(p("documentId"),p("expectedRevision","integer"),p("frame","integer"))
        "animation.pose.read" -> listOf(p("documentId"), p("expectedRevision", "integer"), p("layerId"), p("sourceFrame", "integer"))
        "animation.poses.apply" -> listOf(p("documentId"), p("expectedRevision", "integer"), p("layerId"), p("sourceFrame", "integer"), p("poses", "array"), p("overwrite", "boolean", true))
        "animation.preview" -> listOf(p("documentId"),p("expectedRevision","integer"),p("frame","integer"),p("maxEdge","integer",true))
        "animation.export" -> listOf(p("documentId"),p("expectedRevision","integer"),p("maxEdge","integer",true))
        "history.goto" -> listOf(id, p("expectedRevision", "integer"))
        "history.delete" -> listOf(p("documentId"),id,p("expectedRevision","integer"))
        "history.revert_actor_operations" -> listOf(id)
        "storage.set_directory" -> listOf(p("directory"))
        "canvas.region" -> listOf(p("x", "integer"), p("y", "integer"), p("width", "integer"),
            p("height", "integer"), p("maxEdge", "integer", true),p("selectionOutline","boolean",true),
            p("documentId", optional = true), p("expectedRevision", "integer", true))
        "edit.paste_new" -> listOf(p("confirmResize", "object", true))
        "image.import" -> listOf(p("base64"), p("confirmResize", "object", true))
        "export.image" -> listOf(p("format"), p("documentId"), p("expectedRevision", "integer"), p("name", optional = true),
            p("x", "integer", true), p("y", "integer", true), p("cropWidth", "integer", true),
            p("cropHeight", "integer", true), p("width", "integer", true), p("height", "integer", true))
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
    }.let { fields ->
        fields + (if (ArtCapabilityReply.supports(name)) listOf(p("responseMode", optional = true)) else emptyList()) +
            (if (ArtCapabilityReply.tracksRequest(name)) listOf(p("requestId", optional = true)) else emptyList())
    }.distinctBy { it.name }
}
