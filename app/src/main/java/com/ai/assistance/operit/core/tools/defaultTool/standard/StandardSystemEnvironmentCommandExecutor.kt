package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.systemenvironment.SystemEnvironmentClient
import com.ai.assistance.operit.core.tools.HiddenTerminalCommandResultData
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.TerminalCommandResultData
import com.ai.assistance.operit.core.tools.TerminalSessionCloseResultData
import com.ai.assistance.operit.core.tools.TerminalSessionCreationResultData
import com.ai.assistance.operit.core.tools.TerminalSessionScreenResultData
import com.ai.assistance.operit.core.tools.TerminalStreamEventData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Compatibility executor for legacy terminal tool names.
 *
 * The tool contracts remain stable, while all concrete runtime/session/process behavior is delegated
 * to the active System Environment capability provider. Base owns no terminal or Ubuntu runtime.
 */
class StandardSystemEnvironmentCommandExecutor(private val context: Context) {
    fun createOrGetSession(tool: AITool): ToolResult = runBlocking(Dispatchers.IO) {
        try {
            val sessionName = tool.param("session_name")
            if (sessionName.isNullOrBlank()) {
                return@runBlocking failure(tool, context.getString(R.string.terminal_error_missing_session_name))
            }
            val session = SystemEnvironmentClient.resolveSession(sessionName)
            ToolResult(
                toolName = tool.name,
                success = true,
                result = TerminalSessionCreationResultData(
                    sessionId = session.sessionId,
                    sessionName = session.sessionName,
                    isNewSession = !session.reused
                )
            )
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to resolve System Environment session", error)
            failure(tool, context.getString(R.string.terminal_error_create_session, error.message ?: ""))
        }
    }

    fun executeCommandInSession(tool: AITool): ToolResult = runBlocking(Dispatchers.IO) {
        val sessionId = tool.param("session_id")
        if (sessionId.isNullOrBlank()) {
            return@runBlocking failure(tool, context.getString(R.string.terminal_error_missing_session_id))
        }
        val command = tool.param("command").orEmpty()
        val timeoutMs = tool.timeoutMs(DEFAULT_SESSION_TIMEOUT_MS)
        var pid: Int? = null
        val output = StringBuilder()
        var completed = false
        var terminalError: String? = null
        var timedOut = false
        try {
            withTimeout(timeoutMs) {
                SystemEnvironmentClient.executeSessionFlow(sessionId, command).collect { event ->
                    pid = event.pid
                    if (event.outputChunk.isNotEmpty()) output.append(event.outputChunk)
                    if (event.error != null) terminalError = event.error
                    if (event.isCompleted) completed = true
                }
            }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
            pid?.let { runCatching { SystemEnvironmentClient.terminateProcess(it) } }
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to execute System Environment session command", error)
            return@runBlocking failure(
                tool,
                context.getString(R.string.terminal_error_execute_command, error.message ?: "")
            )
        }

        val errorMessage = when {
            timedOut -> null // Preserve legacy terminal-tool timeout semantics.
            terminalError != null -> terminalError
            !completed -> context.getString(R.string.terminal_error_command_failed)
            else -> null
        }
        ToolResult(
            toolName = tool.name,
            success = errorMessage == null,
            result = TerminalCommandResultData(
                command = command,
                output = output.toString(),
                exitCode = if (timedOut || errorMessage != null) -1 else 0,
                sessionId = sessionId,
                timedOut = timedOut
            ),
            error = errorMessage
        )
    }

    fun executeCommandInSessionStream(tool: AITool): Flow<ToolResult> = flow {
        val sessionId = tool.param("session_id")
        if (sessionId.isNullOrBlank()) {
            emit(failure(tool, context.getString(R.string.terminal_error_missing_session_id)))
            return@flow
        }
        val command = tool.param("command").orEmpty()
        val timeoutMs = tool.timeoutMs(DEFAULT_SESSION_TIMEOUT_MS)
        emit(
            ToolResult(
                toolName = tool.name,
                success = true,
                result = TerminalStreamEventData(
                    type = "start",
                    command = command,
                    sessionId = sessionId,
                    chunkIndex = 0,
                    receivedChars = 0
                ),
                error = ""
            )
        )

        var pid: Int? = null
        var chunkIndex = 0
        var receivedChars = 0
        var completed = false
        var terminalError: String? = null
        var timedOut = false
        val output = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                SystemEnvironmentClient.executeSessionFlow(sessionId, command).collect { event ->
                    pid = event.pid
                    if (event.error != null) terminalError = event.error
                    val chunk = event.outputChunk
                    if (chunk.isNotEmpty()) {
                        output.append(chunk)
                        receivedChars += chunk.length
                        emit(
                            ToolResult(
                                toolName = tool.name,
                                success = true,
                                result = TerminalStreamEventData(
                                    type = "chunk",
                                    command = command,
                                    sessionId = sessionId,
                                    chunk = chunk,
                                    chunkIndex = chunkIndex++,
                                    receivedChars = receivedChars
                                ),
                                error = ""
                            )
                        )
                    }
                    if (event.isCompleted) completed = true
                }
            }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
            pid?.let { runCatching { SystemEnvironmentClient.terminateProcess(it) } }
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to stream System Environment session command", error)
            emit(failure(tool, context.getString(R.string.terminal_error_execute_command, error.message ?: "")))
            return@flow
        }

        val errorMessage = when {
            timedOut -> null
            terminalError != null -> terminalError
            !completed -> context.getString(R.string.terminal_error_command_failed)
            else -> null
        }
        emit(
            ToolResult(
                toolName = tool.name,
                success = errorMessage == null,
                result = TerminalCommandResultData(
                    command = command,
                    output = output.toString(),
                    exitCode = if (timedOut || errorMessage != null) -1 else 0,
                    sessionId = sessionId,
                    timedOut = timedOut
                ),
                error = errorMessage
            )
        )
    }

    fun executeHiddenCommand(tool: AITool): ToolResult = runBlocking(Dispatchers.IO) {
        try {
            val command = tool.param("command").orEmpty()
            if (command.isBlank()) {
                return@runBlocking failure(tool, context.getString(R.string.terminal_error_missing_command))
            }
            val executorKey = tool.param("executor_key")?.trim().orEmpty().ifBlank { "default" }
            val timeoutMs = tool.timeoutMs(DEFAULT_HIDDEN_TIMEOUT_MS)
            val result = SystemEnvironmentClient.executeHiddenCommand(command, executorKey, timeoutMs)
            val timedOut = result.status.contains("TIMEOUT", ignoreCase = true)
            val errorMessage = when {
                timedOut -> null
                result.success -> null
                result.status.contains("RUNTIME_STOPPED", ignoreCase = true) -> result.error
                else -> context.getString(
                    R.string.terminal_error_execute_hidden_command,
                    buildString {
                        append("state=").append(result.status.ifBlank { "FAILED" })
                        result.error?.let { append(", error=").append(it) }
                        if (result.output.isNotBlank()) append("\n").append(result.output.takeLast(4_000))
                    }
                )
            }
            ToolResult(
                toolName = tool.name,
                success = errorMessage == null,
                result = HiddenTerminalCommandResultData(
                    command = command,
                    output = result.output,
                    exitCode = result.exitCode,
                    executorKey = executorKey,
                    timedOut = timedOut
                ),
                error = errorMessage
            )
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to execute hidden System Environment command", error)
            failure(
                tool,
                context.getString(R.string.terminal_error_execute_hidden_command, error.message ?: "")
            )
        }
    }

    fun inputInSession(tool: AITool): ToolResult = runBlocking(Dispatchers.IO) {
        val sessionId = tool.param("session_id")
        if (sessionId.isNullOrBlank()) {
            return@runBlocking failure(tool, context.getString(R.string.terminal_error_missing_session_id))
        }
        val inputParameter = tool.parameters.find { it.name == "input" }
        val input = inputParameter?.value
        val control = tool.param("control")
        if (inputParameter == null && control.isNullOrBlank()) {
            return@runBlocking failure(tool, context.getString(R.string.terminal_error_missing_input_or_control))
        }
        try {
            SystemEnvironmentClient.sendSessionInput(
                sessionId = sessionId,
                input = input,
                control = control
            )
            val acceptedChars = (input?.length ?: 0) + if (control.isNullOrBlank()) 0 else 1
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(
                    context.getString(R.string.terminal_input_sent, sessionId, acceptedChars)
                )
            )
        } catch (error: IllegalArgumentException) {
            failure(tool, error.message ?: context.getString(R.string.terminal_error_input))
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to write System Environment session input", error)
            failure(tool, context.getString(R.string.terminal_error_input_with_reason, error.message ?: ""))
        }
    }

    fun closeSession(tool: AITool): ToolResult = runBlocking(Dispatchers.IO) {
        val sessionId = tool.param("session_id")
        if (sessionId.isNullOrBlank()) {
            return@runBlocking failure(tool, context.getString(R.string.terminal_error_missing_session_id))
        }
        try {
            SystemEnvironmentClient.closeSession(sessionId)
            ToolResult(
                toolName = tool.name,
                success = true,
                result = TerminalSessionCloseResultData(
                    sessionId = sessionId,
                    success = true,
                    message = context.getString(R.string.terminal_session_closed, sessionId)
                )
            )
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to close System Environment session", error)
            failure(tool, context.getString(R.string.terminal_error_close_session, sessionId, error.message ?: ""))
        }
    }

    fun getSessionScreen(tool: AITool): ToolResult = runBlocking(Dispatchers.IO) {
        val sessionId = tool.param("session_id")
        if (sessionId.isNullOrBlank()) {
            return@runBlocking failure(tool, context.getString(R.string.terminal_error_missing_session_id))
        }
        try {
            val screen = SystemEnvironmentClient.sessionScreen(sessionId)
            ToolResult(
                toolName = tool.name,
                success = true,
                result = TerminalSessionScreenResultData(
                    sessionId = screen.sessionId,
                    rows = screen.rows,
                    cols = screen.cols,
                    content = screen.content
                )
            )
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to read System Environment session screen", error)
            failure(tool, context.getString(R.string.terminal_error_get_screen, error.message ?: ""))
        }
    }

    private fun AITool.param(name: String): String? =
        parameters.find { it.name == name }?.value

    private fun AITool.timeoutMs(defaultValue: Long): Long =
        param("timeout_ms")?.toLongOrNull()?.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS) ?: defaultValue

    private fun failure(tool: AITool, message: String): ToolResult =
        ToolResult(
            toolName = tool.name,
            success = false,
            result = StringResultData(""),
            error = message
        )

    private companion object {
        const val TAG = "SystemEnvironmentExecutor"
        const val DEFAULT_SESSION_TIMEOUT_MS = 1_800_000L
        const val DEFAULT_HIDDEN_TIMEOUT_MS = 120_000L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 3_600_000L
    }
}
