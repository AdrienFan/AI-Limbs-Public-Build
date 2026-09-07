package com.ai.limbs.extensions.rdc.runtime.chat

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

enum class LanerChatPriority { HIGH, NORMAL, LOW }
data class LanerChatNotification(
    val event: String, val unreadCount: Int, val pendingReplyCount: Int, val latestSeq: Long,
    val highestPriority: LanerChatPriority? = null, val highCount: Int = 0,
    val normalCount: Int = 0, val lowCount: Int = 0
)
data class LanerChatQueueChangedEvent(
    val eventId: String, val reason: String, val sessionId: String?, val latestSeq: Long,
    val pendingCount: Int, val unresolvedCount: Int, val highestPriority: LanerChatPriority?,
    val highCount: Int, val normalCount: Int, val lowCount: Int, val activeTurnId: String?,
    val schedulerPaused: Boolean, val attentionRequired: Boolean
)
fun LanerChatNotification.requiresWorkAttention(): Boolean = pendingReplyCount > 0
class LanerChatBridgeService private constructor() {
    private val mutableQueueEvents = MutableSharedFlow<LanerChatQueueChangedEvent>(extraBufferCapacity = 8)
    val queueEvents: SharedFlow<LanerChatQueueChangedEvent> = mutableQueueEvents
    fun queueSnapshotEvent(reason: String) = LanerChatQueueChangedEvent(UUID.randomUUID().toString(), reason, null, 0, 0, 0, null, 0, 0, 0, null, false, false)
    fun workNotificationSnapshot() = LanerChatNotification("none", 0, 0, 0)
    companion object { private val instance = LanerChatBridgeService(); fun getInstance(context: Context): LanerChatBridgeService = instance }
}
