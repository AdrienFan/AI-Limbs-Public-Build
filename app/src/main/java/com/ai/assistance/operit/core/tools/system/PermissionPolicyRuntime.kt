package com.ai.assistance.operit.core.tools.system

import android.content.Context
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

    fun readBlocking(context: Context): PermissionPolicyState =
        runBlocking { read(context.applicationContext) }

    suspend fun read(context: Context): PermissionPolicyState {
        if (!ResidentCoreProcessIdentity.isCurrentProcessCore()) {
            return PermissionPolicyState(
                coexistEnabled = androidPermissionPreferences.getPermissionCoexistEnabled(),
                legacyPreferred = androidPermissionPreferences.getPreferredPermissionLevel(),
                source = "android_host"
            )
        }

        val hostState =
            runCatching {
                withContext(Dispatchers.IO) {
                    ResidentHostComponentProxy.request(
                        ResidentComponentProxyBroker.KIND_PERMISSION_POLICY_HOST,
                        JSONObject().put("action", "state")
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

        return PermissionPolicyState(
            coexistEnabled = androidPermissionPreferences.getPermissionCoexistEnabled(),
            legacyPreferred = androidPermissionPreferences.getPreferredPermissionLevel(),
            source = "resident_local_fallback"
        )
    }
}
