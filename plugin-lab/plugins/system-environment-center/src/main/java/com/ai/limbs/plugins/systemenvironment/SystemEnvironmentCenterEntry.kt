package com.ai.limbs.plugins.systemenvironment

import com.ai.limbs.plugin.runtime.ChildExtensionBinder
import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentContract
import org.json.JSONArray
import org.json.JSONObject

class SystemEnvironmentCenterEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        require(host.pluginId == SystemEnvironmentContract.PARENT_PLUGIN_ID) {
            "Unexpected system environment parent identity"
        }
        val registry = SystemEnvironmentSubsystemRegistry()
        val pageProvider = SystemEnvironmentCenterPageProvider(host, registry)

        host.registerProvider(
            PAGE_PROVIDER_ID,
            pageProvider,
            mapOf("kind" to "plugin_page", "screen_id" to SystemEnvironmentContract.SCREEN_ID)
        )
        host.registerScreen(
            InProcessScreen(
                id = SystemEnvironmentContract.SCREEN_ID,
                title = "系统环境中心",
                description = "通用系统环境空壳、环境配置与前台 Display Slot。",
                schemaId = PLUGIN_CENTER_UI_SCHEMA,
                documentJson = JSONObject()
                    .put("schema", 1)
                    .put("layout", "edge_to_edge")
                    .put(
                        "blocks",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "component_slot")
                                .put("id", SYSTEM_ENVIRONMENT_PAGE_COMPONENT_ID)
                                .put(
                                    "component",
                                    JSONObject()
                                        .put("type", "plugin_page")
                                        .put("provider_id", PAGE_PROVIDER_ID)
                                )
                                .put(
                                    "child_slots",
                                    JSONObject().put(
                                        SYSTEM_ENVIRONMENT_CHILD_SLOT,
                                        JSONObject().put(
                                            "points",
                                            JSONArray().put(SystemEnvironmentContract.EXTENSION_POINT)
                                        )
                                    )
                                )
                        )
                    )
                    .toString()
            )
        )
        host.registerHomeTile(
            InProcessHomeTile(
                id = TILE_ID,
                title = "系统环境中心",
                description = "管理并显示 Ubuntu、Winlator 等可插拔系统环境。",
                screenId = SystemEnvironmentContract.SCREEN_ID
            )
        )

        val pageSlotService = host.services.resolve(
            PLUGIN_CENTER_UI_ACCESSORY_SERVICE,
            PLUGIN_CENTER_UI_ACCESSORY_API
        )
        var pageSlotRegistered = false
        if (pageSlotService?.metadata?.get("authority") == "plugin_center") {
            pageSlotRegistered = runCatching {
                JSONObject(
                    pageSlotService.invoke(
                        "register_page_slot_action",
                        JSONObject()
                            .put("action_id", PAGE_SLOT_ACTION_ID)
                            .put("target_page_id", AI_CHAT_PAGE_ID)
                            .put("slot_id", TOP_BAR_START_SLOT)
                            .put("provider_id", PAGE_PROVIDER_ID)
                            .put("icon_key", "terminal")
                            .put("content_description", "系统环境中心")
                            .put("priority", 100)
                            .toString()
                    )
                ).optBoolean("registered", false)
            }.getOrDefault(false)
        }

        val pointHandle = host.childExtensions.publishPoint(
            point = SystemEnvironmentContract.EXTENSION_POINT,
            apiVersion = SystemEnvironmentContract.API_VERSION,
            title = "系统环境子系统",
            description = "提供运行时控制、通用能力端点与一个前台 Display Adapter。",
            allowedHostCapabilities = setOf(HOST_NETWORK_CAPABILITY),
            binder = ChildExtensionBinder(registry::bind)
        )

        return InProcessPluginHandle {
            if (pageSlotRegistered) {
                runCatching {
                    pageSlotService?.invoke(
                        "unregister_page_slot_action",
                        JSONObject().put("action_id", PAGE_SLOT_ACTION_ID).toString()
                    )
                }
            }
            pointHandle.close()
        }
    }

    private companion object {
        const val PAGE_PROVIDER_ID = "plugin.system_environment_center.page"
        const val TILE_ID = "plugin.system.environment_center.tile"
        const val PLUGIN_CENTER_UI_SCHEMA = "ai_limbs.plugin_center.ui.v1"
        const val SYSTEM_ENVIRONMENT_PAGE_COMPONENT_ID = "system_environment_page"
        const val SYSTEM_ENVIRONMENT_CHILD_SLOT = "after"
        const val HOST_NETWORK_CAPABILITY = "host.network@1"
        const val PLUGIN_CENTER_UI_ACCESSORY_SERVICE = "system.plugin_center.ui_accessories"
        const val PLUGIN_CENTER_UI_ACCESSORY_API = 1
        const val PAGE_SLOT_ACTION_ID = "system_environment_center"
        const val AI_CHAT_PAGE_ID = "host:native.ai_chat"
        const val TOP_BAR_START_SLOT = "top_bar_start"
    }
}
