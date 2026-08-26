package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context

/** Builds the minimal, provider-neutral AI Limbs access bootstrap. */
class AiLimbsAccessContextService(context: Context) {
    private val documents = AiLimbsDocumentProvider(context.applicationContext)

    suspend fun readAccessContext(): String {
        val systemPrompt = documents.documentReference(AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT)
        val customPrompt = documents.documentReference(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT)
        return buildString {
            appendLine("[AI Limbs access bootstrap]")
            appendLine("Welcome to AI Limbs.")
            appendLine("This bootstrap is an execution gate, not background information.")
            appendLine("Before performing any AI Limbs action, the current official AI Limbs system access prompt body must be clearly available in the active context.")
            appendLine("An unchanged prompt version does not prove that its body is still present after context compaction or loss. If the current body is unavailable or uncertain, read it before acting.")
            appendLine("- document_id: ${systemPrompt.documentId}")
            appendLine("- version: ${systemPrompt.version}")
            appendLine("- path: ${systemPrompt.path}")
            appendLine("- read: {\"name\":\"ai_limbs.system_access_prompt.read\",\"parameters\":{}}")
            appendLine("The AI Limbs custom access prompt is a separate user-editable managed document.")
            appendLine("- document_id: ${customPrompt.documentId}")
            appendLine("- version: ${customPrompt.version}")
            appendLine("- empty: ${customPrompt.isEmpty}")
            appendLine("- path: ${customPrompt.path}")
            appendLine("- read: {\"name\":\"ai_limbs.custom_access_prompt.read\",\"parameters\":{}}")
            appendLine("If the custom access prompt is non-empty and its current body is unavailable or uncertain, read it before applying user-defined access instructions.")
            appendLine("Do not rely on a previous read, memory, source-code search, or guessed invocation path when the current prompt body is absent or uncertain.")
            append("Use only these official managed-document capabilities; do not guess or substitute another prompt document.")
        }
    }


}
