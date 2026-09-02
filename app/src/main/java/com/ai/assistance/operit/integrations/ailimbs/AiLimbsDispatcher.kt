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
import com.ai.assistance.operit.integrations.ailimbs.chat.LanerChatBridgeService
import com.ai.assistance.operit.integrations.ailimbs.chat.LanerChatContract
import com.ai.assistance.operit.integrations.ailimbs.chat.LanerChatManagedTurnReplyRequiredException
import com.ai.assistance.operit.integrations.ailimbs.chat.LanerChatRequest
import com.ai.assistance.operit.integrations.ailimbs.chat.LanerChatPriority
import com.ai.assistance.operit.integrations.ailimbs.chat.LanerChatPresenceState
import com.ai.assistance.operit.util.stream.StreamCollector
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

class AiLimbsDispatcher(
    context: Context,
    private val policyEngine: AiLimbsExecutionPolicyEngine
) {
    private val appContext = context.applicationContext
    private val handler = AIToolHandler.getInstance(appContext)
    private val documents = AiLimbsDocumentProvider(appContext)
    private val accessContext = AiLimbsAccessContextService(appContext)
    private val uiCapabilities = AiLimbsUiCapabilityService(appContext)
    private val capabilityResolver = AiLimbsCapabilityResolver(appContext, policyEngine)
    private val developerCatalog = AiLimbsDeveloperCatalogService()
    private val storageIndex = AiLimbsStorageIndex(appContext)
    private val lanerChat = LanerChatBridgeService.getInstance(appContext)
    private val gson = Gson()

    suspend fun execute(tool: String, args: JSONObject): JSONObject {
        val invocation =
            runCatching { policyEngine.normalize(tool, args) }
                .getOrElse { failure ->
                    return error(failure.message ?: "Unknown AI Limbs capability")
                        .put("error_code", "UNKNOWN_CAPABILITY")
                }
        val decision = policyEngine.evaluate(invocation)
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
            is AiLimbsCoreRoute.LanerChat -> executeLanerChatOperation(route.operation, args)
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
                    requestedLimit = args.optInt("limit", 5)
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
            AiLimbsCoreLocalOperation.SHARED_UBUNTU_STATUS -> sharedUbuntuStatus()
            AiLimbsCoreLocalOperation.UI_STATUS -> uiCapabilityStatus()
            AiLimbsCoreLocalOperation.HOST_TOOLS_LIST -> {
                handler.registerDefaultTools()
                val names = JSONArray()
                handler.getAllToolNames().forEach { names.put(it) }
                ok().put("tools", names).put("count", names.length())
            }
            AiLimbsCoreLocalOperation.HOST_TOOL_EXECUTE -> executeHostTool(args)
            AiLimbsCoreLocalOperation.POLICY_DESCRIBE -> policyEngine.describePolicy()
            AiLimbsCoreLocalOperation.POLICY_SESSION_RESET ->
                policyEngine.resetSessionReceipts()
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

    private suspend fun executeLanerChatOperation(
        operation: AiLimbsLanerChatOperation,
        args: JSONObject
    ): JSONObject =
        when (operation) {
            AiLimbsLanerChatOperation.STATUS -> lanerChatStatus()
            AiLimbsLanerChatOperation.SESSION_OPEN -> lanerChatSessionOpen(args)
            AiLimbsLanerChatOperation.SESSION_CLOSE -> lanerChatSessionClose(args)
            AiLimbsLanerChatOperation.NOTIFICATION_CHECK -> lanerChatNotificationCheck(args)
            AiLimbsLanerChatOperation.NOTIFICATION_WAIT -> lanerChatNotificationWait(args)
            AiLimbsLanerChatOperation.INBOX_FETCH -> lanerChatInboxFetch(args)
            AiLimbsLanerChatOperation.ATTACHMENT_FETCH -> lanerChatAttachmentFetch(args)
            AiLimbsLanerChatOperation.TURN_STATUS -> lanerChatTurnStatus(args)
            AiLimbsLanerChatOperation.TURN_CLAIM -> lanerChatTurnClaim(args)
            AiLimbsLanerChatOperation.TURN_REPLY -> lanerChatTurnReply(args)
            AiLimbsLanerChatOperation.TURN_RESOLVE -> lanerChatTurnResolve(args)
            AiLimbsLanerChatOperation.TURN_CANCEL -> lanerChatTurnCancel(args)
            AiLimbsLanerChatOperation.TURN_RESUME -> lanerChatTurnResume(args)
            AiLimbsLanerChatOperation.LEGACY_REPLY -> lanerChatReply(args)
            AiLimbsLanerChatOperation.SEND -> lanerChatSend(args)
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
                    policyEngine.session.transport.wireValue +
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
        val results =
            ToolExecutionManager.executeInvocations(
                invocations = listOf(invocation),
                context = appContext,
                toolHandler = handler,
                packageManager = handler.getOrCreatePackageManager(),
                callerName = "AI Limbs Bridge",
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
                        "AI Limbs Ubuntu Runtime",
                        "AI Limbs Laner Chat Bridge"
                    )
                )
            )

    private fun dispatcherStatus(): JSONObject =
        ok()
            .put("module", "AI Limbs Tool Dispatcher")
            .put(
                "route",
                "Transport -> AiLimbsExecutionPolicyEngine -> AiLimbsDispatcher -> Core | HostTool"
            )
            .put("permission_enforcement", "Unified ALLOW / ASK / FORBID policy")
            .put("policy_version", AiLimbsExecutionPolicyDescriptor.policyVersion)
            .put("session_scope", policyEngine.session.scopeId)
            .put("transport", policyEngine.session.transport.wireValue)
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

    private fun lanerChatStatus(): JSONObject {
        // A remote status query is itself verified agent activity. Renew presence before snapshotting
        // so a reconnecting Laner does not remain visibly stale until another chat operation occurs.
        lanerChat.markAgentSeen()
        val mailbox = lanerChat.snapshot()
        val bridge = AiLimbsBridgeManager.runtimeState.value
        val agentPresence =
            LanerChatContract.presenceState(
                activeSessionId = mailbox.activeSessionId,
                lastAgentSeenAtMs = mailbox.lastAgentSeenAtMs
            )
        val agentOnline = agentPresence != LanerChatPresenceState.WAITING
        return ok()
            .put("module", "AI Limbs Laner Chat Bridge")
            .put("protocol_version", 6)
            .put("provider_type_id", LanerChatContract.PROVIDER_TYPE_ID)
            .put("bridge_provider", bridge.providerId)
            .put("bridge_phase", bridge.phase.name)
            .put("active_session_id", mailbox.activeSessionId ?: JSONObject.NULL)
            .put("bound_chat_id", mailbox.boundChatId ?: JSONObject.NULL)
            .put("agent_session_online", agentOnline)
            .put("agent_session_presence", agentPresence.wireValue)
            .put("last_agent_seen_at", isoTime(mailbox.lastAgentSeenAtMs))
            .put("latest_seq", mailbox.latestSeq)
            .put("unread_count", mailbox.pendingCount)
            .put("attention_count", mailbox.unresolvedCount)
            .put("pending_reply_count", mailbox.unresolvedCount)
            .put("answered_count", mailbox.answeredCount)
            .put("resolved_no_reply_count", mailbox.resolvedNoReplyCount)
            .put("canceled_count", mailbox.canceledCount)
            .put("proactive_pending_count", mailbox.proactivePendingCount)
            .put("proactive_delivered_count", mailbox.proactiveDeliveredCount)
            .put("active_turn_id", mailbox.activeTurnId ?: JSONObject.NULL)
            .put("active_turn_request_count", mailbox.activeTurnRequestCount)
            .put("active_turn_highest_priority", mailbox.activeTurnHighestPriority?.name ?: JSONObject.NULL)
            .put("scheduler_paused", mailbox.schedulerPaused)
            .put("supports_proactive_send", true)
            .put("supports_attachments", true)
            .put("supports_priority", true)
            .put("supports_turn_scheduler", true)
            .put("supports_batch_claim", true)
            .put("supports_no_reply_resolution", true)
            .put("notification_contains_body", false)
    }

    private fun lanerChatSessionOpen(args: JSONObject): JSONObject {
        val opened =
            lanerChat.openSession(
                requestedSessionId = args.optString("session_id").ifBlank { null },
                agentSessionId = args.optString("agent_session_id").ifBlank { null }
            )
        return ok()
            .put("session_id", opened.session.sessionId)
            .put("status", opened.session.status.name)
            .put("last_user_seq", opened.lastUserSeq)
            .put("last_reply_seq", opened.lastReplySeq)
            .put("pending_reply_count", opened.pendingRequests)
            .put("bound_chat_id", opened.session.chatId ?: JSONObject.NULL)
            .put("last_agent_seen_at", isoTime(opened.session.lastAgentSeenAtMs))
    }

    private fun lanerChatSessionClose(args: JSONObject): JSONObject {
        val closed = lanerChat.closeSession(args.optString("session_id").ifBlank { null })
        return ok()
            .put("session_id", closed.sessionId)
            .put("status", closed.status.name)
            .put("closed_at", isoTime(closed.closedAtMs))
    }

    private fun lanerChatNotificationCheck(args: JSONObject): JSONObject {
        val notification =
            lanerChat.notification(
                afterSeq = args.optLong("after_seq", 0L),
                sessionId = args.optString("session_id").ifBlank { null }
            )
        return notificationJson(notification)
    }

    private suspend fun lanerChatNotificationWait(args: JSONObject): JSONObject {
        val timeoutMs =
            if (args.has("timeout_ms")) {
                args.optLong("timeout_ms")
            } else {
                args.optLong("timeout_seconds", 25L).coerceIn(0L, 30L) * 1_000L
            }
        val notification =
            lanerChat.waitForNotification(
                afterSeq = args.optLong("after_seq", 0L),
                timeoutMs = timeoutMs,
                sessionId = args.optString("session_id").ifBlank { null }
            )
        return notificationJson(notification)
    }

    private fun notificationJson(
        notification: com.ai.assistance.operit.integrations.ailimbs.chat.LanerChatNotification
    ): JSONObject =
        ok()
            .put("event", notification.event)
            .put("unread_count", notification.unreadCount)
            .put("pending_reply_count", notification.pendingReplyCount)
            .put("latest_seq", notification.latestSeq)
            .put("highest_priority", notification.highestPriority?.name ?: JSONObject.NULL)
            .put("high_count", notification.highCount)
            .put("normal_count", notification.normalCount)
            .put("low_count", notification.lowCount)
            .put("contains_body", false)

    private fun lanerChatInboxFetch(args: JSONObject): JSONObject {
        val fetched =
            lanerChat.fetchInbox(
                requestedSessionId = args.optString("session_id").ifBlank { null },
                requestId = args.optString("request_id").ifBlank { null },
                afterSeq = args.optLong("after_seq", 0L),
                requestedLimit = args.optInt("limit", 10),
                requestedPriority = parseLanerChatPriority(args.optString("priority"))
            )
        return ok()
            .put("session_id", fetched.sessionId ?: JSONObject.NULL)
            .put("latest_seq", fetched.latestSeq)
            .put("messages", JSONArray(fetched.requests.map(::lanerChatRequestJson)))
            .put("count", fetched.requests.size)
    }

    private fun lanerChatTurnStatus(args: JSONObject): JSONObject {
        val status = lanerChat.turnStatus(args.optString("session_id").ifBlank { null })
        return lanerChatTurnStatusJson(status)
    }

    private fun lanerChatTurnClaim(args: JSONObject): JSONObject {
        val claimed = lanerChat.claimTurn(
            requestedSessionId = args.optString("session_id").ifBlank { null },
            requestedLimit = args.optInt("limit", LanerChatBridgeService.MAX_TURN_REQUESTS)
        ) ?: return ok()
            .put("claimed", false)
            .put("reason", "no_eligible_messages")
            .put("contains_body", false)
        return ok()
            .put("claimed", true)
            .put("duplicate", claimed.duplicate)
            .put("turn", lanerChatTurnJson(claimed.turn))
            .put("messages", JSONArray(claimed.requests.map(::lanerChatRequestJson)))
            .put("count", claimed.requests.size)
            .put(
                "terminal_actions",
                JSONArray(listOf("ai_limbs.chat.turn.reply", "ai_limbs.chat.turn.resolve"))
            )
            .put("contains_body", true)
    }

    private suspend fun lanerChatTurnReply(args: JSONObject): JSONObject {
        val replied = lanerChat.completeTurn(
            turnId = args.optString("turn_id"),
            replyId = args.optString("reply_id").ifBlank { null },
            content = args.optString("content")
        )
        val chatId = replied.requests.firstOrNull()?.chatId
            ?: throw IllegalStateException("Laner chat turn has no bound requests")
        val core = ChatRuntimeHolder.getInstance(appContext).getCore(ChatRuntimeSlot.MAIN)
        val chatHistory = core.getChatHistoryDelegate()
        chatHistory.addMessageToChat(
            message = ChatMessage(
                sender = "ai",
                content = replied.turn.replyContent.orEmpty(),
                timestamp = replied.turn.chatMessageTimestamp,
                roleName = LanerChatContract.DEFAULT_AGENT_NAME,
                provider = LanerChatContract.PROVIDER_MODEL,
                modelName = LanerChatContract.MODEL_ID,
                completedAt = replied.turn.completedAtMs ?: System.currentTimeMillis()
            ),
            chatIdOverride = chatId
        )
        return ok()
            .put("turn_id", replied.turn.turnId)
            .put("reply_id", replied.turn.replyId ?: JSONObject.NULL)
            .put("status", replied.turn.status.name)
            .put("covered_request_ids", JSONArray(replied.turn.requestIds))
            .put("covered_request_count", replied.turn.requestIds.size)
            .put("duplicate", replied.duplicate)
            .put("delivered_to_chat", true)
            .put("completed_at", isoTime(replied.turn.completedAtMs))
    }

    private fun lanerChatTurnResolve(args: JSONObject): JSONObject {
        val resolved = lanerChat.resolveTurnWithoutReply(args.optString("turn_id"))
        return ok()
            .put("turn_id", resolved.turn.turnId)
            .put("status", resolved.turn.status.name)
            .put("covered_request_ids", JSONArray(resolved.turn.requestIds))
            .put("covered_request_count", resolved.turn.requestIds.size)
            .put("duplicate", resolved.duplicate)
            .put("delivered_to_chat", false)
            .put("completed_at", isoTime(resolved.turn.completedAtMs))
    }

    private fun lanerChatTurnCancel(args: JSONObject): JSONObject {
        val result = lanerChat.cancelActiveTurn(args.optString("session_id").ifBlank { null })
        return ok()
            .put("turn_id", result.turn?.turnId ?: JSONObject.NULL)
            .put("turn_status", result.turn?.status?.name ?: JSONObject.NULL)
            .put("scheduler_paused", result.schedulerPaused)
            .put("changed", result.changed)
            .put("requests_preserved", true)
    }

    private fun lanerChatTurnResume(args: JSONObject): JSONObject =
        lanerChatTurnStatusJson(
            lanerChat.resumeScheduler(args.optString("session_id").ifBlank { null })
        )

    private fun lanerChatTurnStatusJson(
        status: com.ai.assistance.operit.integrations.ailimbs.chat.LanerChatTurnStatusSnapshot
    ): JSONObject =
        ok()
            .put("session_id", status.sessionId ?: JSONObject.NULL)
            .put("active_turn", status.activeTurn?.let(::lanerChatTurnJson) ?: JSONObject.NULL)
            .put("active_turn_id", status.activeTurn?.turnId ?: JSONObject.NULL)
            .put("scheduler_paused", status.schedulerPaused)
            .put("eligible_request_count", status.eligibleRequestCount)
            .put("latest_seq", status.latestSeq)
            .put("contains_body", false)

    private fun lanerChatTurnJson(
        turn: com.ai.assistance.operit.integrations.ailimbs.chat.LanerChatAssistantTurn
    ): JSONObject =
        JSONObject()
            .put("turn_id", turn.turnId)
            .put("session_id", turn.sessionId)
            .put("request_ids", JSONArray(turn.requestIds))
            .put("first_seq", turn.firstSeq)
            .put("last_seq", turn.lastSeq)
            .put("highest_priority", turn.highestPriority.name)
            .put("status", turn.status.name)
            .put("claimed_at", isoTime(turn.claimedAtMs))
            .put("completed_at", isoTime(turn.completedAtMs))
            .put("canceled_at", isoTime(turn.canceledAtMs))
            .put("reply_id", turn.replyId ?: JSONObject.NULL)

    private suspend fun lanerChatAttachmentFetch(args: JSONObject): JSONObject {
        val requestId = args.optString("request_id").trim()
        val attachmentId = args.optString("attachment_id").trim()
        val attachment = lanerChat.attachment(requestId, attachmentId)
        val isImage = attachment.mimeType.startsWith("image/", ignoreCase = true)
        val readResult = execute(
            AiLimbsCoreCapabilityRegistry.invokeNameForLocalOperation(
                AiLimbsCoreLocalOperation.HOST_TOOL_EXECUTE
            ),
            JSONObject()
                .put("name", "read_file_full")
                .put("parameters", JSONObject()
                    .put("path", attachment.filePath)
                    .put("direct_image", isImage)
                )
        )
        val response = if (readResult.optBoolean("success", false)) ok() else error("Unable to read Laner chat attachment")
        return response
            .put("request_id", requestId)
            .put("attachment_id", attachmentId)
            .put("file_path", attachment.filePath)
            .put("filename", attachment.fileName)
            .put("mime_type", attachment.mimeType)
            .put("size", attachment.fileSize)
            .put("content_mode", if (isImage) "multimodal_image" else "text_or_document")
            .put("payload", readResult.opt("result") ?: JSONObject.NULL)
            .put("read_error", readResult.opt("error") ?: JSONObject.NULL)
            .put("events", readResult.optJSONArray("events") ?: JSONArray())
    }

    private fun lanerChatReply(args: JSONObject): JSONObject {
        val replied =
            try {
                lanerChat.reply(
                    requestId = args.optString("request_id"),
                    replyId = args.optString("reply_id").ifBlank { null },
                    content = args.optString("content")
                )
            } catch (failure: LanerChatManagedTurnReplyRequiredException) {
                return error(failure.message ?: "Managed turn reply is required")
                    .put("error_code", "TURN_REPLY_REQUIRED")
                    .put("reply_via", "ai_limbs.chat.turn.reply")
                    .put("resolve_via", "ai_limbs.chat.turn.resolve")
            }
        return ok()
            .put("request_id", replied.request.requestId)
            .put("reply_id", replied.request.replyId)
            .put("status", replied.request.status.name)
            .put("duplicate", replied.duplicate)
            .put("delivered_to_live_stream", replied.deliveredToLiveStream)
            .put("answered_at", isoTime(replied.request.answeredAtMs))
    }


    private suspend fun lanerChatSend(args: JSONObject): JSONObject {
        val requestedSessionId = args.optString("session_id").ifBlank { null }
        val opened =
            lanerChat.openSession(
                requestedSessionId = requestedSessionId,
                agentSessionId = null
            )
        val core = ChatRuntimeHolder.getInstance(appContext).getCore(ChatRuntimeSlot.MAIN)
        val chatHistory = core.getChatHistoryDelegate()

        if (opened.session.chatId.isNullOrBlank()) {
            val apiConfig = core.getApiConfigDelegate()
            val currentChatId = chatHistory.currentChatId.value?.takeIf { it.isNotBlank() }
            val isAlreadyBridge = LanerChatContract.isBridgeConfig(apiConfig.activeChatModelConfig.value)
            val bridgeChatId =
                when {
                    isAlreadyBridge && currentChatId != null -> currentChatId
                    currentChatId == null -> {
                        apiConfig.activateLanerBridgeConfiguration()
                        chatHistory.ensureCurrentChat(5_000L)
                    }
                    else -> {
                        apiConfig.activateLanerBridgeConfiguration()
                        chatHistory.createAndSelectNewChat(5_000L)
                    }
                }
            lanerChat.bindUiChat(bridgeChatId)
        }

        val prepared =
            lanerChat.prepareProactiveMessage(
                requestedSessionId = opened.session.sessionId,
                requestedMessageId = args.optString("message_id").ifBlank { null },
                content = args.optString("content")
            )
        val proactive = prepared.message
        chatHistory.addMessageToChat(
            message =
                ChatMessage(
                    sender = "ai",
                    content = proactive.content,
                    timestamp = proactive.chatMessageTimestamp,
                    roleName = LanerChatContract.DEFAULT_AGENT_NAME,
                    provider = LanerChatContract.PROVIDER_MODEL,
                    modelName = LanerChatContract.MODEL_ID,
                    completedAt = System.currentTimeMillis()
                ),
            chatIdOverride = proactive.chatId
        )
        val delivered = lanerChat.markProactiveMessageDelivered(proactive.messageId)
        return ok()
            .put("message_id", delivered.messageId)
            .put("session_id", delivered.sessionId)
            .put("chat_id", delivered.chatId)
            .put("status", delivered.status.name)
            .put("duplicate", prepared.duplicate)
            .put("created_at", isoTime(delivered.createdAtMs))
            .put("delivered_at", isoTime(delivered.deliveredAtMs))
    }

    private fun lanerChatRequestJson(request: LanerChatRequest): JSONObject =
        JSONObject()
            .put("request_id", request.requestId)
            .put("session_id", request.sessionId)
            .put("seq", request.seq)
            .put("chat_id", request.chatId)
            .put("sender", request.sender)
            .put("priority", request.priority.name)
            .put("text", request.text)
            .put("attachment_count", request.attachments.size)
            .put("attachments", JSONArray(request.attachments.map { attachment ->
                JSONObject()
                    .put("attachment_id", attachment.attachmentId)
                    .put("filename", attachment.fileName)
                    .put("mime_type", attachment.mimeType)
                    .put("size", attachment.fileSize)
            }))
            .put("created_at", isoTime(request.createdAtMs))
            .put("status", request.status.name)
            .put("delivery_count", request.deliveryCount)


    private fun parseLanerChatPriority(raw: String): LanerChatPriority? {
        val normalized = raw.trim().uppercase(Locale.US)
        if (normalized.isEmpty()) return null
        return runCatching { LanerChatPriority.valueOf(normalized) }
            .getOrElse { throw IllegalArgumentException("priority must be HIGH, NORMAL, or LOW") }
    }

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
