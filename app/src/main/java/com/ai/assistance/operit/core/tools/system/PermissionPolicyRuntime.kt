package com.ai.assistance.operit.core.tools.system

import android.content.Context
import android.os.Looper
import com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker
import com.ai.assistance.operit.core.tools.system.resident.ResidentCoreProcessIdentity
import com.ai.assistance.operit.core.tools.system.resident.ResidentHostComponentProxy
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PermissionPolicyState(
    val coexistEnabled: Boolean,
    val legacyPreferred: AndroidPermissionLevel?,
    val source: String
)

object PermissionPolicyRuntime {
    private const val TAG = "PermissionPolicyRuntime"
    private const val HOST_POLICY_SYNC_TIMEOUT_MS = 750L

    fun readBlocking(context: Context): PermissionPolicyState {
        val appContext = context.applicationContext
        if (
            shouldUseLocalBootstrapState(
                isResidentCore = ResidentCoreProcessIdentity.isCurrentProcessCore(),
                isMainLooper = Looper.myLooper() === Looper.getMainLooper()
            )
        ) {
            // Plugin Kernel bootstrap owns the Resident Core main looper. It must never wait for
            // the Host UI proxy, which is attaching during the same ownership transition.
            return localState("resident_core_bootstrap")
        }
        return runBlocking { read(appContext) }
    }

    suspend fun read(context: Context): PermissionPolicyState {
        if (!ResidentCoreProcessIdentity.isCurrentProcessCore()) {
            return localState("android_host")
        }

        val hostState =
            runCatching {
                withContext(Dispatchers.IO) {
                    ResidentHostComponentProxy.request(
                        ResidentComponentProxyBroker.KIND_PERMISSION_POLICY_HOST,
                        JSONObject().put("action", "state"),
                        timeoutMs = HOST_POLICY_SYNC_TIMEOUT_MS
                    )
                }
            }.getOrElse { error ->
                AppLogger.w(TAG, "Host permission policy unavailable: " + error.message)
                null
            }

        if (hostState != null && hostState.optBoolean("ok", false)) {
            val preferred =
                if (hostState.isNull("preferred_permission_level")) {
                    null
                } else {
                    AndroidPermissionLevel.fromString(
                        hostState.optString("preferred_permission_level")
                    )
                }
            return PermissionPolicyState(
                coexistEnabled = hostState.optBoolean("permission_coexist_enabled", false),
                legacyPreferred = preferred,
                source = "resident_host"
            )
        }
        return localState("resident_local_fallback")
    }

    internal fun shouldUseLocalBootstrapState(
        isResidentCore: Boolean,
        isMainLooper: Boolean
    ): Boolean = isResidentCore && isMainLooper

    private fun localState(source: String): PermissionPolicyState =
        PermissionPolicyState(
            coexistEnabled = androidPermissionPreferences.getPermissionCoexistEnabled(),
            legacyPreferred = androidPermissionPreferences.getPreferredPermissionLevel(),
            source = source
        )
}
