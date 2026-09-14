package com.ai.limbs.plugins.permission

import com.ai.limbs.plugin.runtime.*
import org.json.JSONArray
import org.json.JSONObject

class PermissionEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        require(host.pluginId == ID) { "Unexpected permission service owner" }
        val controller = PermissionController(host)
        host.registerProvider("$ID.page", PermissionPage(host, controller),
            mapOf("kind" to "plugin_page", "screen_id" to "$ID.screen"))
        host.registerScreen(InProcessScreen(
            id = "$ID.screen", title = "AI Limbs 权限服务",
            description = "配对、启动和管理 Android 权限服务",
            schemaId = "ai_limbs.plugin_center.ui.v1",
            documentJson = JSONObject().put("schema", 1).put("layout", "edge_to_edge")
                .put("blocks", JSONArray().put(JSONObject().put("type", "plugin_page")
                    .put("provider_id", "$ID.page"))).toString()
        ))
        host.registerHomeTile(InProcessHomeTile(
            id = "$ID.tile", title = "权限服务",
            description = "AI Limbs 权限服务 · 无线调试与 root",
            screenId = "$ID.screen"
        ))
        host.registerCapability(InProcessCapabilitySpec(
            id = "plugin.permission_service.status", displayName = "AI Limbs 权限服务状态",
            description = "读取内置权限服务连接状态及当前执行后端",
            keywords = listOf("权限服务", "Shizuku", "ADB", "permission"),
            effect = InProcessCapabilityEffect.READ_ONLY,
            domain = InProcessCapabilityDomain.PLUGIN,
            executor = InProcessCapabilityExecutor { controller.refresh().toString() }
        ))
        host.registerCapability(InProcessCapabilitySpec(
            id = "plugin.permission_service.start", displayName = "启动 AI Limbs 权限服务",
            description = "通过已经配对的本机无线调试端口启动独立权限服务",
            parameters = listOf(InProcessCapabilityParameterSpec("port", "integer", "无线调试主页上的连接端口")),
            inputSchema = """{"type":"object","properties":{"port":{"type":"integer","minimum":1,"maximum":65535}},"required":["port"],"additionalProperties":false}""",
            effect = InProcessCapabilityEffect.PROCESS_EXECUTION,
            domain = InProcessCapabilityDomain.PLUGIN,
            executor = InProcessCapabilityExecutor { args ->
                controller.startAdb(JSONObject(args).getInt("port"))
                controller.refresh().toString()
            }
        ))
        host.registerCapability(InProcessCapabilitySpec(
            id = "plugin.permission_service.stop", displayName = "停止 AI Limbs 权限服务",
            description = "停止内置服务并撤销该次启动许可",
            effect = InProcessCapabilityEffect.STATE_CHANGE,
            domain = InProcessCapabilityDomain.PLUGIN,
            executor = InProcessCapabilityExecutor {
                controller.stop()
                controller.refresh().toString()
            }
        ))
        host.logger.i("PermissionService", "Permission service plugin mounted")
        return InProcessPluginHandle { controller.close() }
    }
    private companion object { const val ID = "plugin.system.permission_service" }
}
