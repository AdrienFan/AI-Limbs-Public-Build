package com.ai.limbs.plugins.permission

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.json.JSONArray

internal interface PermissionPageController {
    val state: StateFlow<PermissionState>

    suspend fun refresh(): JSONObject
    suspend fun pair(port: Int, code: String)
    suspend fun startAdb(port: Int)
    suspend fun startRoot()
    suspend fun stop()
    suspend fun select(backend: String)
    suspend fun setExternalEnabled(enabled: Boolean)
    suspend fun connectExternal(packageName: String)
    suspend fun decideExternal(uid: Int, identity: String, mode: String)
}

internal fun PermissionState.toUiJson(): JSONObject =
    JSONObject()
        .put("running", running)
        .put("backend", backend)
        .put("uid", uid)
        .put("busy", busy)
        .put("message", message)
        .put("status_error", statusError)
        .put("management_available", managementAvailable).put("management_error", managementError)
        .put("external_enabled", externalEnabled).put("pending_count", pendingCount)
        .put("apps", JSONArray().apply { apps.forEach { put(it.toJson()) } })

internal fun permissionStateFromUiJson(raw: String?): PermissionState {
    if (raw.isNullOrBlank()) return PermissionState()
    val value = JSONObject(raw)
    return PermissionState(
        running = value.optBoolean("running", false),
        backend = value.optString("backend", ""),
        uid = value.optInt("uid", -1),
        busy = value.optBoolean("busy", false),
        message = value.optString("message", "正在读取状态"),
        statusError = value.optString("status_error", ""),
        managementAvailable = value.optBoolean("management_available", false),
        managementError = value.optString("management_error", ""),
        externalEnabled = value.optBoolean("external_enabled", false),
        pendingCount = value.optInt("pending_count", 0),
        apps = value.optJSONArray("apps")?.let { rows -> List(rows.length()) { ExternalApp.fromJson(rows.getJSONObject(it)) } }.orEmpty()
    )
}
