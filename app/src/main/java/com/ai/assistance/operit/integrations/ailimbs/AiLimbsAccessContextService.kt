package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context

/**
 * Builds the minimal, provider-neutral AI Limbs bootstrap and per-message prompt anchors.
 *
 * The full access prompts are managed documents. The bootstrap keeps only stable recovery
 * instructions, while Laner Chat carries lightweight version anchors so long conversations can
 * recover the current rules without reinjecting the complete prompt on every turn.
 */
class AiLimbsAccessContextService(context: Context) {
    private val documents = AiLimbsDocumentProvider(context.applicationContext)

    suspend fun readAccessContext(): String {
        val systemPrompt = documents.documentReference(AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT)
        val customPrompt = documents.documentReference(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT)
        return buildString {
            appendLine("[AI Limbs access bootstrap]")
            appendLine("Welcome to AI Limbs.")
            appendLine("Before using AI Limbs capabilities, read the current official AI Limbs system access prompt.")
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
            appendLine("If the custom access prompt is non-empty, read its current version before applying user-defined access instructions.")
            append("Use only these official managed-document capabilities; do not guess or substitute another prompt document.")
        }
    }

    suspend fun buildLanerChatPromptAnchor(): String {
        val systemPrompt = documents.documentReference(AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT)
        val customPrompt = documents.documentReference(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT)
        return buildString {
            appendLine("[AI Limbs context]")
            appendLine("System Access Prompt: ai_limbs.system_access_prompt.read @ ${systemPrompt.version}")
            appendLine("Custom Access Prompt: ai_limbs.custom_access_prompt.read @ ${customPrompt.version}; empty=${customPrompt.isEmpty}")
            appendLine("Before performing any AI Limbs action, ensure the referenced prompt versions are loaded. Do not reload unchanged prompts during ordinary conversation. If a loaded version is unknown or outdated, read the corresponding official document first.")
            appendLine()
            appendLine("[Laner Chat context]")
            appendLine("interaction_mode: FULL_CONVERSATION")
            appendLine("user_visible_reply: true")
            append("reply_mode: NORMAL_CHAT")
        }
    }
}
