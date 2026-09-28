package com.ai.limbs.plugins.lanerchat

import com.ai.limbs.plugin.runtime.InProcessPluginHost
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

internal class LanerChatController(
    private val service: LanerChatBridgeService,
    private val host: InProcessPluginHost
) {
    fun status(): JSONObject {
        val mailbox = service.snapshot()
        val presence = LanerChatContract.presenceState(
            activeSessionId = mailbox.activeSessionId,
            lastAgentSeenAtMs = mailbox.lastAgentSeenAtMs,
            hasActiveTurn = mailbox.activeTurnId != null
        )
        return ok()
            .put("module", "AI Limbs Laner Chat Plugin")
            .put("protocol_version", 1)
            .put("shadow_mode", false)
            .put("active_session_id", mailbox.activeSessionId ?: JSONObject.NULL)
            .put("bound_chat_id", mailbox.boundChatId ?: JSONObject.NULL)
            .put("agent_session_presence", presence.wireValue)
            .put("mailbox", LanerChatJson.mailbox(mailbox))
            .put("queue", LanerChatJson.queueEvent(service.queueSnapshotEvent("status")))
            .put("supports_priority", true)
            .put("supports_turn_scheduler", true)
            .put("supports_proactive_prepare", true)
            .put("delivers_to_host_chat", true)
    }

    fun sessionOpen(args: JSONObject): JSONObject =
        ok().put(
            "result",
            LanerChatJson.sessionOpen(
                service.openSession(
                    requestedSessionId = optionalString(args, "session_id"),
                    agentSessionId = optionalString(args, "agent_session_id")
                )
            )
        )

    fun sessionClose(args: JSONObject): JSONObject =
        ok().put(
            "session",
            LanerChatJson.session(service.closeSession(optionalString(args, "session_id")))
        )

    fun bindUiChat(args: JSONObject): JSONObject {
        val chatId = requiredString(args, "chat_id")
        val session = service.bindUiChat(chatId)
        val presentations =
            JSONArray(
                service.presentationSnapshot(chatId).map { request ->
                    JSONObject()
                        .put("message_timestamp", request.chatMessageTimestamp)
                        .put("priority", request.priority.name)
                }
            )
        return ok()
            .put("session", LanerChatJson.session(session))
            .put("message_presentations", presentations)
    }

    fun notificationCheck(args: JSONObject): JSONObject =
        ok().put(
            "notification",
            LanerChatJson.notification(
                service.notification(
                    afterSeq = args.optLong("after_seq", 0L),
                    sessionId = optionalString(args, "session_id")
                )
            )
        )

    suspend fun notificationWait(args: JSONObject): JSONObject {
        val timeoutMs =
            if (args.has("timeout_ms")) {
                args.optLong("timeout_ms", 25_000L)
            } else {
                args.optLong("timeout_seconds", 25L).coerceIn(0L, 30L) * 1_000L
            }.coerceIn(0L, LanerChatBridgeService.MAX_WAIT_MS)
        return ok().put(
            "notification",
            LanerChatJson.notification(
                service.waitForNotification(
                    afterSeq = args.optLong("after_seq", 0L),
                    timeoutMs = timeoutMs,
                    sessionId = optionalString(args, "session_id")
                )
            )
        )
    }

    fun inboxFetch(args: JSONObject): JSONObject =
        ok().put(
            "result",
            LanerChatJson.fetch(
                service.fetchInbox(
                    requestedSessionId = optionalString(args, "session_id"),
                    requestId = optionalString(args, "request_id"),
                    afterSeq = args.optLong("after_seq", 0L),
                    requestedLimit = args.optInt("limit", 10),
                    requestedPriority = parsePriority(args.optString("priority"))
                )
            )
        )

    fun attachmentFetch(args: JSONObject): JSONObject =
        ok().put(
            "attachment",
            LanerChatJson.attachment(
                service.attachment(
                    requestId = requiredString(args, "request_id"),
                    attachmentId = requiredString(args, "attachment_id")
                )
            )
        )

    fun turnStatus(args: JSONObject): JSONObject =
        ok().put(
            "result",
            LanerChatJson.turnStatus(service.turnStatus(optionalString(args, "session_id")))
        )

    fun turnClaim(args: JSONObject): JSONObject =
        ok().put(
            "result",
            LanerChatJson.claim(
                service.claimTurn(
                    requestedSessionId = optionalString(args, "session_id"),
                    requestedLimit = args.optInt("limit", LanerChatBridgeService.MAX_TURN_REQUESTS)
                )
            )
        )

    suspend fun turnReply(args: JSONObject): JSONObject {
        val result = service.completeTurn(
            turnId = requiredString(args, "turn_id"),
            replyId = optionalString(args, "reply_id"),
            content = requiredString(args, "content")
        )
        val turn = result.turn
        val chatId = requireNotNull(result.requests.firstOrNull()?.chatId) {
            "Completed turn has no bound chat"
        }
        publishAssistant(chatId, requireNotNull(turn.replyContent),
            turn.chatMessageTimestamp, requireNotNull(turn.completedAtMs))
        return ok().put("result", LanerChatJson.turnReply(result))
            .put("turn_id", turn.turnId).put("reply_id", turn.replyId ?: JSONObject.NULL)
            .put("status", turn.status.name).put("covered_request_ids", JSONArray(turn.requestIds))
            .put("covered_request_count", result.requests.size).put("duplicate", result.duplicate)
            .put("delivered_to_chat", true).put("delivery_pending", false)
            .put("completed_at", isoTime(requireNotNull(turn.completedAtMs)))
    }

    fun turnResolve(args: JSONObject): JSONObject =
        ok().put(
            "result",
            LanerChatJson.turnResolve(
                service.resolveTurnWithoutReply(requiredString(args, "turn_id"))
            )
        )

    fun turnCancel(args: JSONObject): JSONObject =
        ok().put(
            "result",
            LanerChatJson.turnCancel(service.cancelActiveTurn(optionalString(args, "session_id")))
        )

    fun turnResume(args: JSONObject): JSONObject =
        ok().put(
            "result",
            LanerChatJson.turnStatus(service.resumeScheduler(optionalString(args, "session_id")))
        )

    suspend fun legacyReply(args: JSONObject): JSONObject {
        val result = service.reply(
            requestId = requiredString(args, "request_id"),
            replyId = optionalString(args, "reply_id"),
            content = requiredString(args, "content")
        )
        val request = result.request
        publishAssistant(request.chatId, requireNotNull(request.replyContent),
            request.chatMessageTimestamp.takeIf { it > 0L } ?: requireNotNull(request.answeredAtMs),
            requireNotNull(request.answeredAtMs))
        return ok().put("result", LanerChatJson.reply(result))
            .put("request_id", request.requestId).put("reply_id", request.replyId ?: JSONObject.NULL)
            .put("status", request.status.name).put("duplicate", result.duplicate)
            .put("delivered_to_live_stream", result.deliveredToLiveStream)
            .put("delivered_to_chat", true)
            .put("answered_at", isoTime(requireNotNull(request.answeredAtMs)))
    }

    private suspend fun publishAssistant(chatId: String, content: String, timestamp: Long, completedAt: Long) {
        val payload = JSONObject().put("operation", "publish_assistant")
            .put("chat_id", chatId).put("content", content)
            .put("message_timestamp", timestamp).put("completed_at_ms", completedAt)
            .put("provider", LanerChatContract.PROVIDER_TYPE_ID)
            .put("model_name", LanerChatContract.MODEL_ID)
            .put("role_name", LanerChatContract.DEFAULT_AGENT_NAME)
        val published = JSONObject(host.invokeHostCapability("host.chat@1", payload.toString()))
        check(published.optBoolean("success") && published.optBoolean("persisted")) {
            "Host chat did not persist the assistant message: $published"
        }
    }

    private fun isoTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))

    fun enqueue(args: JSONObject): JSONObject {
        val request = service.enqueueMailbox(
            chatId = requiredString(args, "chat_id"),
            text = args.optString("text"),
            sender = optionalString(args, "sender") ?: LanerChatContract.DEFAULT_SENDER,
            attachments = parseAttachments(args.optJSONArray("attachments")),
            priority = parsePriority(args.optString("priority")) ?: LanerChatPriority.NORMAL
        )
        return ok().put("request", LanerChatJson.request(request, includeBody = true))
    }

    fun cancelRequest(args: JSONObject): JSONObject =
        ok()
            .put(
                "canceled",
                service.cancelRequest(
                    requestId = requiredString(args, "request_id"),
                    reason = optionalString(args, "reason") ?: "Canceled by Laner Chat plugin caller"
                )
            )

    fun proactivePrepare(args: JSONObject): JSONObject {
        val opened = service.openSession(
            requestedSessionId = optionalString(args, "session_id"),
            agentSessionId = optionalString(args, "agent_session_id")
        )
        val prepared = service.prepareProactiveMessage(
            requestedSessionId = opened.session.sessionId,
            requestedMessageId = optionalString(args, "message_id"),
            content = requiredString(args, "content")
        )
        return ok()
            .put("result", LanerChatJson.proactive(prepared))
            .put("delivered_to_host_chat", false)
            .put("delivery_pending", true)
    }

    fun proactiveDelivered(args: JSONObject): JSONObject =
        ok().put(
            "message",
            LanerChatJson.proactiveMessage(
                service.markProactiveMessageDelivered(requiredString(args, "message_id"))
            )
        )

    private fun parseAttachments(array: JSONArray?): List<LanerChatAttachmentInput> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            LanerChatAttachmentInput(
                filePath = requiredString(item, "file_path"),
                fileName = requiredString(item, "file_name"),
                mimeType = item.optString("mime_type", "application/octet-stream"),
                fileSize = item.optLong("file_size", 0L)
            )
        }
    }

    private fun parsePriority(raw: String?): LanerChatPriority? {
        val normalized = raw?.trim()?.uppercase().orEmpty()
        if (normalized.isEmpty()) return null
        return runCatching { LanerChatPriority.valueOf(normalized) }
            .getOrElse { throw IllegalArgumentException("priority must be HIGH, NORMAL, or LOW") }
    }

    private fun optionalString(args: JSONObject, key: String): String? =
        args.optString(key).trim().takeIf { it.isNotEmpty() }

    private fun requiredString(args: JSONObject, key: String): String =
        optionalString(args, key) ?: throw IllegalArgumentException("$key is required")

    private fun ok(): JSONObject = JSONObject().put("success", true)
}
