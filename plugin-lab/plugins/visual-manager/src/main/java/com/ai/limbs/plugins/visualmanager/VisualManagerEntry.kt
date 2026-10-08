package com.ai.limbs.plugins.visualmanager

import com.ai.limbs.plugin.runtime.InProcessCapabilityDomain
import com.ai.limbs.plugin.runtime.InProcessCapabilityEffect
import com.ai.limbs.plugin.runtime.InProcessCapabilityParameterSpec
import com.ai.limbs.plugin.runtime.InProcessCapabilitySpec
import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import org.json.JSONArray
import org.json.JSONObject

class VisualManagerEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        require(host.pluginId == VISUAL_PLUGIN_ID) { "Unexpected Visual Workbench identity" }
        val controller = VisualManagerController(host)
        host.registerProvider(VISUAL_MESSAGE_CONTEXT_ID,
            InProcessCapabilityExecutor { request -> controller.readMessageContext(JSONObject(request)).toString() },
            mapOf("kind" to "message_context", "context_api" to "1", "event" to "user_message"))
        host.registerProvider(VISUAL_FEEDBACK_ID,
            InProcessCapabilityExecutor { request -> controller.postActionFeedback(JSONObject(request)).toString() },
            mapOf("kind" to "operation_feedback", "feedback_api" to "1", "event" to "screen_interaction"))
        host.registerProvider(VISUAL_STATE_ID, controller.stateProvider, mapOf("kind" to "ui_state"))
        host.registerProvider(VISUAL_PAGE_ID, VisualManagerPageProvider(host, controller),
            mapOf("kind" to "plugin_page", "screen_id" to VISUAL_SCREEN_ID))
        host.registerScreen(InProcessScreen(
            id = VISUAL_SCREEN_ID, title = "视觉工作台",
            description = "屏幕、摄像头、页面文字与图像记录",
            schemaId = "ai_limbs.plugin_center.ui.v1",
            documentJson = JSONObject().put("schema", 1).put("layout", "edge_to_edge")
                .put("blocks", JSONArray().put(JSONObject().put("type", "plugin_page").put("provider_id", VISUAL_PAGE_ID)))
                .toString()))
        host.registerHomeTile(InProcessHomeTile(id = VISUAL_TILE_ID, title = "视觉工作台",
            description = "看画面 · 读页面 · 管理图像", screenId = VISUAL_SCREEN_ID))

        fun p(name: String, type: String = "string", description: String,
              required: Boolean = true, default: String? = null) =
            InProcessCapabilityParameterSpec(name, type, description, required, default)
        val kind = p("kind", description = "screen 或 camera")
        val source = p("source_id", description = "sources 返回的 target_id 或 source_id")
        val session = p("session_id", description = "start/status 返回的会话 ID")
        val image = p("asset_id", description = "images.list/preview.save 返回的图像记录 ID")
        val edge = p("max_edge", "integer", "预览最长边 160..1280", false, "1024")
        val cameraOptions = listOf(
            p("width", "integer", "期望拍摄宽度", false, "1280"),
            p("height", "integer", "期望拍摄高度", false, "720"),
            p("jpeg_quality", "integer", "JPEG 质量 1..100", false, "92"),
            p("jpeg_orientation", "integer", "指定 JPEG 角度；省略时使用传感器和屏幕方向", false))

        fun capability(name: String, title: String, effect: InProcessCapabilityEffect,
                       description: String, parameters: List<InProcessCapabilityParameterSpec> = emptyList(),
                       example: String = "{}") {
            val properties = JSONObject()
            val required = JSONArray()
            parameters.forEach { parameter ->
                val field = JSONObject().put("type", parameter.type).put("description", parameter.description)
                when (parameter.name) {
                    "kind" -> field.put("enum", JSONArray(if (name == "stop") listOf("screen", "camera", "all") else listOf("screen", "camera")))
                    "operation" -> field.put("enum", JSONArray(listOf("check", "request", "open_settings")))
                    "observe_mode" -> field.put("enum", JSONArray(listOf("new_frame", "stable", "change_then_stable")))
                    "mode" -> field.put("enum", JSONArray(listOf("auto", "ui", "visual")))
                    "field" -> field.put("enum", JSONArray(listOf("text", "content_description")))
                    "scope" -> field.put("enum", JSONArray(listOf("all", "semantic")))
                    "scene_profile" -> field.put("enum", JSONArray(listOf("ui", "dynamic")))
                }
                parameter.default?.let { value ->
                    field.put("default", when (parameter.type) {
                        "integer" -> value.toInt()
                        "boolean" -> value.toBooleanStrict()
                        else -> value
                    })
                }
                properties.put(parameter.name, field)
                if (parameter.required) required.put(parameter.name)
            }
            host.registerCapability(InProcessCapabilitySpec(
                id = "$VISUAL_PLUGIN_ID.$name", displayName = title, description = description,
                keywords = listOf("视觉", "工作台", "屏幕", "摄像头", "页面", "图像"),
                parameters = parameters, suggestedParamsJson = example,
                inputSchema = JSONObject().put("type", "object").put("properties", properties)
                    .put("required", required).put("additionalProperties", false).toString(),
                effect = effect, domain = InProcessCapabilityDomain.PLUGIN,
                executor = InProcessCapabilityExecutor { json ->
                    controller.call(name, JSONObject(json.ifBlank { "{}" })).toString()
                }))
        }

        val read = InProcessCapabilityEffect.READ_ONLY
        val change = InProcessCapabilityEffect.STATE_CHANGE
        val write = InProcessCapabilityEffect.PERSISTENT_WRITE
        val waitParameters = listOf(
            p("scene_profile", description = "ui 保持严格稳定默认值；dynamic 默认 stable_ratio=0.01。显式阈值覆盖策略；不自动屏蔽动画区域", required = false, default = "ui"),
            p("timeout_ms", "integer", "观察预算 100..15000 毫秒，包含整轮取帧/采样/健康检查；按已测轮耗时停止新轮询，在途 Host 调用不强制取消，超额见 deadline_overshoot_ms；不含最终编码/网络回传", false, "5000"),
            p("stable_ms", "integer", "稳定窗口 100..5000 毫秒，不大于 timeout_ms", false, "250"),
            p("sample_interval_ms", "integer", "采样间隔 50..1000 毫秒", false, "100"),
            p("change_ratio", "number", "发生变化的采样点比例阈值 (0,1]", false),
            p("stable_ratio", "number", "稳定时允许的变化比例 [0,change_ratio)，ui 默认 0、dynamic 默认 0.01；change_ratio 默认 0.02", false),
            p("pixel_tolerance", "integer", "RGB 单通道差异容忍值 0..254", false, "12"),
            p("region_left", "number", "观察区域左边比例，默认 0", false),
            p("region_top", "number", "观察区域上边比例，默认 0", false),
            p("region_width", "number", "区域宽度比例，至少 1/64，默认 1", false),
            p("region_height", "number", "区域高度比例，至少 1/64，默认 1", false))
        capability("status", "视觉工作台状态", read,
            "读取真实会话、授权、操作状态和最新画面时间。未启动时先 sources；相机未授权先 permission request；start 成功才可 frame。success=false 必须处理，勿把调用返回当操作成功。")
        capability("sources", "列出视觉来源", read, "列出显示目标或相机镜头及授权状态。",
            listOf(kind), """{"kind":"camera"}""")
        capability("permission", "相机授权", change,
            "check 查询、request 请求 Android 相机授权、open_settings 打开 AI Limbs 权限设置。拒绝/取消明确失败，不自动重试。",
            listOf(p("operation", description = "check/request/open_settings")), """{"operation":"request"}""")
        capability("start", "开始视觉会话", change,
            "启动指定来源并取得首帧才报告成功。每类同时一个会话；已有会话需明确停止。返回 session_id、preview 和 MCP 图像。不会自动保存图像记录。",
            listOf(kind, source) + cameraOptions, """{"kind":"camera","source_id":"0"}""")
        capability("frame", "获取会话画面", write,
            "从已开启会话取得新画面。save=false 仅替换临时预览；save=true 同时保存原图。返回 MCP 图像；查询 status/preview.read 不重新拍摄。",
            listOf(kind, session, p("save", "boolean", "是否保存原图记录", false, "false"), p("max_edge", "integer", "新画面预览最长边 160..2048；需要细小文字时可取 2048", false, "1024")),
            """{"kind":"camera","session_id":"<start 返回的 ID>","save":false}""")
        capability("get_frame", "获取当前视觉帧", read,
            "从活动会话获取新帧，附 frame_id、原始/预览尺寸、采集时间、屏幕 geometry 和 image_to_touch；图像通过附件返回。屏幕不需要调用观察子代理。",
            listOf(kind, session, p("max_edge", "integer", "预览最长边 160..2048", false, "1024")),
            """{"kind":"screen","session_id":"<status 返回的 ID>"}""")
        capability("observe", "自动选择页面观察方式", read,
            "mode=auto 根据完整 UI 快照选择 UI 或图像。UI 返回 page：默认仅首批有文字/描述/可点击/可编辑的紧凑节点，nodes_has_more 为真时按 nodes_next_offset 调用 page.nodes scope=semantic；scope=all 可读全部节点，page.text 可读完整文字。分类仍使用完整树，node_count 为全部节点数；nodes_total 为当前筛选范围数量。include_image=true 为 UI 补充已有 READY 共享屏图像。明确 visual 跳过 UI 树。失败直接报错，不自动授权、启动或降级。页面和图片不是原子快照；page.timings_ms 区分 Host 快照与插件处理。",
            listOf(p("mode", description = "auto/ui/visual", required = false, default = "auto"),
                p("include_image", "boolean", "为 UI 观察附上一帧；visual 本来就返回图像", false, "false"),
                p("max_edge", "integer", "图像最长边 160..2048", false, "1024")), """{"mode":"auto"}""")
        capability("tap_on_frame", "按帧点击并观察", change,
            "对最新全屏共享帧按比例坐标点击一次。observe_mode 默认 new_frame，只保证新帧；stable 等区域像素稳定；change_then_stable 先检测变化再等稳定。scene_profile=dynamic 容忍少量动画，可指定区域。点击前旧帧或变化的几何拒绝执行；点击后观察跟随旋转，重新建立基准并返回 geometry_changes，不重复点击。show_touch_feedback 默认 false，避免点击圆环污染观察。action_success、observation_success、wait_success 分开；TIMEOUT 不可重放点击。像素稳定不保证加载完成。",
            listOf(p("frame_id", description = "最新屏幕帧编号"), p("x", "number", "图片横向比例 0..1"),
                p("y", "number", "图片纵向比例 0..1"), p("max_edge", "integer", "观察帧最长边 160..2048", false, "1024"),
                p("observe_mode", description = "new_frame/stable/change_then_stable", required = false, default = "new_frame"),
                p("show_touch_feedback", "boolean", "是否显示 Host 点击圆环；默认关闭，动作元数据仍返回", false, "false")) + waitParameters,
            """{"frame_id":"<最新帧编号>","x":0.51,"y":0.73,"observe_mode":"change_then_stable"}""")
        capability("wait_for_visual_change", "等待画面变化", read,
            "以最新屏幕 frame_id 为基准，等待选定区域的像素发生变化。仅使用已有会话，返回最终一帧及 visual_wait；wait_success=false/status=TIMEOUT 表示未检测到变化。不能把 success=true 当作条件满足。几何变化/停止明确失败，不自动开会话。",
            listOf(p("frame_id", description = "最新屏幕帧编号"), p("max_edge", "integer", "返回预览最长边 160..2048", false, "1024")) + waitParameters)
        capability("wait_until_stable", "等待画面稳定", read,
            "等待指定区域在 stable_ms 窗口内像素稳定。基于 64×64 RGB 网格；静止页面允许同一最新生产帧跨观察窗口计时，每轮检查采集有效、可见性、错误和几何。samples 是不同生产帧数，observations 是有效观察次数；不伪造帧时间。像素稳定不保证加载完成或渲染器持续出帧。可排除游戏动态区域，TIMEOUT 不得报告稳定。",
            listOf(p("frame_id", description = "最新屏幕帧编号"), p("max_edge", "integer", "返回预览最长边 160..2048", false, "1024")) + waitParameters)
        capability("capture", "拍摄并保存单张图像", write,
            "屏幕单次截图，或未开启会话时相机单拍并自动释放。保存原图记录并返回预览。已有相机会话请用 frame save=true。",
            listOf(kind, source) + cameraOptions, """{"kind":"screen","source_id":"display:0"}""")
        capability("stop", "停止视觉会话", change,
            "kind=all 停止两类视觉，单类可指定 session_id。会验证停止结果，部分失败返回 success=false 和分项详情；不自动重试。",
            listOf(p("kind", description = "screen/camera/all", required = false, default = "all"),
                p("session_id", description = "可选单类会话 ID", required = false)), """{"kind":"all"}""")
        capability("preview.read", "查看最新预览", read,
            "读取最新已获取画面，不重新取帧。附来源和时间及 MCP 图像；停止后保留最后画面，不表示仍在拍摄。",
            listOf(kind, edge), """{"kind":"camera"}""")
        capability("preview.save", "保存当前预览", write,
            "把最新预览保存为图像记录，不重新拍摄。预览最长边 1024；保存原图请用 frame save=true。",
            listOf(kind), """{"kind":"camera"}""")
        capability("images.list", "列出图像记录", read,
            "列出明确保存的图像。最多 60 张、64 MiB，超过后删除最旧记录；临时预览不入此列表。")
        capability("images.read", "查看图像记录", read,
            "读取指定记录的图像预览，返回 MCP 图像，不触发摄像头或屏幕拍摄。",
            listOf(image, edge), """{"asset_id":"<images.list 返回的 ID>"}""")
        capability("images.delete", "删除图像记录", write, "删除指定图像及元数据。",
            listOf(image), """{"asset_id":"<images.list 返回的 ID>"}""")
        capability("images.clear", "清空图像记录", write, "清空本插件已保存图像，不关闭会话、不清除当前预览。")
        capability("page.inspect", "读取当前页面", read,
            "固定当前应用暴露的完整节点文字快照，附 visual_mode 自动观察方式及依据。返回 snapshot_id；在工作台内调用会读取当前工作台，读取其他应用请从兰儿入口调用。",
            listOf(p("display", description = "可选显示标识", required = false)))
        capability("page.text", "读取页面全文", read,
            "读取固定快照全文。按 next_offset 续读到 has_more=false；偏移按 UTF-16 字符，不能猜测跳页。",
            listOf(p("snapshot_id", description = "page.inspect 返回的 ID"),
                p("node_id", description = "可选节点 ID", required = false),
                p("field", description = "节点字段 text/content_description；未指定节点时读取全文", required = false, default = "text"),
                p("offset", "integer", "全文偏移", false, "0"),
                p("limit", "integer", "分页字符数，默认 12000", false, "12000")),
            """{"snapshot_id":"<page.inspect 返回的 ID>","offset":0}""")
        capability("page.nodes", "分页读取固定页面节点", read,
            "读取 observe/page.inspect 的同一固定快照节点；scope=all 全部节点，semantic 仅有文字/描述/可点击/可编辑节点。offset 是当前 scope 的节点序号，按 nodes_next_offset 续读，不重新获取 UI。节点 ID 不变，文字全文仍用 page.text。快照过期明确报错，不返回其他快照。",
            listOf(p("snapshot_id", description = "observe/page.inspect 返回的 ID"),
                p("scope", description = "all/semantic", required = false, default = "all"),
                p("offset", "integer", "当前 scope 的节点偏移", false, "0"),
                p("limit", "integer", "每页节点数 1..100", false, "20")),
            """{"snapshot_id":"<observe 返回的 ID>","scope":"semantic","offset":0,"limit":8}""")

        host.logger.i("VisualWorkbench", "Visual Workbench mounted")
        return InProcessPluginHandle { controller.dispose() }
    }
}
