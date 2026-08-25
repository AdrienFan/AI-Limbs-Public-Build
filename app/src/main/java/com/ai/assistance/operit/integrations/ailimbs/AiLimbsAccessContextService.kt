package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context

/**
 * Builds the minimal, provider-neutral AI Limbs bootstrap and per-message prompt anchors.
 *
 * The full access prompts are managed documents. The bootstrap keeps stable recovery instructions,
 * while Laner Chat places an action checkpoint after each user message so long conversations can
 * recover current rules without reinjecting the complete prompt on every turn.
 */
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

    fun buildLanerChatContextHeader(): String =
        buildString {
            appendLine("[Laner Chat context]")
            appendLine("interaction_mode: FULL_CONVERSATION")
            appendLine("user_visible_reply: true")
            append("reply_mode: NORMAL_CHAT")
        }

    suspend fun buildLanerChatActionCheckpoint(): String {
        val systemPrompt = documents.documentReference(AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT)
        val customPrompt = documents.documentReference(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT)
        return buildString {
            appendLine("[AI Limbs action checkpoint]")
            appendLine("System Access Prompt: ai_limbs.system_access_prompt.read @ ${systemPrompt.version}")
            appendLine("Custom Access Prompt: ai_limbs.custom_access_prompt.read @ ${customPrompt.version}; empty=${customPrompt.isEmpty}")
            appendLine("Evaluate this checkpoint after reading the user message above.")
            appendLine("If this turn requires any AI Limbs action, ensure the CURRENT System Access Prompt BODY, not only its version, is clearly available in active context before acting.")
            appendLine("An unchanged version does not prove that the body is still present after context compaction or loss. If the body is unavailable or uncertain, read the current official document first.")
            appendLine("If the custom prompt is non-empty and its current body is unavailable or uncertain, read it before applying user-defined access instructions.")
            appendLine("When an AI Limbs capability is needed and its invocation method is unknown, use Capability Resolver first. Do not substitute source-code search, guessed tool names, parameters, or invocation addresses.")
            append("If this turn requires no AI Limbs action, continue the normal conversation without reloading unchanged prompts.")
        }
    }
}
