package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.TerminalSessionCreationResultData
import com.ai.assistance.operit.core.tools.FileOperationData
import com.ai.assistance.operit.data.model.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionManagerResultModeTest {
    @Test
    fun structuredModePreservesStructuredPayload() {
        val payload = FileOperationData(
            operation = "status",
            env = "system",
            path = "/system",
            successful = true,
            details = "System environment running"
        )
        val result = ToolExecutionManager.finalizeCollectedToolResult(
            displayToolName = "system.status",
            collectedResults = listOf(ToolResult("system.status", true, payload)),
            preserveStructuredResult = true
        )

        assertTrue(result.result is FileOperationData)
        assertEquals("status", (result.result as FileOperationData).operation)
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
        val payload = FileOperationData(
            operation = "status",
            env = "system",
            path = "/system",
            successful = true,
            details = "System environment running"
        )
        val result = ToolExecutionManager.finalizeCollectedToolResult(
            displayToolName = "system.status",
            collectedResults = listOf(ToolResult("system.status", true, payload)),
            preserveStructuredResult = false
        )

        assertTrue(result.result is StringResultData)
        assertTrue((result.result as StringResultData).value.contains("System environment running"))
    }
}
