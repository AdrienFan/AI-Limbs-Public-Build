package com.ai.limbs.plugins.permission

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

internal interface PermissionPageController {
    val state: StateFlow<PermissionState>

    suspend fun refresh(): JSONObject
    suspend fun pair(port: Int, code: String)
    suspend fun startAdb(port: Int)
    suspend fun startRoot()
    suspend fun stop()
    suspend fun select(backend: String)
}

internal fun PermissionState.toUiJson(): JSONObject =
    JSONObject()
        .put("running", running)
        .put("backend", backend)
        .put("uid", uid)
        .put("busy", busy)
        .put("message", message)

internal fun permissionStateFromUiJson(raw: String?): PermissionState {
    if (raw.isNullOrBlank()) return PermissionState()
    val value = JSONObject(raw)
    return PermissionState(
        running = value.optBoolean("running", false),
        backend = value.optString("backend", ""),
        uid = value.optInt("uid", -1),
        busy = value.optBoolean("busy", false),
        message = value.optString("message", "正在读取状态")
    )
}
