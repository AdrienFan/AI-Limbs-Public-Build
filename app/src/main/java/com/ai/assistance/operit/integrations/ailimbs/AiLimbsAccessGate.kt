package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import org.json.JSONArray
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

    fun exportHandoffState(): JSONObject = synchronized(stateLock) {
        // The old Host dies at the ownership boundary. Any DELIVERING entry therefore becomes
        // delivered rather than being replayed by the new Core authority.
        JSONObject().put("delivered_extensions", JSONArray(phases.keys.toList()))
    }

    fun restoreHandoffState(state: JSONObject) = synchronized(stateLock) {
        phases.clear()
        activeExternalInvocations = 0
        val delivered = state.optJSONArray("delivered_extensions") ?: JSONArray()
        for (index in 0 until delivered.length()) {
            val id = delivered.optString(index).trim().lowercase()
            if (id.isNotBlank()) phases[id] = Phase.DELIVERED
        }
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

    fun exportHandoffState(): JSONObject = synchronized(stateLock) {
        JSONObject()
            .put("work_selected", workSelected)
            .put("non_work_permit", nonWorkPermit)
            .put("work_unlocked", workUnlocked)
    }

    fun restoreHandoffState(state: JSONObject) = synchronized(stateLock) {
        val selected = state.optBoolean("work_selected", false)
        val nonWork = state.optBoolean("non_work_permit", false)
        val unlocked = state.optBoolean("work_unlocked", false)
        check(!(selected && nonWork)) { "Invalid work-mode handoff: WORK and NON_WORK both selected" }
        check(!unlocked || selected) { "Invalid work-mode handoff: unlocked without WORK selection" }
        workSelected = selected
        nonWorkPermit = nonWork
        workUnlocked = unlocked
    }

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
 * Receipt ledger owned by the authoritative AI Limbs policy runtime.
 *
 * All execution sessions share this ledger through the process-wide Interaction Cycle authority.
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
    private var residentHandoffFrozen = false
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
            check(!residentHandoffFrozen) { "Access Gate is frozen for Resident policy handoff" }
            customPromptReceiptVersion = null
            workManualReceiptVersion = null
            subsystemDiscoveryLedger.resetForContextBoundary()
            workModeGate.reset()
        }
    }

    internal fun beginInteractionCycleInvocation() = synchronized(stateLock) {
        check(!residentHandoffFrozen) { "Access Gate is frozen for Resident policy handoff" }
        subsystemDiscoveryLedger.beginInvocation()
    }

    internal fun endInteractionCycleInvocation() = subsystemDiscoveryLedger.endInvocation()

    internal fun workGateState(): AiLimbsWorkGateState = workModeGate.state()

    internal fun snapshot(): JSONObject {
        val receiptState = synchronized(stateLock) {
            Pair(customPromptReceiptVersion != null, workManualReceiptVersion != null)
        }
        return JSONObject()
            .put("work_gate_state", workModeGate.state().name)
            .put("custom_access_prompt_receipt", receiptState.first)
            .put("work_manual_receipt", receiptState.second)
            .put("resident_handoff_frozen", synchronized(stateLock) { residentHandoffFrozen })
    }

    internal fun freezeAndExportHandoffState(): JSONObject = synchronized(stateLock) {
        check(!residentHandoffFrozen) { "Access Gate Resident handoff is already frozen" }
        val receipts = JSONObject()
            .put("custom_prompt_version", customPromptReceiptVersion ?: JSONObject.NULL)
            .put("work_manual_version", workManualReceiptVersion ?: JSONObject.NULL)
        val exported = JSONObject()
            .put("receipts", receipts)
            .put("work_mode", workModeGate.exportHandoffState())
            .put("subsystem_discovery", subsystemDiscoveryLedger.exportHandoffState())
        residentHandoffFrozen = true
        exported
    }

    internal fun cancelResidentHandoffFreeze() = synchronized(stateLock) {
        residentHandoffFrozen = false
    }

    internal fun restoreHandoffState(state: JSONObject) = synchronized(stateLock) {
        val receipts = state.getJSONObject("receipts")
        customPromptReceiptVersion = receipts.opt("custom_prompt_version")
            .takeUnless { it == null || it == JSONObject.NULL }
            ?.toString()?.takeIf { it.isNotBlank() }
        workManualReceiptVersion = receipts.opt("work_manual_version")
            .takeUnless { it == null || it == JSONObject.NULL }
            ?.toString()?.takeIf { it.isNotBlank() }
        workModeGate.restoreHandoffState(state.getJSONObject("work_mode"))
        subsystemDiscoveryLedger.restoreHandoffState(state.getJSONObject("subsystem_discovery"))
        residentHandoffFrozen = false
    }

    internal suspend fun selectWorkMode(mode: AiLimbsWorkMode): AiLimbsWorkGateState =
        synchronized(stateLock) {
            check(!residentHandoffFrozen) { "Access Gate is frozen for Resident policy handoff" }
            val before = workModeGate.state()
            if (mode == AiLimbsWorkMode.WORK && before != AiLimbsWorkGateState.WORK_UNLOCKED) {
                workManualReceiptVersion = null
            }
            workModeGate.select(mode)
        }

    internal fun subsystemDiscoveryDecision(
        extensionId: String
    ): AiLimbsSubsystemDiscoveryDecision = synchronized(stateLock) {
        check(!residentHandoffFrozen) { "Access Gate is frozen for Resident policy handoff" }
        subsystemDiscoveryLedger.decision(extensionId)
    }

    internal fun claimNormalExecution(): Boolean = synchronized(stateLock) {
        check(!residentHandoffFrozen) { "Access Gate is frozen for Resident policy handoff" }
        workModeGate.claimNormalExecution()
    }

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
            check(!residentHandoffFrozen) { "Access Gate is frozen for Resident policy handoff" }
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
