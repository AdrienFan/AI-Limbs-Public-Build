package com.ai.limbs.plugins.laneraccess

import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import org.json.JSONArray
import org.json.JSONObject

class LanerAccessManagerEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        require(host.pluginId == PLUGIN_ID) {
            "Unexpected Laner access manager identity: ${host.pluginId}"
        }
        val pageProvider = LanerAccessManagerPageProvider(host)
        host.registerProvider(
            PAGE_PROVIDER_ID,
            pageProvider,
            mapOf(
                "kind" to "plugin_page",
                "screen_id" to SCREEN_ID
            )
        )
        host.registerScreen(
            InProcessScreen(
                id = SCREEN_ID,
                title = "兰儿接入管理",
                description = "管理自定义接入提示与工作手册，数据与历史由 AI Limbs Host 持有。",
                schemaId = PLUGIN_CENTER_UI_SCHEMA,
                documentJson = JSONObject()
                    .put("schema", 1)
                    .put("layout", "edge_to_edge")
                    .put(
                        "blocks",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "plugin_page")
                                .put("provider_id", PAGE_PROVIDER_ID)
                        )
                    )
                    .toString()
            )
        )
        host.registerHomeTile(
            InProcessHomeTile(
                id = TILE_ID,
                title = "兰儿接入管理",
                description = "自定义接入提示、工作手册与历史恢复",
                screenId = SCREEN_ID
            )
        )
        return InProcessPluginHandle { Unit }
    }

    private companion object {
        const val PLUGIN_ID = "plugin.laner.access_manager"
        const val PAGE_PROVIDER_ID = "plugin.laner.access_manager.page"
        const val SCREEN_ID = "plugin.laner.access_manager.screen"
        const val TILE_ID = "plugin.laner.access_manager.tile"
        const val PLUGIN_CENTER_UI_SCHEMA = "ai_limbs.plugin_center.ui.v1"
    }
}
