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
        require(host.pluginId == VISUAL_PLUGIN_ID) {
            "Unexpected Visual Manager identity: ${host.pluginId}"
        }

        val controller = VisualManagerController(host)
        host.registerProvider(
            VISUAL_PAGE_ID,
            VisualManagerPageProvider(host),
            mapOf("kind" to "plugin_page", "screen_id" to VISUAL_SCREEN_ID)
        )
        host.registerScreen(
            InProcessScreen(
                id = VISUAL_SCREEN_ID,
                title = "视觉管理",
                description = "管理屏幕共享、摄像头视觉会话与视觉缓存。",
                schemaId = "ai_limbs.plugin_center.ui.v1",
                documentJson = JSONObject()
                    .put("schema", 1)
                    .put("layout", "edge_to_edge")
                    .put(
                        "blocks",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "plugin_page")
                                .put("provider_id", VISUAL_PAGE_ID)
                        )
                    )
                    .toString()
            )
        )
        host.registerHomeTile(
            InProcessHomeTile(
                id = VISUAL_TILE_ID,
                title = "视觉管理",
                description = "屏幕共享 · 摄像头 · 视觉缓存",
                screenId = VISUAL_SCREEN_ID
            )
        )

        fun parameter(
            name: String,
            type: String = "string",
            description: String = "",
            required: Boolean = true,
            default: String? = null
        ) = InProcessCapabilityParameterSpec(
            name = name,
            type = type,
            description = description,
            required = required,
            default = default
        )

        fun capability(
            name: String,
            title: String,
            effect: InProcessCapabilityEffect,
            description: String,
            parameters: List<InProcessCapabilityParameterSpec> = emptyList(),
            block: suspend (JSONObject) -> JSONObject
        ) {
            val properties = JSONObject()
            val required = JSONArray()
            parameters.forEach { item ->
                val field = JSONObject()
                    .put("type", item.type)
                    .put("description", item.description)
                item.default?.let { field.put("default", it) }
                properties.put(item.name, field)
                if (item.required) required.put(item.name)
            }
            host.registerCapability(
                InProcessCapabilitySpec(
                    id = "$VISUAL_PLUGIN_ID.$name",
                    displayName = title,
                    description = description,
                    invokeAliases = listOf("visual.$name"),
                    keywords = listOf("视觉", "屏幕", "摄像头", "共享"),
                    parameters = parameters,
                    inputSchema = JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", required)
                        .put("additionalProperties", false)
                        .toString(),
                    effect = effect,
                    domain = InProcessCapabilityDomain.PLUGIN,
                    executor = InProcessCapabilityExecutor { json ->
                        block(JSONObject(json.ifBlank { "{}" })).toString()
                    }
                )
            )
        }

        val read = InProcessCapabilityEffect.READ_ONLY
        val change = InProcessCapabilityEffect.STATE_CHANGE
        val write = InProcessCapabilityEffect.PERSISTENT_WRITE

        capability(
            "status",
            "读取视觉管理状态",
            read,
            "读取本插件持有的屏幕共享、摄像头会话、可用视觉来源和视觉缓存摘要。"
        ) { controller.dashboard() }

        capability(
            "screen.list_targets",
            "列出屏幕共享目标",
            read,
            "列出 Host 当前可用于屏幕视觉会话的显示目标。"
        ) { controller.screenTargets() }

        capability(
            "screen.capture",
            "单次屏幕截图",
            change,
            "通过 Host 的单帧截图原语获取一张屏幕图像，并归档进视觉管理缓存。"
        ) { controller.screenCapture(it) }

        capability(
            "screen.start",
            "开始屏幕共享",
            change,
            "创建本插件拥有的屏幕视觉会话。默认 prime=true，会立即取首帧并触发必要的系统共享屏授权。",
            listOf(
                parameter("target_id", description = "目标 ID，例如 display:0", required = false),
                parameter("prime", "boolean", "是否立即取首帧并触发授权", false, "true")
            )
        ) { controller.screenStart(it) }

        capability(
            "screen.stop",
            "停止屏幕共享",
            change,
            "停止指定屏幕视觉会话；不传 session_id 时停止本插件拥有的全部屏幕会话。",
            listOf(
                parameter("session_id", description = "屏幕会话 ID", required = false)
            )
        ) { controller.screenStop(it) }

        capability(
            "screen.frame",
            "读取屏幕共享帧",
            change,
            "从指定屏幕视觉会话读取一帧，并将可访问的结果归档进视觉管理缓存。",
            listOf(parameter("session_id", description = "屏幕会话 ID"))
        ) { controller.screenFrame(it) }

        capability(
            "camera.list_sources",
            "列出摄像头来源",
            read,
            "列出可用摄像头、朝向、传感器方向和常用 JPEG 尺寸，并报告 CAMERA 权限状态。"
        ) { controller.cameraSources() }

        capability(
            "camera.start",
            "开始摄像头视觉会话",
            change,
            "启动本插件拥有的持续摄像头视觉会话。",
            cameraParameters(::parameter)
        ) { controller.cameraStart(it) }

        capability(
            "camera.configure",
            "配置摄像头视觉会话",
            change,
            "修改 JPEG 质量或方向；修改宽高时 Host 会报告是否需要重启会话。",
            listOf(
                parameter("session_id", description = "摄像头会话 ID"),
                parameter("jpeg_quality", "integer", "JPEG 质量 1-100", false),
                parameter("jpeg_orientation", "integer", "JPEG 方向角度", false),
                parameter("width", "integer", "期望宽度", false),
                parameter("height", "integer", "期望高度", false)
            )
        ) { controller.cameraConfigure(it) }

        capability(
            "camera.stop",
            "停止摄像头视觉会话",
            change,
            "停止指定摄像头会话；不传 session_id 时停止本插件拥有的全部摄像头会话。",
            listOf(
                parameter("session_id", description = "摄像头会话 ID", required = false)
            )
        ) { controller.cameraStop(it) }

        capability(
            "camera.frame",
            "读取摄像头会话帧",
            change,
            "从指定持续摄像头会话读取一帧，并归档进视觉管理缓存。",
            listOf(parameter("session_id", description = "摄像头会话 ID"))
        ) { controller.cameraFrame(it) }

        capability(
            "camera.capture",
            "摄像头单帧拍摄",
            change,
            "临时打开指定摄像头拍摄一帧，Host 拍摄完成后立即释放摄像头，并将结果归档进视觉管理缓存。",
            cameraParameters(::parameter)
        ) { controller.cameraCapture(it) }

        capability(
            "assets.list",
            "列出视觉缓存",
            read,
            "列出视觉管理插件保存的屏幕和摄像头帧及其大小、时间和本地路径。"
        ) { controller.listAssets() }

        capability(
            "assets.delete",
            "删除视觉缓存项",
            write,
            "删除指定视觉缓存项及其元数据。",
            listOf(parameter("asset_id", description = "assets.list 返回的 asset_id"))
        ) { controller.deleteAsset(it) }

        capability(
            "assets.clear",
            "清空视觉缓存",
            write,
            "清空视觉管理插件自己的视觉缓存以及属于本插件的 Host 临时帧目录。"
        ) { controller.clearAssets() }

        host.logger.i("VisualManager", "Visual Manager mounted")
        return InProcessPluginHandle {
            runCatching { controller.stopAll() }
                .onFailure { host.logger.e("VisualManager", "Failed to stop visual sessions", it) }
            host.logger.i("VisualManager", "Visual Manager stopped")
        }
    }

    private fun cameraParameters(
        parameter: (
            String,
            String,
            String,
            Boolean,
            String?
        ) -> InProcessCapabilityParameterSpec
    ): List<InProcessCapabilityParameterSpec> = listOf(
        parameter("source_id", "string", "摄像头 source_id；为空时可按 lens_facing 选择", false, null),
        parameter("lens_facing", "string", "back、front 或 external", false, "back"),
        parameter("width", "integer", "期望 JPEG 宽度", false, "1280"),
        parameter("height", "integer", "期望 JPEG 高度", false, "720"),
        parameter("jpeg_quality", "integer", "JPEG 质量 1-100", false, "92"),
        parameter("jpeg_orientation", "integer", "JPEG 方向角度", false, "0")
    )
}
