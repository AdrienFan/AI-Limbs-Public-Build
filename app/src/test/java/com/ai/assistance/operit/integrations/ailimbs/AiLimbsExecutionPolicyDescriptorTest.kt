package com.ai.assistance.operit.integrations.ailimbs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsExecutionPolicyDescriptorTest {
    @Test
    fun immutableBootstrapCarriesOnlyStableModelGuidance() {
        val content = AiLimbsSystemAccessPrompt.content

        assertTrue(content.contains("AIL_EXECUTION_POLICY_V2"))
        assertTrue(content.contains("ai_limbs.chat.turn.resolve"))
        assertTrue(content.contains("IMAGE_PIXELS"))
        assertFalse(content.contains("editable", ignoreCase = true))
        assertTrue(AiLimbsSystemAccessPrompt.SOURCE_URI.startsWith("code://"))
        assertTrue(AiLimbsSystemAccessPrompt.version.startsWith("sha256:"))
    }

    @Test
    fun workManualReceiptDependsOnRealMutationTarget() {
        val ordinaryWrite =
            AiLimbsExecutionPolicyDescriptor.specForHostTool(
                "write_file",
                JSONObject().put("path", "/storage/emulated/0/Laner/notes/today.txt")
            )
        val projectMove =
            AiLimbsExecutionPolicyDescriptor.specForHostTool(
                "move_file",
                JSONObject()
                    .put("source", "/tmp/result.kt")
                    .put("destination_path", "/root/laner/projects/AI-Limbs/app/result.kt")
            )

        assertEquals(
            setOf(AiLimbsRequiredReceipt.CUSTOM_ACCESS_PROMPT),
            ordinaryWrite.requiredReceipts
        )
        assertEquals(
            setOf(
                AiLimbsRequiredReceipt.CUSTOM_ACCESS_PROMPT,
                AiLimbsRequiredReceipt.WORK_MANUAL
            ),
            projectMove.requiredReceipts
        )
    }

    @Test
    fun imageIntentNeverPretendsPixelsWereActuallyAttached() {
        val spec =
            AiLimbsExecutionPolicyDescriptor.specForHostTool(
                "read_file_full",
                JSONObject()
                    .put("path", "/storage/emulated/0/Pictures/example.png")
                    .put("direct_image", true)
            )

        assertEquals(AiLimbsPayloadKind.STRUCTURED_DATA, spec.payloadKind)
    }

    @Test
    fun hostExecutorWrapperIsOnlyAnAllowlistedTransportAbi() {
        val spec =
            AiLimbsExecutionPolicyDescriptor.specForCoreRoute(
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
        assertTrue(explanation.contains("工作手册收据"))
        assertTrue(explanation.contains("唯一地址"))
        assertTrue(explanation.contains("实际附带像素"))
    }
}
