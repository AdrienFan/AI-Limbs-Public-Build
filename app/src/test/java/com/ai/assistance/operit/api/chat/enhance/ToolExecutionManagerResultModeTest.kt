package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.TerminalSessionCreationResultData
import com.ai.assistance.operit.core.tools.UbuntuRuntimeStatusResultData
import com.ai.assistance.operit.data.model.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionManagerResultModeTest {
    @Test
    fun structuredModePreservesUbuntuStatusPayload() {
        val payload = UbuntuRuntimeStatusResultData(
            state = "RUNNING",
            detail = "Ubuntu is running.",
            idleMode = "KEEP_RUNNING"
        )
        val result = ToolExecutionManager.finalizeCollectedToolResult(
            displayToolName = "ubuntu.status",
            collectedResults = listOf(ToolResult("ubuntu.status", true, payload)),
            preserveStructuredResult = true
        )

        assertTrue(result.result is UbuntuRuntimeStatusResultData)
        assertEquals("RUNNING", (result.result as UbuntuRuntimeStatusResultData).state)
    }

    @Test
    fun structuredModePreservesTerminalSessionId() {
        val payload = TerminalSessionCreationResultData(
            sessionId = "session-42",
            sessionName = "AI Limbs Local",
            isNewSession = true
        )
        val result = ToolExecutionManager.finalizeCollectedToolResult(
            displayToolName = "create_terminal_session",
            collectedResults = listOf(ToolResult("create_terminal_session", true, payload)),
            preserveStructuredResult = true
        )

        assertTrue(result.result is TerminalSessionCreationResultData)
        assertEquals("session-42", (result.result as TerminalSessionCreationResultData).sessionId)
    }

    @Test
    fun legacyModeKeepsChatTextAggregation() {
        val payload = UbuntuRuntimeStatusResultData(
            state = "RUNNING",
            detail = "Ubuntu is running.",
            idleMode = "KEEP_RUNNING"
        )
        val result = ToolExecutionManager.finalizeCollectedToolResult(
            displayToolName = "ubuntu.status",
            collectedResults = listOf(ToolResult("ubuntu.status", true, payload)),
            preserveStructuredResult = false
        )

        assertTrue(result.result is StringResultData)
        assertTrue((result.result as StringResultData).value.contains("Ubuntu runtime: RUNNING"))
    }
}
