package com.ai.assistance.operit.integrations.ailimbs

import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapts the fixed Desktop Commander tool surface to Operit's native tools.
 * Every side-effecting operation still enters ToolExecutionManager and therefore
 * keeps ALLOW / ASK / FORBID semantics unchanged.
 */
class AiLimbsRdcToolAdapter(
    private val dispatcher: AiLimbsOperitDispatcher
) {
    suspend fun execute(toolName: String, args: JSONObject): JSONObject =
        when (toolName) {
            "read_file" -> readFile(args)
            "write_file" -> writeFile(args)
            "list_directory" -> listDirectory(args)
            "start_process" -> startProcess(args)
            else -> executeSameNamedOperitTool(toolName, args)
        }

    private suspend fun readFile(args: JSONObject): JSONObject {
        val path = args.optString("path")
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val length = args.optInt("length", 0).coerceAtLeast(0)
        val params = JSONObject()
            .put("path", path)
            .put("environment", resolveEnvironment(path, args))
            .put("text_only", "true")
        val operitName = if (length > 0) "read_file_part" else "read_file_full"
        if (length > 0) {
            params.put("start_line", offset + 1)
            params.put("end_line", offset + length)
        }
        return mcpResult(executeOperit(operitName, params))
    }

    private suspend fun writeFile(args: JSONObject): JSONObject {
        val path = args.optString("path")
        val params = JSONObject()
            .put("path", path)
            .put("content", args.optString("content"))
            .put("append", args.optString("mode").equals("append", ignoreCase = true))
            .put("environment", resolveEnvironment(path, args))
        return mcpResult(executeOperit("write_file", params))
    }

    private suspend fun listDirectory(args: JSONObject): JSONObject {
        val path = args.optString("path")
        val params = JSONObject()
            .put("path", path)
            .put("environment", resolveEnvironment(path, args))
        return mcpResult(executeOperit("list_files", params))
    }

    private suspend fun startProcess(args: JSONObject): JSONObject {
        val command = args.optString("command")
        val shell = args.optString("shell").trim().lowercase()
        if (shell == "operit") {
            val request = try {
                JSONObject(command)
            } catch (e: Exception) {
                return mcpError("shell=operit expects JSON: ${e.message}")
            }
            val name = request.optString("name").trim()
            if (name.isBlank()) return mcpError("shell=operit requires a tool name")
            val parameters = request.optJSONObject("parameters") ?: JSONObject()
            return mcpResult(executeOperit(name, parameters))
        }

        if (shell == "android") {
            return mcpResult(
                executeOperit(
                    "execute_shell",
                    JSONObject().put("command", command)
                )
            )
        }

        val timeoutMs = args.optLong("timeout_ms", DEFAULT_TERMINAL_TIMEOUT_MS)
        val params = JSONObject()
            .put("command", command)
            .put("executor_key", "default")
            .put("timeout_ms", timeoutMs)
        return mcpResult(executeOperit("execute_hidden_terminal_command", params))
    }

    private suspend fun executeSameNamedOperitTool(
        toolName: String,
        args: JSONObject
    ): JSONObject = mcpResult(executeOperit(toolName, args))

    private suspend fun executeOperit(name: String, params: JSONObject): JSONObject =
        dispatcher.execute(
            "operit.tool.execute",
            JSONObject()
                .put("name", name)
                .put("parameters", params)
        )

    private fun resolveEnvironment(path: String, args: JSONObject): String {
        val explicit = args.optJSONObject("options")
            ?.optString("environment")
            ?.trim()
            .orEmpty()
        if (explicit.isNotBlank()) return explicit

        return if (
            path == "/root" || path.startsWith("/root/") ||
            path == "/home" || path.startsWith("/home/") ||
            path == "/etc" || path.startsWith("/etc/") ||
            path == "/usr" || path.startsWith("/usr/") ||
            path == "/var" || path.startsWith("/var/") ||
            path == "/tmp" || path.startsWith("/tmp/")
        ) {
            "linux"
        } else {
            "android"
        }
    }

    private fun mcpResult(result: JSONObject): JSONObject {
        val content = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", result.toString(2))
        )
        return JSONObject()
            .put("content", content)
            .put("isError", !result.optBoolean("success", false))
    }

    private fun mcpError(message: String): JSONObject {
        val result = JSONObject()
            .put("success", false)
            .put("error", message)
        return mcpResult(result)
    }

    companion object {
        private const val DEFAULT_TERMINAL_TIMEOUT_MS = 120_000L
    }
}
