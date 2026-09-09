package com.ai.limbs.plugins.systemenvironment

import com.ai.limbs.plugin.runtime.ChildExtensionBinder
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import com.ai.limbs.plugin.runtime.InProcessSystemIds
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentContract
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class SystemEnvironmentCenterEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        require(host.pluginId == SystemEnvironmentContract.PARENT_PLUGIN_ID) {
            "Unexpected system environment parent identity"
        }
        val registry = SystemEnvironmentSubsystemRegistry()
        val pageProvider = SystemEnvironmentCenterPageProvider(host, registry)
        SystemEnvironmentCapabilityRouter(registry).register(host)

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

        var pointHandle: AutoCloseable? = null
        val pointObserver = host.scope.launch {
            host.providers.observe(InProcessSystemIds.EXTENSION_HUB_PROVIDER).collect { binding ->
                pointHandle?.close()
                pointHandle = null
                if (binding != null) {
                    check(binding.ownerPluginId == InProcessSystemIds.EXTENSION_HUB_PLUGIN_ID) {
                        "Extension Hub provider has an unexpected owner"
                    }
                    val hub = binding.payload as? ExtensionHubService
                        ?: error("Extension Hub provider payload is incompatible")
                    pointHandle = hub.publishPoint(
                        ownerPluginId = host.pluginId,
                        point = SystemEnvironmentContract.EXTENSION_POINT,
                        apiVersion = SystemEnvironmentContract.API_VERSION,
                        title = "系统环境子系统",
                        description = "提供运行时控制、通用能力端点与一个前台 Display Adapter。",
                        allowedHostCapabilities = setOf(HOST_NETWORK_CAPABILITY),
                        binder = ChildExtensionBinder(registry::bind)
                    )
                }
            }
        }

        return InProcessPluginHandle {
            pointObserver.cancelAndJoin()
            pointHandle?.close()
        }
    }

    private companion object {
        const val PAGE_PROVIDER_ID = "plugin.system_environment_center.page"
        const val TILE_ID = "plugin.system.environment_center.tile"
        const val PLUGIN_CENTER_UI_SCHEMA = "ai_limbs.plugin_center.ui.v1"
        const val SYSTEM_ENVIRONMENT_PAGE_COMPONENT_ID = "system_environment_page"
        const val SYSTEM_ENVIRONMENT_CHILD_SLOT = "after"
        const val HOST_NETWORK_CAPABILITY = "host.network@1"
    }
}
