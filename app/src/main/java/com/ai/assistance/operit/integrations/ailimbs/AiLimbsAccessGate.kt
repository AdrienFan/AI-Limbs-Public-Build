package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import org.json.JSONObject

internal data class AiLimbsMissingReceipt(
    val receipt: AiLimbsRequiredReceipt,
    val reference: AiLimbsDocumentReference,
    val readTool: String
)

internal enum class AiLimbsWorkMode {
    WORK,
    NON_WORK
}

internal enum class AiLimbsWorkGateState {
    SELECTION_REQUIRED,
    NON_WORK_ONCE,
    WORK_MANUAL_REQUIRED,
    WORK_UNLOCKED
}

internal enum class AiLimbsSubsystemDiscoveryDecision {
    DELIVER,
    BLOCK,
    ALLOW
}

internal class AiLimbsSubsystemDiscoveryLedger {
    private enum class Phase { DELIVERING, DELIVERED }

    private val stateLock = Any()
    private val phases = linkedMapOf<String, Phase>()
    private var activeExternalInvocations = 0

    fun beginInvocation() = synchronized(stateLock) {
        activeExternalInvocations += 1
    }

    fun endInvocation() = synchronized(stateLock) {
        check(activeExternalInvocations > 0) { "Subsystem discovery invocation underflow" }
        activeExternalInvocations -= 1
        if (activeExternalInvocations == 0) {
            phases.filterValues { it == Phase.DELIVERING }.keys.toList().forEach { key ->
                phases[key] = Phase.DELIVERED
            }
        }
    }

    fun resetForContextBoundary() = synchronized(stateLock) {
        phases.clear()
    }

    fun decision(extensionId: String): AiLimbsSubsystemDiscoveryDecision = synchronized(stateLock) {
        val normalized = extensionId.trim().lowercase()
        require(normalized.isNotBlank()) { "Subsystem extension id must not be blank" }
        when (phases[normalized]) {
            Phase.DELIVERING -> AiLimbsSubsystemDiscoveryDecision.BLOCK
            Phase.DELIVERED -> AiLimbsSubsystemDiscoveryDecision.ALLOW
            null -> {
                phases[normalized] =
                    if (activeExternalInvocations > 0) Phase.DELIVERING else Phase.DELIVERED
                AiLimbsSubsystemDiscoveryDecision.DELIVER
            }
        }
    }
}

internal class AiLimbsWorkModeGate {
    private val stateLock = Any()
    private var workSelected = false
    private var nonWorkPermit = false
    private var workUnlocked = false

    fun reset() {
        synchronized(stateLock) {
            workSelected = false
            nonWorkPermit = false
            workUnlocked = false
        }
    }

    fun state(): AiLimbsWorkGateState = synchronized(stateLock) { stateLocked() }

    fun select(mode: AiLimbsWorkMode): AiLimbsWorkGateState =
        synchronized(stateLock) {
            if (workUnlocked) return@synchronized AiLimbsWorkGateState.WORK_UNLOCKED
            when (mode) {
                AiLimbsWorkMode.WORK -> {
                    workSelected = true
                    nonWorkPermit = false
                }
                AiLimbsWorkMode.NON_WORK -> {
                    if (!workSelected) nonWorkPermit = true
                }
            }
            stateLocked()
        }

    fun onWorkManualRead() {
        synchronized(stateLock) {
            if (workSelected) {
                workUnlocked = true
                nonWorkPermit = false
            }
        }
    }

    fun claimNormalExecution(): Boolean = synchronized(stateLock) {
        when {
            workUnlocked -> true
            workSelected -> false
            nonWorkPermit -> {
                nonWorkPermit = false
                true
            }
            else -> false
        }
    }

    private fun stateLocked(): AiLimbsWorkGateState =
        when {
            workUnlocked -> AiLimbsWorkGateState.WORK_UNLOCKED
            workSelected -> AiLimbsWorkGateState.WORK_MANUAL_REQUIRED
            nonWorkPermit -> AiLimbsWorkGateState.NON_WORK_ONCE
            else -> AiLimbsWorkGateState.SELECTION_REQUIRED
        }
}

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
    private val workModeGate = AiLimbsWorkModeGate()

    private var customPromptReceiptVersion: String? = null
    private var workManualReceiptVersion: String? = null
    private val subsystemDiscoveryLedger = AiLimbsSubsystemDiscoveryLedger()

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
        subsystemDiscoveryLedger.resetForContextBoundary()
        workModeGate.reset()
    }

    internal fun beginInteractionCycleInvocation() = subsystemDiscoveryLedger.beginInvocation()

    internal fun endInteractionCycleInvocation() = subsystemDiscoveryLedger.endInvocation()

    internal fun workGateState(): AiLimbsWorkGateState = workModeGate.state()

    internal suspend fun selectWorkMode(mode: AiLimbsWorkMode): AiLimbsWorkGateState {
        val before = workModeGate.state()
        if (mode == AiLimbsWorkMode.WORK && before != AiLimbsWorkGateState.WORK_UNLOCKED) {
            synchronized(stateLock) { workManualReceiptVersion = null }
        }
        return workModeGate.select(mode)
    }

    internal fun subsystemDiscoveryDecision(
        extensionId: String
    ): AiLimbsSubsystemDiscoveryDecision = subsystemDiscoveryLedger.decision(extensionId)

    internal fun claimNormalExecution(): Boolean = workModeGate.claimNormalExecution()

    internal suspend fun missingWorkManual(): AiLimbsMissingReceipt? =
        firstMissing(setOf(AiLimbsRequiredReceipt.WORK_MANUAL))

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
                in workManualReadTools -> {
                    workManualReceiptVersion = version
                    workModeGate.onWorkManualRead()
                }
            }
        }
    }
}
