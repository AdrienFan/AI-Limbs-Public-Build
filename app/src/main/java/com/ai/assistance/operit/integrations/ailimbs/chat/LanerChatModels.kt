package com.ai.assistance.operit.integrations.ailimbs.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable

@Serializable
enum class LanerChatSessionStatus {
    OPEN,
    CLOSED
}

@Serializable
enum class LanerChatMessageStatus {
    PENDING,
    DELIVERED,
    ANSWERED,
    CANCELED
}

@Serializable
enum class LanerChatPriority {
    HIGH,
    NORMAL,
    LOW
}

@Serializable
enum class LanerChatAssistantTurnStatus {
    ACTIVE,
    COMPLETED,
    CANCELED,
    INTERRUPTED
}

@Serializable
data class LanerChatSession(
    val sessionId: String,
    val status: LanerChatSessionStatus = LanerChatSessionStatus.OPEN,
    val openedAtMs: Long,
    val closedAtMs: Long? = null,
    val lastAgentSeenAtMs: Long? = null,
    val agentSessionId: String? = null,
    val chatId: String? = null
)

@Serializable
enum class LanerChatProactiveMessageStatus {
    PENDING,
    DELIVERED
}

@Serializable
data class LanerChatProactiveMessage(
    val messageId: String,
    val sessionId: String,
    val chatId: String,
    val content: String,
    val createdAtMs: Long,
    val chatMessageTimestamp: Long,
    val status: LanerChatProactiveMessageStatus = LanerChatProactiveMessageStatus.PENDING,
    val deliveredAtMs: Long? = null
)

@Serializable
data class LanerChatAttachment(
    val attachmentId: String,
    val filePath: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long
)

@Serializable
data class LanerChatRequest(
    val requestId: String,
    val sessionId: String,
    val seq: Long,
    val chatId: String,
    val sender: String,
    val text: String,
    val createdAtMs: Long,
    val priority: LanerChatPriority = LanerChatPriority.NORMAL,
    val attachments: List<LanerChatAttachment> = emptyList(),
    val status: LanerChatMessageStatus = LanerChatMessageStatus.PENDING,
    val deliveryCount: Int = 0,
    val deliveredAtMs: Long? = null,
    val answeredAtMs: Long? = null,
    val canceledAtMs: Long? = null,
    val replyId: String? = null,
    val replyContent: String? = null,
    val chatMessageTimestamp: Long = 0L
)

@Serializable
data class LanerChatAssistantTurn(
    val turnId: String,
    val sessionId: String,
    val requestIds: List<String>,
    val firstSeq: Long,
    val lastSeq: Long,
    val highestPriority: LanerChatPriority,
    val status: LanerChatAssistantTurnStatus = LanerChatAssistantTurnStatus.ACTIVE,
    val claimedAtMs: Long,
    val completedAtMs: Long? = null,
    val canceledAtMs: Long? = null,
    val replyId: String? = null,
    val replyContent: String? = null,
    val chatMessageTimestamp: Long = 0L
)

@Serializable
internal data class LanerChatStoredState(
    val lastSeq: Long = 0L,
    val activeSessionId: String? = null,
    val sessions: List<LanerChatSession> = emptyList(),
    val requests: List<LanerChatRequest> = emptyList(),
    val proactiveMessages: List<LanerChatProactiveMessage> = emptyList(),
    val assistantTurns: List<LanerChatAssistantTurn> = emptyList(),
    val schedulerPaused: Boolean = false
)

data class LanerChatMailboxStatus(
    val activeSessionId: String? = null,
    val boundChatId: String? = null,
    val latestSeq: Long = 0L,
    val pendingCount: Int = 0,
    val deliveredCount: Int = 0,
    val answeredCount: Int = 0,
    val canceledCount: Int = 0,
    val proactivePendingCount: Int = 0,
    val proactiveDeliveredCount: Int = 0,
    val lastAgentSeenAtMs: Long? = null,
    val activeTurnId: String? = null,
    val activeTurnChatId: String? = null,
    val activeTurnRequestCount: Int = 0,
    val activeTurnHighestPriority: LanerChatPriority? = null,
    val schedulerPaused: Boolean = false
) {
    val unresolvedCount: Int
        get() = pendingCount + deliveredCount
}

data class LanerChatNotification(
    val event: String,
    val unreadCount: Int,
    val pendingReplyCount: Int,
    val latestSeq: Long,
    val highestPriority: LanerChatPriority? = null,
    val highCount: Int = 0,
    val normalCount: Int = 0,
    val lowCount: Int = 0
)

data class LanerChatQueueChangedEvent(
    val eventId: String,
    val reason: String,
    val sessionId: String?,
    val latestSeq: Long,
    val pendingCount: Int,
    val unresolvedCount: Int,
    val highestPriority: LanerChatPriority?,
    val highCount: Int,
    val normalCount: Int,
    val lowCount: Int,
    val activeTurnId: String?,
    val schedulerPaused: Boolean,
    val attentionRequired: Boolean
)
data class LanerChatSessionOpenResult(
    val session: LanerChatSession,
    val lastUserSeq: Long,
    val lastReplySeq: Long,
    val pendingRequests: Int
)

data class LanerChatFetchResult(
    val sessionId: String?,
    val requests: List<LanerChatRequest>,
    val latestSeq: Long
)

data class LanerChatReplyResult(
    val request: LanerChatRequest,
    val duplicate: Boolean,
    val deliveredToLiveStream: Boolean
)

data class LanerChatProactiveSendResult(
    val message: LanerChatProactiveMessage,
    val duplicate: Boolean
)

data class LanerChatTurnStatusSnapshot(
    val sessionId: String?,
    val activeTurn: LanerChatAssistantTurn?,
    val schedulerPaused: Boolean,
    val eligibleRequestCount: Int,
    val latestSeq: Long
)

data class LanerChatTurnClaimResult(
    val turn: LanerChatAssistantTurn,
    val requests: List<LanerChatRequest>,
    val duplicate: Boolean
)

data class LanerChatTurnReplyResult(
    val turn: LanerChatAssistantTurn,
    val requests: List<LanerChatRequest>,
    val duplicate: Boolean
)

data class LanerChatTurnCancelResult(
    val turn: LanerChatAssistantTurn?,
    val schedulerPaused: Boolean,
    val changed: Boolean
)

data class LanerChatPendingExchange(
    val request: LanerChatRequest,
    val reply: CompletableDeferred<String>
)
