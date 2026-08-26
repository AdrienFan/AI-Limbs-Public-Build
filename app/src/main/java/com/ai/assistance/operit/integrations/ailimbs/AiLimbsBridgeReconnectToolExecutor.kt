package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import com.ai.assistance.operit.api.chat.AIForegroundService
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

/**
 * Agent-safe Bridge reconnect entry point.
 *
 * The reconnect itself is deferred so the current RDC call can return its acknowledgement before
 * the active Realtime transport is torn down. The actual reconnect still goes through the same
 * AIForegroundService -> BridgeAction.RECONNECT path used by Bridge Center.
 */
class AiLimbsBridgeReconnectToolExecutor(context: Context) {
    private val appContext = context.applicationContext
    fun execute(tool: AITool): ToolResult {
        val state = AiLimbsBridgeManager.runtimeState.value
        val available = BridgeAction.RECONNECT in AiLimbsBridgeManager.availableActions(appContext, state)
        if (!available) {
            return failure(
                tool,
                "Bridge reconnect is not available for provider ${state.providerId} while phase=${state.phase.name}."
            )
        }

        val scheduled = scheduleReconnect(appContext)
        val result = JSONObject()
            .put("accepted", true)
            .put("request_id", scheduled.requestId)
            .put("already_pending", !scheduled.created)
            .put("provider", state.providerId)
            .put("phase_before", state.phase.name)
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

        private fun scheduleReconnect(context: Context): ScheduleResult {
            while (true) {
                pendingRequestId.get()?.let { existing ->
                    return ScheduleResult(existing, created = false)
                }

                val requestId = UUID.randomUUID().toString()
                if (pendingRequestId.compareAndSet(null, requestId)) {
                    dispatchReconnect(context.applicationContext, requestId)
                    return ScheduleResult(requestId, created = true)
                }
            }
        }
        private fun dispatchReconnect(context: Context, requestId: String) {
            AppLogger.i(TAG, "Agent Bridge reconnect accepted: requestId=$requestId")
            reconnectScope.launch {
                delay(AGENT_RECONNECT_DELAY_MS)
                try {
                    AppLogger.i(TAG, "Dispatching deferred Bridge reconnect: requestId=$requestId")
                    AIForegroundService.requestBridgeAction(context, BridgeAction.RECONNECT)
                } catch (error: Throwable) {
                    AppLogger.e(TAG, "Deferred Bridge reconnect failed: requestId=$requestId", error)
                } finally {
                    pendingRequestId.compareAndSet(requestId, null)
                }
            }
        }
    }
}
