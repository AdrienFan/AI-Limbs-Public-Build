package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import org.json.JSONObject

/**
 * Connection-scoped protocol gate for AI Limbs capabilities.
 *
 * A prompt receipt is valid only for the exact managed-document version that was read.
 * New bridge sessions reset all receipts, and document version changes invalidate them
 * automatically on the next capability call.
 */
class AiLimbsAccessGate(context: Context) {
    private val documents = AiLimbsDocumentProvider(context.applicationContext)
    private val stateLock = Any()

    private var systemPromptReceiptVersion: String? = null
    private var customPromptReceiptVersion: String? = null

    fun resetForNewSession() {
        synchronized(stateLock) {
            systemPromptReceiptVersion = null
            customPromptReceiptVersion = null
        }
    }

    suspend fun rejectionBefore(tool: String): JSONObject? {
        val systemReference =
            documents.documentReference(AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT)
        val customReference =
            documents.documentReference(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT)

        if (tool == SYSTEM_PROMPT_READ_TOOL) return null

        val systemReady = synchronized(stateLock) {
            systemPromptReceiptVersion == systemReference.version
        }
        if (!systemReady) {
            return requiredPromptError(
                code = SYSTEM_PROMPT_REQUIRED,
                state = "bootstrap_pending",
                reference = systemReference,
                readTool = SYSTEM_PROMPT_READ_TOOL,
                customReference = customReference
            )
        }

        if (tool in CUSTOM_PROMPT_READ_TOOLS) return null
        if (customReference.isEmpty) return null

        val customReady = synchronized(stateLock) {
            customPromptReceiptVersion == customReference.version
        }
        if (!customReady) {
            return requiredPromptError(
                code = CUSTOM_PROMPT_REQUIRED,
                state = "custom_prompt_pending",
                reference = customReference,
                readTool = CUSTOM_PROMPT_CANONICAL_READ_TOOL,
                customReference = customReference
            )
        }
        return null
    }

    fun recordSuccessfulRead(tool: String, result: JSONObject) {
        if (!result.optBoolean("success", false)) return
        val version = result.optString("version").trim()
        if (version.isBlank()) return

        synchronized(stateLock) {
            when (tool) {
                SYSTEM_PROMPT_READ_TOOL -> {
                    systemPromptReceiptVersion = version
                    customPromptReceiptVersion = null
                }
                in CUSTOM_PROMPT_READ_TOOLS -> {
                    customPromptReceiptVersion = version
                }
            }
        }
    }

    private fun requiredPromptError(
        code: String,
        state: String,
        reference: AiLimbsDocumentReference,
        readTool: String,
        customReference: AiLimbsDocumentReference
    ): JSONObject =
        JSONObject()
            .put("success", false)
            .put("error_code", code)
            .put("gate_state", state)
            .put("required_document", reference.documentId)
            .put("required_version", reference.version)
            .put("required_path", reference.path)
            .put(
                "required_read",
                JSONObject()
                    .put("name", readTool)
                    .put("parameters", JSONObject())
            )
            .put(
                "current_versions",
                JSONObject()
                    .put("system", if (code == SYSTEM_PROMPT_REQUIRED) reference.version else JSONObject.NULL)
                    .put("custom", customReference.version)
                    .put("custom_empty", customReference.isEmpty)
            )
            .put(
                "error",
                if (code == SYSTEM_PROMPT_REQUIRED) {
                    "Read the current AI Limbs system access prompt before using other AI Limbs capabilities."
                } else {
                    "Read the current non-empty AI Limbs custom access prompt before using other AI Limbs capabilities."
                }
            )

    companion object {
        const val SYSTEM_PROMPT_REQUIRED = "SYSTEM_ACCESS_PROMPT_REQUIRED"
        const val CUSTOM_PROMPT_REQUIRED = "CUSTOM_ACCESS_PROMPT_REQUIRED"
        const val SYSTEM_PROMPT_READ_TOOL = "ai_limbs.system_access_prompt.read"
        const val CUSTOM_PROMPT_CANONICAL_READ_TOOL = "ai_limbs.custom_access_prompt.read"

        val CUSTOM_PROMPT_READ_TOOLS =
            setOf(
                CUSTOM_PROMPT_CANONICAL_READ_TOOL,
                "ai_limbs.access_prompt.read",
                "laner.access_prompt.read"
            )
    }
}
