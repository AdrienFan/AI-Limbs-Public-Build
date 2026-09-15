package com.ai.limbs.plugins.permission

import com.ai.limbs.plugin.runtime.InProcessPluginPresentationEntry
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHandle
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHost
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONObject

class PermissionPresentationEntry : InProcessPluginPresentationEntry {
    override suspend fun mount(
        host: InProcessPluginPresentationHost
    ): InProcessPluginPresentationHandle {
        require(host.pluginId == ID) {
            "Unexpected permission service presentation owner"
        }
        val controller = PermissionPresentationController(host)
        val registration = host.registerPageProvider(
            "$ID.page",
            PermissionPage(host, controller),
            mapOf("kind" to "plugin_page", "screen_id" to "$ID.screen")
        )
        return InProcessPluginPresentationHandle { registration.close() }
    }

    private companion object {
        const val ID = "plugin.system.permission_service"
    }
}

private class PermissionPresentationController(
    private val host: InProcessPluginPresentationHost
) : PermissionPageController {
    private val provider: InProcessUiStateProvider =
        requireNotNull(host.providers.resolve(UI_PROVIDER_ID)?.payload as? InProcessUiStateProvider) {
            "Permission Service Core UI provider is unavailable"
        }

    override val state: StateFlow<PermissionState> =
        provider.stateJson
            .map(::permissionStateFromUiJson)
            .stateIn(
                host.scope,
                SharingStarted.Eagerly,
                permissionStateFromUiJson(provider.stateJson.value)
            )

    private suspend fun perform(
        event: String,
        payload: JSONObject = JSONObject()
    ): JSONObject = JSONObject(provider.perform(event, payload.toString()))

    override suspend fun refresh(): JSONObject = perform("refresh")

    override suspend fun pair(port: Int, code: String) {
        perform("pair", JSONObject().put("port", port).put("code", code))
    }

    override suspend fun startAdb(port: Int) {
        perform("start_adb", JSONObject().put("port", port))
    }

    override suspend fun startRoot() {
        perform("start_root")
    }

    override suspend fun stop() {
        perform("stop")
    }

    override suspend fun select(backend: String) {
        perform("select", JSONObject().put("backend", backend))
    }

    private companion object {
        const val UI_PROVIDER_ID = "plugin.system.permission_service.ui"
    }
}
