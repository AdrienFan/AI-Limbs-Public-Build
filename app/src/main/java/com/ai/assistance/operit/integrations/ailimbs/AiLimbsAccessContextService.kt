package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import org.json.JSONObject

/**
 * Generated access bootstrap. The policy engine enforces execution; this payload explains current
 * immutable and managed document versions without duplicating deterministic rules in prose.
 */
class AiLimbsAccessContextService(context: Context) {
    private val documents = AiLimbsDocumentProvider(context.applicationContext)

    suspend fun readAccessContext(): String {
        val systemPrompt =
            documents.documentReference(AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT)
        val customPrompt =
            documents.documentReference(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT)
        val workManual =
            documents.documentReference(AiLimbsDocumentId.WORK_MANUAL)
        val systemReadTool =
            checkNotNull(
                AiLimbsCoreCapabilityRegistry.managedDocumentInvokeName(
                    AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT,
                    write = false
                )
            )
        val customReadTool =
            checkNotNull(
                AiLimbsCoreCapabilityRegistry.managedDocumentInvokeName(
                    AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT,
                    write = false
                )
            )
        val workManualReadTool =
            checkNotNull(
                AiLimbsCoreCapabilityRegistry.managedDocumentInvokeName(
                    AiLimbsDocumentId.WORK_MANUAL,
                    write = false
                )
            )
        val policyDescribe =
            AiLimbsCoreCapabilityRegistry.invokeNameForLocalOperation(
                AiLimbsCoreLocalOperation.POLICY_DESCRIBE
            )
        val sessionReset =
            AiLimbsCoreCapabilityRegistry.invokeNameForLocalOperation(
                AiLimbsCoreLocalOperation.POLICY_SESSION_RESET
            )

        return JSONObject()
            .put("protocol", "AIL_EXECUTION_POLICY_V2")
            .put("kind", "IMMUTABLE_ACCESS_BOOTSTRAP")
            .put(
                "system_access_prompt",
                JSONObject()
                    .put("document_id", systemPrompt.documentId)
                    .put("version", systemPrompt.version)
                    .put("source_uri", systemPrompt.path)
                    .put("immutable", true)
                    .put("auto_injected", true)
                    .put("content", AiLimbsSystemAccessPrompt.content)
                    .put("read", capabilityInvocation(systemReadTool))
                    .put("write", JSONObject.NULL)
            )
            .put(
                "execution_policy",
                JSONObject()
                    .put("version", AiLimbsExecutionPolicyDescriptor.policyVersion)
                    .put("transport_neutral", true)
                    .put("describe", capabilityInvocation(policyDescribe))
                    .put("explanation_zh", AiLimbsExecutionPolicyDescriptor.renderChineseExplanation())
            )
            .put(
                "custom_access_prompt",
                JSONObject()
                    .put("document_id", customPrompt.documentId)
                    .put("version", customPrompt.version)
                    .put("empty", customPrompt.isEmpty)
                    .put("path", customPrompt.path)
                    .put("read_when_requested", capabilityInvocation(customReadTool))
            )
            .put(
                "work_manual",
                JSONObject()
                    .put("document_id", workManual.documentId)
                    .put("effective_version", workManual.version)
                    .put("path", workManual.path)
                    .put("read_when_requested", capabilityInvocation(workManualReadTool))
            )
            .put(
                "context_boundary",
                JSONObject()
                    .put("transient_reconnect_resets_receipts", false)
                    .put("explicit_reset", capabilityInvocation(sessionReset))
            )
            .put("persistent_artifacts_indexed", true)
            .toString()
    }

    private fun capabilityInvocation(name: String): JSONObject =
        JSONObject()
            .put("name", name)
            .put("parameters", JSONObject())
}
