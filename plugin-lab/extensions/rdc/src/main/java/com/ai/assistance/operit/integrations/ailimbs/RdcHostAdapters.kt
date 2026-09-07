package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import com.ai.limbs.plugin.runtime.ChildExtensionHost
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject

object AiLimbsExecutionPolicyDescriptor {
    const val policyVersion: String = "host-managed"
}

internal object RdcPluginHostBridge {
    @Volatile var host: ChildExtensionHost? = null

    suspend fun invoke(tool: String, args: JSONObject): JSONObject {
        val current = host ?: error("RDC child host is not mounted")
        val request = JSONObject()
            .put("transport", "rdc")
            .put("tool", tool)
            .put("args", args)
        return JSONObject(current.invokeHostCapability("core.bridge.remote.invoke", request.toString()))
    }
}

enum class AiLimbsExecutionTransport { RDC }
data class AiLimbsExecutionSession(val transport: AiLimbsExecutionTransport, val scopeId: String)
class AiLimbsRemoteInvocationExecutor(context: Context, val session: AiLimbsExecutionSession) {
    suspend fun execute(name: String, args: JSONObject): JSONObject =
        RdcPluginHostBridge.invoke(name, args)
}

class AiLimbsRdcToolAdapter(
    context: Context,
    private val remoteExecutor: AiLimbsRemoteInvocationExecutor
) {
    suspend fun execute(toolName: String, args: JSONObject): JSONObject = when (toolName.trim()) {
        "start_process" -> startProcess(args)
        "read_process_output" -> processTool("read", args)
        "interact_with_process" -> processTool("interact", args)
        "list_sessions", "list_processes" -> processTool("list", args)
        "kill_process", "force_terminate" -> processTool("terminate", args)
        "read_file" -> readFile(args)
        "write_file" -> writeFile(args)
        "list_directory" -> listDirectory(args)
        "get_file_info" -> hostTool("file_info", withEnvironment(args))
        "create_directory" -> hostTool("make_directory", withEnvironment(args))
        else -> executeFallback(toolName, args)
    }

    private suspend fun startProcess(args: JSONObject): JSONObject {
        val command = args.optString("command")
        val shell = args.optString("shell").trim().lowercase()
        if (shell == "android") {
            return hostTool("execute_shell", JSONObject().put("command", command))
        }
        if (shell == "operit") {
            val request = runCatching { JSONObject(command) }.getOrElse {
                return mcpError("shell=operit expects JSON: ${it.message}")
            }
            val name = request.optString("name").trim()
            if (name.isBlank()) return mcpError("shell=operit requires a tool name")
            val parameters = request.optJSONObject("parameters") ?: JSONObject()
            val result = if (isFormalCapability(name)) {
                remoteExecutor.execute(name, parameters)
            } else {
                executeHostTool(name, parameters)
            }
            return mcpResult(result)
        }
        val params = JSONObject(args.toString())
            .put("command", command)
            .put("timeout_ms", args.optLong("timeout_ms", DEFAULT_RDC_START_WAIT_MS))
        params.remove("shell")
        return processTool("start", params)
    }

    private suspend fun processTool(operation: String, args: JSONObject): JSONObject {
        val params = JSONObject(args.toString()).put("operation", operation)
        return mcpProcessResult(remoteExecutor.execute(SYSTEM_ENVIRONMENT_PROCESS, params))
    }
    private suspend fun readFile(args: JSONObject): JSONObject {
        val path = args.optString("path")
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val length = args.optInt("length", 0).coerceAtLeast(0)
        val params = JSONObject()
            .put("path", path)
            .put("environment", resolveEnvironment(path, args))
            .put("text_only", "true")
        val hostToolName = if (length > 0) {
            params.put("start_line", offset + 1)
            params.put("end_line", offset + length)
            "read_file_part"
        } else {
            "read_file_full"
        }
        return hostTool(hostToolName, params)
    }

    private suspend fun writeFile(args: JSONObject): JSONObject {
        val path = args.optString("path")
        val params = JSONObject()
            .put("path", path)
            .put("content", args.optString("content"))
            .put("append", args.optString("mode").equals("append", ignoreCase = true))
            .put("environment", resolveEnvironment(path, args))
        return hostTool("write_file", params)
    }
    private suspend fun listDirectory(args: JSONObject): JSONObject {
        val path = args.optString("path")
        return hostTool(
            "list_files",
            JSONObject()
                .put("path", path)
                .put("environment", resolveEnvironment(path, args))
        )
    }

    private suspend fun executeFallback(toolName: String, args: JSONObject): JSONObject {
        return if (isFormalCapability(toolName)) {
            mcpResult(remoteExecutor.execute(toolName, JSONObject(args.toString())))
        } else {
            hostTool(toolName, withEnvironment(args))
        }
    }

    private suspend fun hostTool(name: String, params: JSONObject): JSONObject =
        mcpResult(executeHostTool(name, params))

    private suspend fun executeHostTool(name: String, params: JSONObject): JSONObject =
        remoteExecutor.execute(
            HOST_TOOL_EXECUTE,
            JSONObject()
                .put("name", name)
                .put("parameters", params)
        )
    private fun withEnvironment(args: JSONObject): JSONObject {
        val params = JSONObject(args.toString())
        val path = params.optString("path").trim()
        if (path.isNotBlank() && !params.has("environment")) {
            params.put("environment", resolveEnvironment(path, args))
        }
        return params
    }

    private fun resolveEnvironment(path: String, args: JSONObject): String {
        val explicit = args.optJSONObject("options")
            ?.optString("environment")
            ?.trim()
            .orEmpty()
        if (explicit.isNotBlank()) return explicit
        return if (LINUX_PREFIXES.any { prefix -> path == prefix || path.startsWith("$prefix/") }) {
            "linux"
        } else {
            "android"
        }
    }

    private fun isFormalCapability(name: String): Boolean =
        name.startsWith("ai_limbs.") || name.startsWith("plugin.")

    private fun mcpProcessResult(result: JSONObject): JSONObject {
        val success = result.optBoolean("success", false)
        val text = result.optString("text").ifBlank { result.toString(2) }
        val response = JSONObject()
            .put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", text))
            )
            .put("isError", !success)
        if (result.has("execution_policy")) {
            response.put("execution_policy", result.optJSONObject("execution_policy"))
        }
        return response
    }

    private fun mcpResult(result: JSONObject): JSONObject {
        val success = result.optBoolean("success", false)
        return JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", result.toString(2))
                )
            )
            .put("isError", !success)
    }

    private fun mcpError(message: String): JSONObject =
        mcpResult(JSONObject().put("success", false).put("error", message))
    private companion object {
        const val SYSTEM_ENVIRONMENT_PROCESS = "plugin.system_environment.process"
        const val HOST_TOOL_EXECUTE = "ai_limbs.host_tool.execute"
        const val DEFAULT_RDC_START_WAIT_MS = 10_000L
        val LINUX_PREFIXES = listOf("/root", "/home", "/etc", "/usr", "/var", "/tmp")
    }
}

internal class AiLimbsRdcSearchCompat(
    private val remoteExecutor: AiLimbsRemoteInvocationExecutor,
    private val scope: CoroutineScope
) {
    suspend fun start(args: JSONObject): JSONObject =
        remoteExecutor.execute("start_search", args)

    fun getMore(args: JSONObject): JSONObject =
        JSONObject()
            .put("success", false)
            .put("error", "Search continuation is delegated to AI Limbs host in the formal build")

    fun stop(args: JSONObject): JSONObject =
        JSONObject().put("success", true).put("stopped", true)
}
