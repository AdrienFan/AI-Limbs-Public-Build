package com.ai.limbs.extensions.systemenvironment.ubuntu

import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.provider.type.HiddenExecResult
import org.json.JSONObject

internal class UbuntuRuntimeUnavailable(val phase: String, message: String) : IllegalStateException(message)

/** Domain outcomes are declared by Ubuntu; receivers must not guess them from error text. */
internal object UbuntuCapabilityOutcome {
    fun hidden(result: HiddenExecResult): JSONObject {
        val completed = result.state == HiddenExecResult.State.OK
        val success = completed && result.exitCode == 0
        val value = JSONObject().put("success", success).put("status", result.state.name)
            .put("exit_code", result.exitCode)
            .put("output", result.output.ifBlank { result.rawOutputPreview })
            .put("error", result.error.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        if (success) return value
        val code = when (result.state) {
            HiddenExecResult.State.OK -> "UBUNTU_COMMAND_EXIT_NONZERO"
            HiddenExecResult.State.RUNTIME_STOPPED -> "UBUNTU_RUNTIME_NOT_RUNNING"
            HiddenExecResult.State.SHELL_START_FAILED -> "UBUNTU_SHELL_START_FAILED"
            HiddenExecResult.State.SHELL_NOT_READY -> "UBUNTU_SHELL_NOT_READY"
            HiddenExecResult.State.PROCESS_EXITED -> "UBUNTU_PROCESS_EXITED"
            HiddenExecResult.State.TIMEOUT -> "UBUNTU_COMMAND_TIMEOUT"
            HiddenExecResult.State.EXECUTION_ERROR -> "UBUNTU_EXECUTION_ERROR"
            HiddenExecResult.State.MISSING_BEGIN_MARKER,
            HiddenExecResult.State.MISSING_END_MARKER,
            HiddenExecResult.State.INVALID_EXIT_CODE -> "UBUNTU_OUTPUT_PROTOCOL_ERROR"
        }
        val notStarted = result.state in setOf(HiddenExecResult.State.RUNTIME_STOPPED,
            HiddenExecResult.State.SHELL_START_FAILED, HiddenExecResult.State.SHELL_NOT_READY)
        if (completed) value.put("error", "Command exited with code ${result.exitCode}")
        return value.put("error_code", code)
            .put("execution_state", if (completed) "COMPLETED" else if (notStarted) "NOT_STARTED" else "UNKNOWN")
            .put("automatic_reexecution", false)
            .put("next_action", inspectAction(if (result.state == HiddenExecResult.State.RUNTIME_STOPPED)
                "plugin.ubuntu.start" else "plugin.ubuntu.status"))
    }

    fun failure(error: Throwable): JSONObject {
        val value = JSONObject().put("success", false)
            .put("error", error.message ?: error::class.java.simpleName)
        return when (error) {
            is UbuntuRuntimeUnavailable -> value.put("status", error.phase)
                .put("error_code", "UBUNTU_RUNTIME_NOT_RUNNING").put("execution_state", "NOT_STARTED")
                .put("automatic_reexecution", false)
                .put("next_action", inspectAction(if (error.phase == "STOPPED") "plugin.ubuntu.start" else "plugin.ubuntu.status"))
            is IllegalArgumentException -> value.put("error_code", "UBUNTU_INVALID_ARGUMENT")
                .put("execution_state", "NOT_STARTED")
            else -> value.put("error_code", "UBUNTU_EXECUTION_ERROR").put("execution_state", "UNKNOWN")
                .put("automatic_reexecution", false).put("next_action", inspectAction("plugin.ubuntu.status"))
        }
    }

    private fun inspectAction(name: String): JSONObject = JSONObject().put("type", "CAPABILITY_INVOKE")
        .put("capability", JSONObject().put("name", name).put("parameters", JSONObject()))
        .put("instruction", "Inspect the runtime or start it explicitly; do not repeat the original command automatically.")
}
