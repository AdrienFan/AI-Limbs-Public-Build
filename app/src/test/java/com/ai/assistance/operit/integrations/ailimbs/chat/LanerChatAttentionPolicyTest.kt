package com.ai.assistance.operit.integrations.ailimbs.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanerChatAttentionPolicyTest {
    @Test
    fun unresolvedMessageStatesRequireAttention() {
        assertTrue(LanerChatMessageStatus.PENDING.requiresAttention())
        assertTrue(LanerChatMessageStatus.DELIVERED.requiresAttention())
    }

    @Test
    fun terminalMessageStatesDoNotRequireAttention() {
        assertFalse(LanerChatMessageStatus.ANSWERED.requiresAttention())
        assertFalse(LanerChatMessageStatus.RESOLVED_NO_REPLY.requiresAttention())
        assertFalse(LanerChatMessageStatus.CANCELED.requiresAttention())
    }

    @Test
    fun deliveredOnlyNotificationStillRequiresWorkAttention() {
        val deliveredOnly = LanerChatNotification(
            event = "new_message", unreadCount = 0, pendingReplyCount = 1, latestSeq = 7
        )
        val resolved = LanerChatNotification(
            event = "idle", unreadCount = 0, pendingReplyCount = 0, latestSeq = 7
        )
        assertTrue(deliveredOnly.requiresWorkAttention())
        assertFalse(resolved.requiresWorkAttention())
    }
}