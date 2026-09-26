package com.ai.limbs.plugins.lanerchat

import org.json.JSONArray
import org.json.JSONObject

internal object LanerChatJson {
    fun session(value: LanerChatSession): JSONObject =
        JSONObject()
            .put("session_id", value.sessionId)
            .put("status", value.status.name)
            .put("opened_at_ms", value.openedAtMs)
            .put("closed_at_ms", value.closedAtMs ?: JSONObject.NULL)
            .put("last_agent_seen_at_ms", value.lastAgentSeenAtMs ?: JSONObject.NULL)
            .put("agent_session_id", value.agentSessionId ?: JSONObject.NULL)
            .put("chat_id", value.chatId ?: JSONObject.NULL)

    fun attachment(value: LanerChatAttachment): JSONObject =
        JSONObject()
            .put("attachment_id", value.attachmentId)
            .put("file_path", value.filePath)
            .put("file_name", value.fileName)
            .put("mime_type", value.mimeType)
            .put("file_size", value.fileSize)

    fun request(value: LanerChatRequest, includeBody: Boolean = true): JSONObject =
        JSONObject()
            .put("request_id", value.requestId)
            .put("session_id", value.sessionId)
            .put("seq", value.seq)
            .put("chat_id", value.chatId)
            .put("sender", value.sender)
            .put("text", if (includeBody) value.text else JSONObject.NULL)
            .put("created_at_ms", value.createdAtMs)
            .put("priority", value.priority.name)
            .put(
                "attachments",
                if (includeBody) JSONArray(value.attachments.map(::attachment)) else JSONArray()
            )
            .put("status", value.status.name)
            .put("delivery_count", value.deliveryCount)
            .put("delivered_at_ms", value.deliveredAtMs ?: JSONObject.NULL)
            .put("answered_at_ms", value.answeredAtMs ?: JSONObject.NULL)
            .put("resolved_at_ms", value.resolvedAtMs ?: JSONObject.NULL)
            .put("canceled_at_ms", value.canceledAtMs ?: JSONObject.NULL)
            .put("reply_id", value.replyId ?: JSONObject.NULL)
            .put("reply_content", if (includeBody) value.replyContent ?: JSONObject.NULL else JSONObject.NULL)
            .put("chat_message_timestamp", value.chatMessageTimestamp)

    fun turn(value: LanerChatAssistantTurn): JSONObject =
        JSONObject()
            .put("turn_id", value.turnId)
            .put("session_id", value.sessionId)
            .put("request_ids", JSONArray(value.requestIds))
            .put("first_seq", value.firstSeq)
            .put("last_seq", value.lastSeq)
            .put("highest_priority", value.highestPriority.name)
            .put("status", value.status.name)
            .put("claimed_at_ms", value.claimedAtMs)
            .put("completed_at_ms", value.completedAtMs ?: JSONObject.NULL)
            .put("canceled_at_ms", value.canceledAtMs ?: JSONObject.NULL)
            .put("reply_id", value.replyId ?: JSONObject.NULL)
            .put("reply_content", value.replyContent ?: JSONObject.NULL)
            .put("chat_message_timestamp", value.chatMessageTimestamp)

    fun mailbox(value: LanerChatMailboxStatus): JSONObject =
        JSONObject()
            .put("active_session_id", value.activeSessionId ?: JSONObject.NULL)
            .put("bound_chat_id", value.boundChatId ?: JSONObject.NULL)
            .put("latest_seq", value.latestSeq)
            .put("pending_count", value.pendingCount)
            .put("delivered_count", value.deliveredCount)
            .put("answered_count", value.answeredCount)
            .put("resolved_no_reply_count", value.resolvedNoReplyCount)
            .put("canceled_count", value.canceledCount)
            .put("proactive_pending_count", value.proactivePendingCount)
            .put("proactive_delivered_count", value.proactiveDeliveredCount)
            .put("last_agent_seen_at_ms", value.lastAgentSeenAtMs ?: JSONObject.NULL)
            .put("active_turn_id", value.activeTurnId ?: JSONObject.NULL)
            .put("active_turn_chat_id", value.activeTurnChatId ?: JSONObject.NULL)
            .put("active_turn_request_count", value.activeTurnRequestCount)
            .put("active_turn_highest_priority", value.activeTurnHighestPriority?.name ?: JSONObject.NULL)
            .put("scheduler_paused", value.schedulerPaused)

    fun notification(value: LanerChatNotification): JSONObject =
        JSONObject()
            .put("event", value.event)
            .put("unread_count", value.unreadCount)
            .put("pending_reply_count", value.pendingReplyCount)
            .put("latest_seq", value.latestSeq)
            .put("highest_priority", value.highestPriority?.name ?: JSONObject.NULL)
            .put("high_count", value.highCount)
            .put("normal_count", value.normalCount)
            .put("low_count", value.lowCount)
            .put("contains_body", false)

    fun queueEvent(value: LanerChatQueueChangedEvent): JSONObject =
        JSONObject()
            .put("event_id", value.eventId)
            .put("reason", value.reason)
            .put("session_id", value.sessionId ?: JSONObject.NULL)
            .put("latest_seq", value.latestSeq)
            .put("pending_count", value.pendingCount)
            .put("unresolved_count", value.unresolvedCount)
            .put("highest_priority", value.highestPriority?.name ?: JSONObject.NULL)
            .put("high_count", value.highCount)
            .put("normal_count", value.normalCount)
            .put("low_count", value.lowCount)
            .put("active_turn_id", value.activeTurnId ?: JSONObject.NULL)
            .put("scheduler_paused", value.schedulerPaused)
            .put("attention_required", value.attentionRequired)
            .put("contains_body", false)

    fun sessionOpen(value: LanerChatSessionOpenResult): JSONObject =
        JSONObject()
            .put("session", session(value.session))
            .put("last_user_seq", value.lastUserSeq)
            .put("last_reply_seq", value.lastReplySeq)
            .put("pending_requests", value.pendingRequests)

    fun fetch(value: LanerChatFetchResult): JSONObject =
        JSONObject()
            .put("session_id", value.sessionId ?: JSONObject.NULL)
            .put("latest_seq", value.latestSeq)
            .put("messages", JSONArray(value.requests.map { request(it, includeBody = true) }))
            .put("count", value.requests.size)
            .put("contains_body", true)

    fun turnStatus(value: LanerChatTurnStatusSnapshot): JSONObject =
        JSONObject()
            .put("session_id", value.sessionId ?: JSONObject.NULL)
            .put("active_turn", value.activeTurn?.let(::turn) ?: JSONObject.NULL)
            .put("active_turn_id", value.activeTurn?.turnId ?: JSONObject.NULL)
            .put("scheduler_paused", value.schedulerPaused)
            .put("eligible_request_count", value.eligibleRequestCount)
            .put("latest_seq", value.latestSeq)
            .put("contains_body", false)

    fun claim(value: LanerChatTurnClaimResult?): JSONObject {
        if (value == null) {
            return JSONObject()
                .put("claimed", false)
                .put("reason", "no_eligible_messages")
                .put("contains_body", false)
        }
        return JSONObject()
            .put("claimed", true)
            .put("duplicate", value.duplicate)
            .put("turn", turn(value.turn))
            .put("messages", JSONArray(value.requests.map { request(it, includeBody = true) }))
            .put("count", value.requests.size)
            .put("contains_body", true)
    }

    fun reply(value: LanerChatReplyResult): JSONObject =
        JSONObject()
            .put("request", request(value.request, includeBody = true))
            .put("duplicate", value.duplicate)
            .put("delivered_to_live_stream", value.deliveredToLiveStream)

    fun turnReply(value: LanerChatTurnReplyResult): JSONObject =
        JSONObject()
            .put("turn", turn(value.turn))
            .put("covered_requests", JSONArray(value.requests.map { request(it, includeBody = false) }))
            .put("covered_request_count", value.requests.size)
            .put("duplicate", value.duplicate)

    fun turnResolve(value: LanerChatTurnResolveResult): JSONObject =
        JSONObject()
            .put("turn", turn(value.turn))
            .put("covered_requests", JSONArray(value.requests.map { request(it, includeBody = false) }))
            .put("covered_request_count", value.requests.size)
            .put("duplicate", value.duplicate)

    fun turnCancel(value: LanerChatTurnCancelResult): JSONObject =
        JSONObject()
            .put("turn", value.turn?.let(::turn) ?: JSONObject.NULL)
            .put("scheduler_paused", value.schedulerPaused)
            .put("changed", value.changed)
            .put("requests_preserved", true)

    fun proactive(value: LanerChatProactiveSendResult): JSONObject =
        JSONObject()
            .put("message", proactiveMessage(value.message))
            .put("duplicate", value.duplicate)

    fun proactiveMessage(value: LanerChatProactiveMessage): JSONObject =
        JSONObject()
            .put("message_id", value.messageId)
            .put("session_id", value.sessionId)
            .put("chat_id", value.chatId)
            .put("content", value.content)
            .put("created_at_ms", value.createdAtMs)
            .put("chat_message_timestamp", value.chatMessageTimestamp)
            .put("status", value.status.name)
            .put("delivered_at_ms", value.deliveredAtMs ?: JSONObject.NULL)
}
