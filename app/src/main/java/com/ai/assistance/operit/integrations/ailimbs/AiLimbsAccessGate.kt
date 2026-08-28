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
    private val systemPromptReadTools =
        AiLimbsCoreCapabilityRegistry.managedDocumentInvokeNames(
            AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT,
            write = false
        )
    private val customPromptReadTools =
        AiLimbsCoreCapabilityRegistry.managedDocumentInvokeNames(
            AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT,
            write = false
        )
    private val systemPromptCanonicalReadTool =
        checkNotNull(
            AiLimbsCoreCapabilityRegistry.managedDocumentInvokeName(
                AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT,
                write = false
            )
        )
    private val customPromptCanonicalReadTool =
        checkNotNull(
            AiLimbsCoreCapabilityRegistry.managedDocumentInvokeName(
                AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT,
                write = false
            )
        )

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

        if (tool in systemPromptReadTools) return null

        val systemReady = synchronized(stateLock) {
            systemPromptReceiptVersion == systemReference.version
        }
        if (!systemReady) {
            return requiredPromptError(
                code = SYSTEM_PROMPT_REQUIRED,
                state = "bootstrap_pending",
                reference = systemReference,
                readTool = systemPromptCanonicalReadTool,
                customReference = customReference
            )
        }

        if (tool in customPromptReadTools) return null
        if (customReference.isEmpty) return null

        val customReady = synchronized(stateLock) {
            customPromptReceiptVersion == customReference.version
        }
        if (!customReady) {
            return requiredPromptError(
                code = CUSTOM_PROMPT_REQUIRED,
                state = "custom_prompt_pending",
                reference = customReference,
                readTool = customPromptCanonicalReadTool,
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
            if (tool in systemPromptReadTools) {
                systemPromptReceiptVersion = version
                customPromptReceiptVersion = null
            } else if (tool in customPromptReadTools) {
                customPromptReceiptVersion = version
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
    }
}
