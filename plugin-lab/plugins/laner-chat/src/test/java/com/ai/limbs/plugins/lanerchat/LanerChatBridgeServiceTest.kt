package com.ai.limbs.plugins.lanerchat

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanerChatBridgeServiceTest {
    @Test
    fun durableMailboxPreservesSeqTurnAndIdempotentReplyAcrossReopen() = runBlocking {
        val root = Files.createTempDirectory("laner-chat-plugin-test").toFile()
        try {
            val service = LanerChatBridgeService.create(root)
            val session = service.bindUiChat("chat-A")
            val first = service.enqueueMailbox(
                chatId = "chat-A",
                text = "M1",
                priority = LanerChatPriority.NORMAL
            )
            val second = service.enqueueMailbox(
                chatId = "chat-A",
                text = "M2",
                priority = LanerChatPriority.HIGH
            )

            assertEquals(first.seq + 1L, second.seq)
            assertEquals(2, service.notification(0L, session.sessionId).pendingReplyCount)

            val claimed = service.claimTurn(session.sessionId, requestedLimit = 50)
            assertNotNull(claimed)
            claimed!!
            assertEquals(listOf(first.requestId, second.requestId), claimed.turn.requestIds)
            assertEquals(LanerChatPriority.HIGH, claimed.turn.highestPriority)

            val completed = service.completeTurn(
                turnId = claimed.turn.turnId,
                replyId = "reply-fixed",
                content = "R1"
            )
            assertFalse(completed.duplicate)

            val duplicate = service.completeTurn(
                turnId = claimed.turn.turnId,
                replyId = "reply-fixed",
                content = "R1"
            )
            assertTrue(duplicate.duplicate)

            val reopened = LanerChatBridgeService.create(root)
            val snapshot = reopened.snapshot()
            assertEquals(0, snapshot.unresolvedCount)
            assertEquals(2, snapshot.answeredCount)
            assertNull(snapshot.activeTurnId)
            assertNull(reopened.turnStatus(session.sessionId).activeTurn)

        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun canceledTurnPreservesRequestsAndResumeMakesThemEligibleAgain() {
        val root = Files.createTempDirectory("laner-chat-plugin-cancel-test").toFile()
        try {
            val service = LanerChatBridgeService.create(root)
            val session = service.bindUiChat("chat-B")
            service.enqueueMailbox(chatId = "chat-B", text = "M1")
            val claimed = requireNotNull(service.claimTurn(session.sessionId, 10))

            val canceled = service.cancelActiveTurn(session.sessionId)
            assertTrue(canceled.changed)
            assertTrue(canceled.schedulerPaused)
            assertEquals(LanerChatAssistantTurnStatus.CANCELED, canceled.turn?.status)
            assertEquals(1, service.snapshot().unresolvedCount)

            val resumed = service.resumeScheduler(session.sessionId)
            assertFalse(resumed.schedulerPaused)
            assertEquals(1, resumed.eligibleRequestCount)

            val next = requireNotNull(service.claimTurn(session.sessionId, 10))
            assertEquals(claimed.turn.requestIds, next.turn.requestIds)
        } finally {
            root.deleteRecursively()
        }
    }
}
