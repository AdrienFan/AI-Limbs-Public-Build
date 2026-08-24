package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.api.chat.llmprovider.MediaLinkParser
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapts the fixed Desktop Commander tool surface to Operit's native tools.
 * Operit tools still enter ToolExecutionManager and keep ALLOW / ASK / FORBID semantics. AI Limbs
 * managed documents route through their versioned core provider instead of raw filesystem writes.
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
        managedDocumentTool(path, write = false)?.let { tool ->
            return mcpResult(dispatcher.execute(tool, JSONObject()))
        }
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val length = args.optInt("length", 0).coerceAtLeast(0)
        val environment = resolveEnvironment(path, args)
        val directImage = environment == "android" && isDirectImagePath(path)
        val params = JSONObject()
            .put("path", path)
            .put("environment", environment)
        // RDC image reads must bypass text-only validation so the file reaches ImagePool and the remote result carries real pixels.
        if (directImage) {
            params.put("direct_image", true)
        } else {
            params.put("text_only", "true")
        }
        val operitName = if (directImage || length <= 0) "read_file_full" else "read_file_part"
        if (!directImage && length > 0) {
            params.put("start_line", offset + 1)
            params.put("end_line", offset + length)
        }
        return mcpResult(executeOperit(operitName, params))
    }

    private suspend fun writeFile(args: JSONObject): JSONObject {
        val path = args.optString("path")
        managedDocumentTool(path, write = true)?.let { tool ->
            if (args.optString("mode").equals("append", ignoreCase = true)) {
                return mcpError("AI Limbs managed documents require a full body save")
            }
            return mcpResult(
                dispatcher.execute(
                    tool,
                    JSONObject().put("content", args.optString("content"))
                )
            )
        }
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
            val result =
                if (isAiLimbsCoreTool(name)) {
                    dispatcher.execute(name, parameters)
                } else {
                    executeOperit(name, parameters)
                }
            return mcpResult(result)
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
    ): JSONObject =
        mcpResult(
            if (isAiLimbsCoreTool(toolName)) {
                dispatcher.execute(toolName, args)
            } else {
                executeOperit(toolName, args)
            }
        )

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

    private fun isDirectImagePath(path: String): Boolean =
        path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in DIRECT_IMAGE_EXTENSIONS

    private fun isAiLimbsCoreTool(name: String): Boolean =
        name.startsWith("ai_limbs.") ||
            name.startsWith("capability.") ||
            name.startsWith("laner.") ||
            name == "operit.tools.list"

    private fun managedDocumentTool(path: String, write: Boolean): String? {
        val normalizedPath = path.replace('\\', '/')
        if (!normalizedPath.contains("/ai_limbs/docs/")) return null
        val action = if (write) "write" else "read"
        return when (normalizedPath.substringAfterLast('/')) {
            "AI_LIMBS_SYSTEM_ACCESS_PROMPT.md" -> "ai_limbs.system_access_prompt.$action"
            "AI_LIMBS_CUSTOM_ACCESS_PROMPT.md" -> "ai_limbs.custom_access_prompt.$action"
            "LANER_ACCESS_PROMPT.md" -> "ai_limbs.custom_access_prompt.$action"
            "LANER_WORK_MANUAL.md" -> "ai_limbs.work_manual.$action"
            "LANER_TOOL_MANUAL.md" -> "ai_limbs.tool_manual.$action"
            else -> null
        }
    }

    private fun mcpResult(result: JSONObject): JSONObject {
        val serializedResult = result.toString(2)
        val success = result.optBoolean("success", false)
        val images =
            if (success) {
                MediaLinkParser.extractImageLinks(serializedResult)
            } else {
                emptyList()
            }
        // ImagePool links are an Operit-internal transport token. Exposing them beside an MCP
        // ImageContent block lets the host see two competing image protocols; consume the link
        // here so the RDC model-facing result matches Desktop Commander's text + image contract.
        val modelText =
            if (images.isNotEmpty()) {
                MediaLinkParser.removeImageLinks(serializedResult).trim()
            } else {
                serializedResult
            }
        val content = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", modelText)
        )
        images.forEach { image ->
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put("mimeType", image.mimeType)
                    .put("data", image.base64Data)
            )
            AppLogger.i(
                TAG,
                "RDC image content prepared: mime=${image.mimeType} base64Chars=${image.base64Data.length}"
            )
        }
        return JSONObject()
            .put("content", content)
            .put("isError", !success)
    }
    private fun mcpError(message: String): JSONObject {
        val result = JSONObject()
            .put("success", false)
            .put("error", message)
        return mcpResult(result)
    }

    companion object {
        private const val TAG = "AiLimbsRdcToolAdapter"
        private const val DEFAULT_TERMINAL_TIMEOUT_MS = 120_000L
        private val DIRECT_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    }
}
