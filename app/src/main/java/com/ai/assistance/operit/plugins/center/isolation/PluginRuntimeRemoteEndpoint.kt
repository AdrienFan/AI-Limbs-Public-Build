package com.ai.assistance.operit.plugins.center.isolation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class PluginRuntimeRemoteSession(
    val sessionId: String,
    val pid: Int
)

/**
 * Core-side control endpoint for the isolated plugin worker.
 *
 * It may launch/recover the empty worker process, but it never replays a business operation.
 * Callers must decide whether an operation is safe to retry after a worker failure.
 */
internal class PluginRuntimeRemoteEndpoint(context: Context) {
    private val appContext = context.applicationContext
    private val sessionMutex = Mutex()

    suspend fun ensureSession(): PluginRuntimeRemoteSession = sessionMutex.withLock {
        val state = PluginRuntimeController.probe(appContext)
        check(state.optBoolean("available", false) && state.optBoolean("consistent", false)) {
            "Plugin runtime is not available: ${state.toString().take(1024)}"
        }
        val sessionId = state.getString("session_id")
        val pid = state.getInt("pid")
        check(sessionId.isNotBlank() && pid > 0) { "Plugin runtime identity is incomplete" }
        PluginRuntimeRemoteSession(sessionId, pid)
    }

    suspend fun currentSessionOrNull(): PluginRuntimeRemoteSession? = withContext(Dispatchers.IO) {
        val state = runCatching { PluginRuntimeController.status(appContext) }.getOrNull()
            ?: return@withContext null
        if (!state.optBoolean("available", false) || !state.optBoolean("consistent", false)) {
            return@withContext null
        }
        val sessionId = state.optString("session_id").trim()
        val pid = state.optInt("pid", -1)
        if (sessionId.isBlank() || pid <= 0) null else PluginRuntimeRemoteSession(sessionId, pid)
    }

    suspend fun request(
        session: PluginRuntimeRemoteSession,
        operation: String,
        payload: JSONObject = JSONObject(),
        timeoutMs: Int = PluginRuntimeWire.BUSINESS_TIMEOUT_MS
    ): JSONObject = withContext(Dispatchers.IO) {
        val result = PluginRuntimeWire.request(
            operation = operation,
            sessionId = session.sessionId,
            payload = JSONObject(payload.toString()),
            timeoutMs = timeoutMs
        )
        check(result.getString("session_id") == session.sessionId) {
            "Plugin runtime session changed during $operation"
        }
        check(result.getInt("pid") == session.pid) {
            "Plugin runtime PID changed during $operation"
        }
        result.optJSONObject("operation_result") ?: JSONObject()
    }
}
