package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.util.stream.StreamCollector
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject

class AiLimbsOperitDispatcher(context: Context) {
    private val appContext = context.applicationContext
    private val handler = AIToolHandler.getInstance(appContext)
    private val documents = AiLimbsDocumentProvider(appContext)
    private val accessContext = AiLimbsAccessContextService(appContext)
    private val uiCapabilities = AiLimbsUiCapabilityService(appContext)
    private val capabilityResolver = AiLimbsCapabilityResolver(appContext)
    private val gson = Gson()

    suspend fun execute(tool: String, args: JSONObject): JSONObject = when (tool) {
        "ai_limbs.access_context.read" ->
            ok()
                .put("document", "access_context")
                .put("content", accessContext.readAccessContext())
        "ai_limbs.access_prompt.read", "laner.access_prompt.read" ->
            ok()
                .put("document", AiLimbsDocumentId.ACCESS_PROMPT.stableId)
                .put("content", documents.readAccessPrompt())
        "ai_limbs.access_prompt.write", "laner.access_prompt.write" -> {
            val changed = documents.writeAccessPrompt(args.optString("content"))
            ok().put("document", AiLimbsDocumentId.ACCESS_PROMPT.stableId).put("changed", changed)
        }
        "ai_limbs.work_manual.read", "laner.work_manual.read" ->
            ok()
                .put("document", AiLimbsDocumentId.WORK_MANUAL.stableId)
                .put("content", documents.readWorkManualForAgent())
                .put("editable_content", documents.readWorkManual())
        "ai_limbs.work_manual.write", "laner.work_manual.write" -> {
            val changed = documents.writeWorkManual(args.optString("content"))
            ok().put("document", AiLimbsDocumentId.WORK_MANUAL.stableId).put("changed", changed)
        }
        "ai_limbs.tool_manual.read" ->
            ok()
                .put("document", AiLimbsDocumentId.TOOL_MANUAL.stableId)
                .put("content", documents.readToolManual())
        "ai_limbs.tool_manual.write" -> {
            val changed = documents.writeToolManual(args.optString("content"))
            ok().put("document", AiLimbsDocumentId.TOOL_MANUAL.stableId).put("changed", changed)
        }
        "capability.search" ->
            capabilityResolver.search(
                query = args.optString("query"),
                requestedLimit = args.optInt("limit", 5)
            )
        "capability.describe" ->
            capabilityResolver.describe(
                args.optString("capability_id")
                    .ifBlank { args.optString("id") }
                    .ifBlank { args.optString("invoke_id") }
            )
        "ai_limbs.core.status" -> coreStatus()
        "ai_limbs.dispatcher.status" -> dispatcherStatus()
        "ai_limbs.ubuntu.share.status" -> sharedUbuntuStatus()
        "ai_limbs.ui.status" -> uiCapabilityStatus()
        "operit.tools.list" -> {
            handler.registerDefaultTools()
            val names = JSONArray()
            handler.getAllToolNames().forEach { names.put(it) }
            ok().put("tools", names).put("count", names.length())
        }
        "ubuntu.status", "ubuntu.start", "ubuntu.stop", "ubuntu.idle.get", "ubuntu.idle.set" ->
            executeOperitTool(
                JSONObject()
                    .put("name", tool)
                    .put("parameters", args)
            )
        "operit.tool.execute" -> executeOperitTool(args)
        else -> error("Unknown AI Limbs tool: $tool")
    }

    private suspend fun executeOperitTool(args: JSONObject): JSONObject {
        val name = args.optString("name").trim()
        if (name.isBlank()) return error("Missing Operit tool name")
        val paramsObject = args.optJSONObject("parameters") ?: JSONObject()
        val params = mutableListOf<ToolParameter>()
        val keys = paramsObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            params += ToolParameter(key, paramsObject.opt(key)?.toString() ?: "")
        }
        val aiTool = AITool(name = name, parameters = params)
        val invocation = ToolInvocation(aiTool, rawText = "<ai-limbs-direct-tool/>", responseLocation = 0..0)
        val emitted = mutableListOf<String>()
        val results = ToolExecutionManager.executeInvocations(
            invocations = listOf(invocation),
            context = appContext,
            toolHandler = handler,
            packageManager = handler.getOrCreatePackageManager(),
            callerName = "AI Limbs Bridge",
            collector = object : StreamCollector<String> {
                override suspend fun emit(value: String) { emitted += value }
            }
        )
        val result = results.firstOrNull() ?: return error("Operit tool returned no result")
        return JSONObject()
            .put("success", result.success)
            .put("tool", result.toolName)
            .put("result", parseJsonOrString(gson.toJson(result.result)))
            .put("error", result.error ?: JSONObject.NULL)
            .put("events", JSONArray(emitted))
    }

    private suspend fun uiCapabilityStatus(): JSONObject {
        val status = uiCapabilities.readStatus()
        return ok()
            .put("preferred_permission_level", status.preferredPermissionLevel.name)
            .put("active_backend", status.activeBackend)
            .put("selected_backend_available", status.selectedBackendAvailable)
            .put("direct_ui_ready", status.directUiReady)
            .put("accessibility_provider_installed", status.accessibilityProviderInstalled)
            .put(
                "accessibility_provider_version",
                status.accessibilityProviderVersion ?: JSONObject.NULL
            )
            .put("accessibility_service_enabled", status.accessibilityServiceEnabled)
            .put("automatic_ui_base_enabled", status.automaticUiBaseEnabled)
            .put("automatic_ui_subagent_enabled", status.automaticUiSubagentEnabled)
            .put("ui_controller_model", status.uiControllerModelName ?: JSONObject.NULL)
            .put("ui_controller_image_enabled", status.uiControllerImageEnabled)
            .put("ui_subagent_ready", status.uiSubagentReady)
            .put("next_action", status.nextAction ?: JSONObject.NULL)
    }

    private fun coreStatus(): JSONObject =
        ok()
            .put("module", "AI Limbs Core")
            .put("version", BuildConfig.VERSION_NAME)
            .put("provider", AiLimbsCoreCapabilityRegistry.CORE_PROVIDER)
            .put(
                "registered_capabilities",
                JSONArray(AiLimbsCoreCapabilityRegistry.registeredToolNames())
            )
            .put(
                "modules",
                JSONArray(
                    listOf(
                        "AI Limbs Core",
                        "AI Limbs Capability Resolver",
                        "AI Limbs Tool Dispatcher",
                        "AI Limbs Ubuntu Runtime"
                    )
                )
            )

    private fun dispatcherStatus(): JSONObject =
        ok()
            .put("module", "AI Limbs Tool Dispatcher")
            .put("route", "AiLimbsOperitDispatcher -> ToolExecutionManager -> AIToolHandler")
            .put("permission_enforcement", "ToolPermissionSystem ALLOW / ASK / FORBID")
            .put("transport_neutral", true)

    private fun sharedUbuntuStatus(): JSONObject {
        val terminal = com.ai.assistance.operit.core.tools.system.Terminal.getInstance(appContext)
        val state = terminal.currentSharedHiddenTerminalState()
        val usage = terminal.currentUbuntuUsageState()
        return ok()
            .put("module", "Laner Ubuntu Shared View")
            .put("active", state.isActive)
            .put("active_operation_count", state.activeOperationCount)
            .put("has_recent_operation", state.operationId != null)
            .put("read_only", true)
            .put("persisted", false)
            .put("participant_count", usage.participantCount)
            .put("user_interface_clients", usage.userInterfaceClients)
            .put("hidden_ai_operations", usage.hiddenAiOperations)
    }

    private fun parseJsonOrString(raw: String): Any = try {
        when {
            raw.startsWith("{") -> JSONObject(raw)
            raw.startsWith("[") -> JSONArray(raw)
            else -> raw
        }
    } catch (_: Exception) { raw }

    private fun ok() = JSONObject().put("success", true)
    private fun error(message: String) = JSONObject().put("success", false).put("error", message)
}
