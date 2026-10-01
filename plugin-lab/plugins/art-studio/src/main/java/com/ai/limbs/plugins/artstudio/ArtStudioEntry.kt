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

        fun registerCapability(name: String, title: String, effect: InProcessCapabilityEffect,
                       description: String = title, block: suspend (JSONObject) -> JSONObject) {
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
        fun capability(name: String, title: String, effect: InProcessCapabilityEffect,
                       description: String = title, block: (JSONObject) -> JSONObject) {
            registerCapability(name, title, effect,
                description + " 成功改变画布时在原结果中自动附带 thumbnail 元数据和 mcp_content 图片块；无需另取缩略图。") { parameters ->
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
        capability("menu.catalog", "读取画室完整菜单清单", read,
            "读取从图层到帮助七栏的菜单树、参数、当前可用状态与灰色原因；手机页面使用同一清单。") {
            ArtStudioMenuCatalog.describe(store.menuContext())
        }
        capability("menu.execute", "执行画室菜单操作", write,
            "action 使用 menu.catalog 的真实叶子 ID；parameters 使用该项参数。documentWrite=true 时必须传 documentId 与 expectedRevision；未实现项会拒绝。两位协作者共用业务实现、文件锁与撤销历史。图片导入超预算返回 needs_confirmation 与 imagePlan；取得用户同意后在 parameters.confirmResize 原样传回 imagePlan.confirmation。停靠菜单支持 enabled 显隐开关；结果返回 accepted 与已保存的 dockPanels，显示请求在页面打开时消费。") { p ->
            val arguments = p.optJSONObject("parameters")?.let { JSONObject(it.toString()) } ?: JSONObject()
            if (p.has("documentId")) arguments.put("documentId",p.getString("documentId"))
            if (p.has("expectedRevision")) arguments.put("expectedRevision",p.getInt("expectedRevision"))
            store.executeMenu("LANER",p.getString("action"),arguments)
        }
        capability("dock.state", "读取画室停靠面板状态", read,
            "visible 是四个面板的显示状态；activePane 为当前展开面板或 null；allCollapsed/restorePane 为总标题折叠恢复状态；confirmClose 为关闭提示偏好。与手机页面共享并持久保存。") {
            store.dockPanelState()
        }
        capability("dock.command", "操作画室停靠面板", write,
            "command: set_visible（panel+enabled）、expand/collapse（panel）、collapse_all/restore、set_confirmation（enabled）。panel: color/layers/brushes/footprints。隐藏移除标题与内容；折叠保留标题；显示或展开会请求页面打开右栏。AI 显式隐藏不弹手机确认框。") { p ->
            store.changeDockPanels(p.getString("command"), p.optString("panel").takeIf { it.isNotBlank() },
                if (p.has("enabled")) p.getBoolean("enabled") else null)
        }
        capability("text.fonts", "读取基础文字可用字体", read,
            "返回当前设备的中英文字体标识、默认字体与基础排版边界；字体标识用于 text.create/update。") {
            ArtText.fonts()
        }
        capability("text.create", "添加基础可编辑文字", write,
            "创建独立文字图层，保存原文和样式及透明渲染缓存；content 支持多行，boxWidth 控制自动换行，fontSize 单位为画布像素，align 为 left/center/right。documentId 与 expectedRevision 必须来自最新 document.info。字体缺字和复杂排版会明确拒绝。移动、缩放、旋转沿用 transform.*，删除使用 layer.delete；" + ArtText.NOTICE) { p ->
            store.writeText("LANER", p, false)
        }
        capability("text.update", "修改可编辑文字", write,
            "id 是 kind=text 的图层；必须传完整 content，未传样式字段使用该文字现有值。documentId 与 expectedRevision 防止覆盖另一端编辑；保留当前移动、缩放和旋转。共享撤销历史，成功自动附图；" + ArtText.NOTICE) { p ->
            store.writeText("LANER", p, true)
        }
        capability("image.set_background", "设置图像背景色与透明度", write,
            "与图像菜单共用工程操作日志；color 是 #AARRGGBB，00 为全透明。") { p ->
            store.apply("LANER", "IMAGE_BACKGROUND", JSONObject().put("color", p.getString("color")))
        }
        capability("image.crop_to_selection", "裁切图像到选区边界", write,
            "按当前选区的边界矩形裁切画布；没有选区或没有有效像素交集时拒绝。") {
            store.cropToSelection("LANER")
        }
        capability("image.resize_canvas", "更改画室画布大小", write,
            "不缩放图层；offsetX/offsetY 是旧图像左上角在新画布中的位置，默认 0。") { p ->
            store.apply("LANER", "CANVAS_RESIZE", JSONObject()
                .put("width", p.getInt("width")).put("height", p.getInt("height"))
                .put("x", -p.optInt("offsetX", 0)).put("y", -p.optInt("offsetY", 0)))
        }
        capability("view.state", "读取画室视图状态", read,
            "查看面板、状态栏、网格、像素网格、页面模式、缩放方向和角标；canvasZoom 包含实际像素比例 percent、范围 minPercent/maxPercent 及 documentId。未挂载画布时没有 canvasZoom。") {
            ArtStudioViewControl.describe()
        }
        capability("view.set", "设置画室视图选项", InProcessCapabilityEffect.UI_INTERACTION,
            "option 可取 panelsHidden、statusBarVisible、gridVisible、pixelGridVisible；设置与阿伟菜单相同的视图状态。") { p ->
            ArtStudioViewControl.setOption(p.getString("option"), p.getBoolean("enabled"))
        }
        capability("view.tool_options", "操作工具参数浮窗", InProcessCapabilityEffect.UI_INTERACTION,
            "action 为 show/minimize/restore/close/move。show 必须传 toolbox.catalog 的 toolId，可用工具同时被选中，planned 项只显示说明。move 必须传非负有限 xDp/yDp，以画室内容左上角为原点，布局后限制在可见区域内。窗口为插件内非模态浮窗，可继续绘画；状态读取 view.state 的 toolOptionsWindow。操作不改变作品，accepted 表示共享状态已更新，最终布局坐标随后读取。") { p ->
            ArtStudioToolOptionsControl.command(p)
        }
        capability("view.zoom_tool", "设置缩放工具方向", InProcessCapabilityEffect.UI_INTERACTION,
            "mode 为 in（放大）、out（缩小）或 toggle（交替切换）。只设置方向，不立即缩放、不改变选中工具；与手机角标及点击画布共享状态。立即缩放使用 view.command 的 zoom_in/zoom_out。") { p ->
            ArtStudioViewControl.setZoomToolMode(p.getString("mode"))
        }
        capability("view.zoom", "设置画布显示比例", InProcessCapabilityEffect.UI_INTERACTION,
            "documentId 使用 view.state 的 canvasZoom.documentId；percent 为显示百分比，100 表示一个图像像素对应一个屏幕像素，必须在当前 minPercent/maxPercent 内。以可视区域中心缩放；返回 accepted 表示已排队，实际值读取 view.state。只改变显示，不改变图片像素或历史。") { p ->
            ArtStudioViewControl.requestZoom(p.getString("documentId"), p.getDouble("percent"))
        }
        capability("view.command", "操作画室视图", InProcessCapabilityEffect.UI_INTERACTION,
            "在画室画布打开时执行 zoom_in/out/100、fit/fit_width/fit_height、rotate_right/left、reset_rotation、mirror、reset_display 或 refresh。") { p ->
            ArtStudioViewControl.command(p.getString("command"))
        }
        registerCapability("view.presentation", "切换画室页面模式", InProcessCapabilityEffect.UI_INTERACTION,
            "向宿主请求 normal、fullscreen_portrait 或 fullscreen_landscape；宿主确认后才视为成功。") { p ->
            val mode = p.getString("mode")
            require(mode in setOf("normal", "fullscreen_portrait", "fullscreen_landscape"))
            val response = JSONObject(host.invokeHostCapability("host.ui.presentation@1",
                JSONObject().put("operation", "set_mode").put("screen_id", ART_SCREEN)
                    .put("mode", mode).toString()))
            check(response.optBoolean("ok") && response.optString("mode") == mode) {
                response.optString("error").ifBlank { "宿主未确认画室显示模式：$response" }
            }
            ArtStudioViewControl.setPresentationMode(mode)
            response
        }

        capability("assistant.list","读取辅助尺规",read,
            "documentId 绑定当前工程，可选 expectedRevision；返回尺规、选择、吸附设置、可用类型与未实现部分。所有控制点都是文档坐标，尺规不导出为作品像素。") { p -> store.assistantList(p) }
        capability("assistant.create","创建辅助尺规",write,
            "必须传 documentId/expectedRevision；type 从 toolbox.catalog.assistants.types 读取，points 为控制点二维数组，name 可选。ruler/infinite_ruler/parallel_ruler 两点；ellipse/concentric_ellipse 三点（两点主轴、第三点在主轴两端之间侧方并位于椭圆上）；vanishing_point 一点。允许画布外消失点，创建后选中。") { p ->
            val a=JSONObject().put("id",UUID.randomUUID().toString()).put("type",p.getString("type"))
                .put("points",p.getJSONArray("points"))
            if(p.has("name"))a.put("name",p.getString("name"))
            store.apply("LANER","ASSISTANT_CREATE",JSONObject(p.toString()).put("assistant",a))
        }
        capability("assistant.select","选择辅助尺规",write,
            "documentId/expectedRevision/id；空 id 清除选择，选择不受编辑锁限制。") { p -> store.apply("LANER","ASSISTANT_SELECT",p) }
        capability("assistant.update","编辑辅助尺规",write,
            "documentId/expectedRevision/id/changes；changes 可含 points/name/visible/enabled/locked/subdivisions(0..100)/rays(4..64)。锁定后只能单独修改 locked；points 仍为原类型控制点。隐藏尺规不参与吸附。") { p -> store.apply("LANER","ASSISTANT_UPDATE",p) }
        capability("assistant.delete","删除辅助尺规",write,
            "documentId/expectedRevision/id；可撤销，不修改已经完成的笔迹，锁定尺规须先解锁。") { p -> store.apply("LANER","ASSISTANT_DELETE",p) }
        capability("assistant.settings","设置尺规显示与画笔吸附",write,
            "documentId/expectedRevision/settings；settings 可含 visible(显示辅助线)、snapping、onlySelected、thresholdDp(4..64)。手机起笔按屏幕 dp 范围及方向挑选并锁定一个尺规；全局隐藏辅助线不关闭吸附，各尺规 visible=false 或 enabled=false 才不参与吸附。") { p -> store.apply("LANER","ASSISTANT_SETTINGS",p) }
        capability("assistant.project","计算沿尺规坐标",read,
            "documentId/expectedRevision/id/points；points 为 1..10000 个文档坐标，可带第三项压力。显式约束到指定尺规，不使用手机距离阈值；起笔点决定平行线、消失点射线与同心椭圆，返回投影后的文档坐标，不写作品。") { p -> store.assistantProject(p) }
        capability("assistant.stroke","沿尺规绘画",write,
            "documentId/expectedRevision/id(尺规)/layerId/points/width；points 为文档坐标，可带压力。tool 可选 ink/pencil/soft/spray/eraser/calligraphy，color/opacity/nibAngle 可选。width 为图层局部像素；投影后转换到当前绘画图层局部坐标，只写一条 STROKE_ADD；笔迹固化，以后编辑或删除尺规不会改变已有作品。") { p -> store.assistantStroke("LANER",p) }
        capability("assistant.preview","检查尺规与画布",read,
            "documentId，可选 expectedRevision；返回256边长的尺规视图缩略图，包含可见辅助线和画布外控制点，不是作品导出。") { p -> store.assistantPreview(p) }
        capability("toolbox.catalog", "读取画室工具清单", read,
            "列出可用的画室基础工具和已预留的 Krita 工具位置；每格都有双击参数浮窗入口；planned 项只能查看说明，没有绘画执行入口。") {
            ArtToolCatalog.describe()
        }
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
        capability("storage.settings", "读取画室默认保存目录", read,
            "读取默认目录、工程/图片/备份目录；尚未设置时保持插件内位置。") {
            store.saveDirectorySettings()
        }
        capability("storage.set_directory", "更改画室默认保存目录", write,
            "directory 为应用可写的绝对目录路径，空字符串显式恢复插件内默认位置。设置前验证可写；影响新工程、图片与备份，已保存工程仍使用原路径。") { p ->
            store.setSaveDirectory(p.getString("directory"))
        }
        capability("document.save", "保存画室工程", write) { store.save() }
        capability("document.recent", "列出最近打开的画室工程", read) {
            JSONObject().put("documents", store.recent())
        }
        capability("document.open_image", "将图片打开为新工程", write,
            "使用 PNG、JPEG/JPG、WebP、BMP、GIF、HEIC/HEIF、AVIF 的 base64 创建独立工程；编码由内容识别，HEIC/AVIF 依赖系统解码器。动图只导入首帧，须向用户说明返回的 imageImport.warnings。超预算返回 needs_confirmation、operationApplied=false、imagePlan；向用户展示原尺寸/建议尺寸，获得缩小同意后用原 base64 和 imagePlan.confirmation 作为 confirmResize 重试。未确认不创建工程。成功含 imageImport 实际尺寸及 resized。") { p ->
            store.openImage(p.getString("base64"), p.optString("name", "未命名图像"), "LANER", p.optJSONObject("confirmResize"))
        }
        capability("document.save_as", "另存为并切换画室工程", write,
            "将当前画布复制成新 ID 的 .ailart 工程，保存到默认保存目录并将新工程设为当前；返回路径。") { p ->
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
        capability("canvas.region", "查看画布局部放大图", read,
            "按画布像素坐标读取 x、y、width、height 区域；maxEdge 为输出长边 64–1024，默认 512。selectionOutline=true 可显示当前选区轮廓，默认false保持作品预览。返回局部图片、工程与版本号，不修改画布或保存文件。") { p ->
            store.canvasRegion(p.getInt("x"), p.getInt("y"), p.getInt("width"),
                p.getInt("height"), p.optInt("maxEdge", 512),
                if (p.has("documentId")) p.getString("documentId") else null,
                if (p.has("expectedRevision")) p.getInt("expectedRevision") else null,p.optBoolean("selectionOutline",false))
        }
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
        capability("colorize.list","读取上色蒙版与颜色线索",read,
            "documentId必填，expectedRevision、maskId可选；includeKeys=true返回完整线索坐标，默认只返回线索摘要。返回蒙版源、脏状态、调色板、参数和基础能力范围。") {p->store.colorizeList(p)}
        capability("colorize.create","从线稿创建上色蒙版",write,
            "documentId/expectedRevision/sourceLayerId必填，name可选。基础版支持未变换的可见根绘画或图像线稿；独立colorize图层放在源上方，以保留暗线的透明填色覆盖明亮区域，原线稿和属性不改写。新蒙版自动选中。") {p->store.colorizeCreate("LANER",p)}
        capability("colorize.stroke","编辑蒙版颜色线索",write,
            "documentId/expectedRevision/maskId/points/width必填，points为1–4096个蒙版局部坐标二维点；当前只支持未变换根蒙版，故与文档坐标相同。width=0.1–256，erase可选false；非擦除时color必须#AARRGGBB非透明。遵循当前选区并保存选区剪裁，颜色线索不写原线稿，需update生成新结果。") {p->store.colorizeStroke("LANER",p)}
        capability("colorize.remove_stroke","删除一笔颜色线索",write,
            "documentId/expectedRevision/maskId/strokeId必填，删除指定线索后需update。") {p->store.apply("LANER","COLORIZE_REMOVE_STROKE",p)}
        capability("colorize.palette","管理线索调色板",write,
            "documentId/expectedRevision/maskId/color/action必填。action=transparent时transparent布尔必填，指定该色区域不产生填色；action=remove删除该颜色全部线索。需update。透明标记不擦除源线稿。") {p->store.apply("LANER","COLORIZE_PALETTE",p)}
        capability("colorize.settings","设置上色蒙版",write,
            "documentId/expectedRevision/maskId/settings必填。settings仅threshold整数1–254默认180、gapClose闭合半径0–8默认0、limitBounds默认false、editKeys/showOutput默认true，后三项布尔。求解参数改变后需update；显示控制不重新计算。") {p->store.apply("LANER","COLORIZE_SETTINGS",p)}
        capability("colorize.clear","清空蒙版颜色线索",write,
            "documentId/expectedRevision/maskId必填。清空线索与调色板；已生成结果保留至下一次update，可撤销。") {p->store.apply("LANER","COLORIZE_CLEAR",p)}
        capability("colorize.update","重新计算蒙版填色",write,
            "documentId/expectedRevision/maskId必填。暗线屏障、缺口闭合及多色种子测地传播；选区限制计算区域，封闭无种子区域透明。最多4194304区域像素并检查动态内存预算；不缩图求解。完成后固化一次输出资源与历史，重放不重算；保留旧结果直到成功。") {p->store.colorizeUpdate("LANER",p)}
        capability("colorize.convert","蒙版转换为绘画图层",write,
            "documentId/expectedRevision/maskId必填；将当前缓存填色转普通绘画层，删除编辑线索数据；可撤销恢复。不会重新计算，请先update使用最新线索结果。") {p->store.apply("LANER","COLORIZE_CONVERT",p)}
        capability("colorize.preview","检查填色与颜色线索",read,
            "documentId必填，expectedRevision/maskId可选；256边长缩略图包含所选蒙版的编辑线索与半透明输出，仅用于编辑检查，不是导出。") {p->store.colorizePreview(p)}
        capability("selection.color_info","读取颜色区域选区参数",read,
            "连续区域为四邻域连通颜色，相似色为范围内全部相近颜色；返回二值表示、搜索预算、参数与高级灰色项目。") {ArtColorSelection.info()}
        capability("selection.contiguous","创建连续区域选区",write,
            "documentId/expectedRevision/layerId/x/y必填，x/y文档整数像素。reference=current/visible默认visible，current保留对象图层变换、忽略本层与父组透明度混合；visible合成可见层，不含背景、参考图像、尺规或蒙版线索。tolerance=0–100默认15，预乘RGBA最大通道差；四邻域固定种子颜色搜索。boundaryMode=true改为在boundaryColor之外连通，默认false/黑色。gapClose=0–8默认0用二值侵蚀断开窄通道后恢复，不替换已失效的种子；expand=-16–16默认0。mode=replace/add/subtract/intersect默认replace，精确二值区域保留孔洞与离散分量。limitToSelection默认false，true在现有选区内查找并剪裁；bounds可选{x,y,width,height}文档整数范围。单次范围4194304像素、32768扫描段及动态内存检查，超预算明确拒绝；成功一次历史与轮廓缩图，返回selectedPixels/sampledColor，不写作品像素。") {p->store.colorSelection("LANER",p,true)}
        capability("selection.similar","创建相似色选区",write,
            "documentId/expectedRevision/layerId/x/y必填，文档整数取样点。扫描参考范围中所有相近颜色，包含互不连通区域；tolerance=0–100默认15，预乘RGBA差包括透明度。reference=current/visible默认visible；无背景与辅助对象。mode=replace/add/subtract/intersect默认replace，expand=-16–16默认0；limitToSelection/bounds可选，同连续区域范围及预算。生成真实二值区域和孔洞；一次历史、自动选区缩图、selectedPixels与sampledColor。") {p->store.colorSelection("LANER",p,false)}
        capability("selection.magnetic_info","读取磁性套索参数与范围",read,
            "真实RGBA对比边缘最短路径，返回搜索、滤波采样、锚点、精度限制和未实现项目。") {ArtMagneticSelection.info()}
        capability("selection.magnetic_trace","预览磁性吸附轮廓",read,
            "documentId/expectedRevision/layerId/anchors必填，anchors为1–128文档坐标[x,y]；closed默认false，闭合至少3点。reference=current/visible默认visible；filterRadius=1–4默认1是Sobel采样半径，searchRadius=2–64默认16，threshold=0–255默认24，strength=1–20默认8，precision=0.25–4默认0.75为折线简化误差像素；limitToSelection/bounds可选限制参考范围。八邻域A*沿真实边缘吸附，每段最多262144搜索像素，整参考范围4194304像素；超预算请增加中间锚点。返回最多2048轮廓点，不写选区。") {p->store.magneticTrace(p)}
        capability("selection.magnetic_create","创建磁性套索选区",write,
            "参数同magnetic_trace，anchors需3–128且始终闭合；mode=replace/add/subtract/intersect默认replace。吸附、闭合、简化后保存真实折线选区，一次历史并自动轮廓缩图。参考和搜索超预算、工程过期明确拒绝，不使用直线代替失败搜索。") {p->store.magneticCreate("LANER",p)}
        capability("selection.bezier_info","读取贝塞尔曲线选区范围",read,
            "返回选区模式、闭合曲线节点上限、复合上限及未实现项目。曲线选区与图层路径独立，使用文档坐标。") {ArtBezierSelection.info()}
        capability("selection.bezier_create","建立贝塞尔曲线选区",write,
            "documentId/expectedRevision/nodes必填。nodes为2–2048节点，x/y为文档像素，in/out可选二维控制柄，type=corner/smooth/symmetric默认corner；路径始终闭合，面积为零明确拒绝。mode=replace/add/subtract/intersect默认replace。保留真实三次曲线；复合选区最多32分量、8192累计节点。没有现有选区时add创建，subtract/intersect产生空选区以阻止像素写入。独立于图层且不写作品像素，工程绑定与共享撤销；自动缩图含选区轮廓。") {p->store.bezierSelectionCreate("LANER",p)}
        capability("selection.bezier_nodes","读取曲线选区节点",read,
            "documentId必填，expectedRevision和componentIndex可选；componentIndex零基默认0，复合选区可从document.info.state.selection.parts读取分量类型。指定分量必须bezier。返回当前文档坐标的节点与控制柄、模式、版本和分量数，已应用选区移动缩放。") {p->store.bezierSelectionNodes(p)}
        capability("selection.bezier_edit","编辑曲线选区节点",write,
            "documentId/expectedRevision/edits必填，componentIndex零基默认0。edits为1–64顺序动作：move_node(node,x,y)、move_handle(node,side=in/out,x,y)、node_type(node,type=corner/smooth/symmetric)、insert_node(segment,t默认0.5)、delete_node(node)、segment_type(segment,type=line/curve)。坐标为文档像素；始终闭合且至少2节点。复合重算布尔边界并保留每个分量；无面积运算结果保存为空选区。一次历史与自动轮廓缩图。") {p->store.bezierSelectionEdit("LANER",p)}
        capability("selection.preview","检查当前选区轮廓",read,
            "documentId必填，expectedRevision可选；返回256长边图片，含选区浅蓝覆盖和轮廓以及当前选区数据。仅编辑检查，作品导出不含选区。局部细节用canvas.region并传selectionOutline=true。") {p->store.selectionPreview(p)}
        capability("enclose.info","读取围合填充范围与默认参数",read,
            "返回四种围合方式、七种颜色条件、限制和高级灰色项目。基础 RGBA8 围合及区域筛选，非完整 Krita 内核。") {ArtEncloseFill.info()}
        capability("enclose.apply","围合填充当前图层",write,
            "documentId/expectedRevision/layerId/shape/points必填。shape=rect/ellipse需两个对角点，lasso需3–2048点并自动闭合，brush需1–2048点和width=1–256默认32，均为文档像素坐标。目标必须当前选中的未锁定可见未变换根绘画/图像层。color=#AARRGGBB，非擦除必填。mode=all/transparent/color/color_or_transparent/not_color/not_transparent/not_color_or_transparent，默认all；regionColor默认白；tolerance=0–100默认15；includeContour默认false，排除触及围合边界的连通区域；invert默认false。reference=current/visible默认visible，visible合成图层透明度及混合但不含文档背景、参考、尺规、蒙版线索；current读取原始图层像素。opacity=0–1默认1，erase默认false，expand=-16–16默认0，feather=0–8默认0，gapClose=0–8默认0只用于非all的二值条件，以形态学开运算断开窄通道再恢复边缘。现有选区剪裁最终输出，围合不会更改选区。最大4194304围合矩形像素和动态内存预检，无缩图处理。完成固化PNG并记录一次历史，自动缩图；没有可写像素返回changed=false，不增加历史。") {p->store.encloseFill("LANER",p)}
        capability("patch.info","读取智能修补范围",read,
            "返回基础局部 PatchMatch 的参数默认值、区域及计算预算、目标图层要求和未实现功能。") { ArtSmartPatch.info() }
        capability("patch.apply","智能修补当前图层",write,
            "documentId/expectedRevision/layerId/points/width 必填；points 为1–4096个文档坐标二维数组，形成圆头涂抹蒙版，遵循选区。width=1–256；patchRadius=1–8 默认4；accuracy=1–100 默认40；searchRadius=16–256 默认64；feather=0–8 默认2。仅当前未锁定可见且未变换的根绘画/图像图层，从其附近未涂抹纹理做局部 PatchMatch；单次最多32768蒙版像素。成功保存一次历史并返回缩图。结果是固化像素，不在回放时重算；无可用纹理或超预算明确拒绝。") { p -> store.smartPatch("LANER",p) }
        capability("fill.contiguous", "填充当前图层连通区域", write,
            "连通填色，遵循当前选区；tolerance 为每个 RGBA 通道允许的最大差值百分比（0–100），referenceAllLayers 决定从所有可见层取参考色；erase=true 时擦除匹配区域的当前图层像素。目标仍是当前可编辑根图层。") { p ->
            store.fillContiguous("LANER", p.getInt("x"), p.getInt("y"), p.getString("color"),
                if (p.has("expectedRevision")) p.getInt("expectedRevision") else null,
                p.optInt("tolerance", 0), p.optBoolean("referenceAllLayers", false),
                p.optBoolean("erase", false))
        }
        capability("color.sample", "从画布合成结果取色", read,
            "输入画布像素坐标；可选 radius=0–32、blend=0–100。sampleMerged=false 时传根绘画／图像层 layerId；blend<100 时需传当前 baseColor（#AARRGGBB），返回与左侧取色工具相同的颜色。") { p ->
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
        capability("history.timeline", "读取足迹状态列表", read,
            "按当前可到达的状态排序；position 指向当前状态，后续状态可重做。每项含 id、操作名称、执行者和时间。") {
            val snapshot = store.current()
            JSONObject().put("timeline", snapshot.getJSONArray("timeline"))
                .put("position", snapshot.getInt("timelinePosition"))
                .put("revision", snapshot.getInt("revision"))
        }
        capability("history.goto", "切换到指定足迹状态", write,
            "传 history.timeline 的状态 id；初始画布传空字符串。必须携带 revision，以免覆盖阿伟或兰儿的新改动。") { p ->
            store.historyJump("LANER", p.getString("id"), p.getInt("expectedRevision"))
        }
        capability("layer.create", "创建画室绘画图层", write,
            "创建可绘画图层；可选 parentId 指定已有图层组，可选 select=true 立即设为活动图层。") { p ->
            p.put("id", UUID.randomUUID().toString()); store.apply("LANER", "LAYER_CREATE", p)
        }
        capability("layer.vector", "创建基础矢量图层", write,
            "保存独立可编辑形状；documentId/expectedRevision 必填，支持已有父组。支持基础形状、徒手路径、贝塞尔节点编辑和矢量书法轮廓；高级SVG尚未实现。") { p ->
            p.put("id", UUID.randomUUID().toString()); store.apply("LANER", "VECTOR_LAYER_CREATE", p)
        }
        capability("reference.list","读取参考图像",read,
            "documentId必填。返回独立于图层的嵌入参考、选择、矩阵和当前revision；文档坐标，作品导出不包含参考。") { p -> store.referenceList(p) }
        capability("reference.preview","查看参考与画布",read,
            "documentId必填，可选expectedRevision；返回包含画布外参考的256边长视图图片块。作品导出不受影响。") { p -> store.referencePreview(p) }
        capability("reference.region","查看参考图像局部细节",read,
            "documentId/id/x/y/width/height必填；坐标是参考原图像素，maxEdge=64至2048默认512，可选expectedRevision。读取嵌入原图局部，忽略显示矩阵/透明度/饱和度，返回图片块。") { p -> store.referenceRegion(p) }
        capability("reference.add","添加参考图像",write,
            "documentId/expectedRevision/base64必填；支持现有图片格式按内容识别，动图首帧。name和matrix=[a,b,c,d,tx,ty]可选，默认摆在画布右侧。工程嵌入原图，最多16张，输入8MiB。超预算先返回缩小确认，取得用户同意后带confirmResize重试。成功含referenceId/imageImport和参考视图缩略图。") { p -> store.referenceAdd("LANER",p) }
        capability("reference.select","选择参考图像",write,
            "documentId/expectedRevision/ids必填，空ids清除。与绘画对象选择独立。") { p -> store.apply("LANER","REFERENCE_SELECT",p) }
        capability("reference.transform","变换参考图像",write,
            "documentId/expectedRevision/ids/matrix必填；matrix是文档坐标中的增量仿射矩阵，左乘现有矩阵，可移动/缩放/旋转/镜像。锁定或隐藏拒绝；页面保持比例选项不限制显式API矩阵。") { p -> store.apply("LANER","REFERENCE_TRANSFORM",p) }
        capability("reference.style","设置参考图像样式",write,
            "documentId/expectedRevision/ids/style必填；style支持opacity/saturation 0至1、visible/locked/keepAspect布尔、name。锁定时只允许单独改locked；keepAspect约束页面缩放。") { p -> store.apply("LANER","REFERENCE_STYLE",p) }
        capability("reference.delete","删除参考图像",write,
            "documentId/expectedRevision/ids必填。删除工程对象，可撤销，不删除手机原图。") { p -> store.apply("LANER","REFERENCE_DELETE",p) }
        capability("reference.show","整体显示或隐藏参考",write,
            "documentId/expectedRevision/visible必填；工程共享的整体显示状态，不改变单图visible，锁定不影响整体隐藏。") { p -> store.apply("LANER","REFERENCE_SHOW",p) }
        capability("shape.list", "读取矢量形状和选择", read,
            "使用 document.info 的 documentId 和 layerId；返回形状源参数、文档边界、选中编号与图层锁定信息。") { p -> store.shapes(p) }
        capability("shape.hit", "命中矢量形状", read,
            "x/y 为文档坐标，tolerance 为文档像素，默认0。在指定可见矢量层按实际填充/描边从上向下查找；返回hitId或null，不改变选择。") { p -> store.shapes(p, true) }
        capability("shape.box", "查询框内矢量对象", read,
            "x/y/width/height为文档坐标矩形；contained=true要求完全包含，false选择相交对象。返回boxedIds，可交给shape.select；使用手机同一几何规则，不创建像素选区。") { p -> store.shapes(p, box = true) }
        capability("comic.info","读取漫画分格范围与默认参数",read,
            "直边矢量分格与合并，文档像素坐标；返回宽度预设、角度规则与灰色项目。对象清单使用shape.list。") {ArtComicPanels.info()}
        capability("comic.frame","创建漫画分格框",write,
            "documentId/expectedRevision/layerId/x/y/width/height必填。坐标尺寸为文档像素；可见未锁定矢量层，支持层及父组变换。style可选fill/stroke/strokeWidth/opacity；默认透明填充、黑色2像素边框，样式粗细为对象局部。先用layer.vector创建矢量层。一次撤销与自动缩图。") {p->store.comicFrame("LANER",p)}
        capability("comic.cut","切分漫画矢量分格",write,
            "documentId/expectedRevision/layerId/start/end必填；start/end=[x,y]为文档像素，切线须从框外完整穿过框。ids可选限定对象，不传处理该层手势范围内的可见对象。支持矩形、凸多边形、闭合L直线路径，最多256顶点；曲线、凹轮廓和锁定交叉对象明确拒绝。thick/thin/special为0–512文档像素，默认12/6/8；automatic默认true，horizontal/vertical/diagonal使用thick/thin/special默认映射，angle=0–45默认15度。automatic=false用preset，默认thick。保留真实多边形、样式与对象仿射矩阵；一次原子记录，过宽拒绝，无交叉changed=false不增加历史；成功自动缩图、removedIds/createdIds/gutterWidth。") {p->store.comicEdit("LANER","cut",p)}
        capability("comic.merge","合并相邻漫画分格",write,
            "documentId/expectedRevision/layerId/start/end必填，文档像素；ids可选限定对象。起终点须在不同格内部，手势恰好穿过两条平行相对直边，间隙0–512像素且大于0。每次合并一条间隙，仅填满直边重叠区，最多256顶点；带孔、曲线、非平行边不可用，碰到第三格拒绝。保留较靠下对象的样式及矩阵；一次撤销、过期锁定检查、自动缩图与removedIds/createdIds。") {p->store.comicEdit("LANER","merge",p)}
        capability("path.create", "创建可编辑贝塞尔路径", write,
            "nodes为逻辑节点数组，每项x/y和可选in/out=[x,y]、type=corner/smooth/symmetric；创建坐标为图层局部。closed/style可选。documentId/expectedRevision/layerId必填；开放路径至少2节点。") { p -> store.pathCreate("LANER",p) }
        capability("path.nodes", "读取路径节点与控制柄", read,
            "documentId/layerId/id必填；返回对象局部nodes、closed、revision和objectToDocument矩阵。节点编号是零基数组索引，编辑前刷新。") { p -> store.pathNodes(p) }
        capability("path.edit", "编辑贝塞尔路径节点", write,
            "documentId/expectedRevision/layerId/id/edits必填；edits为1至64个顺序动作。move_node(node,x,y)、move_handle(node,side=in/out,x,y)、node_type(node,type=corner/smooth/symmetric)、insert_node(segment,t默认0.5)、delete_node(node)、segment_type(segment,type=line/curve)、closed(value)。坐标为对象局部，节点/段零基。一次原子提交，锁定和过期拒绝。") { p -> store.apply("LANER","SHAPE_PATH_EDIT",p) }
        capability("shape.calligraphy", "绘制矢量书法笔画", write,
            "samples为2至1000项{x,y,time,pressure可选默认1}，图层局部坐标，time为递增毫秒。width=0.1至512默认20，angle=0至180默认45（从+X顺时针），fixation=0至1默认1，thinning=-1至1默认0（负值越快越粗），smoothing=0至1默认0，usePressure默认true，cap=flat/round默认round，color=#AARRGGBB，opacity=0至1。生成普通封闭填色轮廓，保存最终几何；节点工具可继续编辑。documentId/expectedRevision/layerId必填。") { p -> store.calligraphy("LANER",p) }
        capability("shape.freehand", "绘制矢量徒手路径", write,
            "points为2至2048个图层局部采样点；mode=raw/curve/straight，precision=0.25至32（默认2），closed可选；style支持fill/stroke/strokeWidth/opacity。保存拟合后的可编辑路径，不重算回放；documentId/expectedRevision/layerId必填。") { p -> store.freehand("LANER", p) }
        capability("shape.create", "创建可编辑矢量形状", write,
            "shape 为对象：kind=line/rectangle/ellipse/polygon/path，points为局部坐标点；基础前三类2点、多边形3至2048点。path需commands=[L或C...]，起点占1点，L再占1点，C占控制点1/2和终点3点，closed可选，最多2048段/6145点。fill/stroke为#AARRGGBB，strokeWidth为0.1至512，opacity为0至1；可选matrix=[a,b,c,d,tx,ty]。ID自动生成。documentId/expectedRevision/layerId必填。") { p ->
            val shape = JSONObject(p.getJSONObject("shape").toString()).put("id", UUID.randomUUID().toString())
            p.put("shape", shape); store.apply("LANER", "SHAPE_CREATE", p)
        }
        capability("shape.select", "选择矢量对象", write,
            "ids为当前矢量层的对象编号列表，空数组取消；只改变对象选择，不创建像素选区。工程编号和版本必填。") { p ->
            store.apply("LANER", "SHAPE_SELECT", p)
        }
        capability("shape.transform", "变换选定形状", write,
            "ids为对象编号，matrix=[a,b,c,d,tx,ty]为矢量层局部坐标的增量仿射变换，支持移动/缩放/旋转；一次操作整体提交，锁定拒绝。工程编号和版本必填。") { p ->
            store.apply("LANER", "SHAPE_TRANSFORM", p)
        }
        capability("shape.style", "设置基础形状样式", write,
            "style只支持fill/stroke/strokeWidth/opacity，未传字段保持现值。ids/documentId/expectedRevision/layerId必填。") { p ->
            store.apply("LANER", "SHAPE_STYLE", p)
        }
        capability("shape.delete", "删除矢量对象", write,
            "ids/documentId/expectedRevision/layerId必填；锁定对象拒绝，共享撤销与图片反馈。") { p ->
            store.apply("LANER", "SHAPE_DELETE", p)
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
        capability("edit.paste_new", "从剪贴板创建新工程", write,
            "超预算时返回 imagePlan；取得用户同意后传回 confirmResize。") { p ->
            store.pasteAsNew("LANER", p.optJSONObject("confirmResize"))
        }
        capability("edit.clear", "清除选区像素", write) { store.editPixels("LANER", "CLEAR") }
        capability("edit.fill_foreground", "用前景色填充选区", write) { p ->
            store.editPixels("LANER", "FILL", p.getString("color"))
        }
        capability("edit.fill_background", "用指定背景色填充选区", write) { p ->
            store.editPixels("LANER", "FILL", p.getString("color"))
        }
        capability("image.formats", "读取画室图片格式支持", read,
            "列出 PNG、JPEG/JPG、WebP、BMP、GIF、HEIC/HEIF、AVIF 的扩展名、MIME 与当前运行环境的解码器可用性；按内容识别，GIF/动态 WebP 当前只导入首帧。导出仍为 PNG/JPEG。") {
            ArtImageFormats.describe()
        }
        capability("image.limits", "读取图片尺寸与内存预算", read,
            "读取结构尺寸范围与当前保守工作预算；预算随进程内存改变，不是无限制或内存保证。") {
            ArtImagePolicy.describe()
        }
        capability("image.import", "导入常用格式图片", write,
            "支持 PNG、JPEG/JPG、WebP、BMP、GIF、HEIC/HEIF、AVIF，按图片内容识别。动图只导入首帧，须说明 imageImport.warnings。导入当前工程。超预算返回 needs_confirmation 和 imagePlan；须先取得用户缩小同意，再携带原 base64 与 imagePlan.confirmation 作为 confirmResize 重试。成功返回 imageImport 与缩略图。") { p ->
            store.importImage("LANER", p.getString("base64"), p.optJSONObject("confirmResize"))
        }
        capability("export.png", "导出 PNG", write) { p ->
            ArtRenderer.export(store, store.current(), "png", p.optString("name", ""), p)
        }
        capability("export.jpeg", "导出 JPEG", write) { p ->
            ArtRenderer.export(store, store.current(), "jpeg", p.optString("name", ""), p)
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
            "percent" -> "实际显示百分比；范围见 view.state.canvasZoom，100 表示 1:1。仅缩放显示。"
            "content" -> "需要保存的完整文字，最多4096字符；换行符换行。基础排版边界见text.fonts。"
            "fontId" -> "text.fonts返回的当前设备字体标识；创建时默认中英文字体，更新时保留原字体。"
            "fontSize" -> "字号，6–512画布像素。"
            "boxWidth" -> "自动换行框宽度，1–16384画布像素。"
            "lineSpacing" -> "行距倍数，1.0–3.0。"
            "align" -> "水平对齐：left、center、right。"
            "maxEdge" -> "局部预览图片的长边，64–1024 像素，默认 512；用于控制细节和传输大小。"
            "documentId" -> "工程编号；局部图可使用上一张缩略图的编号来拒绝已切换的画布。"
            "directory" -> "应用可写的绝对目录路径；空字符串恢复插件内默认位置，旧工程继续原位保存。"
            "id" -> "图层或工程 ID；先读取 layer.list 或 document.list 确定真实 ID。"
            "parentId" -> "可选的父图层组 ID，空字符串表示根层级。"
            "select" -> "可选；true 表示创建或复制后立即选中该图层。"
            "index" -> "layer.list 返回的 layers 数组中的位置，从 0 开始，方向为底到顶。"
            "opacity" -> "不透明度，0.0 表示透明，1.0 表示完全不透明。"
            "blend" -> "混合模式：normal、multiply、screen 或 add。"
            "query" -> "图层名称中的文字；按名称筛选且忽略大小写。"
            "visible" -> "true 显示，false 隐藏。"
            "locked" -> "true 锁定，false 解锁。"
            "confirmResize" -> "仅在用户同意缩小后传回 imagePlan.confirmation 原对象；绑定源图 SHA-256 和明确目标尺寸。"
            "expectedRevision" -> "操作历史条数；矢量写操作必填，用于拒绝在另一端改动后过期的操作。"
            else -> key
        }
        return InProcessCapabilityParameterSpec(key, type, description, !optional)
    }
    val id = p("id")
    return when (name) {
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
            p("gapClose","integer",true),p("boundaryMode","boolean",true),p("boundaryColor",optional=true),p("limitToSelection","boolean",true),p("bounds","object",true))
        "selection.similar" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("x","integer"),p("y","integer"),
            p("mode",optional=true),p("reference",optional=true),p("tolerance","integer",true),p("expand","integer",true),p("limitToSelection","boolean",true),p("bounds","object",true))
        "selection.magnetic_trace", "selection.magnetic_create" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("anchors","array"),
            p("mode",optional=true),p("reference",optional=true),p("filterRadius","integer",true),p("searchRadius","integer",true),
            p("threshold","integer",true),p("strength","number",true),p("precision","number",true),p("limitToSelection","boolean",true),p("bounds","object",true)) +
            if(name=="selection.magnetic_trace")listOf(p("closed","boolean",true)) else emptyList()
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
        "path.create" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),
            p("nodes","array"),p("closed","boolean",true),p("style","object",true))
        "path.nodes" -> listOf(p("documentId"),p("layerId"),id)
        "path.edit" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),id,p("edits","array"))
        "shape.calligraphy" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),
            p("samples","array"),p("width","number",true),p("angle","number",true),
            p("fixation","number",true),p("thinning","number",true),p("smoothing","number",true),
            p("usePressure","boolean",true),p("cap",optional=true),p("color",optional=true),p("opacity","number",true))
        "shape.freehand" -> listOf(p("documentId"), p("expectedRevision", "integer"), p("layerId"),
            p("points", "array"), p("mode", optional = true), p("precision", "number", true),
            p("closed", "boolean", true), p("style", "object", true))
        "shape.create" -> listOf(p("documentId"), p("expectedRevision", "integer"), p("layerId"), p("shape", "object"))
        "shape.select", "shape.delete" -> listOf(p("documentId"), p("expectedRevision", "integer"), p("layerId"), p("ids", "array"))
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
        "view.command" -> listOf(p("command"))
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
        "selection.bezier_info" -> emptyList()
        "selection.bezier_create" -> listOf(p("documentId"),p("expectedRevision","integer"),p("nodes","array"),p("mode",optional=true))
        "selection.bezier_nodes" -> listOf(p("documentId"),p("expectedRevision","integer",true),p("componentIndex","integer",true))
        "selection.bezier_edit" -> listOf(p("documentId"),p("expectedRevision","integer"),p("edits","array"),p("componentIndex","integer",true))
        "selection.preview" -> listOf(p("documentId"),p("expectedRevision","integer",true))
        "enclose.info" -> emptyList()
        "enclose.apply" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),p("shape"),
            p("points","array"),p("color",optional=true),p("mode",optional=true),p("regionColor",optional=true),
            p("tolerance","integer",true),p("includeContour","boolean",true),p("invert","boolean",true),
            p("reference",optional=true),p("width","integer",true),p("opacity","number",true),
            p("erase","boolean",true),p("expand","integer",true),p("feather","integer",true),p("gapClose","integer",true))
        "patch.info" -> emptyList()
        "patch.apply" -> listOf(p("documentId"),p("expectedRevision","integer"),p("layerId"),
            p("points","array"),p("width","number"),p("patchRadius","integer",true),
            p("accuracy","integer",true),p("searchRadius","integer",true),p("feather","integer",true))
        "assistant.list", "assistant.preview" -> listOf(p("documentId"),p("expectedRevision","integer",true))
        "assistant.create" -> listOf(p("documentId"),p("expectedRevision","integer"),p("type"),p("points","array"),p("name",optional=true))
        "assistant.select", "assistant.delete" -> listOf(p("documentId"),p("expectedRevision","integer"),id)
        "assistant.update" -> listOf(p("documentId"),p("expectedRevision","integer"),id,p("changes","object"))
        "assistant.settings" -> listOf(p("documentId"),p("expectedRevision","integer"),p("settings","object"))
        "assistant.project" -> listOf(p("documentId"),p("expectedRevision","integer"),id,p("points","array"))
        "assistant.stroke" -> listOf(p("documentId"),p("expectedRevision","integer"),id,p("layerId"),p("points","array"),p("width","number"),
            p("tool",optional=true),p("color",optional=true),p("opacity","number",true),p("nibAngle","number",true))
        "view.tool_options" -> listOf(p("action"), p("toolId", optional = true),
            p("xDp", "number", true), p("yDp", "number", true))
        "view.zoom_tool" -> listOf(p("mode"))
        "view.zoom" -> listOf(p("documentId"), p("percent", "number"))
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
        "fill.contiguous" -> listOf(p("x", "integer"), p("y", "integer"), p("color"),
            p("expectedRevision", "integer", true), p("tolerance", "integer", true),
            p("referenceAllLayers", "boolean", true), p("erase", "boolean", true))
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
            p("gradientEndColor", optional = true),
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
    }
}
