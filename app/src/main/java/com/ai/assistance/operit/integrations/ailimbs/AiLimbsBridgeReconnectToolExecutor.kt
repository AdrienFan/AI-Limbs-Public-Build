package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

internal const val BRIDGE_ACTION_CAPABILITY_ID = "plugin.bridge.perform_action"

/**
 * Agent-safe Bridge reconnect entry point.
 *
 * The reconnect itself is deferred so the current RDC call can return its acknowledgement before
 * the active Realtime transport is torn down. Bridge runtime ownership stays inside the Bridge
 * plugin; this compatibility tool delegates the action through its registered capability.
 */
class AiLimbsBridgeReconnectToolExecutor {
    fun execute(tool: AITool): ToolResult {
        if (!bridgeActionCapabilityActive()) {
            return failure(tool, "Bridge plugin action capability is not active")
        }

        val providerId = AiLimbsBridgeProviderCatalog.DEFAULT_PROFILE_ID
        val scheduled = scheduleReconnect(providerId)
        val result = JSONObject()
            .put("accepted", true)
            .put("request_id", scheduled.requestId)
            .put("already_pending", !scheduled.created)
            .put("provider", providerId)
            .put("phase_before", "delegated")
            .put("reconnect_after_ms", AGENT_RECONNECT_DELAY_MS)
            .put("completion", "verify_after_reconnect")
            .put(
                "note",
                "The active provider's normal RECONNECT action will run after this response. Pairing and session credentials are preserved."
            )

        return ToolResult(
            toolName = tool.name,
            success = true,
            result = StringResultData(result.toString()),
            error = null
        )
    }
    private fun failure(tool: AITool, message: String): ToolResult =
        ToolResult(
            toolName = tool.name,
            success = false,
            result = StringResultData(""),
            error = message
        )

    private data class ScheduleResult(
        val requestId: String,
        val created: Boolean
    )

    companion object {
        private const val TAG = "AiLimbsBridgeReconnect"
        private const val AGENT_RECONNECT_DELAY_MS = 2_000L
        private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val pendingRequestId = AtomicReference<String?>(null)

        private fun bridgeActionCapabilityActive(): Boolean =
            PluginPlatformKernel.isInitialized &&
                PluginPlatformKernel.isStarted &&
                BRIDGE_ACTION_CAPABILITY_ID in PluginPlatformKernel.capabilities.activeIds()

        private fun scheduleReconnect(providerId: String): ScheduleResult {
            while (true) {
                pendingRequestId.get()?.let { existing ->
                    return ScheduleResult(existing, created = false)
                }

                val requestId = UUID.randomUUID().toString()
                if (pendingRequestId.compareAndSet(null, requestId)) {
                    dispatchReconnect(providerId, requestId)
                    return ScheduleResult(requestId, created = true)
                }
            }
        }
        private fun dispatchReconnect(providerId: String, requestId: String) {
            AppLogger.i(TAG, "Agent Bridge reconnect accepted: requestId=$requestId")
            reconnectScope.launch {
                delay(AGENT_RECONNECT_DELAY_MS)
                try {
                    AppLogger.i(TAG, "Dispatching deferred Bridge reconnect: requestId=$requestId")
                    check(PluginPlatformKernel.isInitialized && PluginPlatformKernel.isStarted) {
                        "Bridge plugin platform is not active"
                    }
                    val dispatch = PluginPlatformKernel.capabilities.invokePlugin(
                        BRIDGE_ACTION_CAPABILITY_ID,
                        JSONObject()
                            .put("action", BridgeAction.RECONNECT.name)
                            .put("provider_id", providerId)
                    )
                    check(dispatch.optBoolean("success", false)) {
                        dispatch.optString("error").ifBlank { "Bridge reconnect capability was rejected" }
                    }
                } catch (error: Throwable) {
                    AppLogger.e(TAG, "Deferred Bridge reconnect failed: requestId=$requestId", error)
                } finally {
                    pendingRequestId.compareAndSet(requestId, null)
                }
            }
        }
    }
}
