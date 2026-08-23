package com.ai.assistance.operit.integrations.ailimbs.chat

import android.content.Context
import android.os.SystemClock
import com.ai.assistance.operit.data.model.AttachmentInfo
import com.ai.assistance.operit.data.model.ChatMessageTimestampAllocator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import com.ai.assistance.operit.util.stream.asStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable application-internal mailbox used by the Laner chat processing plugin and RDC tools. */
private data class LanerChatLiveReply(val channel: Channel<String>)

class LanerChatBridgeService private constructor(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    private val liveReplies = ConcurrentHashMap<String, LanerChatLiveReply>()
    private var storedState: LanerChatStoredState = loadState()
    private val latestSeqFlow = MutableStateFlow(storedState.lastSeq)
    private val mailboxStatusFlow = MutableStateFlow(buildMailboxStatus(storedState))

    val status: StateFlow<LanerChatMailboxStatus> = mailboxStatusFlow.asStateFlow()

    @Synchronized
    fun openSession(
        requestedSessionId: String?,
        agentSessionId: String?
    ): LanerChatSessionOpenResult {
        val now = System.currentTimeMillis()
        val normalizedRequested = requestedSessionId?.trim().orEmpty()
        val sessions = storedState.sessions.toMutableList()
        val selectedIndex =
            when {
                normalizedRequested.isNotEmpty() -> {
                    val index = sessions.indexOfFirst { it.sessionId == normalizedRequested }
                    require(index >= 0) { "Laner chat session not found: $normalizedRequested" }
                    index
                }
                !storedState.activeSessionId.isNullOrBlank() ->
                    sessions.indexOfFirst { it.sessionId == storedState.activeSessionId }
                else ->
                    sessions.indexOfLast { session ->
                        storedState.requests.any { request ->
                            request.sessionId == session.sessionId && request.isUnresolved()
                        }
                    }
            }
        val session =
            if (selectedIndex >= 0) {
                val existing = sessions[selectedIndex]
                sessions[selectedIndex].copy(
                    status = LanerChatSessionStatus.OPEN,
                    closedAtMs = null,
                    lastAgentSeenAtMs = now,
                    agentSessionId =
                        agentSessionId?.trim()?.takeIf { it.isNotEmpty() }
                            ?: existing.agentSessionId
                ).also { sessions[selectedIndex] = it }
            } else {
                LanerChatSession(
                    sessionId = UUID.randomUUID().toString(),
                    openedAtMs = now,
                    lastAgentSeenAtMs = now,
                    agentSessionId = agentSessionId?.trim()?.takeIf { it.isNotEmpty() }
                ).also(sessions::add)
            }
        commitState(
            storedState.copy(
                activeSessionId = session.sessionId,
                sessions = sessions
            )
        )
        val requests = storedState.requests.filter { it.sessionId == session.sessionId }
        return LanerChatSessionOpenResult(
            session = session,
            lastUserSeq = requests.maxOfOrNull { it.seq } ?: 0L,
            lastReplySeq = requests.filter { it.status == LanerChatMessageStatus.ANSWERED }
                .maxOfOrNull { it.seq } ?: 0L,
            pendingRequests = requests.count { it.isUnresolved() }
        )
    }

    @Synchronized
    fun closeSession(requestedSessionId: String?): LanerChatSession {
        val sessionId =
            requestedSessionId?.trim()?.takeIf { it.isNotEmpty() }
                ?: storedState.activeSessionId
                ?: throw IllegalStateException("No active Laner chat session")
        val sessions = storedState.sessions.toMutableList()
        val index = sessions.indexOfFirst { it.sessionId == sessionId }
        require(index >= 0) { "Laner chat session not found: $sessionId" }
        if (sessions[index].status == LanerChatSessionStatus.CLOSED) {
            return sessions[index]
        }
        val closed = sessions[index].copy(
            status = LanerChatSessionStatus.CLOSED,
            closedAtMs = System.currentTimeMillis()
        )
        sessions[index] = closed
        commitState(
            storedState.copy(
                activeSessionId = storedState.activeSessionId.takeUnless { it == sessionId },
                sessions = sessions
            )
        )
        return closed
    }

    @Synchronized
    fun enqueue(
        chatId: String,
        text: String,
        sender: String = LanerChatContract.DEFAULT_SENDER,
        attachments: List<AttachmentInfo> = emptyList(),
        priority: LanerChatPriority = LanerChatPriority.NORMAL
    ): LanerChatPendingExchange {
        require(text.isNotBlank() || attachments.isNotEmpty()) { "Laner chat message is empty" }
        val now = System.currentTimeMillis()
        val session = ensureUiSessionLocked(now, chatId)
        val nextSeq = storedState.lastSeq + 1L
        val request =
            LanerChatRequest(
                requestId = UUID.randomUUID().toString(),
                sessionId = session.sessionId,
                seq = nextSeq,
                chatId = chatId,
                sender = sender,
                text = text,
                createdAtMs = now,
                priority = priority,
                attachments = attachments.map { attachment ->
                    LanerChatAttachment(
                        attachmentId = UUID.randomUUID().toString(),
                        filePath = attachment.filePath,
                        fileName = attachment.fileName,
                        mimeType = attachment.mimeType,
                        fileSize = attachment.fileSize
                    )
                }
            )
        val channel = Channel<String>(Channel.UNLIMITED)
        liveReplies[request.requestId] = LanerChatLiveReply(channel)
        try {
            commitState(
                storedState.copy(
                    lastSeq = nextSeq,
                    activeSessionId = session.sessionId,
                    requests = storedState.requests + request
                )
            )
        } catch (error: Throwable) {
            liveReplies.remove(request.requestId)?.channel?.cancel(CancellationException("Laner chat enqueue failed"))
            throw error
        }
        return LanerChatPendingExchange(request, channel.receiveAsFlow().asStream())
    }

    /** Bind the visible Bridge conversation to its durable Laner session. */
    @Synchronized
    fun bindUiChat(chatId: String): LanerChatSession {
        val normalizedChatId = chatId.trim()
        require(normalizedChatId.isNotEmpty()) { "chat_id is required" }
        return ensureUiSessionLocked(System.currentTimeMillis(), normalizedChatId)
    }

    /**
     * Persist an AI-originated message before chat-history delivery.
     *
     * A stable message ID makes retries idempotent. The persisted chat timestamp is reused so a
     * retry after an interrupted delivery updates the same chat row instead of duplicating it.
     */
    @Synchronized
    fun prepareProactiveMessage(
        requestedSessionId: String?,
        requestedMessageId: String?,
        content: String
    ): LanerChatProactiveSendResult {
        val normalizedContent = content.trim()
        require(normalizedContent.isNotEmpty()) { "message content is required" }
        val sessionId =
            requestedSessionId?.trim()?.takeIf { it.isNotEmpty() }
                ?: storedState.activeSessionId
                ?: throw IllegalStateException(
                    "No active Laner chat session; call ai_limbs.chat.session.open first"
                )
        val session = storedState.sessions.firstOrNull { it.sessionId == sessionId }
            ?: throw IllegalArgumentException("Laner chat session not found: $sessionId")
        val normalizedMessageId =
            requestedMessageId?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val existing = storedState.proactiveMessages.firstOrNull {
            it.messageId == normalizedMessageId
        }
        if (existing != null) {
            check(
                existing.sessionId == sessionId &&
                    existing.content == normalizedContent
            ) {
                "Laner proactive message ID already exists with different message data"
            }
            touchAgentLocked(sessionId)
            return LanerChatProactiveSendResult(existing, duplicate = true)
        }
        check(session.status == LanerChatSessionStatus.OPEN) {
            "Laner chat session is closed: $sessionId"
        }
        val chatId = checkNotNull(session.chatId?.takeIf { it.isNotBlank() }) {
            "No AI Limbs chat is bound to session $sessionId; open Laner Bridge Chat first"
        }

        val now = System.currentTimeMillis()
        val created =
            LanerChatProactiveMessage(
                messageId = normalizedMessageId,
                sessionId = sessionId,
                chatId = chatId,
                content = normalizedContent,
                createdAtMs = now,
                chatMessageTimestamp = ChatMessageTimestampAllocator.next(now)
            )
        touchAgentLocked(sessionId)
        commitState(
            storedState.copy(proactiveMessages = storedState.proactiveMessages + created)
        )
        return LanerChatProactiveSendResult(created, duplicate = false)
    }

    @Synchronized
    fun markProactiveMessageDelivered(messageId: String): LanerChatProactiveMessage {
        val normalizedMessageId = messageId.trim()
        require(normalizedMessageId.isNotEmpty()) { "message_id is required" }
        val index = storedState.proactiveMessages.indexOfFirst {
            it.messageId == normalizedMessageId
        }
        require(index >= 0) { "Laner proactive message not found: $normalizedMessageId" }
        val existing = storedState.proactiveMessages[index]
        if (existing.status == LanerChatProactiveMessageStatus.DELIVERED) return existing
        val delivered =
            existing.copy(
                status = LanerChatProactiveMessageStatus.DELIVERED,
                deliveredAtMs = System.currentTimeMillis()
            )
        val messages = storedState.proactiveMessages.toMutableList().also {
            it[index] = delivered
        }
        commitState(storedState.copy(proactiveMessages = messages))
        return delivered
    }

    @Synchronized
    fun notification(afterSeq: Long, sessionId: String? = null): LanerChatNotification {
        touchAgentLocked(sessionId)
        val matching = unresolvedRequests(sessionId).filter { it.seq > afterSeq.coerceAtLeast(0L) }
        val highCount = matching.count { it.priority == LanerChatPriority.HIGH }
        val normalCount = matching.count { it.priority == LanerChatPriority.NORMAL }
        val lowCount = matching.count { it.priority == LanerChatPriority.LOW }
        val highestPriority =
            when {
                highCount > 0 -> LanerChatPriority.HIGH
                normalCount > 0 -> LanerChatPriority.NORMAL
                lowCount > 0 -> LanerChatPriority.LOW
                else -> null
            }
        return LanerChatNotification(
            event = if (matching.isEmpty()) EVENT_IDLE else EVENT_NEW_MESSAGE,
            unreadCount = matching.count { it.status == LanerChatMessageStatus.PENDING },
            pendingReplyCount = matching.size,
            latestSeq = storedState.lastSeq,
            highestPriority = highestPriority,
            highCount = highCount,
            normalCount = normalCount,
            lowCount = lowCount
        )
    }

    suspend fun waitForNotification(
        afterSeq: Long,
        timeoutMs: Long,
        sessionId: String? = null
    ): LanerChatNotification {
        val boundedTimeout = timeoutMs.coerceIn(0L, MAX_WAIT_MS)
        val deadline = SystemClock.elapsedRealtime() + boundedTimeout
        while (true) {
            val observedSeq = latestSeqFlow.value
            notification(afterSeq, sessionId).let { current ->
                if (current.event == EVENT_NEW_MESSAGE || boundedTimeout == 0L) return current
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return notification(afterSeq, sessionId)
            if (latestSeqFlow.value > observedSeq) continue
            val changed = withTimeoutOrNull(remaining) {
                latestSeqFlow.first { it > observedSeq }
            }
            if (changed == null) return notification(afterSeq, sessionId)
        }
    }

    @Synchronized
    fun fetchInbox(
        requestedSessionId: String?,
        requestId: String?,
        afterSeq: Long,
        requestedLimit: Int,
        requestedPriority: LanerChatPriority? = null
    ): LanerChatFetchResult {
        val explicitSessionId = requestedSessionId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedRequestId = requestId?.trim().orEmpty()
        val sessionFilter =
            explicitSessionId
                ?: storedState.activeSessionId.takeIf { normalizedRequestId.isEmpty() }
        if (normalizedRequestId.isEmpty()) {
            checkNotNull(sessionFilter) {
                "No active Laner chat session; call ai_limbs.chat.session.open first"
            }
        }
        val limit = requestedLimit.coerceIn(1, MAX_FETCH_LIMIT)
        val selected =
            storedState.requests.asSequence()
                .filter { it.isUnresolved() }
                .filter { sessionFilter == null || it.sessionId == sessionFilter }
                .filter { requestedPriority == null || it.priority == requestedPriority }
                .filter {
                    if (normalizedRequestId.isNotEmpty()) {
                        it.requestId == normalizedRequestId
                    } else {
                        it.seq > afterSeq.coerceAtLeast(0L)
                    }
                }
                .sortedBy { it.seq }
                .take(limit)
                .toList()
        if (normalizedRequestId.isNotEmpty() && selected.isEmpty()) {
            throw IllegalArgumentException("Unanswered Laner chat request not found: $normalizedRequestId")
        }
        val resolvedSessionId =
            explicitSessionId
                ?: selected.firstOrNull()?.sessionId
                ?: storedState.activeSessionId
        touchAgentLocked(resolvedSessionId)
        if (selected.isNotEmpty()) {
            val selectedIds = selected.mapTo(mutableSetOf()) { it.requestId }
            val now = System.currentTimeMillis()
            val updated = storedState.requests.map { request ->
                if (request.requestId in selectedIds) {
                    request.copy(
                        status = LanerChatMessageStatus.DELIVERED,
                        deliveryCount = request.deliveryCount + 1,
                        deliveredAtMs = now
                    )
                } else {
                    request
                }
            }
            commitState(storedState.copy(requests = updated))
        }
        val deliveredById = storedState.requests.associateBy { it.requestId }
        return LanerChatFetchResult(
            sessionId = resolvedSessionId,
            requests = selected.map { deliveredById.getValue(it.requestId) },
            latestSeq = storedState.lastSeq
        )
    }

    @Synchronized
    fun attachment(requestId: String, attachmentId: String): LanerChatAttachment {
        val normalizedRequestId = requestId.trim()
        val normalizedAttachmentId = attachmentId.trim()
        require(normalizedRequestId.isNotEmpty()) { "request_id is required" }
        require(normalizedAttachmentId.isNotEmpty()) { "attachment_id is required" }
        val request = storedState.requests.firstOrNull { it.requestId == normalizedRequestId }
            ?: throw IllegalArgumentException("Laner chat request not found: $normalizedRequestId")
        val attachment = request.attachments.firstOrNull { it.attachmentId == normalizedAttachmentId }
            ?: throw IllegalArgumentException("Laner chat attachment not found: $normalizedAttachmentId")
        check(java.io.File(attachment.filePath).isFile) {
            "Laner chat attachment file is no longer available: ${attachment.fileName}"
        }
        touchAgentLocked(request.sessionId)
        return attachment
    }

    @Synchronized
    fun reply(requestId: String, replyId: String?, content: String): LanerChatReplyResult {
        val normalizedRequestId = requestId.trim()
        val normalizedContent = content.trim()
        require(normalizedRequestId.isNotEmpty()) { "request_id is required" }
        require(normalizedContent.isNotEmpty()) { "reply content is required" }
        val index = storedState.requests.indexOfFirst { it.requestId == normalizedRequestId }
        require(index >= 0) { "Laner chat request not found: $normalizedRequestId" }
        val existing = storedState.requests[index]
        touchAgentLocked(existing.sessionId)
        val normalizedReplyId =
            replyId?.trim()?.takeIf { it.isNotEmpty() } ?: "reply:$normalizedRequestId"
        if (existing.status == LanerChatMessageStatus.ANSWERED) {
            check(existing.replyId == normalizedReplyId && existing.replyContent == normalizedContent) {
                "Laner chat request already answered with different reply data"
            }
            return LanerChatReplyResult(existing, duplicate = true, deliveredToLiveStream = false)
        }
        check(existing.status != LanerChatMessageStatus.CANCELED) {
            "Laner chat request was canceled: $normalizedRequestId"
        }
        check(existing.replyChunkSeq == 0 && existing.replyId == null) {
            "Streaming reply already started; use reply.delta and reply.complete"
        }
        val answered = existing.copy(
            status = LanerChatMessageStatus.ANSWERED,
            answeredAtMs = System.currentTimeMillis(),
            replyId = normalizedReplyId,
            replyContent = normalizedContent
        )
        val requests = storedState.requests.toMutableList().also { it[index] = answered }
        commitState(storedState.copy(requests = requests))
        val live = liveReplies.remove(normalizedRequestId)
        val deliveredLive = live?.channel?.trySend(normalizedContent)?.isSuccess == true
        live?.channel?.close()
        return LanerChatReplyResult(answered, duplicate = false, deliveredToLiveStream = deliveredLive)
    }

    @Synchronized
    fun startReply(requestId: String, replyId: String?): LanerChatReplyStartResult {
        val normalizedRequestId = requestId.trim()
        require(normalizedRequestId.isNotEmpty()) { "request_id is required" }
        val normalizedReplyId =
            replyId?.trim()?.takeIf { it.isNotEmpty() } ?: "reply:$normalizedRequestId"
        val index = storedState.requests.indexOfFirst { it.requestId == normalizedRequestId }
        require(index >= 0) { "Laner chat request not found: $normalizedRequestId" }
        val existing = storedState.requests[index]
        touchAgentLocked(existing.sessionId)
        check(existing.status != LanerChatMessageStatus.CANCELED) {
            "Laner chat request was canceled: $normalizedRequestId"
        }
        if (existing.replyId != null) {
            check(existing.replyId == normalizedReplyId) {
                "Laner chat reply already started with a different reply_id"
            }
            return LanerChatReplyStartResult(existing, duplicate = true)
        }
        check(existing.status != LanerChatMessageStatus.ANSWERED) {
            "Laner chat request is already answered: $normalizedRequestId"
        }
        val started = existing.copy(replyId = normalizedReplyId, replyContent = "", replyChunkSeq = 0)
        val requests = storedState.requests.toMutableList().also { it[index] = started }
        commitState(storedState.copy(requests = requests))
        return LanerChatReplyStartResult(started, duplicate = false)
    }

    @Synchronized
    fun appendReplyDelta(
        requestId: String,
        replyId: String?,
        seq: Int,
        content: String
    ): LanerChatReplyDeltaResult {
        val normalizedRequestId = requestId.trim()
        require(normalizedRequestId.isNotEmpty()) { "request_id is required" }
        require(seq >= 1) { "seq must start at 1" }
        require(content.isNotEmpty()) { "delta content is required" }
        val index = storedState.requests.indexOfFirst { it.requestId == normalizedRequestId }
        require(index >= 0) { "Laner chat request not found: $normalizedRequestId" }
        val existing = storedState.requests[index]
        touchAgentLocked(existing.sessionId)
        val expectedReplyId = checkNotNull(existing.replyId) {
            "Streaming reply has not started; call ai_limbs.chat.reply.start first"
        }
        val normalizedReplyId =
            replyId?.trim()?.takeIf { it.isNotEmpty() } ?: expectedReplyId
        check(expectedReplyId == normalizedReplyId) {
            "reply_id does not match the active streaming reply"
        }
        check(existing.status != LanerChatMessageStatus.CANCELED) {
            "Laner chat request was canceled: $normalizedRequestId"
        }
        if (seq <= existing.replyChunkSeq) {
            return LanerChatReplyDeltaResult(existing, duplicate = true, deliveredToLiveStream = false)
        }
        check(existing.status != LanerChatMessageStatus.ANSWERED) {
            "Laner chat request is already answered: $normalizedRequestId"
        }
        check(seq == existing.replyChunkSeq + 1) {
            "Streaming reply sequence gap: expected ${existing.replyChunkSeq + 1}, got $seq"
        }
        val updated = existing.copy(
            replyContent = (existing.replyContent ?: "") + content,
            replyChunkSeq = seq
        )
        val requests = storedState.requests.toMutableList().also { it[index] = updated }
        commitState(storedState.copy(requests = requests))
        val deliveredLive =
            liveReplies[normalizedRequestId]?.channel?.trySend(content)?.isSuccess == true
        return LanerChatReplyDeltaResult(updated, duplicate = false, deliveredToLiveStream = deliveredLive)
    }

    @Synchronized
    fun completeReply(requestId: String, replyId: String?): LanerChatReplyCompleteResult {
        val normalizedRequestId = requestId.trim()
        require(normalizedRequestId.isNotEmpty()) { "request_id is required" }
        val index = storedState.requests.indexOfFirst { it.requestId == normalizedRequestId }
        require(index >= 0) { "Laner chat request not found: $normalizedRequestId" }
        val existing = storedState.requests[index]
        touchAgentLocked(existing.sessionId)
        val expectedReplyId = checkNotNull(existing.replyId) {
            "Streaming reply has not started; call ai_limbs.chat.reply.start first"
        }
        val normalizedReplyId =
            replyId?.trim()?.takeIf { it.isNotEmpty() } ?: expectedReplyId
        check(expectedReplyId == normalizedReplyId) {
            "reply_id does not match the active streaming reply"
        }
        if (existing.status == LanerChatMessageStatus.ANSWERED) {
            return LanerChatReplyCompleteResult(existing, duplicate = true)
        }
        check(existing.status != LanerChatMessageStatus.CANCELED) {
            "Laner chat request was canceled: $normalizedRequestId"
        }
        check(!existing.replyContent.isNullOrEmpty()) { "streaming reply has no content" }
        val answered = existing.copy(
            status = LanerChatMessageStatus.ANSWERED,
            answeredAtMs = System.currentTimeMillis()
        )
        val requests = storedState.requests.toMutableList().also { it[index] = answered }
        commitState(storedState.copy(requests = requests))
        liveReplies.remove(normalizedRequestId)?.channel?.close()
        return LanerChatReplyCompleteResult(answered, duplicate = false)
    }

    @Synchronized
    fun cancelRequest(requestId: String, reason: String): Boolean {
        val index = storedState.requests.indexOfFirst { it.requestId == requestId }
        if (index < 0) return false
        val existing = storedState.requests[index]
        if (!existing.isUnresolved()) return false
        val canceled = existing.copy(
            status = LanerChatMessageStatus.CANCELED,
            canceledAtMs = System.currentTimeMillis()
        )
        val requests = storedState.requests.toMutableList().also { it[index] = canceled }
        commitState(storedState.copy(requests = requests))
        liveReplies.remove(requestId)?.channel?.cancel(CancellationException(reason))
        return true
    }

    @Synchronized
    fun snapshot(): LanerChatMailboxStatus = buildMailboxStatus(storedState)

    @Synchronized
    private fun ensureUiSessionLocked(now: Long, chatId: String? = null): LanerChatSession {
        val active = storedState.activeSessionId?.let { id ->
            storedState.sessions.firstOrNull {
                it.sessionId == id && it.status == LanerChatSessionStatus.OPEN
            }
        }
        if (active != null) {
            if (chatId.isNullOrBlank() || active.chatId == chatId) return active
            val rebound = active.copy(chatId = chatId)
            val sessions = storedState.sessions.map { session ->
                if (session.sessionId == rebound.sessionId) rebound else session
            }
            commitState(storedState.copy(sessions = sessions))
            return rebound
        }
        val created =
            LanerChatSession(
                sessionId = UUID.randomUUID().toString(),
                openedAtMs = now,
                chatId = chatId
            )
        commitState(
            storedState.copy(
                activeSessionId = created.sessionId,
                sessions = storedState.sessions + created
            )
        )
        return created
    }

    private fun unresolvedRequests(sessionId: String?): List<LanerChatRequest> =
        storedState.requests.filter { request ->
            request.isUnresolved() && (sessionId.isNullOrBlank() || request.sessionId == sessionId)
        }

    private fun touchAgentLocked(sessionId: String?) {
        val targetSessionId =
            sessionId?.trim()?.takeIf { it.isNotEmpty() }
                ?: storedState.activeSessionId
                ?: return
        val sessions = storedState.sessions.toMutableList()
        val index = sessions.indexOfFirst { it.sessionId == targetSessionId }
        if (index < 0) return
        val now = System.currentTimeMillis()
        val previous = sessions[index]
        if (now - (previous.lastAgentSeenAtMs ?: 0L) < AGENT_SEEN_WRITE_INTERVAL_MS) return
        sessions[index] = previous.copy(lastAgentSeenAtMs = now)
        commitState(storedState.copy(sessions = sessions))
    }

    private fun loadState(): LanerChatStoredState {
        val raw = preferences.getString(KEY_STATE, null) ?: return LanerChatStoredState()
        return try {
            json.decodeFromString(raw)
        } catch (error: Exception) {
            throw IllegalStateException("Stored Laner chat mailbox is invalid", error)
        }
    }

    private fun commitState(updated: LanerChatStoredState) {
        val committed = preferences.edit().putString(KEY_STATE, json.encodeToString(updated)).commit()
        check(committed) { "Unable to persist Laner chat mailbox" }
        storedState = updated
        latestSeqFlow.value = updated.lastSeq
        mailboxStatusFlow.value = buildMailboxStatus(updated)
    }

    private fun buildMailboxStatus(state: LanerChatStoredState): LanerChatMailboxStatus {
        val activeSession = state.sessions.firstOrNull { it.sessionId == state.activeSessionId }
        return LanerChatMailboxStatus(
            activeSessionId = state.activeSessionId,
            boundChatId = activeSession?.chatId,
            latestSeq = state.lastSeq,
            pendingCount = state.requests.count { it.status == LanerChatMessageStatus.PENDING },
            deliveredCount = state.requests.count { it.status == LanerChatMessageStatus.DELIVERED },
            answeredCount = state.requests.count { it.status == LanerChatMessageStatus.ANSWERED },
            canceledCount = state.requests.count { it.status == LanerChatMessageStatus.CANCELED },
            proactivePendingCount =
                state.proactiveMessages.count {
                    it.status == LanerChatProactiveMessageStatus.PENDING
                },
            proactiveDeliveredCount =
                state.proactiveMessages.count {
                    it.status == LanerChatProactiveMessageStatus.DELIVERED
                },
            lastAgentSeenAtMs = activeSession?.lastAgentSeenAtMs
        )
    }

    private fun LanerChatRequest.isUnresolved(): Boolean =
        status == LanerChatMessageStatus.PENDING || status == LanerChatMessageStatus.DELIVERED

    companion object {
        const val EVENT_NEW_MESSAGE = "new_message"
        const val EVENT_IDLE = "idle"
        const val MAX_WAIT_MS = 30_000L
        const val MAX_FETCH_LIMIT = 20
        private const val PREFERENCES_NAME = "ai_limbs_laner_chat"
        private const val KEY_STATE = "mailbox_state"
        private const val AGENT_SEEN_WRITE_INTERVAL_MS = 10_000L

        @Volatile
        private var instance: LanerChatBridgeService? = null

        fun getInstance(context: Context): LanerChatBridgeService =
            instance ?: synchronized(this) {
                instance ?: LanerChatBridgeService(context.applicationContext).also { instance = it }
            }
    }
}
