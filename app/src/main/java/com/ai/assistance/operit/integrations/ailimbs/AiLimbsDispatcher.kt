package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.plugins.center.PluginChatModeRuntime
import com.ai.assistance.operit.util.stream.StreamCollector
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

class AiLimbsDispatcher(
    context: Context,
    private val policyEngine: AiLimbsExecutionPolicyEngine,
    private val preserveHostToolResultData: Boolean = false,
    private val toolExecutionOverride: ToolExecutionManager.ToolExecutionOverride? = null
) {
    private val appContext = context.applicationContext
    private val handler = AIToolHandler.getInstance(appContext)
    private val documents = AiLimbsDocumentProvider(appContext)
    private val accessContext = AiLimbsAccessContextService(appContext)
    private val uiCapabilities = AiLimbsUiCapabilityService(appContext)
    private val capabilityResolver = AiLimbsCapabilityResolver(appContext, policyEngine)
    private val developerCatalog = AiLimbsDeveloperCatalogService()
    private val storageIndex = AiLimbsStorageIndex(appContext)
    private val subsystemIngressGate = AiLimbsSubsystemIngressGate(policyEngine)
    private val gson = Gson()

    suspend fun execute(tool: String, args: JSONObject): JSONObject {
        val invocation =
            runCatching { policyEngine.normalize(tool, args) }
                .getOrElse { failure ->
                    return error(failure.message ?: "Unknown AI Limbs capability")
                        .put("error_code", "UNKNOWN_CAPABILITY")
                        .put("next_action", capabilityResolver.capabilitySearchUsage(tool))
                }
        val preflight = policyEngine.evaluatePreflight(invocation)
        if (!preflight.proceed) {
            return policyEngine.rejectionJson(invocation, preflight)
        }
        subsystemIngressGate.intercept(invocation)?.let { discovery ->
            return discovery.put("execution_policy", preflight.inspection.toJson())
        }
        val decision = policyEngine.commitExecution(invocation, preflight)
        if (!decision.proceed) {
            return policyEngine.rejectionJson(invocation, decision)
        }
        val result = executeCapabilityRoute(invocation)
        policyEngine.recordSuccessfulExecution(invocation, result)
        return result.put("execution_policy", decision.inspection.toJson())
    }

    private suspend fun executeCapabilityRoute(
        invocation: AiLimbsNormalizedInvocation
    ): JSONObject =
        when (val route = invocation.route) {
            is AiLimbsCapabilityRoute.Core ->
                executeCoreRoute(route.registration, invocation.parameters)
            is AiLimbsCapabilityRoute.Plugin ->
                route.registration.executor.execute(JSONObject(invocation.parameters.toString()))
            is AiLimbsCapabilityRoute.HostTool ->
                executeHostTool(
                    JSONObject()
                        .put("name", route.targetName)
                        .put("parameters", invocation.parameters)
                )
        }

    private suspend fun executeCoreRoute(
        registration: AiLimbsCoreCapabilityRegistration,
        args: JSONObject
    ): JSONObject =
        when (val route = registration.route) {
            is AiLimbsCoreRoute.Local -> executeLocalOperation(route.operation, args)
            is AiLimbsCoreRoute.ManagedDocumentRead -> executeManagedDocumentRead(route.documentId)
            is AiLimbsCoreRoute.ManagedDocumentWrite -> executeManagedDocumentWrite(route.documentId, args)
            is AiLimbsCoreRoute.PluginChatModeCompatibility ->
                executePluginChatModeCompatibility(route.operation, args)
            AiLimbsCoreRoute.ForwardHostTool ->
                error("ForwardHostTool must be normalized to HostTool before dispatch")
                    .put("error_code", "INVALID_CAPABILITY_ROUTE")
        }

    private suspend fun executeLocalOperation(
        operation: AiLimbsCoreLocalOperation,
        args: JSONObject
    ): JSONObject =
        when (operation) {
            AiLimbsCoreLocalOperation.ACCESS_CONTEXT_READ ->
                ok()
                    .put("document", "access_bootstrap")
                    .put("version", AiLimbsSystemAccessPrompt.version)
                    .put("policy_version", AiLimbsExecutionPolicyDescriptor.policyVersion)
                    .put("content", accessContext.readAccessContext())
            AiLimbsCoreLocalOperation.CAPABILITY_SEARCH ->
                capabilityResolver.search(
                    query = args.optString("query"),
                    requestedLimit = args.optInt("limit", 8)
                )
            AiLimbsCoreLocalOperation.CAPABILITY_DESCRIBE ->
                capabilityResolver.describe(
                    args.optString("capability_id")
                        .ifBlank { args.optString("id") }
                        .ifBlank { args.optString("invoke_id") }
                )
            AiLimbsCoreLocalOperation.DEVELOPER_CATALOG_READ -> developerCatalog.read(args)
            AiLimbsCoreLocalOperation.CORE_STATUS -> coreStatus()
            AiLimbsCoreLocalOperation.DISPATCHER_STATUS -> dispatcherStatus()
            AiLimbsCoreLocalOperation.UI_STATUS -> uiCapabilityStatus()
            AiLimbsCoreLocalOperation.HOST_TOOLS_LIST -> {
                handler.registerDefaultTools()
                val names = JSONArray()
                handler.getAllToolNames().forEach { names.put(it) }
                ok().put("tools", names).put("count", names.length())
            }
            AiLimbsCoreLocalOperation.HOST_TOOL_EXECUTE -> executeHostTool(args)
            AiLimbsCoreLocalOperation.POLICY_DESCRIBE -> policyEngine.describePolicy()
            AiLimbsCoreLocalOperation.WORK_MODE_SELECT -> policyEngine.selectWorkMode(args)
            AiLimbsCoreLocalOperation.POLICY_SESSION_RESET ->
                policyEngine.resetInteractionCycle()
            AiLimbsCoreLocalOperation.STORAGE_SEARCH ->
                storageIndex.search(
                    query = args.optString("query"),
                    projectId = args.optString("project_id").ifBlank { null },
                    requestedLimit = args.optInt("limit", 20)
                )
            AiLimbsCoreLocalOperation.STORAGE_DESCRIBE ->
                storageIndex.describe(args.optString("artifact_id"))
            AiLimbsCoreLocalOperation.STORAGE_PROJECT_FILES ->
                storageIndex.projectFiles(
                    projectId = args.optString("project_id"),
                    logicalOwner = args.optString("logical_owner").ifBlank { null }
                )
        }

    private suspend fun executeManagedDocumentRead(documentId: AiLimbsDocumentId): JSONObject =
        when (documentId) {
            AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT -> {
                val reference = documents.documentReference(documentId)
                ok()
                    .put("document", reference.documentId)
                    .put("version", reference.version)
                    .put("path", reference.path)
                    .put("content", documents.readSystemAccessPrompt())
            }
            AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT -> {
                val reference = documents.documentReference(documentId)
                ok()
                    .put("document", reference.documentId)
                    .put("version", reference.version)
                    .put("path", reference.path)
                    .put("empty", reference.isEmpty)
                    .put("content", documents.readCustomAccessPrompt())
            }
            AiLimbsDocumentId.WORK_MANUAL -> {
                val reference = documents.documentReference(documentId)
                ok()
                    .put("document", reference.documentId)
                    .put("version", reference.version)
                    .put("path", reference.path)
                    .put("content", documents.readWorkManualForAgent())
                    .put("editable_content", documents.readWorkManual())
            }
        }

    private suspend fun executeManagedDocumentWrite(
        documentId: AiLimbsDocumentId,
        args: JSONObject
    ): JSONObject =
        when (documentId) {
            AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT ->
                error("AI Limbs system access prompt is immutable code")
                    .put("error_code", "IMMUTABLE_SYSTEM_ACCESS_PROMPT")
            AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT -> {
                val changed = documents.writeCustomAccessPrompt(args.optString("content"))
                val reference = documents.documentReference(documentId)
                ok()
                    .put("document", reference.documentId)
                    .put("version", reference.version)
                    .put("path", reference.path)
                    .put("empty", reference.isEmpty)
                    .put("changed", changed)
            }
            AiLimbsDocumentId.WORK_MANUAL -> {
                val changed = documents.writeWorkManual(args.optString("content"))
                val reference = documents.documentReference(documentId)
                ok()
                    .put("document", reference.documentId)
                    .put("version", reference.version)
                    .put("path", reference.path)
                    .put("changed", changed)
            }
        }

    private suspend fun executePluginChatModeCompatibility(
        operation: String,
        args: JSONObject
    ): JSONObject =
        when (operation) {
            "attachment.fetch" -> pluginChatModeAttachmentFetch(args)
            "turn.reply" -> pluginChatModeTurnReply(args)
            "reply" -> pluginChatModeReply(args)
            "send" -> pluginChatModeSend(args)
            else ->
                flattenPluginChatCompatibility(
                    operation,
                    PluginChatModeRuntime.invokeBusinessCompatibility(operation, args)
                )
        }

    private suspend fun executeHostTool(args: JSONObject): JSONObject {
        val name = args.optString("name").trim()
        if (name.isBlank()) return error("Missing host tool name")
        if (isReservedPluginCapabilityName(name)) {
            return error("Host tools cannot use the reserved plugin capability namespace: $name")
                .put("error_code", "PLUGIN_CAPABILITY_NAMESPACE_RESERVED")
        }
        val parameters = args.optJSONObject("parameters") ?: JSONObject()
        val operation: suspend () -> JSONObject = {
            executeHostToolNow(name, parameters)
        }
        return if (storageIndex.isPersistentHostTool(name)) {
            storageIndex.executePersistentHostOperation(
                hostToolName = name,
                parameters = parameters,
                source =
                    policyEngine.session.sourceTransportId +
                        ":" +
                        policyEngine.session.scopeId,
                operation = operation
            )
        } else {
            operation()
        }
    }

    private suspend fun executeHostToolNow(
        name: String,
        parameters: JSONObject
    ): JSONObject {
        val params = mutableListOf<ToolParameter>()
        val keys = parameters.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            params += ToolParameter(key, parameters.opt(key)?.toString() ?: "")
        }
        val aiTool = AITool(name = name, parameters = params)
        val invocation =
            ToolInvocation(
                aiTool,
                rawText = "<ai-limbs-direct-tool/>",
                responseLocation = 0..0
            )
        val emitted = mutableListOf<String>()
        val preapprovedAsk = AiLimbsExecutionAuthorization.allows(
            coroutineContext,
            policyEngine.session,
            name
        )
        val results =
            ToolExecutionManager.executeInvocations(
                invocations = listOf(invocation),
                context = appContext,
                toolHandler = handler,
                packageManager = handler.getOrCreatePackageManager(),
                callerName = "AI Limbs Bridge",
                preapprovedAsk = preapprovedAsk,
                preserveStructuredResult = preserveHostToolResultData,
                executionOverride = toolExecutionOverride,
                collector =
                    object : StreamCollector<String> {
                        override suspend fun emit(value: String) {
                            emitted += value
                        }
                    }
            )
        val result = results.firstOrNull() ?: return error("Host tool returned no result")
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
                        "AI Limbs Execution Policy Engine",
                        "AI Limbs Storage Index",
                        "AI Limbs System Environment Capability Bus",
                        "AI Limbs Plugin Chat Mode Compatibility"
                    )
                )
            )

    private fun dispatcherStatus(): JSONObject {
        val kernelRole = PluginPlatformKernel.lifecycleSnapshot().optString("runtime_role")
        val authority = if (kernelRole == "business") "resident_core" else "legacy_host"
        return ok()
            .put("module", "AI Limbs Tool Dispatcher")
            .put(
                "route",
                "Transport -> AiLimbsExecutionPolicyEngine -> AiLimbsDispatcher -> Core | HostTool"
            )
            .put("dispatcher_owner", authority)
            .put("policy_owner", authority)
            .put("owner_pid", android.os.Process.myPid())
            .put("interaction_cycle_generation", AiLimbsInteractionCycleRuntime.state(appContext).currentGeneration())
            .put("permission_enforcement", "Unified ALLOW / ASK / FORBID policy")
            .put("policy_version", AiLimbsExecutionPolicyDescriptor.policyVersion)
            .put("session_scope", policyEngine.session.scopeId)
            .put("transport", policyEngine.session.sourceTransportId)
            .put("transport_neutral", true)
    }

    private fun flattenPluginChatCompatibility(
        operation: String,
        raw: JSONObject
    ): JSONObject {
        val out = JSONObject(raw.toString())

        fun copy(source: JSONObject?) {
            if (source == null) return
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out.put(key, source.opt(key))
            }
        }

        when (operation) {
            "status" -> {
                copy(raw.optJSONObject("mailbox"))
                val queue = raw.optJSONObject("queue")
                if (queue != null) {
                    out.put("unread_count", queue.optInt("pending_count", 0))
                    out.put("attention_count", queue.optInt("unresolved_count", 0))
                    out.put("pending_reply_count", queue.optInt("unresolved_count", 0))
                    out.put("highest_priority", queue.opt("highest_priority") ?: JSONObject.NULL)
                }
            }
            "session.open" -> {
                val result = raw.optJSONObject("result")
                copy(result)
                val session = result?.optJSONObject("session")
                copy(session)
                if (session != null) {
                    out.put("bound_chat_id", session.opt("chat_id") ?: JSONObject.NULL)
                }
                if (result != null) {
                    out.put("pending_reply_count", result.optInt("pending_requests", 0))
                }
            }
            "session.close" -> copy(raw.optJSONObject("session"))
            "notification.check", "notification.wait" ->
                copy(raw.optJSONObject("notification"))
            "inbox.fetch",
            "turn.status",
            "turn.claim",
            "turn.resolve",
            "turn.cancel",
            "turn.resume" -> copy(raw.optJSONObject("result"))
        }

        if (operation == "turn.claim" && out.optBoolean("claimed", false)) {
            out.put(
                "terminal_actions",
                JSONArray(listOf("ai_limbs.chat.turn.reply", "ai_limbs.chat.turn.resolve"))
            )
        }
        return out
    }

    private suspend fun pluginChatModeAttachmentFetch(args: JSONObject): JSONObject {
        val requestId = args.optString("request_id").trim()
        val attachmentId = args.optString("attachment_id").trim()
        val raw =
            PluginChatModeRuntime.invokeBusinessCompatibility(
                "attachment.fetch",
                JSONObject(args.toString())
            )
        val attachment = raw.getJSONObject("attachment")
        val filePath = attachment.getString("file_path")
        val mimeType = attachment.optString("mime_type", "application/octet-stream")
        val isImage = mimeType.startsWith("image/", ignoreCase = true)
        val readResult =
            execute(
                AiLimbsCoreCapabilityRegistry.invokeNameForLocalOperation(
                    AiLimbsCoreLocalOperation.HOST_TOOL_EXECUTE
                ),
                JSONObject()
                    .put("name", "read_file_full")
                    .put(
                        "parameters",
                        JSONObject()
                            .put("path", filePath)
                            .put("direct_image", isImage)
                    )
            )
        val response =
            if (readResult.optBoolean("success", false)) {
                ok()
            } else {
                error("Unable to read plugin chat attachment")
            }
        return response
            .put("request_id", requestId)
            .put("attachment_id", attachmentId)
            .put("file_path", filePath)
            .put(
                "filename",
                attachment.optString("file_name").ifBlank {
                    attachment.optString("filename")
                }
            )
            .put("mime_type", mimeType)
            .put(
                "size",
                if (attachment.has("file_size")) {
                    attachment.optLong("file_size")
                } else {
                    attachment.optLong("size")
                }
            )
            .put("content_mode", if (isImage) "multimodal_image" else "text_or_document")
            .put("payload", readResult.opt("result") ?: JSONObject.NULL)
            .put("read_error", readResult.opt("error") ?: JSONObject.NULL)
            .put("events", readResult.optJSONArray("events") ?: JSONArray())
    }

    private suspend fun pluginChatModeTurnReply(args: JSONObject): JSONObject {
        val raw =
            PluginChatModeRuntime.invokeBusinessCompatibility(
                "turn.reply",
                JSONObject(args.toString())
            )
        val result = raw.getJSONObject("result")
        val turn = result.getJSONObject("turn")
        val covered = result.optJSONArray("covered_requests") ?: JSONArray()
        val firstRequest = covered.optJSONObject(0)
        val chatId = firstRequest?.optString("chat_id").orEmpty().trim()
        val content = turn.optString("reply_content")
        if (chatId.isNotEmpty() && content.isNotBlank()) {
            mirrorPluginChatAssistantMessage(
                chatId = chatId,
                content = content,
                timestamp = turn.optLong("chat_message_timestamp", System.currentTimeMillis()),
                completedAt = nullableLong(turn, "completed_at_ms")
            )
        }
        return JSONObject(raw.toString())
            .put("turn_id", turn.optString("turn_id"))
            .put("reply_id", turn.opt("reply_id") ?: JSONObject.NULL)
            .put("status", turn.optString("status"))
            .put("covered_request_ids", turn.optJSONArray("request_ids") ?: JSONArray())
            .put("covered_request_count", result.optInt("covered_request_count", covered.length()))
            .put("duplicate", result.optBoolean("duplicate", false))
            .put("delivered_to_chat", chatId.isNotEmpty() && content.isNotBlank())
            .put("delivery_pending", false)
            .put("completed_at", isoTime(nullableLong(turn, "completed_at_ms")))
    }

    private suspend fun pluginChatModeReply(args: JSONObject): JSONObject {
        val raw =
            PluginChatModeRuntime.invokeBusinessCompatibility(
                "reply",
                JSONObject(args.toString())
            )
        val result = raw.getJSONObject("result")
        val request = result.getJSONObject("request")
        val chatId = request.optString("chat_id").trim()
        val content = request.optString("reply_content")
        if (chatId.isNotEmpty() && content.isNotBlank()) {
            mirrorPluginChatAssistantMessage(
                chatId = chatId,
                content = content,
                timestamp = request.optLong("chat_message_timestamp", System.currentTimeMillis()),
                completedAt = nullableLong(request, "answered_at_ms")
            )
        }
        return JSONObject(raw.toString())
            .put("request_id", request.optString("request_id"))
            .put("reply_id", request.opt("reply_id") ?: JSONObject.NULL)
            .put("status", request.optString("status"))
            .put("duplicate", result.optBoolean("duplicate", false))
            .put("delivered_to_live_stream", result.optBoolean("delivered_to_live_stream", false))
            .put("delivered_to_chat", chatId.isNotEmpty() && content.isNotBlank())
            .put("answered_at", isoTime(nullableLong(request, "answered_at_ms")))
    }

    private suspend fun pluginChatModeSend(args: JSONObject): JSONObject {
        val openedRaw =
            PluginChatModeRuntime.invokeBusinessCompatibility(
                "session.open",
                JSONObject()
                    .put("session_id", args.optString("session_id").ifBlank { JSONObject.NULL })
            )
        val opened = openedRaw.getJSONObject("result")
        val session = opened.getJSONObject("session")
        val sessionId = session.getString("session_id")
        var chatId = session.optString("chat_id").trim()

        val core = ChatRuntimeHolder.getInstance(appContext).getCore(ChatRuntimeSlot.MAIN)
        val chatHistory = core.getChatHistoryDelegate()
        if (chatId.isEmpty()) {
            val apiConfig = core.getApiConfigDelegate()
            val currentChatId = chatHistory.currentChatId.value?.takeIf { it.isNotBlank() }
            val activeIsChatMode =
                PluginChatModeRuntime.isChatModeConfig(apiConfig.activeChatModelConfig.value)
            chatId =
                when {
                    activeIsChatMode && currentChatId != null -> currentChatId
                    currentChatId == null -> {
                        apiConfig.activateChatModeConfiguration(
                            PluginChatModeRuntime.businessConfigurationTemplate()
                        )
                        chatHistory.ensureCurrentChat(5_000L)
                    }
                    else -> {
                        apiConfig.activateChatModeConfiguration(
                            PluginChatModeRuntime.businessConfigurationTemplate()
                        )
                        chatHistory.createAndSelectNewChat(5_000L)
                    }
                }
            PluginChatModeRuntime.invokeBusinessCompatibility(
                "ui.bind_chat",
                JSONObject().put("chat_id", chatId)
            )
        }

        val preparedRaw =
            PluginChatModeRuntime.invokeBusinessCompatibility(
                "proactive.prepare",
                JSONObject()
                    .put("session_id", sessionId)
                    .put("message_id", args.optString("message_id").ifBlank { JSONObject.NULL })
                    .put("content", args.optString("content"))
            )
        val prepared = preparedRaw.getJSONObject("result")
        val message = prepared.getJSONObject("message")
        mirrorPluginChatAssistantMessage(
            chatId = message.getString("chat_id"),
            content = message.getString("content"),
            timestamp = message.optLong("chat_message_timestamp", System.currentTimeMillis()),
            completedAt = System.currentTimeMillis()
        )

        val deliveredRaw =
            PluginChatModeRuntime.invokeBusinessCompatibility(
                "proactive.delivered",
                JSONObject().put("message_id", message.getString("message_id"))
            )
        val delivered = deliveredRaw.getJSONObject("message")
        return ok()
            .put("message_id", delivered.getString("message_id"))
            .put("session_id", delivered.getString("session_id"))
            .put("chat_id", delivered.getString("chat_id"))
            .put("status", delivered.optString("status"))
            .put("duplicate", prepared.optBoolean("duplicate", false))
            .put("created_at", isoTime(nullableLong(delivered, "created_at_ms")))
            .put("delivered_at", isoTime(nullableLong(delivered, "delivered_at_ms")))
    }

    private suspend fun mirrorPluginChatAssistantMessage(
        chatId: String,
        content: String,
        timestamp: Long,
        completedAt: Long?
    ) {
        val binding = PluginChatModeRuntime.businessBinding()
        val providerTypeId = binding.metadata["provider_type_id"].orEmpty()
        val modelName =
            binding.metadata["model_name"]?.takeIf { it.isNotBlank() }
                ?: providerTypeId
        val roleName =
            binding.metadata["display_name"]?.takeIf { it.isNotBlank() }
                ?: "AI"
        val core = ChatRuntimeHolder.getInstance(appContext).getCore(ChatRuntimeSlot.MAIN)
        core.getChatHistoryDelegate().addMessageToChat(
            message =
                ChatMessage(
                    sender = "ai",
                    content = content,
                    timestamp = timestamp,
                    roleName = roleName,
                    provider = providerTypeId,
                    modelName = modelName,
                    completedAt = completedAt ?: System.currentTimeMillis()
                ),
            chatIdOverride = chatId
        )
    }

    private fun nullableLong(value: JSONObject, key: String): Long? =
        if (value.has(key) && !value.isNull(key)) value.optLong(key) else null

    private fun isoTime(timestampMs: Long?): Any {
        if (timestampMs == null) return JSONObject.NULL
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(timestampMs))
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
