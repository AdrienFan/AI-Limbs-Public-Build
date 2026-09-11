package com.ai.assistance.operit.integrations.ailimbs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsExecutionPolicyDescriptorTest {
    @Test
    fun immutableBootstrapCarriesV3WorkModeGuidance() {
        val content = AiLimbsSystemAccessPrompt.content

        assertTrue(content.contains("AIL_EXECUTION_POLICY_V3"))
        assertTrue(content.contains("ai_limbs.work_mode.select"))
        assertTrue(content.contains("NON_WORK"))
        assertTrue(content.contains("WORK"))
        assertTrue(content.contains("ai_limbs.chat.turn.resolve"))
        assertTrue(content.contains("IMAGE_PIXELS"))
        assertFalse(content.contains("work_context"))
        assertTrue(AiLimbsSystemAccessPrompt.SOURCE_URI.startsWith("code://"))
        assertTrue(AiLimbsSystemAccessPrompt.version.startsWith("sha256:"))
    }

    @Test
    fun hostPolicyNeverInfersWorkManualFromParametersOrPaths() {
        val ordinaryWrite = AiLimbsExecutionPolicyDescriptor.specForHostTool(
            "write_file",
            JSONObject().put("path", "/storage/emulated/0/Laner/notes/today.txt"),
            AiLimbsExecutionTransport.RDC
        )
        val projectMove = AiLimbsExecutionPolicyDescriptor.specForHostTool(
            "move_file",
            JSONObject()
                .put("source", "/tmp/result.kt")
                .put("destination_path", "/root/laner/projects/AI-Limbs/app/result.kt")
                .put("work_context", true),
            AiLimbsExecutionTransport.RDC
        )

        val expected = setOf(AiLimbsRequiredReceipt.CUSTOM_ACCESS_PROMPT)
        assertEquals(expected, ordinaryWrite.requiredReceipts)
        assertEquals(expected, projectMove.requiredReceipts)
        assertFalse(AiLimbsRequiredReceipt.WORK_MANUAL in projectMove.requiredReceipts)
    }

    @Test
    fun pluginAndOperatorSpecsLeaveWorkModeToHostGate() {
        val operatorSpec = AiLimbsExecutionPolicyDescriptor.specForHostTool(
            "create_terminal_session", JSONObject(), AiLimbsExecutionTransport.RDC
        )
        val pluginSpec = AiLimbsExecutionPolicyDescriptor.specForHostTool(
            "create_terminal_session", JSONObject(), AiLimbsExecutionTransport.PLUGIN_RUNTIME
        )

        val expected = setOf(AiLimbsRequiredReceipt.CUSTOM_ACCESS_PROMPT)
        assertEquals(expected, operatorSpec.requiredReceipts)
        assertEquals(expected, pluginSpec.requiredReceipts)
        assertEquals(AiLimbsEffect.PROCESS_EXECUTION, pluginSpec.effect)
    }

    @Test
    fun canonicalRdcAliasesKeepCorrectPolicySemantics() {
        val fileInfo = AiLimbsExecutionPolicyDescriptor.specForHostTool(
            "file_info", JSONObject(), AiLimbsExecutionTransport.RDC
        )
        val projectDirectory = AiLimbsExecutionPolicyDescriptor.specForHostTool(
            "make_directory",
            JSONObject().put("path", "/root/laner/projects/new-module"),
            AiLimbsExecutionTransport.RDC
        )

        assertEquals(AiLimbsEffect.READ_ONLY, fileInfo.effect)
        assertEquals(AiLimbsDomain.STORAGE, fileInfo.domain)
        assertEquals(AiLimbsEffect.PERSISTENT_WRITE, projectDirectory.effect)
        assertEquals(
            setOf(AiLimbsRequiredReceipt.CUSTOM_ACCESS_PROMPT),
            projectDirectory.requiredReceipts
        )
    }

    @Test
    fun rdcSearchBackendsRemainReadOnlyStorageCapabilities() {
        val findFiles = AiLimbsExecutionPolicyDescriptor.specForHostTool(
            "find_files", JSONObject(), AiLimbsExecutionTransport.RDC
        )
        val grepCode = AiLimbsExecutionPolicyDescriptor.specForHostTool(
            "grep_code", JSONObject(), AiLimbsExecutionTransport.RDC
        )
        listOf(findFiles, grepCode).forEach { spec ->
            assertEquals(AiLimbsEffect.READ_ONLY, spec.effect)
            assertEquals(AiLimbsDomain.STORAGE, spec.domain)
        }
    }

    @Test
    fun imageIntentNeverPretendsPixelsWereActuallyAttached() {
        val spec = AiLimbsExecutionPolicyDescriptor.specForHostTool(
            "read_file_full",
            JSONObject()
                .put("path", "/storage/emulated/0/Pictures/example.png")
                .put("direct_image", true),
            AiLimbsExecutionTransport.RDC
        )

        assertEquals(AiLimbsPayloadKind.STRUCTURED_DATA, spec.payloadKind)
    }

    @Test
    fun workModeSelectionIsProtocolAllowedButCustomPromptGuarded() {
        val spec = AiLimbsExecutionPolicyDescriptor.specForCoreRoute(
            AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.WORK_MODE_SELECT)
        )

        assertEquals(AiLimbsPermissionMode.PROTOCOL_ALLOW, spec.permissionMode)
        assertEquals(
            setOf(AiLimbsRequiredReceipt.CUSTOM_ACCESS_PROMPT),
            spec.requiredReceipts
        )
        assertFalse(spec.hostPermissionEnforced)
    }

    @Test
    fun hostExecutorWrapperIsOnlyAnAllowlistedTransportAbi() {
        val spec = AiLimbsExecutionPolicyDescriptor.specForCoreRoute(
            AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.HOST_TOOL_EXECUTE)
        )

        assertEquals(AiLimbsPermissionMode.PROTOCOL_ALLOW, spec.permissionMode)
        assertTrue(spec.requiredReceipts.isEmpty())
        assertFalse(spec.hostPermissionEnforced)
    }

    @Test
    fun generatedChinesePolicyExplainsHardInvariants() {
        val explanation = AiLimbsExecutionPolicyDescriptor.renderChineseExplanation()

        assertTrue(explanation.contains("统一执行政策"))
        assertTrue(explanation.contains("工作模式墙"))
        assertTrue(explanation.contains("NON_WORK"))
        assertTrue(explanation.contains("WORK"))
        assertTrue(explanation.contains("不根据能力名"))
        assertTrue(explanation.contains("唯一地址"))
        assertTrue(explanation.contains("实际附带像素"))
    }
}
