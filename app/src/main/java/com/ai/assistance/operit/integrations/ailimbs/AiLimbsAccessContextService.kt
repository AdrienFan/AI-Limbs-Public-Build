package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds an explicit diagnostic snapshot of the AI Limbs access gate as a compact machine protocol.
 *
 * Runtime enforcement lives in AiLimbsAccessGate. This payload is available only through explicit
 * diagnostic reads and must not be automatically prepended to model-facing tool results.
 */
class AiLimbsAccessContextService(context: Context) {
    private val documents = AiLimbsDocumentProvider(context.applicationContext)

    suspend fun readAccessContext(): String {
        val systemPrompt = documents.documentReference(AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT)
        val customPrompt = documents.documentReference(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT)
        val systemReadTool = checkNotNull(
            AiLimbsCoreCapabilityRegistry.managedDocumentInvokeName(
                AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT,
                write = false
            )
        )
        val customReadTool = checkNotNull(
            AiLimbsCoreCapabilityRegistry.managedDocumentInvokeName(
                AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT,
                write = false
            )
        )

        return JSONObject()
            .put("protocol", "AIL_ACCESS_V1")
            .put("kind", "EXECUTION_GATE")
            .put("before", "ANY_AI_LIMBS_ACTION")
            .put(
                "system_access_prompt",
                JSONObject()
                    .put("document_id", systemPrompt.documentId)
                    .put("version", systemPrompt.version)
                    .put("path", systemPrompt.path)
                    .put("require", "BODY_IN_ACTIVE_CONTEXT")
                    .put("version_only_proves_body", false)
                    .put(
                        "refresh_if",
                        JSONArray()
                            .put("CONTEXT_COMPACTION")
                            .put("CONTEXT_LOSS")
                            .put("BODY_UNAVAILABLE")
                            .put("BODY_UNCERTAIN")
                    )
                    .put("read", capabilityInvocation(systemReadTool))
            )
            .put(
                "custom_access_prompt",
                JSONObject()
                    .put("document_id", customPrompt.documentId)
                    .put("version", customPrompt.version)
                    .put("empty", customPrompt.isEmpty)
                    .put("path", customPrompt.path)
                    .put("require_if_not_empty", "BODY_IN_ACTIVE_CONTEXT")
                    .put(
                        "refresh_if",
                        JSONArray()
                            .put("BODY_UNAVAILABLE")
                            .put("BODY_UNCERTAIN")
                    )
                    .put("read", capabilityInvocation(customReadTool))
            )
            .put(
                "forbid",
                JSONArray()
                    .put("PREVIOUS_READ_AS_CURRENT")
                    .put("MEMORY_AS_DOCUMENT")
                    .put("SOURCE_SEARCH_AS_DOCUMENT")
                    .put("GUESSED_INVOCATION")
                    .put("DOCUMENT_SUBSTITUTION")
            )
            .put("managed_document_only", true)
            .toString()
    }

    private fun capabilityInvocation(name: String): JSONObject =
        JSONObject()
            .put("name", name)
            .put("parameters", JSONObject())
}
