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
    private val accessGate: AiLimbsAccessGate? = null
) {
    private val appContext = context.applicationContext
    private val handler = AIToolHandler.getInstance(appContext)
    private val documents = AiLimbsDocumentProvider(appContext)
    private val accessContext = AiLimbsAccessContextService(appContext)
    private val uiCapabilities = AiLimbsUiCapabilityService(appContext)
    private val capabilityResolver = AiLimbsCapabilityResolver(appContext)
    private val lanerChat = LanerChatBridgeService.getInstance(appContext)
    private val gson = Gson()

    suspend fun execute(tool: String, args: JSONObject): JSONObject {
        accessGate?.rejectionBefore(tool)?.let { return it }
        val result = executeUngated(tool, args)
        accessGate?.recordSuccessfulRead(tool, result)
        return result
    }

    private suspend fun executeUngated(tool: String, args: JSONObject): JSONObject = when (tool) {
        "ai_limbs.access_context.read" ->
            ok()
                .put("document", "access_bootstrap")
                .put("content", accessContext.readAccessContext())
        "ai_limbs.system_access_prompt.read" -> {
            val reference = documents.documentReference(AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT)
            ok()
                .put("document", reference.documentId)
                .put("version", reference.version)
                .put("path", reference.path)
                .put("content", documents.readSystemAccessPrompt())
        }
        "ai_limbs.system_access_prompt.write" -> {
            val changed = documents.writeSystemAccessPrompt(args.optString("content"))
            val reference = documents.documentReference(AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT)
            ok()
                .put("document", reference.documentId)
                .put("version", reference.version)
                .put("path", reference.path)
                .put("changed", changed)
        }
        "ai_limbs.custom_access_prompt.read",
        "ai_limbs.access_prompt.read",
        "laner.access_prompt.read" -> {
            val reference = documents.documentReference(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT)
            ok()
                .put("document", reference.documentId)
                .put("version", reference.version)
                .put("path", reference.path)
                .put("empty", reference.isEmpty)
                .put("content", documents.readCustomAccessPrompt())
        }
        "ai_limbs.custom_access_prompt.write",
        "ai_limbs.access_prompt.write",
        "laner.access_prompt.write" -> {
            val changed = documents.writeCustomAccessPrompt(args.optString("content"))
            val reference = documents.documentReference(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT)
            ok()
                .put("document", reference.documentId)
                .put("version", reference.version)
                .put("path", reference.path)
                .put("empty", reference.isEmpty)
                .put("changed", changed)
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
        "ai_limbs.bridge.reconnect" ->
            executeHostTool(
                JSONObject()
                    .put("name", tool)
                    .put("parameters", args)
            )
        "ai_limbs.chat.status" -> lanerChatStatus()
        "ai_limbs.chat.session.open" -> lanerChatSessionOpen(args)
        "ai_limbs.chat.session.close" -> lanerChatSessionClose(args)
        "ai_limbs.chat.notification.check" -> lanerChatNotificationCheck(args)
        "ai_limbs.chat.notification.wait" -> lanerChatNotificationWait(args)
        "ai_limbs.chat.inbox.fetch" -> lanerChatInboxFetch(args)
        "ai_limbs.chat.attachment.fetch" -> lanerChatAttachmentFetch(args)
        "ai_limbs.chat.turn.status" -> lanerChatTurnStatus(args)
        "ai_limbs.chat.turn.claim" -> lanerChatTurnClaim(args)
        "ai_limbs.chat.turn.reply" -> lanerChatTurnReply(args)
        "ai_limbs.chat.turn.cancel" -> lanerChatTurnCancel(args)
        "ai_limbs.chat.turn.resume" -> lanerChatTurnResume(args)
        "ai_limbs.chat.reply" -> lanerChatReply(args)
        "ai_limbs.chat.send" -> lanerChatSend(args)
        "ai_limbs.ui.status" -> uiCapabilityStatus()
        "ai_limbs.host_tools.list", "operit.tools.list" -> {
            handler.registerDefaultTools()
            val names = JSONArray()
            handler.getAllToolNames().forEach { names.put(it) }
            ok().put("tools", names).put("count", names.length())
        }
        "ubuntu.status", "ubuntu.start", "ubuntu.stop", "ubuntu.idle.get", "ubuntu.idle.set" ->
            executeHostTool(
                JSONObject()
                    .put("name", tool)
                    .put("parameters", args)
            )
        "ai_limbs.host_tool.execute", "operit.tool.execute" -> executeHostTool(args)
        else -> error("Unknown AI Limbs tool: $tool")
    }

    private suspend fun executeHostTool(args: JSONObject): JSONObject {
        val name = args.optString("name").trim()
        if (name.isBlank()) return error("Missing host tool name")
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
                        "AI Limbs Ubuntu Runtime",
                        "AI Limbs Laner Chat Bridge"
                    )
                )
            )

    private fun dispatcherStatus(): JSONObject =
        ok()
            .put("module", "AI Limbs Tool Dispatcher")
            .put("route", "AiLimbsDispatcher -> ToolExecutionManager -> AIToolHandler")
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
            .put("protocol_version", 5)
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
            .put("pending_reply_count", mailbox.unresolvedCount)
            .put("answered_count", mailbox.answeredCount)
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
        val readResult = executeHostTool(
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
            lanerChat.reply(
                requestId = args.optString("request_id"),
                replyId = args.optString("reply_id").ifBlank { null },
                content = args.optString("content")
            )
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
