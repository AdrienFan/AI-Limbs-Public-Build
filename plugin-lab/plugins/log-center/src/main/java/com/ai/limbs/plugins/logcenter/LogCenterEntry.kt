package com.ai.limbs.plugins.logcenter

import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import org.json.JSONArray
import org.json.JSONObject

class LogCenterEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        require(host.pluginId == PLUGIN_ID) { "Unexpected Log Center identity: ${host.pluginId}" }
        val provider = LogCenterPageProvider(host)
        host.registerProvider(
            PAGE_PROVIDER_ID,
            provider,
            mapOf("kind" to "plugin_page", "screen_id" to SCREEN_ID)
        )
        host.registerScreen(
            InProcessScreen(
                id = SCREEN_ID,
                title = "日志中心",
                description = "动态筛选并导出 AI Limbs 基座、父插件与子插件日志。",
                schemaId = PLUGIN_CENTER_UI_SCHEMA,
                documentJson = JSONObject()
                    .put("schema", 1)
                    .put("layout", "edge_to_edge")
                    .put("blocks", JSONArray().put(
                        JSONObject().put("type", "plugin_page").put("provider_id", PAGE_PROVIDER_ID)
                    ))
                    .toString()
            )
        )
        host.registerHomeTile(
            InProcessHomeTile(
                id = TILE_ID,
                title = "日志中心",
                description = "按基座、插件或子插件筛选、查看与导出日志",
                screenId = SCREEN_ID
            )
        )
        host.logger.i("LogCenter", "Log Center mounted")
        return InProcessPluginHandle { host.logger.i("LogCenter", "Log Center stopped") }
    }

    private companion object {
        const val PLUGIN_ID = "plugin.system.log_center"
        const val PAGE_PROVIDER_ID = "plugin.system.log_center.page"
        const val SCREEN_ID = "plugin.system.log_center.screen"
        const val TILE_ID = "plugin.system.log_center.tile"
        const val PLUGIN_CENTER_UI_SCHEMA = "ai_limbs.plugin_center.ui.v1"
    }
}
