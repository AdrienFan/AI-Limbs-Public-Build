package com.ai.limbs.plugins.permission

import com.ai.limbs.plugin.runtime.InProcessProviderBinding
import com.ai.limbs.plugin.runtime.InProcessProviderDirectory
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
internal class PermissionPresentationController(
    private val providers: InProcessProviderDirectory,
    scope: CoroutineScope,
    reportError: (Throwable) -> Unit
) : PermissionPageController {
    override val state: StateFlow<PermissionState> = providers.observe(UI_PROVIDER_ID)
        .flatMapLatest { binding ->
            val provider = uiProvider(binding)
            if (provider == null) {
                flowOf(synchronizing())
            } else {
                provider.stateJson.map { raw ->
                    if (raw.isNullOrBlank()) synchronizing() else permissionStateFromUiJson(raw)
                }
            }
        }
        .catch { error ->
            if (error is CancellationException) throw error
            reportError(error)
            emit(synchronizing().copy(message = "服务状态同步失败，请查看日志中心", statusError = "状态同步失败"))
        }
        .stateIn(scope, SharingStarted.Eagerly, synchronizing())

    private fun uiProvider(binding: InProcessProviderBinding?): InProcessUiStateProvider? {
        if (binding == null) return null
        check(binding.id == UI_PROVIDER_ID && binding.ownerPluginId == OWNER_ID) {
            "Permission UI-state provider identity mismatch"
        }
        return checkNotNull(binding.payload as? InProcessUiStateProvider) {
            "Permission UI-state provider has an incompatible protocol"
        }
    }

    private suspend fun perform(event: String, payload: JSONObject = JSONObject()): JSONObject {
        // Resolve the current binding for each action: an upgraded/replaced provider must never
        // receive actions through a reference captured from the preceding plugin version.
        val provider = checkNotNull(uiProvider(providers.resolve(UI_PROVIDER_ID))) {
            "服务状态尚未同步，请稍后再操作"
        }
        return JSONObject(provider.perform(event, payload.toString()))
    }

    override suspend fun refresh(): JSONObject = perform("refresh")
    override suspend fun pair(port: Int, code: String) {
        perform("pair", JSONObject().put("port", port).put("code", code))
    }
    override suspend fun startAdb(port: Int) { perform("start_adb", JSONObject().put("port", port)) }
    override suspend fun startRoot() { perform("start_root") }
    override suspend fun stop() { perform("stop") }
    override suspend fun select(backend: String) { perform("select", JSONObject().put("backend", backend)) }
    override suspend fun setExternalEnabled(enabled: Boolean) {
        perform("external_enable", JSONObject().put("enabled", enabled))
    }
    override suspend fun connectExternal(packageName: String) {
        perform("external_connect", JSONObject().put("package_name", packageName))
    }
    override suspend fun decideExternal(uid: Int, identity: String, mode: String) {
        perform("external_decide", JSONObject().put("uid", uid).put("identity", identity).put("mode", mode))
    }

    private companion object {
        const val OWNER_ID = "plugin.system.permission_service"
        const val UI_PROVIDER_ID = "$OWNER_ID.ui"
        fun synchronizing() = PermissionState(
            busy = true, message = "正在同步服务状态", statusError = "状态同步中",
            managementError = "服务状态尚未同步"
        )
    }
}
