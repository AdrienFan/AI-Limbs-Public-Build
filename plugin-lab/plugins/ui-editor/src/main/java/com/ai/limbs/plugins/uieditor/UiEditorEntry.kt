package com.ai.limbs.plugins.uieditor

import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import org.json.JSONArray
import org.json.JSONObject

class UiEditorEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        require(host.pluginId == PLUGIN_ID) { "Unexpected UI Editor identity: ${host.pluginId}" }
        val provider = UiEditorPageProvider(host)
        host.registerProvider(
            PAGE_PROVIDER_ID,
            provider,
            mapOf("kind" to "plugin_page", "screen_id" to SCREEN_ID)
        )
        host.registerScreen(
            InProcessScreen(
                id = SCREEN_ID,
                title = "UI 编辑器",
                description = "编辑 AI Limbs 页面布局与界面元素。",
                schemaId = PLUGIN_CENTER_UI_SCHEMA,
                documentJson = JSONObject()
                    .put("schema", 1)
                    .put("layout", "edge_to_edge")
                    .put(
                        "blocks",
                        JSONArray().put(
                            JSONObject().put("type", "plugin_page").put("provider_id", PAGE_PROVIDER_ID)
                        )
                    )
                    .toString()
            )
        )
        host.registerHomeTile(
            InProcessHomeTile(
                id = TILE_ID,
                title = "UI 编辑器",
                description = "编辑工具箱等 AI Limbs 页面布局与界面元素",
                screenId = SCREEN_ID
            )
        )
        host.logger.i("UiEditor", "UI Editor mounted")
        return InProcessPluginHandle { host.logger.i("UiEditor", "UI Editor stopped") }
    }

    private companion object {
        const val PLUGIN_ID = "plugin.system.ui_editor"
        const val PAGE_PROVIDER_ID = "plugin.system.ui_editor.page"
        const val SCREEN_ID = "plugin.system.ui_editor.screen"
        const val TILE_ID = "plugin.system.ui_editor.tile"
        const val PLUGIN_CENTER_UI_SCHEMA = "ai_limbs.plugin_center.ui.v1"
    }
}
