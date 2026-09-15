package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.os.Process
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsIngressResult
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsIngressSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Host-side proxy for the one authoritative Resident Core Dispatcher.
 *
 * Once a takeover fence exists for another PID this client is fail-closed: losing Core or seeing an
 * invalid fence never recreates a Host-local Interaction Cycle/Policy/Dispatcher.
 */
internal class ResidentCoreDispatcherClient private constructor(
    context: Context,
    private val ingressSession: AiLimbsIngressSession
) {
    private val appContext = context.applicationContext

    suspend fun invoke(tool: String, args: JSONObject): AiLimbsIngressResult = withContext(Dispatchers.IO) {
        val ownership = currentOwnership()
        if (!ownership.ready) {
            return@withContext AiLimbsIngressResult(ownership.errorPayload(), null)
        }
        val coreSession = checkNotNull(ownership.coreSession)
        val corePid = checkNotNull(ownership.corePid)
        val request = JSONObject()
            .put("source_id", ingressSession.sourceId)
            .put("execution_session", JSONObject()
                .put("transport", ingressSession.executionSession.transport.wireValue)
                .put("scope_id", ingressSession.executionSession.scopeId)
                .put("source_transport_id", ingressSession.executionSession.sourceTransportId))
            .put("tool", tool)
            .put("args", JSONObject(args.toString()))

        val response = runCatching {
            ResidentCoreDispatchWire.request(coreSession, corePid, "invoke", request)
        }.getOrElse { error ->
            return@withContext AiLimbsIngressResult(
                JSONObject()
                    .put("success", false)
                    .put("error_code", "RESIDENT_CORE_DISPATCH_UNREACHABLE")
                    .put("error", error.toString().take(1024))
                    .put("dispatcher_owner", "resident_core")
                    .put("fallback_allowed", false),
                null
            )
        }
        if (!response.optBoolean("success", false)) {
            return@withContext AiLimbsIngressResult(
                JSONObject()
                    .put("success", false)
                    .put("error_code", response.optString("error_code", "RESIDENT_CORE_DISPATCH_REJECTED"))
                    .put("error", response.optString("error", "Resident Core rejected dispatch"))
                    .put("dispatcher_owner", "resident_core")
                    .put("fallback_allowed", false),
                null
            )
        }
        val result = response.getJSONObject("result")
        val payload = result.getJSONObject("payload")
        val bootstrap =
            if (result.isNull("access_bootstrap")) null
            else result.optString("access_bootstrap").takeIf { it.isNotBlank() }
        AiLimbsIngressResult(payload, bootstrap)
    }

    fun rearmBootstrapBlocking() {
        val ownership = currentOwnership()
        check(ownership.ready) {
            ownership.error ?: "Resident Core Dispatcher is not ready for bootstrap rearm."
        }
        val response =
            ResidentCoreDispatchWire.request(checkNotNull(ownership.coreSession), checkNotNull(ownership.corePid), "rearm_bootstrap")
        check(response.optBoolean("success", false)) {
            response.optString("error", "Resident Core rejected bootstrap rearm.")
        }
    }

    private fun currentOwnership(): Ownership {
        val fenceResult = runCatching { ResidentBusinessTakeoverFence.snapshot(appContext) }
        if (fenceResult.isFailure) {
            return Ownership(false, null, null, "RESIDENT_CORE_FENCE_INVALID", fenceResult.exceptionOrNull().toString())
        }
        val fence = fenceResult.getOrNull()
            ?: return Ownership(false, null, null, "RESIDENT_CORE_OWNERSHIP_LOST", "Resident takeover fence disappeared; Host-local fallback is forbidden until an explicit role transition.")
        val corePid = fence.optInt("core_pid", -1)
        if (corePid <= 0 || corePid == Process.myPid()) {
            return Ownership(false, null, null, "RESIDENT_CORE_FENCE_IDENTITY_INVALID", "Resident takeover fence does not identify another Core process.")
        }
        val state = fence.optString("state")
        val coreSession = fence.optString("core_session").takeIf { it.length in 1..64 }
        return when {
            state == "owned" && coreSession != null -> Ownership(true, coreSession, corePid, null, null)
            state == "armed" -> Ownership(false, coreSession, corePid, "RESIDENT_CORE_TAKEOVER_PENDING", "Resident Core takeover is still pending.")
            state == "failed" -> Ownership(false, coreSession, corePid, "RESIDENT_CORE_TAKEOVER_FAILED", fence.optString("error", "Resident Core takeover failed."))
            else -> Ownership(false, coreSession, corePid, "RESIDENT_CORE_TAKEOVER_STATE_INVALID", "Resident takeover fence state is inconsistent: $state")
        }
    }

    private data class Ownership(
        val ready: Boolean,
        val coreSession: String?,
        val corePid: Int?,
        val errorCode: String?,
        val error: String?
    ) {
        fun errorPayload(): JSONObject = JSONObject()
            .put("success", false)
            .put("error_code", errorCode ?: "RESIDENT_CORE_NOT_READY")
            .put("error", error ?: "Resident Core Dispatcher is not ready.")
            .put("dispatcher_owner", "resident_core")
            .put("fallback_allowed", false)
    }

    companion object {
        /** Returns null only when this process is still legitimately allowed to own local dispatch. */
        fun forExternalCoreOrNull(context: Context, ingressSession: AiLimbsIngressSession): ResidentCoreDispatcherClient? {
            val appContext = context.applicationContext
            val fenceResult = runCatching { ResidentBusinessTakeoverFence.snapshot(appContext) }
            if (fenceResult.isSuccess) {
                val fence = fenceResult.getOrNull() ?: return null
                if (fence.optInt("core_pid", -1) == Process.myPid() &&
                    ResidentCoreProcessIdentity.isCurrentProcessCore()) return null
            }
            // Invalid persisted ownership is also fail-closed; never create a second local policy plane.
            return ResidentCoreDispatcherClient(appContext, ingressSession)
        }
    }
}
