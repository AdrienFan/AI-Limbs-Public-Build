package com.ai.limbs.plugins.visualmanager

import com.ai.limbs.plugin.runtime.InProcessCapabilityDomain
import com.ai.limbs.plugin.runtime.InProcessCapabilityEffect
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessCapabilityParameterSpec
import com.ai.limbs.plugin.runtime.InProcessCapabilitySpec
import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import org.json.JSONArray
import org.json.JSONObject

class VisualManagerEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        require(host.pluginId == VISUAL_PLUGIN_ID) { "Unexpected Visual Workbench identity" }
        val controller = VisualManagerController(host)
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
            listOf(kind, session, p("save", "boolean", "是否保存原图记录", false, "false")),
            """{"kind":"camera","session_id":"<start 返回的 ID>","save":false}""")
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
            "固定当前应用暴露的完整节点文字快照。返回 snapshot_id；在工作台内调用会读取当前工作台，读取其他应用请从兰儿入口调用。",
            listOf(p("display", description = "可选显示标识", required = false)))
        capability("page.text", "读取页面全文", read,
            "读取固定快照全文。按 next_offset 续读到 has_more=false；偏移按 UTF-16 字符，不能猜测跳页。",
            listOf(p("snapshot_id", description = "page.inspect 返回的 ID"),
                p("node_id", description = "可选节点 ID", required = false),
                p("offset", "integer", "全文偏移", false, "0"),
                p("limit", "integer", "分页字符数，默认 12000", false, "12000")),
            """{"snapshot_id":"<page.inspect 返回的 ID>","offset":0}""")

        host.logger.i("VisualWorkbench", "Visual Workbench mounted")
        return InProcessPluginHandle { controller.dispose() }
    }
}
