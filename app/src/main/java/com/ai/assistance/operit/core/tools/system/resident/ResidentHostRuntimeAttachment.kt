package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import org.json.JSONObject

internal enum class ResidentHostRuntimeMode {
    LEGACY_HOST,
    UI_PROXY_ATTACHED,
    UI_PROXY_PENDING,
    UI_PROXY_BLOCKED
}

internal data class ResidentHostRuntimeAttachment(
    val mode: ResidentHostRuntimeMode,
    val reason: String,
    val corePid: Int? = null,
    val coreSessionId: String? = null,
    val coreBuildMatches: Boolean? = null,
    val businessPhase: String? = null,
    val fenceState: String? = null
) {
    val usesUiProxy: Boolean get() = mode != ResidentHostRuntimeMode.LEGACY_HOST
    val attachedToLiveCore: Boolean get() = mode == ResidentHostRuntimeMode.UI_PROXY_ATTACHED

    fun snapshot(): JSONObject = JSONObject()
        .put("mode", mode.name.lowercase())
        .put("reason", reason)
        .put("core_pid", corePid ?: JSONObject.NULL)
        .put("core_session_id", coreSessionId ?: JSONObject.NULL)
        .put("core_build_matches", coreBuildMatches ?: JSONObject.NULL)
        .put("business_phase", businessPhase ?: JSONObject.NULL)
        .put("fence_state", fenceState ?: JSONObject.NULL)
}

/**
 * Selects the Android Host startup role before PluginPlatformKernel or business services start.
 *
 * A reachable Resident Core with business ownership forces UI_PROXY. A takeover fence also forces
 * UI_PROXY even when Core is temporarily unavailable, so Host restart can never become a silent
 * LEGACY_HOST fallback. LEGACY_HOST is allowed only when no takeover ownership/fence remains after
 * an explicit OFF/detached state transition.
 */
internal object ResidentHostRuntimeResolver {
    suspend fun resolve(context: Context): ResidentHostRuntimeAttachment {
        val appContext = context.applicationContext
        val fenceResult = runCatching { ResidentBusinessTakeoverFence.snapshot(appContext) }
        if (fenceResult.isFailure) {
            return ResidentHostRuntimeAttachment(
                mode = ResidentHostRuntimeMode.UI_PROXY_BLOCKED,
                reason = "takeover_fence_invalid:${fenceResult.exceptionOrNull()}"
            )
        }
        val fence = fenceResult.getOrNull()
        val fenceState = fence?.optString("state")?.takeIf { it.isNotBlank() }

        val coreResult = runCatching { ResidentCoreController.status(appContext) }
        if (coreResult.isFailure) {
            return if (fence != null) {
                ResidentHostRuntimeAttachment(
                    mode = ResidentHostRuntimeMode.UI_PROXY_BLOCKED,
                    reason = "core_status_failed_with_takeover_fence:${coreResult.exceptionOrNull()}",
                    fenceState = fenceState
                )
            } else {
                ResidentHostRuntimeAttachment(
                    mode = ResidentHostRuntimeMode.LEGACY_HOST,
                    reason = "core_status_failed_without_takeover_fence"
                )
            }
        }

        val core = coreResult.getOrThrow()
        if (!core.optBoolean("available", false)) {
            return if (fence != null) {
                ResidentHostRuntimeAttachment(
                    mode = ResidentHostRuntimeMode.UI_PROXY_BLOCKED,
                    reason = "core_unavailable_while_takeover_fence_exists",
                    fenceState = fenceState
                )
            } else {
                ResidentHostRuntimeAttachment(
                    mode = ResidentHostRuntimeMode.LEGACY_HOST,
                    reason = "resident_core_not_running"
                )
            }
        }

        val buildMatches = core.optBoolean("build_matches", false)
        val coreConsistent = core.optBoolean("consistent", false)
        val corePid = core.optInt("pid").takeIf { it > 0 }
        val coreSession = core.optString("session_id").takeIf { it.isNotBlank() }
        val owner = core.optString("runtime_owner")
        val businessPhase = core.optString("business_phase")
        val businessAttached = core.optBoolean("business_attached", false)
        val kernelStarted = core.optBoolean("plugin_kernel_started", false)
        val bridgeIngressPrepared = core.optBoolean("bridge_ingress_prepared", false)
        val fenceMatchesCore = fence != null &&
            corePid != null &&
            coreSession != null &&
            fence.optInt("core_pid") == corePid &&
            fence.optString("core_session") == coreSession

        if (businessAttached) {
            val validOwner = buildMatches &&
                coreConsistent &&
                owner == "resident_core" &&
                core.optString("phase") == "running" &&
                businessPhase == "running" &&
                kernelStarted &&
                bridgeIngressPrepared &&
                fenceMatchesCore &&
                fenceState == "owned"
            return if (validOwner) {
                ResidentHostRuntimeAttachment(
                    mode = ResidentHostRuntimeMode.UI_PROXY_ATTACHED,
                    reason = "resident_core_is_business_owner",
                    corePid = corePid,
                    coreSessionId = coreSession,
                    coreBuildMatches = true,
                    businessPhase = businessPhase,
                    fenceState = fenceState
                )
            } else {
                ResidentHostRuntimeAttachment(
                    mode = ResidentHostRuntimeMode.UI_PROXY_BLOCKED,
                    reason = "resident_core_business_snapshot_inconsistent",
                    corePid = corePid,
                    coreSessionId = coreSession,
                    coreBuildMatches = buildMatches,
                    businessPhase = businessPhase,
                    fenceState = fenceState
                )
            }
        }

        val handoffPending = owner == "handoff_pending" || businessPhase in setOf(
            "waiting_for_host_exit",
            "acquiring_owner",
            "starting_kernel",
            "claiming_backend",
            "starting_bridge",
            "starting_plugin_services"
        )
        if (handoffPending || fence != null) {
            val validPending = buildMatches &&
                coreConsistent &&
                fenceMatchesCore &&
                fenceState == "armed" &&
                (handoffPending || businessPhase == "detached")
            return ResidentHostRuntimeAttachment(
                mode = if (validPending) ResidentHostRuntimeMode.UI_PROXY_PENDING else ResidentHostRuntimeMode.UI_PROXY_BLOCKED,
                reason = when {
                    validPending -> "resident_core_takeover_pending"
                    !buildMatches -> "resident_core_build_mismatch_during_takeover"
                    !coreConsistent -> "resident_core_snapshot_inconsistent_during_takeover"
                    !fenceMatchesCore -> "resident_core_takeover_fence_identity_mismatch"
                    fenceState == "failed" -> "resident_core_takeover_failed_without_fallback"
                    else -> "resident_core_takeover_state_inconsistent"
                },
                corePid = corePid,
                coreSessionId = coreSession,
                coreBuildMatches = buildMatches,
                businessPhase = businessPhase,
                fenceState = fenceState
            )
        }

        if (owner == "unavailable" || businessPhase == "failed") {
            return ResidentHostRuntimeAttachment(
                mode = ResidentHostRuntimeMode.UI_PROXY_BLOCKED,
                reason = "resident_core_takeover_failed_without_fallback",
                corePid = corePid,
                coreSessionId = coreSession,
                coreBuildMatches = buildMatches,
                businessPhase = businessPhase,
                fenceState = fenceState
            )
        }

        return ResidentHostRuntimeAttachment(
            mode = ResidentHostRuntimeMode.LEGACY_HOST,
            reason = "resident_core_detached_without_takeover",
            corePid = corePid,
            coreSessionId = coreSession,
            coreBuildMatches = buildMatches,
            businessPhase = businessPhase
        )
    }
}
