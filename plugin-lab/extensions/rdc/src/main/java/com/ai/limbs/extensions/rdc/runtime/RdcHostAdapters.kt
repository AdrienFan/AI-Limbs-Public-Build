package com.ai.limbs.extensions.rdc.runtime

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.BridgeRemoteIngress
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject

object AiLimbsExecutionPolicyDescriptor {
    const val policyVersion: String = "host-managed"
}

class AiLimbsRemoteInvocationExecutor(
    private val remoteIngress: BridgeRemoteIngress
) {
    suspend fun execute(name: String, args: JSONObject): JSONObject =
        remoteIngress.invoke(name, args)
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
        "edit_block" -> editBlock(args)
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
            // shell=operit is the generic AI Limbs capability ingress. Forward the requested
            // capability unchanged; the Host live registry/resolver owns Core vs Plugin vs HostTool
            // classification. Child Bridge providers must not maintain a static capability list.
            return mcpResult(remoteExecutor.execute(name, parameters))
        }
        val params = JSONObject(args.toString())
            .put("command", command)
            .put("timeout_ms", args.optLong("timeout_ms", DEFAULT_RDC_START_WAIT_MS))
        params.remove("shell")
        return processTool("start", params)
    }

    private suspend fun processTool(operation: String, args: JSONObject): JSONObject {
        val params = JSONObject(args.toString()).put("operation", operation)
        if (operation == "read") {
            params.put("length", args.optInt("length", DEFAULT_PROCESS_PAGE_LINES)
                .coerceIn(1, MAX_PROCESS_PAGE_LINES))
        }
        return mcpProcessResult(remoteExecutor.execute(SYSTEM_ENVIRONMENT_PROCESS, params))
    }
    private suspend fun readFile(args: JSONObject): JSONObject {
        val path = args.optString("path").trim()
        if (path.isEmpty()) return mcpError("read_file requires a path")
        val requestedOffset = args.optInt("offset", 0)
        val requestedLength = args.optInt("length", DEFAULT_FILE_PAGE_LINES)
        val length = if (requestedLength > 0) requestedLength.coerceAtMost(MAX_FILE_PAGE_LINES)
            else DEFAULT_FILE_PAGE_LINES
        val environment = resolveEnvironment(path, args)
        // Host read_file_part reports the total line count. Probe one line to
        // implement negative offsets instead of silently reading from line one.
        val offset = if (requestedOffset < 0) {
            val probe = executeHostTool("read_file_part", JSONObject()
                .put("path", path).put("environment", environment)
                .put("text_only", "true").put("start_line", 1).put("end_line", 1))
            if (!probe.optBoolean("success", false)) return mcpResult(probe)
            val total = totalLines(probe) ?: return mcpError("Host did not report the file line count")
            (total.toLong() + requestedOffset).coerceAtLeast(0).toInt()
        } else requestedOffset
        val params = JSONObject()
            .put("path", path)
            .put("environment", environment)
            .put("text_only", "true")
            .put("start_line", offset.toLong().plus(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .put("end_line", offset.toLong().plus(length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val raw = executeHostTool("read_file_part", params)
        if (!raw.optBoolean("success", false)) return mcpResult(raw)
        val total = totalLines(raw) ?: return mcpError("Host did not report the file line count")
        // Host events echo the full result body. Return it once with a cursor.
        val result = JSONObject(raw.toString()).apply { remove("events") }
        val value = result.optJSONObject("result")?.optString("value").orEmpty()
        if (value.contains("... (file content truncated) ...")) {
            return mcpError("One page exceeded the Host's 32000-character file limit; request fewer lines or use a bounded shell command")
        }
        if (offset >= total) result.put("result", JSONObject().put("value", ""))
        val next = (offset.toLong() + length).coerceAtMost(total.toLong()).toInt()
        result.put("page", JSONObject()
            .put("offset", offset).put("length", if (offset >= total) 0 else next - offset)
            .put("total_lines", total).put("has_more", next < total)
            .put("next_offset", if (next < total) next else JSONObject.NULL))
        return mcpResult(result)
    }
    private fun totalLines(result: JSONObject): Int? =
        Regex("""Lines\s+\d+-\d+\s+of\s+(\d+)""")
            .find(result.optJSONObject("result")?.optString("value").orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()

    private suspend fun writeFile(args: JSONObject): JSONObject {
        val path = args.optString("path")
        val params = JSONObject()
            .put("path", path)
            .put("content", args.optString("content"))
            .put("append", args.optString("mode").equals("append", ignoreCase = true))
            .put("environment", resolveEnvironment(path, args))
        return hostTool("write_file", params)
    }
    private suspend fun editBlock(args: JSONObject): JSONObject {
        val path = args.optString("file_path").trim()
        val old = args.optString("old_string")
        val replacement = args.optString("new_string")
        val expected = args.optInt("expected_replacements", 1)
        if (path.isBlank() || old.isBlank() || replacement.isBlank() || expected != 1 || args.has("range")) {
            return mcpError("edit_block supports a non-empty text replacement with expected_replacements=1")
        }
        // The Host dispatcher owns edit_file permissions and replacement semantics.
        // Routing edit_block as a Host tool name was rejected as an unregistered target.
        return mcpResult(
            remoteExecutor.execute(
                "edit_file",
                JSONObject()
                    .put("path", path)
                    .put("old", old)
                    .put("new", replacement)
                    .put("environment", resolveEnvironment(path, args))
            )
        )
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
        val display = if (text.length > MAX_PROCESS_TEXT_CHARS) {
            val header = text.lineSequence().firstOrNull().orEmpty()
            header + "\n[Bridge display shortened; use read_process_output with an absolute offset and a small length]\n" +
                text.takeLast(MAX_PROCESS_TEXT_CHARS - header.length - 110)
        } else text
        val response = JSONObject()
            .put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", display))
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
        const val DEFAULT_FILE_PAGE_LINES = 20
        const val MAX_FILE_PAGE_LINES = 20
        const val DEFAULT_PROCESS_PAGE_LINES = 20
        const val MAX_PROCESS_PAGE_LINES = 20
        const val MAX_PROCESS_TEXT_CHARS = 16_000
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
