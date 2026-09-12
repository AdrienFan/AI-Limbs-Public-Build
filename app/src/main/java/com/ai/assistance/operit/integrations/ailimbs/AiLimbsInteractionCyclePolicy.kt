package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import org.json.JSONObject

/** Persisted Host-owned configuration for the AI Limbs interaction cycle. */
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

/** Host policy facade used by the kernel primitive and interaction-cycle runtime. */
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
    val admitted: Boolean = true
)

internal data class AiLimbsInteractionCycleResetResult(
    val generation: Long,
    val appliedImmediately: Boolean,
    val cycleStartedAtMs: Long
)

/**
 * One Host-owned interaction clock shared by all external Bridge providers/transports.
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

    fun beginInvocation(): AiLimbsInteractionCycleLease = synchronized(stateLock) {
        val now = clockMs()
        if (manualResetStartedAtMs != null && activeInvocations > 0) {
            return@synchronized AiLimbsInteractionCycleLease(
                generation = generation + 1L,
                startedNewCycle = false,
                admitted = false
            )
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

    fun endInvocation(): AiLimbsInteractionCycleResetResult? = synchronized(stateLock) {
        check(activeInvocations > 0) { "AI Limbs interaction cycle invocation underflow" }
        activeInvocations -= 1
        val resetStartedAtMs = manualResetStartedAtMs
        if (resetStartedAtMs != null && activeInvocations == 0) {
            cycleStartedAtMs = resetStartedAtMs
            generation += 1L
            expiredPending = false
            manualResetStartedAtMs = null
            AiLimbsInteractionCycleResetResult(generation, true, cycleStartedAtMs)
        } else {
            if (resetStartedAtMs == null) refreshExpiry(clockMs())
            null
        }
    }

    fun resetFromNow(): AiLimbsInteractionCycleResetResult = synchronized(stateLock) {
        val now = clockMs()
        expiredPending = false
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

/** Process-wide Host state for external AI Limbs ingress. */
internal class AiLimbsInteractionCycleRuntimeState(context: Context) {
    private val appContext = context.applicationContext
    val accessGate = AiLimbsAccessGate(appContext)
    private val controller = AiLimbsInteractionCycleController(
        timeoutProvider = AiLimbsInteractionCyclePolicy(appContext)::timeoutMs
    )
    private val stateLock = Any()
    private var currentGeneration = 1L
    private var bootstrapDeliveredGeneration: Long? = null

    fun beginInvocation(): AiLimbsInteractionCycleLease = synchronized(stateLock) {
        val lease = controller.beginInvocation()
        if (!lease.admitted) return@synchronized lease
        if (lease.startedNewCycle) applyContextBoundary(lease.generation)
        accessGate.beginInteractionCycleInvocation()
        currentGeneration = lease.generation
        lease
    }

    fun endInvocation() = synchronized(stateLock) {
        val completedReset = controller.endInvocation()
        accessGate.endInteractionCycleInvocation()
        if (completedReset != null) applyContextBoundary(completedReset.generation)
    }

    fun resetInteractionCycle(): AiLimbsInteractionCycleResetResult = synchronized(stateLock) {
        val reset = controller.resetFromNow()
        if (reset.appliedImmediately) applyContextBoundary(reset.generation)
        reset
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
            if (bootstrapDeliveredGeneration == generation) {
                bootstrapDeliveredGeneration = null
            }
        }
    }

    fun rearmCurrentBootstrap() {
        synchronized(stateLock) {
            bootstrapDeliveredGeneration = null
        }
    }
}

/** Bridge providers never own or reset this Host runtime. */
internal object AiLimbsInteractionCycleRuntime {
    private val stateLock = Any()
    @Volatile private var runtime: AiLimbsInteractionCycleRuntimeState? = null

    fun state(context: Context): AiLimbsInteractionCycleRuntimeState =
        runtime ?: synchronized(stateLock) {
            runtime ?: AiLimbsInteractionCycleRuntimeState(context.applicationContext)
                .also { runtime = it }
        }

    fun reset(context: Context): AiLimbsInteractionCycleResetResult =
        state(context.applicationContext).resetInteractionCycle()
}
