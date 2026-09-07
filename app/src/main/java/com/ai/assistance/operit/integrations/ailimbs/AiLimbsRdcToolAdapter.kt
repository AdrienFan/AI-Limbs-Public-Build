package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import com.ai.assistance.operit.api.chat.llmprovider.MediaLinkParser
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Adapts the fixed Desktop Commander tool surface to the host app native tools.
 * Host tools still enter ToolExecutionManager and keep ALLOW / ASK / FORBID semantics. AI Limbs
 * managed documents route through their versioned core provider instead of raw filesystem writes.
 */
internal val AI_LIMBS_RDC_HOST_TOOL_ALIASES = mapOf(
    "get_file_info" to "file_info",
    "create_directory" to "make_directory"
)

internal fun aiLimbsRdcHostAlias(toolName: String): String? =
    AI_LIMBS_RDC_HOST_TOOL_ALIASES[toolName]

class AiLimbsRdcToolAdapter(
    context: Context,
    private val ingressGateway: AiLimbsIngressGateway
) {
    private val appContext = context.applicationContext
    private val handler = AIToolHandler.getInstance(appContext)
    private val processRouter = AiLimbsProcessRouter(ingressGateway)
    private val managedDocumentRoot =
        File(appContext.filesDir, "ai_limbs/docs").canonicalFile
    private val toolRegistry =
        AiLimbsRdcToolRegistry()
            .register("read_file") { args -> readFile(args) }
            .register("read_multiple_files") { args -> readMultipleFiles(args) }
            .register("write_file") { args -> writeFile(args) }
            .register("list_directory") { args -> listDirectory(args) }
            .register("get_file_info") { args -> executeAliasedHostTool("get_file_info", args) }
            .register("create_directory") { args -> executeAliasedHostTool("create_directory", args) }
            .register("start_process") { args -> startProcess(args) }
            .register("read_process_output") { args -> processTool("read", args) }
            .register("interact_with_process") { args -> processTool("interact", args) }
            .register("list_sessions") { args -> processTool("list", args) }
            .register(listOf("kill_process", "force_terminate")) { args ->
                processTool("terminate", args)
            }

    suspend fun execute(toolName: String, args: JSONObject): JSONObject =
        toolRegistry.executeOrNull(toolName, args) ?: executeSameNamedHostTool(toolName, args)

    internal fun registeredAdapterToolNames(): Set<String> = toolRegistry.names

    private suspend fun readFile(args: JSONObject): JSONObject {
        val path = args.optString("path")
        managedDocumentTool(path, write = false)?.let { tool ->
            return mcpResult(ingressGateway.executeWithinSession(tool, JSONObject()))
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
        val hostToolName = if (directImage || length <= 0) "read_file_full" else "read_file_part"
        if (!directImage && length > 0) {
            params.put("start_line", offset + 1)
            params.put("end_line", offset + length)
        }
        return mcpResult(executeHostTool(hostToolName, params))
    }

    private suspend fun readMultipleFiles(args: JSONObject): JSONObject {
        val paths = args.optJSONArray("paths") ?: return mcpError(
            "read_multiple_files requires at least one path"
        )
        if (paths.length() == 0) return mcpError("read_multiple_files requires at least one path")

        val content = JSONArray()
        var successCount = 0
        var failureCount = 0

        for (index in 0 until paths.length()) {
            val path = paths.optString(index).trim()
            if (path.isBlank()) {
                failureCount++
                content.put(JSONObject().put("type", "text").put("text", "=== [empty path] ===\nInvalid path"))
                continue
            }
            content.put(JSONObject().put("type", "text").put("text", "=== $path ==="))
            val result = readFile(JSONObject().put("path", path))
            if (result.optBoolean("isError", false)) failureCount++ else successCount++
            val fileContent = result.optJSONArray("content") ?: JSONArray()
            for (itemIndex in 0 until fileContent.length()) {
                val item = fileContent.optJSONObject(itemIndex)
                if (item != null) content.put(item)
            }
        }
        content.put(
            JSONObject().put("type", "text").put(
                "text",
                "read_multiple_files: $successCount succeeded, $failureCount failed"
            )
        )
        return JSONObject().put("content", content).put("isError", successCount == 0)
    }

    private suspend fun writeFile(args: JSONObject): JSONObject {
        val path = args.optString("path")
        managedDocumentTool(path, write = true)?.let { tool ->
            if (args.optString("mode").equals("append", ignoreCase = true)) {
                return mcpError("AI Limbs managed documents require a full body save")
            }
            return mcpResult(
                ingressGateway.executeWithinSession(
                    tool,
                    JSONObject().put("content", args.optString("content"))
                )
            )
        }
        val environment = resolveEnvironment(path, args)
        val append = args.optString("mode").equals("append", ignoreCase = true)
        val params = JSONObject()
            .put("path", path)
            .put("content", args.optString("content"))
            .put("append", append)
            .put("environment", environment)
        return mcpResult(executeHostTool("write_file", params))
    }

    private suspend fun listDirectory(args: JSONObject): JSONObject {
        val path = args.optString("path")
        val environment = resolveEnvironment(path, args)
        val params = JSONObject()
            .put("path", path)
            .put("environment", environment)
        return mcpResult(executeHostTool("list_files", params))
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
                if (shouldEnterAiLimbsDispatcher(name)) {
                    ingressGateway.executeWithinSession(name, parameters)
                } else {
                    executeHostTool(name, parameters)
                }
            return mcpResult(result)
        }

        if (shell == "android") {
            return mcpResult(
                executeHostTool(
                    "execute_shell",
                    JSONObject().put("command", command)
                )
            )
        }

        val timeoutMs = args.optLong("timeout_ms", DEFAULT_RDC_START_WAIT_MS)
        val params = JSONObject()
            .put("command", command)
            .put("timeout_ms", timeoutMs)
        return processTool("start", params)
    }

    private suspend fun processTool(operation: String, args: JSONObject): JSONObject =
        mcpProcessResult(processRouter.executeSystemEnvironment(operation, args))

    private suspend fun executeAliasedHostTool(
        rdcToolName: String,
        args: JSONObject
    ): JSONObject {
        val hostToolName = aiLimbsRdcHostAlias(rdcToolName)
            ?: return mcpError("RDC tool '$rdcToolName' has no AI Limbs host alias")
        val params = JSONObject(args.toString())
        val path = params.optString("path")
        if (path.isNotBlank() && !params.has("environment")) {
            params.put("environment", resolveEnvironment(path, args))
        }
        return mcpResult(executeHostTool(hostToolName, params))
    }

    private suspend fun executeSameNamedHostTool(
        toolName: String,
        args: JSONObject
    ): JSONObject {
        if (shouldEnterAiLimbsDispatcher(toolName)) {
            return mcpResult(ingressGateway.executeWithinSession(toolName, args))
        }
        handler.registerDefaultTools()
        if (toolName !in handler.getAllToolNames()) {
            return mcpError(
                "RDC tool '$toolName' is not mapped to an AI Limbs adapter or registered host tool"
            )
        }
        return mcpResult(executeHostTool(toolName, args))
    }

    private suspend fun executeHostTool(name: String, params: JSONObject): JSONObject =
        ingressGateway.executeWithinSession(
            AiLimbsCoreCapabilityRegistry.invokeNameForLocalOperation(
                AiLimbsCoreLocalOperation.HOST_TOOL_EXECUTE
            ),
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

    private fun shouldEnterAiLimbsDispatcher(name: String): Boolean =
        AiLimbsCapabilityRegistry.isRegisteredInvokeName(name) ||
            isReservedPluginCapabilityName(name)

    private fun managedDocumentTool(path: String, write: Boolean): String? {
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (candidate.parentFile != managedDocumentRoot) return null
        val documentId = AiLimbsDocumentId.fromFileName(candidate.name) ?: return null
        return AiLimbsCoreCapabilityRegistry.managedDocumentInvokeName(documentId, write)
    }


    private fun mcpProcessResult(result: JSONObject): JSONObject {
        val success = result.optBoolean("success", false)
        val text = result.optString("text").ifBlank { result.toString(2) }
        val response = JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("isError", !success)
        if (result.has("execution_policy")) response.put("execution_policy", result.optJSONObject("execution_policy"))
        return response
    }

    private fun mcpResult(result: JSONObject): JSONObject {
        val success = result.optBoolean("success", false)
        val images =
            if (success) {
                MediaLinkParser.extractImageLinks(result.toString())
            } else {
                emptyList()
            }
        result.put(
            "payload_kind",
            if (images.isNotEmpty()) {
                AiLimbsPayloadKind.IMAGE_PIXELS.name
            } else {
                AiLimbsPayloadKind.STRUCTURED_DATA.name
            }
        )
        val serializedResult = result.toString(2)
        // ImagePool links are a host-internal transport token. Exposing them beside an MCP
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
        private const val DEFAULT_RDC_START_WAIT_MS = 10_000L
        private val DIRECT_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    }
}
