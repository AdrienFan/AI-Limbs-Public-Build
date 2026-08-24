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
    val replyContent: String? = null
)

@Serializable
internal data class LanerChatStoredState(
    val lastSeq: Long = 0L,
    val activeSessionId: String? = null,
    val sessions: List<LanerChatSession> = emptyList(),
    val requests: List<LanerChatRequest> = emptyList(),
    val proactiveMessages: List<LanerChatProactiveMessage> = emptyList()
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
    val lastAgentSeenAtMs: Long? = null
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

data class LanerChatPendingExchange(
    val request: LanerChatRequest,
    val reply: CompletableDeferred<String>
)
