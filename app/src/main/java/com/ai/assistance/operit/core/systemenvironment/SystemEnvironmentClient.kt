package com.ai.assistance.operit.core.systemenvironment

import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * Base-side compatibility client for the installed Ubuntu system-environment child.
 *
 * Canonical calls use plugin.ubuntu.* because these runtime operations are owned by the Ubuntu child.
 * The child keeps legacy plugin.system_environment.* invoke aliases for older callers.
 */
internal object SystemEnvironmentClient {
    data class Status(
        val running: Boolean,
        val state: String,
        val detail: String,
        val error: String?
    )

    data class SessionResolution(
        val sessionId: String,
        val sessionName: String,
        val reused: Boolean
    )

    data class HiddenCommandResult(
        val success: Boolean,
        val status: String,
        val exitCode: Int,
        val output: String,
        val error: String?
    )

    data class SessionScreen(
        val sessionId: String,
        val rows: Int,
        val cols: Int,
        val content: String
    )

    data class ProcessEvent(
        val pid: Int,
        val sessionId: String,
        val outputChunk: String,
        val isCompleted: Boolean,
        val waitingForInput: Boolean,
        val error: String?
    )

    fun isAvailable(): Boolean =
        PluginPlatformKernel.isInitialized &&
            STATUS_CAPABILITY in PluginPlatformKernel.capabilities.activeIds()

    suspend fun status(): Status {
        val result = call(STATUS_CAPABILITY)
        val state = result.optString("state")
        return Status(
            running = state.equals("RUNNING", ignoreCase = true),
            state = state,
            detail = result.optString("detail"),
            error = result.nullableString("error")
        )
    }

    suspend fun ensureRunning(): Boolean {
        val current = runCatching { status() }.getOrNull()
        if (current?.running == true) return true
        val started = call(START_CAPABILITY)
        return started.optBoolean("success", false) ||
            started.optString("state").equals("RUNNING", ignoreCase = true)
    }

    suspend fun resolveSession(sessionName: String): SessionResolution {
        check(ensureRunning()) { "System Environment provider is not running" }
        val result = call(SESSION_CREATE_CAPABILITY, JSONObject().put("session_name", sessionName))
        return SessionResolution(
            sessionId = result.getString("session_id"),
            sessionName = result.optString("session_name", sessionName),
            reused = result.optBoolean("reused", false)
        )
    }

    suspend fun createSession(sessionName: String): String = resolveSession(sessionName).sessionId

    suspend fun executeSession(
        sessionId: String,
        command: String,
        timeoutMs: Long = 1_800_000L
    ): String {
        val result = call(
            SESSION_EXECUTE_CAPABILITY,
            JSONObject()
                .put("session_id", sessionId)
                .put("command", command)
                .put("timeout_ms", timeoutMs)
        )
        return result.optString("output")
    }

    suspend fun executeHiddenCommand(
        command: String,
        executorKey: String = "base",
        timeoutMs: Long = 120_000L
    ): HiddenCommandResult {
        check(ensureRunning()) { "System Environment provider is not running" }
        val result = invokeRaw(
            COMMAND_CAPABILITY,
            JSONObject()
                .put("command", command)
                .put("executor_key", executorKey)
                .put("timeout_ms", timeoutMs)
        )
        return HiddenCommandResult(
            success = result.optBoolean("success", false),
            status = result.optString("status"),
            exitCode = result.optInt("exit_code", if (result.optBoolean("success", false)) 0 else -1),
            output = result.optString("output"),
            error = result.nullableString("error")
        )
    }

    suspend fun executeCommand(
        command: String,
        executorKey: String = "base",
        timeoutMs: Long = 120_000L
    ): String {
        val result = executeHiddenCommand(command, executorKey, timeoutMs)
        if (!result.success) throw IllegalStateException(result.error ?: "System Environment command failed: ${result.status}")
        return result.output
    }

    suspend fun sendSessionInput(
        sessionId: String,
        input: String? = null,
        control: String? = null
    ) {
        require(input != null || !control.isNullOrBlank()) { "Either input or control is required" }
        val request = JSONObject()
            .put("session_id", sessionId)
        if (input != null) request.put("input", input)
        if (!control.isNullOrBlank()) request.put("control", control)
        call(SESSION_INPUT_CAPABILITY, request)
    }

    suspend fun sendInput(sessionId: String, input: String) {
        sendSessionInput(sessionId = sessionId, input = input)
    }

    suspend fun interruptSession(sessionId: String) {
        call(SESSION_INTERRUPT_CAPABILITY, JSONObject().put("session_id", sessionId))
    }

    suspend fun closeSession(sessionId: String) {
        call(SESSION_CLOSE_CAPABILITY, JSONObject().put("session_id", sessionId))
    }

    suspend fun sessionScreen(sessionId: String): SessionScreen {
        val result = call(SESSION_SCREEN_CAPABILITY, JSONObject().put("session_id", sessionId))
        return SessionScreen(
            sessionId = result.optString("session_id", sessionId),
            rows = result.optInt("rows", 0),
            cols = result.optInt("cols", 0),
            content = result.optString("content")
        )
    }

    fun executeSessionFlow(
        sessionId: String,
        command: String
    ): Flow<ProcessEvent> = flow {
        val started = call(
            PROCESS_CAPABILITY,
            JSONObject()
                .put("operation", "start")
                .put("command", command)
                .put("session_id", sessionId)
                .put("timeout_ms", 0)
        )
        val pid = started.getInt("pid")
        val startedRunning = started.optBoolean("running", true)
        emit(ProcessEvent(pid, sessionId, "", false, false, started.nullableString("error")))
        // Always perform at least one structured read. A very fast command may finish before start()
        // returns, but its buffered output is still retained by the provider for the read phase.
        while (true) {
            val snapshot = call(
                PROCESS_CAPABILITY,
                JSONObject()
                    .put("operation", "read_structured")
                    .put("pid", pid)
                    .put("offset", 0)
                    .put("length", PROCESS_READ_LINES)
                    .put("timeout_ms", PROCESS_READ_WAIT_MS)
            )
            val output = snapshot.optString("output")
            val running = snapshot.optBoolean("running", false)
            val waiting = snapshot.optBoolean("waiting_for_input", false)
            val error = snapshot.nullableString("error")
            if (output.isNotEmpty() || !running || error != null) {
                emit(ProcessEvent(pid, sessionId, output, !running, waiting, error))
            }
            if (!running) break
            if (waiting) delay(WAITING_POLL_DELAY_MS)
        }
    }

    suspend fun terminateProcess(pid: Int) {
        call(
            PROCESS_CAPABILITY,
            JSONObject()
                .put("operation", "terminate")
                .put("pid", pid)
        )
    }

    private suspend fun invokeRaw(capabilityId: String, parameters: JSONObject = JSONObject()): JSONObject {
        check(PluginPlatformKernel.isInitialized) { "Plugin platform is not initialized" }
        return PluginPlatformKernel.capabilities.invokePlugin(capabilityId, parameters)
    }

    private suspend fun call(capabilityId: String, parameters: JSONObject = JSONObject()): JSONObject {
        val result = invokeRaw(capabilityId, parameters)
        if (!result.optBoolean("success", false)) {
            throw IllegalStateException(
                result.optString("error").ifBlank {
                    result.optString("message").ifBlank { "System Environment capability failed: $capabilityId" }
                }
            )
        }
        return result
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private const val STATUS_CAPABILITY = "plugin.ubuntu.status"
    private const val START_CAPABILITY = "plugin.ubuntu.start"
    private const val SESSION_CREATE_CAPABILITY = "plugin.ubuntu.session.create"
    private const val SESSION_EXECUTE_CAPABILITY = "plugin.ubuntu.session.execute"
    private const val SESSION_INPUT_CAPABILITY = "plugin.ubuntu.session.input"
    private const val SESSION_INTERRUPT_CAPABILITY = "plugin.ubuntu.session.interrupt"
    private const val SESSION_SCREEN_CAPABILITY = "plugin.ubuntu.session.screen"
    private const val SESSION_CLOSE_CAPABILITY = "plugin.ubuntu.session.close"
    private const val COMMAND_CAPABILITY = "plugin.ubuntu.command"
    private const val PROCESS_CAPABILITY = "plugin.ubuntu.process"
    private const val PROCESS_READ_LINES = 500
    private const val PROCESS_READ_WAIT_MS = 500L
    private const val WAITING_POLL_DELAY_MS = 100L
}
