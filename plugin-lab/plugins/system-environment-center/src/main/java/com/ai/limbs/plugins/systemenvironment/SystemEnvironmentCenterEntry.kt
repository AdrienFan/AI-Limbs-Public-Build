package com.ai.limbs.plugins.systemenvironment

import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.UbuntuSubsystem
import org.json.JSONArray
import org.json.JSONObject

class SystemEnvironmentCenterEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        val ubuntu = UbuntuSubsystem.mount(host)
        val pageProvider = ubuntu.pageProvider

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
                title = "系统环境中心",
                description = "系统环境中心父级插件；当前内置 Ubuntu 迁移子系统，后续统一承载 Ubuntu、Winlator 等系统环境。",
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
                title = "系统环境中心",
                description = "统一承载和管理系统环境；Ubuntu 作为首个内置迁移子系统运行。",
                screenId = SCREEN_ID
            )
        )

        return InProcessPluginHandle { ubuntu.close() }
    }

    private companion object {
        const val PAGE_PROVIDER_ID = "plugin.system_environment_center.page"
        const val SCREEN_ID = "plugin.system_environment_center.screen"
        const val TILE_ID = "plugin.system.environment_center.tile"
        const val PLUGIN_CENTER_UI_SCHEMA = "ai_limbs.plugin_center.ui.v1"
    }
}
