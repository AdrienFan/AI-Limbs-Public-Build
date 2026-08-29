package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import org.json.JSONObject

internal data class AiLimbsMissingReceipt(
    val receipt: AiLimbsRequiredReceipt,
    val reference: AiLimbsDocumentReference,
    val readTool: String
)

/**
 * Receipt ledger owned by one explicit AI Limbs execution session.
 *
 * The execution policy engine is the only component that turns a missing receipt into a decision.
 * Realtime transport reconnects do not reset this ledger; an actual model-context boundary calls
 * ai_limbs.policy.session.reset.
 */
class AiLimbsAccessGate(context: Context) {
    private val documents = AiLimbsDocumentProvider(context.applicationContext)
    private val stateLock = Any()

    private var customPromptReceiptVersion: String? = null
    private var workManualReceiptVersion: String? = null

    private val customPromptReadTools =
        AiLimbsCoreCapabilityRegistry.managedDocumentInvokeNames(
            AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT,
            write = false
        )
    private val workManualReadTools =
        AiLimbsCoreCapabilityRegistry.managedDocumentInvokeNames(
            AiLimbsDocumentId.WORK_MANUAL,
            write = false
        )
    private val customPromptCanonicalReadTool =
        checkNotNull(
            AiLimbsCoreCapabilityRegistry.managedDocumentInvokeName(
                AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT,
                write = false
            )
        )
    private val workManualCanonicalReadTool =
        checkNotNull(
            AiLimbsCoreCapabilityRegistry.managedDocumentInvokeName(
                AiLimbsDocumentId.WORK_MANUAL,
                write = false
            )
        )

    fun resetForContextBoundary() {
        synchronized(stateLock) {
            customPromptReceiptVersion = null
            workManualReceiptVersion = null
        }
    }

    internal suspend fun firstMissing(
        requiredReceipts: Set<AiLimbsRequiredReceipt>
    ): AiLimbsMissingReceipt? {
        if (AiLimbsRequiredReceipt.CUSTOM_ACCESS_PROMPT in requiredReceipts) {
            val reference = documents.documentReference(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT)
            val ready =
                reference.isEmpty ||
                    synchronized(stateLock) {
                        customPromptReceiptVersion == reference.version
                    }
            if (!ready) {
                return AiLimbsMissingReceipt(
                    receipt = AiLimbsRequiredReceipt.CUSTOM_ACCESS_PROMPT,
                    reference = reference,
                    readTool = customPromptCanonicalReadTool
                )
            }
        }

        if (AiLimbsRequiredReceipt.WORK_MANUAL in requiredReceipts) {
            val reference = documents.documentReference(AiLimbsDocumentId.WORK_MANUAL)
            val ready =
                synchronized(stateLock) {
                    workManualReceiptVersion == reference.version
                }
            if (!ready) {
                return AiLimbsMissingReceipt(
                    receipt = AiLimbsRequiredReceipt.WORK_MANUAL,
                    reference = reference,
                    readTool = workManualCanonicalReadTool
                )
            }
        }
        return null
    }

    internal fun recordSuccessfulRead(invocation: AiLimbsNormalizedInvocation, result: JSONObject) {
        if (!result.optBoolean("success", false)) return
        val version = result.optString("version").trim()
        if (version.isBlank()) return

        synchronized(stateLock) {
            when (invocation.canonicalName) {
                in customPromptReadTools -> customPromptReceiptVersion = version
                in workManualReadTools -> workManualReceiptVersion = version
            }
        }
    }
}
