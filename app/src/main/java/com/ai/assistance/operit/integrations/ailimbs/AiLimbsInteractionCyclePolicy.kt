package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.ai.assistance.operit.core.tools.system.resident.ResidentBusinessTakeoverFence
import com.ai.assistance.operit.core.tools.system.resident.ResidentCoreProcessIdentity
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Persisted package configuration consumed by the authoritative AI Limbs interaction cycle. */
class AiLimbsInteractionCyclePolicyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun timeoutMs(): Long = snapshot().timeoutMs

    fun snapshot(): AiLimbsInteractionCyclePolicySnapshot {
        val persisted = persistedTimeoutMsOrNull()
        return AiLimbsInteractionCyclePolicySnapshot(
            timeoutMs = persisted ?: DEFAULT_TIMEOUT_MS,
            defaultTimeoutMs = DEFAULT_TIMEOUT_MS,
            configured = persisted != null,
            source = if (persisted != null) "persisted" else "default"
        )
    }

    fun setTimeoutMs(value: Long): Boolean {
        if (!isValidTimeoutMs(value)) return false
        prefs.edit().putLong(KEY_TIMEOUT_MS, value).apply()
        return true
    }

    private fun persistedTimeoutMsOrNull(): Long? {
        if (!prefs.contains(KEY_TIMEOUT_MS)) return null
        return runCatching { prefs.getLong(KEY_TIMEOUT_MS, -1L) }
            .getOrNull()
            ?.takeIf(::isValidTimeoutMs)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30L * 60L * 1000L
        const val MIN_TIMEOUT_MS = 60L * 1000L
        const val MAX_TIMEOUT_MS = 365L * 24L * 60L * 60L * 1000L

        fun isValidTimeoutMs(value: Long): Boolean = value in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS

        private const val PREFS = "ai_limbs_interaction_cycle_policy"
        private const val KEY_TIMEOUT_MS = "timeout_ms"
    }
}

data class AiLimbsInteractionCyclePolicySnapshot(
    val timeoutMs: Long,
    val defaultTimeoutMs: Long,
    val configured: Boolean,
    val source: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("timeout_ms", timeoutMs)
        .put("default_timeout_ms", defaultTimeoutMs)
        .put("configured", configured)
        .put("source", source)
}

/** Policy facade used by the authoritative interaction-cycle runtime and its management primitive. */
class AiLimbsInteractionCyclePolicy(context: Context) {
    private val store = AiLimbsInteractionCyclePolicyStore(context)

    fun timeoutMs(): Long = store.timeoutMs()

    fun snapshot(): AiLimbsInteractionCyclePolicySnapshot = store.snapshot()

    fun setTimeoutMs(value: Long): AiLimbsInteractionCyclePolicySnapshot {
        require(isValidTimeout(value)) { "Invalid AI Limbs interaction cycle timeout: $value" }
        check(store.setTimeoutMs(value)) { "Could not persist AI Limbs interaction cycle timeout" }
        return store.snapshot()
    }

    companion object {
        fun isValidTimeout(value: Long): Boolean =
            AiLimbsInteractionCyclePolicyStore.isValidTimeoutMs(value)
    }
}

internal data class AiLimbsInteractionCycleLease(
    val generation: Long,
    val startedNewCycle: Boolean,
    val admitted: Boolean = true,
    val blockedReason: String? = null
)

internal data class AiLimbsInteractionCycleResetResult(
    val generation: Long,
    val appliedImmediately: Boolean,
    val cycleStartedAtMs: Long
)

internal data class AiLimbsInteractionCycleCloseResult(
    val generation: Long,
    val nextGeneration: Long,
    val appliedImmediately: Boolean
)

internal data class AiLimbsInteractionCycleBoundaryResult(
    val generation: Long,
    val cycleStartedAtMs: Long,
    val resetApplied: Boolean = false,
    val closeApplied: Boolean = false
)

/**
 * One authoritative interaction clock shared by all external Bridge providers/transports.
 *
 * Timeout is elapsed cycle time, not inactivity time. Expiry is soft: active work continues. A new
 * generation is created only at a later ingress boundary after all active invocations have finished.
 */
internal class AiLimbsInteractionCycleController(
    private val timeoutProvider: () -> Long,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private val stateLock = Any()
    private var cycleStartedAtMs = clockMs()
    private var generation = 1L
    private var activeInvocations = 0
    private var expiredPending = false
    private var manualResetStartedAtMs: Long? = null
    private var manualClosePending = false

    fun beginInvocation(): AiLimbsInteractionCycleLease = synchronized(stateLock) {
        val now = clockMs()
        if ((manualResetStartedAtMs != null || manualClosePending) && activeInvocations > 0) {
            return@synchronized AiLimbsInteractionCycleLease(
                generation = generation + 1L,
                startedNewCycle = false,
                admitted = false
            )
        }
        if (manualClosePending && activeInvocations == 0) {
            cycleStartedAtMs = now
            generation += 1L
            expiredPending = false
            manualClosePending = false
            activeInvocations += 1
            return@synchronized AiLimbsInteractionCycleLease(generation, startedNewCycle = true)
        }
        if (manualResetStartedAtMs == null) refreshExpiry(now)
        val startedNewCycle = expiredPending && activeInvocations == 0
        if (startedNewCycle) {
            cycleStartedAtMs = now
            generation += 1L
            expiredPending = false
        }
        activeInvocations += 1
        AiLimbsInteractionCycleLease(generation, startedNewCycle)
    }

    fun endInvocation(): AiLimbsInteractionCycleBoundaryResult? = synchronized(stateLock) {
        check(activeInvocations > 0) { "AI Limbs interaction cycle invocation underflow" }
        activeInvocations -= 1
        val resetStartedAtMs = manualResetStartedAtMs
        when {
            resetStartedAtMs != null && activeInvocations == 0 -> {
                cycleStartedAtMs = resetStartedAtMs
                generation += 1L
                expiredPending = false
                manualResetStartedAtMs = null
                AiLimbsInteractionCycleBoundaryResult(
                    generation = generation,
                    cycleStartedAtMs = cycleStartedAtMs,
                    resetApplied = true
                )
            }
            manualClosePending && activeInvocations == 0 ->
                AiLimbsInteractionCycleBoundaryResult(
                    generation = generation,
                    cycleStartedAtMs = cycleStartedAtMs,
                    closeApplied = true
                )
            else -> {
                if (resetStartedAtMs == null && !manualClosePending) refreshExpiry(clockMs())
                null
            }
        }
    }

    fun resetFromNow(): AiLimbsInteractionCycleResetResult = synchronized(stateLock) {
        val now = clockMs()
        expiredPending = false
        manualClosePending = false
        if (activeInvocations == 0) {
            cycleStartedAtMs = now
            generation += 1L
            manualResetStartedAtMs = null
            AiLimbsInteractionCycleResetResult(generation, true, now)
        } else {
            manualResetStartedAtMs = now
            AiLimbsInteractionCycleResetResult(generation + 1L, false, now)
        }
    }

    fun closeCurrentCycle(): AiLimbsInteractionCycleCloseResult = synchronized(stateLock) {
        manualResetStartedAtMs = null
        expiredPending = false
        manualClosePending = true
        AiLimbsInteractionCycleCloseResult(
            generation = generation,
            nextGeneration = generation + 1L,
            appliedImmediately = activeInvocations == 0
        )
    }

    fun snapshot(): JSONObject = synchronized(stateLock) {
        JSONObject()
            .put("generation", generation)
            .put("cycle_started_at_ms", cycleStartedAtMs)
            .put("active_invocations", activeInvocations)
            .put("expired_pending", expiredPending)
            .put("manual_reset_pending", manualResetStartedAtMs != null)
            .put("manual_reset_started_at_ms", manualResetStartedAtMs ?: JSONObject.NULL)
            .put("manual_close_pending", manualClosePending)
            .put("closed_awaiting_next_ingress", manualClosePending && activeInvocations == 0)
            .put("timeout_ms", runCatching { timeoutProvider() }
                .getOrDefault(AiLimbsInteractionCyclePolicyStore.DEFAULT_TIMEOUT_MS))
    }

    fun activeInvocationCount(): Int = synchronized(stateLock) { activeInvocations }

    fun exportHandoffState(): JSONObject = synchronized(stateLock) {
        refreshExpiry(clockMs())
        check(activeInvocations == 0) {
            "Interaction Cycle handoff requires zero active invocations; found $activeInvocations"
        }
        check(manualResetStartedAtMs == null) {
            "Interaction Cycle handoff cannot race a pending manual reset"
        }
        JSONObject()
            .put("generation", generation)
            .put("cycle_started_at_ms", cycleStartedAtMs)
            .put("expired_pending", expiredPending)
            .put("manual_close_pending", manualClosePending)
    }

    fun restoreHandoffState(state: JSONObject) = synchronized(stateLock) {
        check(activeInvocations == 0) { "Cannot restore Interaction Cycle with active invocations" }
        val restoredGeneration = state.getLong("generation")
        val restoredStartedAt = state.getLong("cycle_started_at_ms")
        require(restoredGeneration > 0L) { "Invalid restored Interaction Cycle generation" }
        require(restoredStartedAt > 0L) { "Invalid restored Interaction Cycle start time" }
        generation = restoredGeneration
        cycleStartedAtMs = restoredStartedAt
        expiredPending = state.optBoolean("expired_pending", false)
        manualResetStartedAtMs = null
        manualClosePending = state.optBoolean("manual_close_pending", false)
    }

    private fun refreshExpiry(now: Long) {
        if (expiredPending) return
        val timeoutMs = runCatching { timeoutProvider() }
            .getOrDefault(AiLimbsInteractionCyclePolicyStore.DEFAULT_TIMEOUT_MS)
            .takeIf(AiLimbsInteractionCyclePolicyStore::isValidTimeoutMs)
            ?: AiLimbsInteractionCyclePolicyStore.DEFAULT_TIMEOUT_MS
        val elapsed = (now - cycleStartedAtMs).coerceAtLeast(0L)
        if (elapsed >= timeoutMs) expiredPending = true
    }
}

/** Process-wide authoritative state for external AI Limbs ingress. */
internal class AiLimbsInteractionCycleRuntimeState(context: Context) {
    private val appContext = context.applicationContext
    val accessGate = AiLimbsAccessGate(appContext)
    private val controller = AiLimbsInteractionCycleController(
        timeoutProvider = AiLimbsInteractionCyclePolicy(appContext)::timeoutMs
    )
    private val stateLock = Any()
    private var currentGeneration = 1L
    private var bootstrapDeliveredGeneration: Long? = null
    private var residentHandoffFrozen = false

    fun beginInvocation(): AiLimbsInteractionCycleLease = synchronized(stateLock) {
        if (residentHandoffFrozen) {
            return@synchronized AiLimbsInteractionCycleLease(
                generation = currentGeneration,
                startedNewCycle = false,
                admitted = false,
                blockedReason = "RESIDENT_POLICY_HANDOFF_PENDING"
            )
        }
        val lease = controller.beginInvocation()
        if (!lease.admitted) return@synchronized lease
        if (lease.startedNewCycle) applyContextBoundary(lease.generation)
        accessGate.beginInteractionCycleInvocation()
        currentGeneration = lease.generation
        lease
    }

    fun endInvocation() = synchronized(stateLock) {
        val completedBoundary = controller.endInvocation()
        accessGate.endInteractionCycleInvocation()
        if (completedBoundary != null) applyContextBoundary(completedBoundary.generation)
    }

    fun resetInteractionCycle(): AiLimbsInteractionCycleResetResult = synchronized(stateLock) {
        check(!residentHandoffFrozen)
        val reset = controller.resetFromNow()
        if (reset.appliedImmediately) applyContextBoundary(reset.generation)
        reset
    }

    fun closeInteractionCycle(): AiLimbsInteractionCycleCloseResult = synchronized(stateLock) {
        check(!residentHandoffFrozen)
        val close = controller.closeCurrentCycle()
        if (close.appliedImmediately) applyContextBoundary(close.generation)
        close
    }

    private fun applyContextBoundary(generation: Long) {
        accessGate.resetForContextBoundary()
        bootstrapDeliveredGeneration = null
        currentGeneration = generation
    }

    fun currentGeneration(): Long = synchronized(stateLock) { currentGeneration }

    fun claimBootstrap(generation: Long): Boolean = synchronized(stateLock) {
        if (bootstrapDeliveredGeneration == generation) return@synchronized false
        bootstrapDeliveredGeneration = generation
        true
    }

    fun rearmBootstrap(generation: Long) {
        synchronized(stateLock) {
            check(!residentHandoffFrozen)
            if (bootstrapDeliveredGeneration == generation) {
                bootstrapDeliveredGeneration = null
            }
        }
    }

    fun rearmCurrentBootstrap() {
        synchronized(stateLock) {
            check(!residentHandoffFrozen)
            bootstrapDeliveredGeneration = null
        }
    }

    fun snapshot(): JSONObject = synchronized(stateLock) {
        controller.snapshot()
            .put("current_generation", currentGeneration)
            .put("bootstrap_delivered_generation", bootstrapDeliveredGeneration ?: JSONObject.NULL)
            .put("resident_handoff_frozen", residentHandoffFrozen)
            .put("access_gate", accessGate.snapshot())
    }

    fun beginResidentHandoffFreeze() = synchronized(stateLock) {
        check(!residentHandoffFrozen) { "Resident policy handoff is already frozen" }
        // Close ingress first. Existing invocations retain their leases and may drain naturally;
        // every later beginInvocation is rejected with RESIDENT_POLICY_HANDOFF_PENDING.
        residentHandoffFrozen = true
    }

    fun activeInvocationCount(): Int = controller.activeInvocationCount()

    fun exportFrozenHandoffState(): JSONObject = synchronized(stateLock) {
        check(residentHandoffFrozen) { "Resident policy handoff must be frozen before export" }
        check(controller.activeInvocationCount() == 0) {
            "Interaction Cycle handoff export attempted before invocation drain completed"
        }
        JSONObject()
            .put("controller", controller.exportHandoffState())
            .put("current_generation", currentGeneration)
            .put("bootstrap_delivered_generation", bootstrapDeliveredGeneration ?: JSONObject.NULL)
            .put("access_gate", accessGate.freezeAndExportHandoffState())
    }

    fun cancelResidentHandoffFreeze() = synchronized(stateLock) {
        accessGate.cancelResidentHandoffFreeze()
        residentHandoffFrozen = false
    }

    fun restoreHandoffState(state: JSONObject) = synchronized(stateLock) {
        controller.restoreHandoffState(state.getJSONObject("controller"))
        val restoredGeneration = state.getLong("current_generation")
        check(restoredGeneration == state.getJSONObject("controller").getLong("generation")) {
            "Interaction Cycle handoff generation is inconsistent"
        }
        currentGeneration = restoredGeneration
        bootstrapDeliveredGeneration = state.optLong("bootstrap_delivered_generation", -1L)
            .takeIf { it > 0L }
        residentHandoffFrozen = false
        accessGate.restoreHandoffState(state.getJSONObject("access_gate"))
    }
}

/** Bridge providers never own or reset this runtime; BUSINESS ownership decides its process. */
internal object AiLimbsInteractionCycleRuntime {
    private val stateLock = Any()
    @Volatile private var runtime: AiLimbsInteractionCycleRuntimeState? = null

    fun state(context: Context): AiLimbsInteractionCycleRuntimeState =
        runtime ?: synchronized(stateLock) {
            runtime ?: run {
                val appContext = context.applicationContext
                val fence = runCatching { ResidentBusinessTakeoverFence.snapshot(appContext) }
                    .getOrElse { error ->
                        throw IllegalStateException(
                            "Cannot establish AI Limbs policy authority with an invalid Resident takeover fence",
                            error
                        )
                    }
                if (fence != null) {
                    val currentProcessIsBoundCore =
                        fence.optInt("core_pid", -1) == Process.myPid() &&
                            ResidentCoreProcessIdentity.isCurrentProcessCore()
                    if (!currentProcessIsBoundCore) {
                        error(
                            "Resident Core owns the AI Limbs Interaction Cycle/Policy plane; " +
                                "Host-local authority is forbidden for core_pid=${fence.optInt("core_pid", -1)}"
                        )
                    }
                }
                AiLimbsInteractionCycleRuntimeState(appContext).also { runtime = it }
            }
        }

    suspend fun freezeAndExportForResidentHandoff(
        context: Context,
        timeoutMs: Long = RESIDENT_HANDOFF_DRAIN_TIMEOUT_MS
    ): JSONObject {
        val current = state(context.applicationContext)
        current.beginResidentHandoffFreeze()
        try {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (current.activeInvocationCount() > 0 && SystemClock.elapsedRealtime() < deadline) {
                delay(50L)
            }
            check(current.activeInvocationCount() == 0) {
                "Resident Host ingress drain timed out with ${current.activeInvocationCount()} active invocations"
            }
            return current.exportFrozenHandoffState()
        } catch (error: Throwable) {
            current.cancelResidentHandoffFreeze()
            throw error
        }
    }

    fun cancelResidentHandoffFreeze(context: Context) {
        runtime?.cancelResidentHandoffFreeze()
    }

    fun restoreFromResidentHandoff(context: Context, handoffState: JSONObject): AiLimbsInteractionCycleRuntimeState =
        synchronized(stateLock) {
            check(runtime == null) {
                "Resident Core policy authority was initialized before Interaction Cycle handoff restore"
            }
            val restored = AiLimbsInteractionCycleRuntimeState(context.applicationContext)
            restored.restoreHandoffState(handoffState)
            runtime = restored
            restored
        }

    fun reset(context: Context): AiLimbsInteractionCycleResetResult =
        state(context.applicationContext).resetInteractionCycle()

    fun close(context: Context): AiLimbsInteractionCycleCloseResult =
        state(context.applicationContext).closeInteractionCycle()

    private const val RESIDENT_HANDOFF_DRAIN_TIMEOUT_MS = 10_000L
}
