package com.ai.assistance.operit.core.tools.system.resident

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Process
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.privilege.PrivilegeRuntime
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutor
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Host-owned controller for the independent AI Limbs resident process. */
internal object AiLimbsResidentRuntime {
    const val PROCESS_NAME = "ail_resident"
    internal const val GUARDIAN_WATCHDOG_STALE_AFTER_MS = 150_000L
    private const val RESIDENT_PROTOCOL_VERSION = 4

    private const val TAG = "AiLimbsResident"
    private const val PREFS = "ai_limbs_resident_runtime_v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_ON_AUTO_RETRY_BLOCKED = "on_auto_retry_blocked_after_handoff_failure"
    private const val PLUGIN_CENTER_OWNER = "ai_limbs.system.plugin_center"
    private const val MAIN_CLASS =
        "com.ai.assistance.operit.core.tools.system.resident.AiLimbsResidentMain"
    private const val HOST_SERVICE_CLASS =
        "com.ai.assistance.operit.api.chat.AIForegroundService"
    private const val ACTION_RESIDENT_STATE_CHANGED =
        "com.ai.assistance.operit.action.RESIDENT_STATE_CHANGED"
    private const val CORE_FORCE_STOP_TIMEOUT_MS = 5_000L
    private const val PERMISSION_BACKEND_RETURN_TIMEOUT_MS = 12_000L

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val coreRecoveryScheduled = AtomicBoolean(false)

    @Synchronized
    fun initialize(context: Context) {
        if (::prefs.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        PrivilegeRuntime.initialize(app)
    }

    fun scheduleEnsureStarted(context: Context) {
        initialize(context)
        if (!isEnabled()) return
        if (prefs.getBoolean(KEY_ON_AUTO_RETRY_BLOCKED, false)) {
            AppLogger.w(
                TAG,
                "Resident automatic ON retry is blocked after an unsafe handoff failure; " +
                    "explicit user retry is required"
            )
            return
        }
        scope.launch {
            runCatching { ensureStartedIfEnabled(app) }
                .onFailure { recordError("Resident auto-start failed: ${it.message}") }
        }
    }

    fun scheduleCoreCrashRecovery(context: Context) {
        initialize(context)
        if (!isEnabled() || !coreRecoveryScheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                recoverCrashedCoreIfNeeded()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                recordError("Resident Core auto-recovery failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                coreRecoveryScheduled.set(false)
            }
        }
    }

    suspend fun invoke(
        context: Context,
        ownerPluginId: String,
        operation: String,
        args: JSONObject
    ): JSONObject {
        initialize(context)
        require(ownerPluginId == PLUGIN_CENTER_OWNER) {
            "Only Plugin Center may control the AI Limbs resident runtime"
        }
        return when (operation) {
            "status" -> status()
            "core_status" -> ResidentCoreController.status(app)
            "core_probe" -> lifecycleMutex.withLock {
                check(permissionBackendReady()) { "AI Limbs permission backend is not ready" }
                val executor = checkNotNull(debuggerExecutorOrNull()) { "DEBUGGER Shell is unavailable" }
                ResidentCoreController.probe(app, executor)
            }
            "core_stop" -> lifecycleMutex.withLock { ResidentCoreController.stop(app) }
            "set_enabled" -> {
                require(args.has("enabled")) { "enabled is required" }
                setEnabled(args.getBoolean("enabled"))
            }
            "start" -> start()
            "stop" -> stop()
            else -> error("Unknown resident runtime operation: $operation")
        }
    }

    private fun isEnabled(): Boolean =
        prefs.getBoolean(KEY_ENABLED, false)

    internal fun isEnabledForHost(): Boolean =
        ::prefs.isInitialized && prefs.getBoolean(KEY_ENABLED, false)

    internal fun guardianWatchdogSnapshot(
        context: Context,
        staleAfterMs: Long
    ): JSONObject {
        initialize(context)
        require(staleAfterMs > 0L) { "staleAfterMs must be positive" }
        return guardianWatchdogSnapshotLocked(staleAfterMs)
    }

    internal fun scheduleGuardianWatchdogRecovery(
        context: Context,
        staleAfterMs: Long
    ) {
        initialize(context)
        if (!isEnabled()) return
        if (prefs.getBoolean(KEY_ON_AUTO_RETRY_BLOCKED, false)) return
        require(staleAfterMs > 0L) { "staleAfterMs must be positive" }
        scope.launch {
            lifecycleMutex.withLock {
                if (!isEnabled()) return@withLock
                if (prefs.getBoolean(KEY_ON_AUTO_RETRY_BLOCKED, false)) return@withLock

                val before = guardianWatchdogSnapshotLocked(staleAfterMs)
                if (before.optBoolean("healthy", false)) return@withLock

                val executor = if (permissionBackendReady()) debuggerExecutorOrNull() else null
                if (executor == null) {
                    recordError(
                        "Resident Guardian watchdog detected ${before.optString("reason", "unknown")}, " +
                            "but the permission backend is unavailable for safe recovery"
                    )
                    return@withLock
                }

                AppLogger.w(
                    TAG,
                    "Resident Guardian watchdog recovery: reason=${before.optString("reason", "unknown")} " +
                        "pid=${before.optInt("guardian_pid", -1)} " +
                        "heartbeat_age_ms=${before.optLong("heartbeat_age_ms", -1L)}"
                )

                var probe = localProbe()
                val stalePid = probe.pid?.takeIf { probe.running }
                if (stalePid != null) {
                    runCatching { Process.killProcess(stalePid) }
                    for (attempt in 0 until 12) {
                        delay(100L)
                        probe = localProbe()
                        if (!probe.running) break
                    }

                    if (probe.running) {
                        val result = executor.executeCommand(
                            "/system/bin/run-as ${quote(app.packageName)} /system/bin/kill -9 $stalePid"
                        )
                        if (!result.success) {
                            AppLogger.w(
                                TAG,
                                "Guardian watchdog fallback kill failed pid=$stalePid: " +
                                    result.stderr.ifBlank { result.stdout }
                            )
                        }
                    }
                }

                for (attempt in 0 until 20) {
                    probe = localProbe()
                    if (!probe.running && guardianLeaseIsFree()) break
                    delay(100L)
                }

                probe = localProbe()
                if (probe.running || !guardianLeaseIsFree()) {
                    recordError("Resident Guardian watchdog could not retire stale Guardian")
                    return@withLock
                }

                ensureGuardianStartedLocked()
                var after = guardianWatchdogSnapshotLocked(staleAfterMs)
                for (attempt in 0 until 20) {
                    if (after.optBoolean("healthy", false)) break
                    delay(100L)
                    after = guardianWatchdogSnapshotLocked(staleAfterMs)
                }

                if (after.optBoolean("healthy", false)) {
                    AppLogger.i(
                        TAG,
                        "Resident Guardian watchdog recovery succeeded pid=${after.optInt("guardian_pid", -1)}"
                    )
                } else {
                    recordError(
                        "Resident Guardian watchdog recovery did not become healthy: " +
                            after.optString("reason", "unknown")
                    )
                }
            }
        }
    }

    private suspend fun ensureStartedIfEnabled(context: Context): JSONObject {
        initialize(context)
        if (!isEnabled()) return status()
        return start()
    }

    private suspend fun setEnabled(enabled: Boolean): JSONObject = lifecycleMutex.withLock {
        clearOnAutoRetryBlock()
        persistEnabled(enabled)
        notifyHostResidentStateChanged()
        if (enabled) startLocked() else stopLocked()
    }

    private suspend fun start(): JSONObject = lifecycleMutex.withLock {
        clearOnAutoRetryBlock()
        persistEnabled(true)
        notifyHostResidentStateChanged()
        startLocked()
    }

    private suspend fun stop(): JSONObject = lifecycleMutex.withLock {
        clearOnAutoRetryBlock()
        persistEnabled(false)
        notifyHostResidentStateChanged()
        stopLocked()
    }

    /**
     * Automatic level-1 recovery for an unexpected Resident Core process death.
     *
     * Reuse the existing verified OFF cleanup while preserving desired Resident=ON. The cold-restarted
     * Host restores normal plugins as LEGACY_HOST, then scheduleEnsureStarted() performs the existing
     * fully validated Resident ON ownership transaction again.
     */
    private suspend fun recoverCrashedCoreIfNeeded(): JSONObject = lifecycleMutex.withLock {
        if (!isEnabled()) return@withLock status()

        val attachment = ResidentHostRuntimeResolver.resolve(app)
        if (attachment.mode != ResidentHostRuntimeMode.UI_PROXY_BLOCKED) return@withLock status()

        val fence = ResidentBusinessTakeoverFence.snapshot(app) ?: return@withLock status()
        if (fence.optString("state") != "owned") return@withLock status()

        val crashedPid = fence.optInt("core_pid", -1)
        if (crashedPid <= 0) return@withLock status()

        var core = ResidentCoreController.status(app)
        if (core.optBoolean("available", false)) return@withLock status()
        // Host watchdogs now request this independently. A single slow IPC response must not
        // retire a healthy owner: require sustained unavailability and the same takeover session.
        repeat(2) {
            delay(1_000L)
            core = ResidentCoreController.status(app)
            if (core.optBoolean("available", false)) return@withLock status()
        }
        val currentFence = ResidentBusinessTakeoverFence.snapshot(app) ?: return@withLock status()
        if (currentFence.optString("state") != "owned" ||
            currentFence.optString("core_session") != fence.optString("core_session") ||
            currentFence.optInt("core_pid", -1) != crashedPid
        ) return@withLock status()

        // An owned fence plus unreachable Core is recoverable even when the Core process is wedged
        // and still holds bootstrap.lock. stopLocked() owns the verified force-stop path. Do not use
        // the recorded PID as the ownership fact: Android can recycle a stale PID after Core exit.
        AppLogger.w(
            TAG,
            "Resident Core pid=$crashedPid is unreachable " +
                "phase=${core.optString("phase", "unknown")} " +
                "processAlive=${core.optBoolean("process_alive", false)}; " +
                "recycling owner state while preserving desired ON"
        )
        val result = stopLocked()
            .put("auto_core_recovery", true)
            .put("crashed_core_pid", crashedPid)
            .put("desired_state_preserved", isEnabled())

        check(result.optBoolean("off_cleanup_confirmed", false)) {
            "Automatic Core recovery cleanup was not confirmed: ${result.optJSONArray("off_cleanup_errors")}"
        }
        check(result.optBoolean("host_restart_scheduled", false)) {
            "Automatic Core recovery cleanup completed but Host restart was not scheduled"
        }
        check(isEnabled()) { "Automatic Core recovery accidentally changed Resident desired state" }
        result
    }

    /**
     * Base-owned explicit recovery path for a Host that was forced into UI_PROXY_BLOCKED.
     * This deliberately bypasses Plugin Center authorization because Plugin Kernel may be unavailable.
     * Safety remains identical to ordinary OFF: Core/owner loss and the plugin_kernel lease are proven
     * before stale takeover state is cleared. No plugin store or plugin data is modified.
     */
    internal suspend fun recoverBlockedHost(context: Context): JSONObject {
        initialize(context)
        return lifecycleMutex.withLock {
            val attachment = ResidentHostRuntimeResolver.resolve(app)
            check(attachment.mode == ResidentHostRuntimeMode.UI_PROXY_BLOCKED) {
                "Resident recovery is only valid for UI_PROXY_BLOCKED, current=${attachment.mode}"
            }
            persistEnabled(false)
            notifyHostResidentStateChanged()
            stopLocked()
                .put("explicit_recovery", true)
                .put("recovery_reason", attachment.reason)
                .put("recovery_fence_state", attachment.fenceState ?: JSONObject.NULL)
        }
    }

    private suspend fun startLocked(): JSONObject {
        val guardianStatus = ensureGuardianStartedLocked()
        val guardian = localProbe()
        if (!guardian.running) return guardianStatus

        val core = ResidentCoreController.status(app)
        if (core.optBoolean("available", false) && core.optBoolean("business_attached", false)) {
            clearError()
            return status(guardian)
        }

        val hostKernel = PluginPlatformKernel.lifecycleSnapshot()
        val hostRole = hostKernel.optString("runtime_role", "legacy_host")
        val fence = ResidentBusinessTakeoverFence.snapshot(app)
        if (hostRole == "ui_proxy") {
            if (fence?.optString("state") == "failed") {
                recordError("Resident Core takeover previously failed; explicit OFF recovery is required")
            } else if (!core.optBoolean("available", false)) {
                recordError("Resident UI proxy has no live Core owner; explicit OFF recovery is required")
            }
            return status(guardian)
        }

        if (fence != null) {
            recordError("Resident takeover fence is ${fence.optString("state", "unknown")}; explicit OFF recovery is required")
            return status(guardian)
        }
        check(hostRole == "legacy_host" && hostKernel.optBoolean("started", false) &&
            hostKernel.optBoolean("owner_lease_held", false)) {
            "Resident ON requires the active LEGACY_HOST Plugin Kernel owner"
        }
        check(permissionBackendReady()) { "AI Limbs permission backend is not ready" }
        val executor = checkNotNull(debuggerExecutorOrNull()) { "DEBUGGER Shell is unavailable" }

        // Success never returns: the old Host process exits and Core acquires plugin_kernel.
        return try {
            ResidentPluginKernelHandoff.execute(app, executor)
        } catch (error: Throwable) {
            recordError("Resident business takeover failed: ${error.message ?: error.javaClass.simpleName}")
            status(localProbe())
        }
    }

    private suspend fun ensureGuardianStartedLocked(): JSONObject {
        var existing = localProbe()
        if (guardianMatchesInstalledBuild(existing)) {
            clearError()
            return status(existing)
        }

        if (existing.running) {
            AppLogger.i(
                TAG,
                "Replacing stale Resident pid=${existing.pid} protocol=${existing.protocolVersion} " +
                    "build=${existing.buildCode} source=${existing.sourceApk}"
            )
            existing.pid?.let { stalePid ->
                runCatching { Process.killProcess(stalePid) }
                for (attempt in 0 until 12) {
                    delay(100L)
                    if (!ResidentProcessLiveness.exists(stalePid)) return@let
                }
            }
            existing = localProbe()
            if (existing.running && existing.pid != null && permissionBackendReady()) {
                debuggerExecutorOrNull()?.let { executor ->
                    val fallback = "/system/bin/run-as ${quote(app.packageName)} " +
                        "/system/bin/kill -9 ${existing.pid}"
                    executor.executeCommand(fallback)
                    for (attempt in 0 until 12) {
                        delay(100L)
                        existing = localProbe()
                        if (!existing.running) return@let
                    }
                }
            }
            if (existing.running) {
                recordError(
                    "旧版或旧安装 Resident 仍在运行，无法安全切换到当前 build=${BuildConfig.VERSION_CODE}"
                )
                return status(existing)
            }
        }

        if (!permissionBackendReady()) {
            recordError("AI Limbs 权限服务未连接或未选择为当前权限后端")
            return status(existing)
        }

        val executor = debuggerExecutorOrNull()
        if (executor == null) {
            recordError("DEBUGGER Shell 当前不可用")
            return status(existing)
        }

        // guardian.lock is the process-ownership fact. resident.meta is diagnostic
        // metadata and may be missing after an interrupted update.
        if (!guardianLeaseIsFree()) {
            delay(250L)
            existing = localProbe()
            if (guardianMatchesInstalledBuild(existing)) {
                clearError()
                return status(existing)
            }
            if (!retireUntrackedGuardianLocked(executor)) {
                recordError("Resident guardian lease is held but no trusted current Guardian could be recovered")
                return status(localProbe())
            }
        }

        check(stateDir().mkdirs() || stateDir().isDirectory) {
            "Could not prepare resident state directory"
        }
        check(guardianLeaseIsFree()) {
            "Resident guardian lease is still held before launch"
        }
        metaFile().delete()
        stopRequestFile().delete()

        val result = executor.executeCommand(launchCommand())
        if (!result.success) {
            recordError(
                result.stderr.ifBlank {
                    result.stdout.ifBlank { "Resident launch command failed: exit=${result.exitCode}" }
                }
            )
            return status(localProbe())
        }

        for (attempt in 0 until 20) {
            delay(150L)
            val probe = localProbe()
            if (probe.running) {
                clearError()
                AppLogger.i(TAG, "Resident started: pid=${probe.pid}, uid=${probe.uid}")
                return status(probe)
            }
        }

        val diagnostic = executor.executeCommand(
            "tail -n 40 ${quote(shellLogPath())} 2>/dev/null || true"
        )
        // The launch command can return before resident.meta / process probing catches up.
        // Re-probe once after reading diagnostics so a late but valid READY handshake is
        // never persisted as KEY_LAST_ERROR.
        val lateProbe = localProbe()
        if (guardianMatchesInstalledBuild(lateProbe)) {
            clearError()
            AppLogger.i(TAG, "Resident became ready after launch probe window: pid=${lateProbe.pid}, uid=${lateProbe.uid}")
            return status(lateProbe)
        }
        val stdout = diagnostic.stdout.trim()
        val stderr = diagnostic.stderr.trim()
        val emittedReady = stdout.lineSequence().any { it.trim().startsWith("AIL_RESIDENT_READY ") }
        recordError(
            when {
                stderr.isNotEmpty() -> stderr
                emittedReady -> "Resident emitted READY but process liveness was not confirmed after launch"
                stdout.isNotEmpty() -> stdout
                else -> "Resident process did not become ready"
            }
        )
        return status(lateProbe)
    }

    private suspend fun stopLocked(): JSONObject = withContext(NonCancellable) {
        val hostKernelBefore = PluginPlatformKernel.lifecycleSnapshot()
        val hostRoleBefore = hostKernelBefore.optString("runtime_role", "legacy_host")
        val coreBefore = ResidentCoreController.status(app)
        val coreWasAvailable = coreBefore.optBoolean("available", false)
        val coreWasBusinessOwner = coreWasAvailable && coreBefore.optBoolean("business_attached", false)
        val fenceBefore = ResidentBusinessTakeoverFence.snapshot(app)
        val policyBefore = ResidentPolicyStateHandoff.snapshot(app)
        // Both repair paths remove stale ownership records. Preserve bounded incident evidence
        // first so the next Host startup cannot erase the cause.
        if (fenceBefore != null && !coreWasAvailable) {
            ResidentFailureDiagnostics.captureOwnerLoss(app, coreBefore, fenceBefore)
        }
        val backendBefore = permissionBackendOwnershipSnapshot()
        val backendOwnerBefore = backendBefore?.optString("runtime_owner")
        val needsBackendReturn =
            hostRoleBefore == "ui_proxy" ||
                coreWasBusinessOwner ||
                fenceBefore != null ||
                policyBefore != null ||
                backendOwnerBefore == "handoff_prepared" ||
                backendOwnerBefore == "resident_core" ||
                backendOwnerBefore == "returning_to_host"
        val failures = mutableListOf<String>()
        val degraded = mutableListOf<String>()

        val drainResult =
            if (coreWasBusinessOwner) {
                try {
                    ResidentCoreController.quiesceBusiness(app).also { drain ->
                        if (!drain.optBoolean("drain_success", false)) {
                            degraded += "Core Dispatcher drain required forced cancellation"
                        }
                    }
                } catch (error: Exception) {
                    AppLogger.w(TAG, "Resident Core quiesce/drain failed; OFF will force shutdown", error)
                    degraded += "Core quiesce failed: ${error.message ?: error.javaClass.simpleName}"
                    null
                }
            } else {
                JSONObject().put("drain_required", false).put("drain_success", true)
            }

        var coreStopResult: JSONObject? = null
        var forcedCoreStop = false
        try {
            coreStopResult = ResidentCoreController.stop(app)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.w(TAG, "Resident Core graceful stop was not confirmed; forcing Core process exit", error)
            degraded += "Core graceful stop failed: ${error.message ?: error.javaClass.simpleName}"
            val coreLeaseHeld = !ResidentCoreController.bootstrapLeaseIsFree(app)
            val pid =
                coreBefore.optInt("pid", -1).takeIf { it > 0 }
                    ?: fenceBefore?.optInt("core_pid", -1)?.takeIf { it > 0 }
            if (coreLeaseHeld) {
                if (
                    pid != null &&
                    pid != Process.myPid() &&
                    ResidentProcessLiveness.matchesResidentCore(pid, Process.myUid())
                ) {
                    val killError =
                        runCatching { Process.killProcess(pid) }.exceptionOrNull()
                    if (killError != null) {
                        failures +=
                            "Verified Core process kill failed: " +
                                (killError.message ?: killError.javaClass.simpleName)
                    } else {
                        val deadline =
                            android.os.SystemClock.elapsedRealtime() + CORE_FORCE_STOP_TIMEOUT_MS
                        while (
                            !ResidentCoreController.bootstrapLeaseIsFree(app) &&
                            android.os.SystemClock.elapsedRealtime() < deadline
                        ) {
                            delay(50L)
                        }
                        forcedCoreStop = ResidentCoreController.bootstrapLeaseIsFree(app)
                        if (forcedCoreStop) {
                            degraded += "Core process required verified forced termination"
                        } else {
                            failures += "Core bootstrap lease remained after forced OFF"
                        }
                    }
                } else {
                    failures +=
                        "Core bootstrap lease is held but no verified Resident Core PID is available for forced OFF"
                }
            } else {
                degraded += "Core owner lease was already released before forced OFF"
            }
        }

        val coreAfter = ResidentCoreController.status(app)
        if (coreAfter.optBoolean("available", false) || coreAfter.optString("phase") != "stopped") {
            failures += "Resident Core ownership release is not proven"
        }
        if (!ResidentCoreController.bootstrapLeaseIsFree(app)) {
            failures += "Resident Core bootstrap lease is still held"
        }

        val backendReturn =
            if (needsBackendReturn) awaitPermissionBackendReturnedToHost() else permissionBackendOwnershipSnapshot()
        if (needsBackendReturn && backendReturn == null) {
            degraded +=
                "Permission backend was lost or did not reattach within the OFF recovery window; " +
                    "ordinary Host mode will resume without privileged backend ownership"
        }
        val cleanBackendRelease =
            coreStopResult?.optBoolean("backend_release_confirmed", false) == true
        val cleanContinuousResourceRelease =
            coreStopResult?.optBoolean("continuous_resource_release_confirmed", false) == true
        if (coreWasAvailable && coreStopResult != null && !cleanContinuousResourceRelease) {
            degraded += "Core CPU/network resources were not cleanly released before process exit"
        }
        if (forcedCoreStop) {
            degraded +=
                "Core CPU/network token cleanup relied on process death; device-level release must be verified"
        }
        val backendReturnMode = when {
            !needsBackendReturn -> "not_required"
            backendReturn == null -> "backend_lost_or_unreachable"
            cleanBackendRelease -> "clean_release"
            forcedCoreStop || coreWasAvailable -> "recovered_after_core_exit"
            else -> "recovered_after_prior_core_loss"
        }
        if (needsBackendReturn && backendReturn != null && !cleanBackendRelease) {
            degraded += "Permission backend returned through Core-lifetime recovery rather than clean release"
        }

        var probe = localProbe()
        val pid = probe.pid
        if (probe.running && pid != null) {
            runCatching {
                check(stateDir().mkdirs() || stateDir().isDirectory)
                stopRequestFile().writeText(System.currentTimeMillis().toString())
            }
            for (attempt in 0 until 40) {
                delay(100L)
                probe = localProbe()
                if (!probe.running) break
            }
        }
        probe = localProbe()
        if (probe.running && probe.pid != null) {
            runCatching { Process.killProcess(probe.pid) }
            for (attempt in 0 until 12) {
                delay(100L)
                probe = localProbe()
                if (!probe.running) break
            }
        }
        val executor = if (permissionBackendReady()) debuggerExecutorOrNull() else null
        probe = localProbe()
        if (probe.running && probe.pid != null && executor != null) {
            val fallback = "/system/bin/run-as ${quote(app.packageName)} /system/bin/kill -9 ${probe.pid}"
            executor.executeCommand(fallback)
            for (attempt in 0 until 8) {
                delay(100L)
                probe = localProbe()
                if (!probe.running) break
            }
        }
        probe = localProbe()
        if (probe.running) failures += "Resident Guardian is still alive after stop request"
        else {
            metaFile().delete()
            stopRequestFile().delete()
        }

        val fenceAfterCoreStop = ResidentBusinessTakeoverFence.snapshot(app)
        val policyAfterCoreStop = ResidentPolicyStateHandoff.snapshot(app)
        val needsOwnerRecovery =
            hostRoleBefore == "ui_proxy" || fenceAfterCoreStop != null || policyAfterCoreStop != null
        val ownerLeaseFree = !needsOwnerRecovery || pluginKernelLeaseIsFree()
        if (!ownerLeaseFree) failures += "plugin_kernel owner lease is still held after Resident OFF"

        if (
            failures.isEmpty() && ownerLeaseFree &&
            (fenceAfterCoreStop != null || policyAfterCoreStop != null)
        ) {
            runCatching { ResidentPolicyStateHandoff.clearAfterVerifiedOwnerLoss(app) }
                .onFailure { failures += "Stale policy handoff recovery failed: ${it.message}" }
            runCatching { ResidentBusinessTakeoverFence.clearAfterVerifiedOwnerLoss(app) }
                .onFailure { failures += "Stale takeover fence recovery failed: ${it.message}" }
        }
        if (ResidentBusinessTakeoverFence.snapshot(app) != null) failures += "Resident takeover fence remains after OFF"
        if (ResidentPolicyStateHandoff.snapshot(app) != null) failures += "Resident policy handoff remains after OFF"
        if (!waitForHostTransitionWakeRelease()) {
            failures += "Host transition PARTIAL_WAKE_LOCK did not release"
        }

        if (failures.isEmpty()) {
            clearError()
            AppLogger.i(TAG, "Resident OFF cleanup confirmed mode=$backendReturnMode degraded=${degraded.isNotEmpty()}")
        } else {
            recordError(failures.distinct().joinToString("; "))
        }

        val result = status(probe)
            .put("drain_result", drainResult ?: JSONObject.NULL)
            .put("core_stop_result", coreStopResult ?: JSONObject.NULL)
            .put("forced_core_stop", forcedCoreStop)
            .put("permission_backend_return", backendReturn ?: JSONObject.NULL)
            .put("permission_backend_return_mode", backendReturnMode)
            .put("off_cleanup_confirmed", failures.isEmpty())
            .put("off_cleanup_errors", org.json.JSONArray(failures.distinct()))
            .put("off_degraded", degraded.isNotEmpty())
            .put("off_degraded_reasons", org.json.JSONArray(degraded.distinct()))

        if (failures.isEmpty() && hostRoleBefore == "ui_proxy") {
            val restart = scheduleHostRoleRestart()
            result.put("host_restart_scheduled", restart)
            if (!restart) recordError("Resident OFF completed, but Host role restart could not be scheduled")
        } else {
            result.put("host_restart_scheduled", false)
        }
        result
    }

    private suspend fun status(probe: LocalProbe = localProbe()): JSONObject =
        withContext(Dispatchers.IO) {
            val guardian = readKeyValues(guardianFile())
            val hostShell = readKeyValues(hostShellStateFile())
            val hostShellPid = hostShell["pid"]?.toIntOrNull()
            val hostShellAlive =
                hostShell["state"] == "running" &&
                    hostShellPid != null &&
                    hostShellPid > 0 &&
                    ResidentProcessLiveness.exists(hostShellPid)
            val hostWake = readKeyValues(hostWakeLockStateFile())
            val hostWakePid = hostWake["pid"]?.toIntOrNull()
            val hostWakeHeld =
                hostWake["held"] == "true" &&
                    hostWakePid != null &&
                    hostWakePid > 0 &&
                    ResidentProcessLiveness.exists(hostWakePid)
            val core = ResidentCoreController.status(app)
            val fence = ResidentBusinessTakeoverFence.snapshot(app)
            val policyHandoff = ResidentPolicyStateHandoff.snapshot(app)
            val hostKernel = PluginPlatformKernel.lifecycleSnapshot()
            val hostRole = hostKernel.optString("runtime_role", "legacy_host")
            val enabled = isEnabled()
            val coreAvailable = core.optBoolean("available", false)
            val coreProcessAlive = core.optBoolean("process_alive", coreAvailable)
            val coreConsistent = coreAvailable && core.optBoolean("consistent", false)
            val coreOwned =
                coreConsistent && core.optBoolean("business_attached", false)
            val fenceState = fence?.optString("state")
            val permissionBackendOwnership = permissionBackendOwnershipSnapshot()
            val permissionOwner = permissionBackendOwnership?.optString("runtime_owner")
            val uiProxy = core.optJSONObject("ui_proxy")
            val hostAttachComplete =
                coreOwned &&
                    uiProxy != null &&
                    uiProxy.optBoolean("server_running", false) &&
                    uiProxy.optBoolean("host_attached", false) &&
                    uiProxy.optLong("host_generation", 0L) > 0L
            val continuousResources = core.optJSONObject("continuous_resources")
            val cpuWakeTokenHeld =
                continuousResources?.optBoolean("cpu_wake_token_held", false) == true
            val networkCallbackRegistered =
                continuousResources?.optBoolean("network_callback_registered", false) == true
            val networkAvailable =
                continuousResources?.optBoolean("network_available", false) == true
            val networkValidated =
                continuousResources?.optBoolean("network_validated", false) == true
            val rawLastError = prefs.getString(KEY_LAST_ERROR, null)
            val runtimeFullyHealthy =
                enabled &&
                    guardianMatchesInstalledBuild(probe) &&
                    coreOwned &&
                    hostAttachComplete &&
                    fenceState != "failed" &&
                    core.optString("business_phase") != "failed" &&
                    core.optString("business_phase") != "quiesce_failed"
            val lastError =
                if (runtimeFullyHealthy) {
                    if (rawLastError != null) clearError()
                    null
                } else {
                    rawLastError
                }
            val offFactsClean =
                !probe.running &&
                    !coreAvailable &&
                    fence == null &&
                    policyHandoff == null &&
                    (hostRole != "ui_proxy" || permissionOwner == "android_host")
            val runtimePhase = when {
                !enabled && lastError != null -> "off_failed"
                !enabled && offFactsClean -> "off"
                !enabled -> "stopping"
                lastError != null ||
                    (coreAvailable && !coreConsistent) ||
                    (fenceState == "owned" && !coreAvailable) ||
                    fenceState == "failed" ||
                    core.optString("business_phase") == "failed" ||
                    core.optString("business_phase") == "quiesce_failed" -> "failed"
                coreOwned && !hostAttachComplete -> "host_attach_pending"
                coreOwned -> "on"
                fenceState == "armed" || core.optString("runtime_owner") == "handoff_pending" -> "handoff"
                probe.running -> "starting"
                else -> "starting_guardian"
            }
            val runtimeOwner = when {
                coreOwned -> "resident_core"
                fenceState == "armed" -> "handoff_pending"
                hostRole == "legacy_host" && hostKernel.optBoolean("started", false) -> "android_host"
                else -> "unavailable"
            }
            JSONObject()
                .put("available", true)
                .put("api", 3)
                .put("mode", "lockscreen_continuous")
                .put("runtime_phase", runtimePhase)
                .put("runtime_owner", runtimeOwner)
                .put("host_role", hostRole)
                .put("host_kernel", hostKernel)
                .put("core", core)
                .put("takeover_fence", fence ?: JSONObject.NULL)
                .put("policy_handoff", policyHandoff ?: JSONObject.NULL)
                .put("plugins_migrated", core.optBoolean("plugin_services_prepared", false))
                .put("enabled", enabled)
                .put("running", probe.running)
                .put("guardian_process_alive", probe.running)
                .put("guardian_pid", probe.pid ?: JSONObject.NULL)
                .put("guardian_session_id", probe.sessionId ?: JSONObject.NULL)
                .put("core_running", coreAvailable)
                .put("core_reachable", coreAvailable)
                .put("core_consistent", coreConsistent)
                .put("core_consistency_error",
                    if (core.has("consistency_error")) core.opt("consistency_error") else JSONObject.NULL)
                .put("host_attach_complete", hostAttachComplete)
                .put("host_attach", uiProxy ?: JSONObject.NULL)
                .put("pid", probe.pid ?: JSONObject.NULL)
                .put("uid", probe.uid ?: JSONObject.NULL)
                .put("ppid", probe.ppid ?: JSONObject.NULL)
                .put("protocol_version", probe.protocolVersion ?: JSONObject.NULL)
                .put("guardian_build_code", probe.buildCode ?: JSONObject.NULL)
                .put("guardian_source_apk", probe.sourceApk ?: JSONObject.NULL)
                .put("guardian_build_matches", guardianMatchesInstalledBuild(probe))
                .put("oom_score_adj_diagnostic_only", probe.oomScoreAdj ?: JSONObject.NULL)
                .put("cgroup", probe.cgroup ?: JSONObject.NULL)
                .put("session_id", probe.sessionId ?: JSONObject.NULL)
                .put("started_wall_ms", probe.startedWallMs ?: JSONObject.NULL)
                .put("last_heartbeat", lastHeartbeat() ?: JSONObject.NULL)
                // Process liveness, token ownership, Android network state and real continuous work
                // are deliberately separate facts. None of the first three prove freezer/LEV success.
                .put("continuous_work", false)
                .put("continuous_work_state", "unverified")
                .put("process_alive", coreProcessAlive)
                .put("core_process_alive", coreProcessAlive)
                .put("cpu_wake_owner",
                    continuousResources?.optString("owner", "none") ?: "none")
                .put("cpu_wake_token_held", cpuWakeTokenHeld)
                .put("cpu_wake_effective", JSONObject.NULL)
                .put("cpu_wake_evidence",
                    continuousResources?.optString("cpu_wake_evidence", "none") ?: "none")
                .put("network_owner",
                    continuousResources?.optString("owner", "none") ?: "none")
                .put("network_callback_registered", networkCallbackRegistered)
                .put("network_available", networkAvailable)
                .put("network_internet_capability",
                    continuousResources?.optBoolean("network_internet_capability", false) == true)
                .put("network_validated", networkValidated)
                .put("network_real_io_verified", false)
                .put("network_effective", JSONObject.NULL)
                .put("continuous_resources", continuousResources ?: JSONObject.NULL)
                .put("host_shell_alive", hostShellAlive)
                .put("host_pid", hostShellPid ?: JSONObject.NULL)
                .put("host_shell_role", hostShell["role"] ?: JSONObject.NULL)
                .put("host_shell_detail", hostShell["detail"] ?: JSONObject.NULL)
                // LEGACY_HOST may transiently hold this during handoff only. UI_PROXY must not.
                .put("host_transition_wake_lock_held", hostWakeHeld)
                .put("host_transition_wake_pid", hostWakePid ?: JSONObject.NULL)
                .put("host_guardian", guardian["host_guardian"] ?: JSONObject.NULL)
                .put("last_host_touch_wall_ms", guardian["last_host_touch_wall_ms"]?.toLongOrNull() ?: JSONObject.NULL)
                .put("last_host_touch_ok", guardian["last_host_touch_ok"]?.toBooleanStrictOrNull() ?: JSONObject.NULL)
                .put("core_recovery_pid", guardian["core_recovery_pid"]?.toIntOrNull()?.takeIf { it > 0 } ?: JSONObject.NULL)
                .put("last_core_recovery_wall_ms", guardian["last_core_recovery_wall_ms"]?.toLongOrNull() ?: JSONObject.NULL)
                .put("last_core_recovery_exit", guardian["last_core_recovery_exit"]?.toIntOrNull() ?: JSONObject.NULL)
                .put("last_core_recovery_ok", guardian["last_core_recovery_ok"]?.toBooleanStrictOrNull() ?: JSONObject.NULL)
                .put("last_core_recovery_detail", guardian["last_core_recovery_detail"] ?: JSONObject.NULL)
                .put("guardian_state", guardian["state"] ?: JSONObject.NULL)
                .put("guardian_watchdog", guardianWatchdogSnapshotLocked(GUARDIAN_WATCHDOG_STALE_AFTER_MS))
                .put("backend", if (PrivilegeRuntime.isSelected()) "ai_limbs" else "shizuku")
                .put("backend_ready", permissionBackendReady())
                .put("permission_backend_ownership", permissionBackendOwnership ?: JSONObject.NULL)
                .put("last_error", lastError ?: JSONObject.NULL)
        }

    private fun permissionBackendReady(): Boolean =
        PrivilegeRuntime.isSelected() &&
            PrivilegeRuntime.connection()?.let { it.uid == 0 || it.uid == 2000 } == true

    private fun debuggerExecutorOrNull(): ShellExecutor? {
        val executor = ShellExecutorFactory.getExecutor(app, AndroidPermissionLevel.DEBUGGER)
        val permission = executor.hasPermission()
        return if (executor.isAvailable() && permission.granted) executor else null
    }

    private fun launchCommand(): String {
        val inner =
            "export CLASSPATH=${quote(app.applicationInfo.sourceDir)}; " +
                "exec /system/bin/app_process /system/bin --nice-name=$PROCESS_NAME " +
                "$MAIN_CLASS ${quote(stateDir().absolutePath)} ${quote(app.packageName)} " +
                "${quote(BuildConfig.VERSION_CODE.toString())} ${quote(app.applicationInfo.sourceDir)}"
        val asApp =
            "exec /system/bin/run-as ${quote(app.packageName)} /system/bin/sh -c ${quote(inner)}"
        return "trap '' HUP; rm -f ${quote(shellLogPath())}; " +
            "/system/bin/setsid /system/bin/sh -c ${quote(asApp)} " +
            "> ${quote(shellLogPath())} 2>&1 < /dev/null &"
    }

    private fun guardianWatchdogSnapshotLocked(staleAfterMs: Long): JSONObject {
        val enabled = isEnabled()
        if (!enabled) {
            return JSONObject()
                .put("enabled", false)
                .put("healthy", true)
                .put("reason", "resident_disabled")
        }

        val probe = localProbe()
        val heartbeat = parseHeartbeat(lastHeartbeat())
        val heartbeatElapsed = heartbeat["elapsed_ms"]?.toLongOrNull()
        val heartbeatPid = heartbeat["pid"]?.toIntOrNull()
        val heartbeatSession = heartbeat["session"]
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val heartbeatAge = heartbeatElapsed?.let { value ->
            (nowElapsed - value).takeIf { it >= 0L }
        }
        val buildMatches = guardianMatchesInstalledBuild(probe)
        val identityMatches =
            probe.running &&
                heartbeatPid != null &&
                heartbeatPid == probe.pid &&
                heartbeatSession != null &&
                heartbeatSession == probe.sessionId
        val heartbeatFresh = heartbeatAge != null && heartbeatAge <= staleAfterMs

        val reason = when {
            !probe.running -> "guardian_process_missing"
            !buildMatches -> "guardian_build_mismatch"
            heartbeatElapsed == null -> "guardian_heartbeat_missing"
            heartbeatAge == null -> "guardian_heartbeat_clock_invalid"
            !identityMatches -> "guardian_heartbeat_identity_mismatch"
            !heartbeatFresh -> "guardian_heartbeat_stale"
            else -> "healthy"
        }
        return JSONObject()
            .put("enabled", true)
            .put("healthy", reason == "healthy")
            .put("reason", reason)
            .put("guardian_pid", probe.pid ?: JSONObject.NULL)
            .put("guardian_session_id", probe.sessionId ?: JSONObject.NULL)
            .put("guardian_build_matches", buildMatches)
            .put("heartbeat_pid", heartbeatPid ?: JSONObject.NULL)
            .put("heartbeat_session_id", heartbeatSession ?: JSONObject.NULL)
            .put("heartbeat_age_ms", heartbeatAge ?: JSONObject.NULL)
            .put("stale_after_ms", staleAfterMs)
    }

    private fun parseHeartbeat(line: String?): Map<String, String> =
        line.orEmpty()
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull { token ->
                val index = token.indexOf('=')
                if (index <= 0) null else token.substring(0, index) to token.substring(index + 1)
            }
            .toMap()

    private fun localProbe(): LocalProbe {
        val meta = readKeyValues(metaFile())
        val pid = meta["pid"]?.toIntOrNull() ?: return LocalProbe()
        val expectedUid = meta["uid"]?.toIntOrNull() ?: Process.myUid()
        if (expectedUid != Process.myUid()) return LocalProbe()

        // guardian.lock is the process-ownership fact. The detached Guardian runs
        // in runas_app while Host runs in untrusted_app, so signal/proc checks are
        // only secondary identity diagnostics and must tolerate SELinux EPERM.
        if (guardianLeaseIsFree() || !ResidentProcessLiveness.exists(pid)) return LocalProbe()

        val cmdline = readProcText(pid, "cmdline")?.replace('\u0000', ' ')?.trim().orEmpty()
        val comm = readProcText(pid, "comm")?.trim().orEmpty()
        // SELinux may hide cmdline across runas_app -> untrusted_app while still exposing
        // the generic app_process comm value (usually "main"). A generic comm is not
        // negative identity evidence; the guardian lease + app-private meta + UID/liveness
        // remain the ownership facts. Reject only when cmdline is actually visible and wrong.
        if (cmdline.isNotEmpty() &&
            PROCESS_NAME !in cmdline && MAIN_CLASS !in cmdline) {
            return LocalProbe()
        }

        val status = parseProcStatus(readProcText(pid, "status"))
        val uid =
            status["Uid"]?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull() ?: expectedUid
        if (uid != Process.myUid()) return LocalProbe()

        return LocalProbe(
            running = true,
            pid = pid,
            uid = uid,
            protocolVersion = meta["protocol_version"]?.toIntOrNull() ?: 1,
            buildCode = meta["build_code"]?.toIntOrNull(),
            sourceApk = meta["source_apk"],
            ppid = status["PPid"]?.trim()?.toIntOrNull(),
            oomScoreAdj = readProcText(pid, "oom_score_adj")?.trim()?.toIntOrNull(),
            cgroup = readProcText(pid, "cgroup")?.trim()?.replace('\n', ';'),
            sessionId = meta["session_id"],
            startedWallMs = meta["started_wall_ms"]?.toLongOrNull()
        )
    }

    private fun guardianMatchesInstalledBuild(probe: LocalProbe): Boolean =
        probe.running &&
            probe.protocolVersion == RESIDENT_PROTOCOL_VERSION &&
            probe.buildCode == BuildConfig.VERSION_CODE &&
            probe.sourceApk == app.applicationInfo.sourceDir

    private fun guardianLeaseIsFree(): Boolean {
        val lease = ResidentRuntimeLease.tryAcquire(stateDir(), "guardian") ?: return false
        lease.close()
        return true
    }

    private suspend fun retireUntrackedGuardianLocked(executor: ShellExecutor): Boolean {
        if (guardianLeaseIsFree()) return true

        val processList = executor.executeCommand("/system/bin/ps -A -o PID,UID,NAME")
        if (!processList.success) {
            AppLogger.w(
                TAG,
                "Could not enumerate untracked Resident Guardian: " +
                    processList.stderr.ifBlank { processList.stdout }
            )
            return false
        }

        val stalePids = processList.stdout.lineSequence().mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 3) return@mapNotNull null
            val pid = fields[0].toIntOrNull() ?: return@mapNotNull null
            val uid = fields[1].toIntOrNull() ?: return@mapNotNull null
            val name = fields.drop(2).joinToString(" ")
            pid.takeIf { uid == Process.myUid() && name == PROCESS_NAME }
        }.distinct().toList()

        if (stalePids.isEmpty()) {
            AppLogger.w(TAG, "Guardian lease is held but no same-UID $PROCESS_NAME process is visible")
            return false
        }

        AppLogger.i(TAG, "Retiring untracked Resident Guardian pid(s)=$stalePids")
        stalePids.forEach { stalePid ->
            val killCommand =
                "/system/bin/run-as ${quote(app.packageName)} /system/bin/kill -9 $stalePid"
            val result = executor.executeCommand(killCommand)
            if (!result.success) {
                AppLogger.w(
                    TAG,
                    "Failed to retire untracked Resident pid=$stalePid: " +
                        result.stderr.ifBlank { result.stdout }
                )
            }
        }

        for (attempt in 0 until 30) {
            delay(100L)
            if (guardianLeaseIsFree()) {
                metaFile().delete()
                guardianFile().delete()
                return true
            }
        }
        return false
    }

    private fun readProcText(pid: Int, name: String): String? =
        runCatching { File("/proc/$pid/$name").readText() }.getOrNull()

    private fun parseProcStatus(text: String?): Map<String, String> =
        text.orEmpty().lineSequence().mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1).trim()
        }.toMap()

    private fun readKeyValues(file: File): Map<String, String> =
        runCatching {
            file.readLines().mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }.toMap()
        }.getOrDefault(emptyMap())

    private fun lastHeartbeat(): String? =
        runCatching { heartbeatFile().useLines { lines -> lines.lastOrNull() } }.getOrNull()

    private fun stateDir(): File = File(app.filesDir, "ai_limbs/resident")
    private fun metaFile(): File = File(stateDir(), "resident.meta")
    private fun heartbeatFile(): File = File(stateDir(), "heartbeat.log")
    private fun guardianFile(): File = File(stateDir(), "guardian.state")
    private fun hostWakeLockStateFile(): File = File(stateDir(), "host_wake_lock.state")

    private fun hostShellStateFile(): File = File(stateDir(), "host_shell.state")
    private fun stopRequestFile(): File = File(stateDir(), "stop.request")
    private fun shellLogPath(): String = "/data/local/tmp/ail_resident_${Process.myUid()}.log"

    internal fun blockAutomaticOnRetryAfterHandoffFailure(
        context: Context,
        error: Throwable,
        cleanupErrors: List<String>
    ) {
        initialize(context)
        val detail = buildString {
            append("Resident ON handoff failed and cannot safely reuse this Host VM: ")
            append(error.message ?: error.javaClass.simpleName)
            if (cleanupErrors.isNotEmpty()) {
                append("; cleanup=")
                append(cleanupErrors.distinct().joinToString(" | "))
            }
        }.take(4000)
        check(
            prefs.edit()
                .putBoolean(KEY_ON_AUTO_RETRY_BLOCKED, true)
                .putString(KEY_LAST_ERROR, detail)
                .commit()
        ) {
            "Could not persist Resident handoff recovery state"
        }
        AppLogger.e(TAG, detail, error)
    }

    private fun clearOnAutoRetryBlock() {
        if (!prefs.getBoolean(KEY_ON_AUTO_RETRY_BLOCKED, false)) return
        check(prefs.edit().remove(KEY_ON_AUTO_RETRY_BLOCKED).commit()) {
            "Could not clear Resident ON automatic-retry block"
        }
    }

    private fun persistEnabled(enabled: Boolean) {
        check(prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()) {
            "Could not persist Resident desired state"
        }
    }

    private fun notifyHostResidentStateChanged() {
        runCatching {
            app.startService(
                Intent(ACTION_RESIDENT_STATE_CHANGED)
                    .setClassName(app.packageName, HOST_SERVICE_CLASS)
            )
        }.onFailure { error ->
            AppLogger.w(TAG, "Could not refresh Host Resident survival state", error)
        }
    }

    private fun permissionBackendOwnershipSnapshot(): JSONObject? {
        if (!PrivilegeRuntime.isSelected()) return null
        val connection = PrivilegeRuntime.connection() ?: return null
        return runCatching { ResidentPermissionWire.describe(connection.binder) }.getOrNull()
    }

    private suspend fun awaitPermissionBackendReturnedToHost(
        timeoutMs: Long = PERMISSION_BACKEND_RETURN_TIMEOUT_MS
    ): JSONObject? {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val ownership = permissionBackendOwnershipSnapshot()
            if (
                ownership?.optString("runtime_owner") == "android_host" &&
                !ownership.optBoolean("core_lifetime_alive", false)
            ) return ownership
            delay(100L)
        }
        return null
    }

    private fun pluginKernelLeaseIsFree(): Boolean {
        val ownerDir = File(app.filesDir, "ai_limbs/runtime_owner")
        val lease = ResidentRuntimeLease.tryAcquire(ownerDir, "plugin_kernel") ?: return false
        lease.close()
        return true
    }

    /** Transitional LEGACY_HOST handoff wake token only; never a continuous-work success signal. */
    private suspend fun waitForHostTransitionWakeRelease(timeoutMs: Long = 2_000L): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val state = readKeyValues(hostWakeLockStateFile())
            val pid = state["pid"]?.toIntOrNull()
            val heldByCurrentHost = state["held"] == "true" && pid == Process.myPid() && ResidentProcessLiveness.exists(pid)
            if (!heldByCurrentHost) return true
            delay(50L)
        }
        return false
    }

    private fun scheduleHostRoleRestart(): Boolean = runCatching {
        val launchIntent = checkNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName)) {
            "No launch intent for ${app.packageName}"
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pending = PendingIntent.getActivity(
            app,
            0xA11,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 500L, pending)
        scope.launch {
            delay(180L)
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
        true
    }.onFailure { error ->
        AppLogger.e(TAG, "Could not schedule Resident Host role restart", error)
    }.getOrDefault(false)

    private fun recordError(message: String) {
        val clean = message.trim().take(4000).ifBlank { "Unknown resident runtime error" }
        prefs.edit().putString(KEY_LAST_ERROR, clean).apply()
        AppLogger.w(TAG, clean)
    }

    private fun clearError() {
        prefs.edit().remove(KEY_LAST_ERROR).apply()
    }

    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private data class LocalProbe(
        val running: Boolean = false,
        val pid: Int? = null,
        val uid: Int? = null,
        val protocolVersion: Int? = null,
        val buildCode: Int? = null,
        val sourceApk: String? = null,
        val ppid: Int? = null,
        val oomScoreAdj: Int? = null,
        val cgroup: String? = null,
        val sessionId: String? = null,
        val startedWallMs: Long? = null
    )
}
