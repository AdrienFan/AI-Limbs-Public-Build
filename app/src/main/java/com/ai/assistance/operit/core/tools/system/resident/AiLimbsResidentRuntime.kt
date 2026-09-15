package com.ai.assistance.operit.core.tools.system.resident

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Process
import android.system.Os
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.privilege.PrivilegeRuntime
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutor
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.util.AppLogger
import java.io.File
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
    private const val RESIDENT_PROTOCOL_VERSION = 3

    private const val TAG = "AiLimbsResident"
    private const val PREFS = "ai_limbs_resident_runtime_v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_ERROR = "last_error"
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
        scope.launch {
            runCatching { ensureStartedIfEnabled(app) }
                .onFailure { recordError("Resident auto-start failed: ${it.message}") }
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

    private suspend fun ensureStartedIfEnabled(context: Context): JSONObject {
        initialize(context)
        if (!isEnabled()) return status()
        return start()
    }

    private suspend fun setEnabled(enabled: Boolean): JSONObject = lifecycleMutex.withLock {
        persistEnabled(enabled)
        notifyHostResidentStateChanged()
        if (enabled) startLocked() else stopLocked()
    }

    private suspend fun start(): JSONObject = lifecycleMutex.withLock {
        persistEnabled(true)
        notifyHostResidentStateChanged()
        startLocked()
    }

    private suspend fun stop(): JSONObject = lifecycleMutex.withLock {
        persistEnabled(false)
        notifyHostResidentStateChanged()
        stopLocked()
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
        if (existing.running && existing.protocolVersion == RESIDENT_PROTOCOL_VERSION) {
            clearError()
            return status(existing)
        }

        if (existing.running && existing.protocolVersion != RESIDENT_PROTOCOL_VERSION) {
            AppLogger.i(
                TAG,
                "Replacing resident protocol ${existing.protocolVersion} with $RESIDENT_PROTOCOL_VERSION"
            )
            existing.pid?.let { stalePid ->
                runCatching { Process.killProcess(stalePid) }
                for (attempt in 0 until 12) {
                    delay(100L)
                    if (!pidExists(stalePid)) return@let
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
                recordError("旧版 Resident 仍在运行，无法安全切换到协议 $RESIDENT_PROTOCOL_VERSION")
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

        check(stateDir().mkdirs() || stateDir().isDirectory) {
            "Could not prepare resident state directory"
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
        recordError(
            diagnostic.stdout.trim().takeIf { it.isNotEmpty() }
                ?: diagnostic.stderr.trim().takeIf { it.isNotEmpty() }
                ?: "Resident process did not become ready"
        )
        return status(localProbe())
    }

    private suspend fun stopLocked(): JSONObject = withContext(NonCancellable) {
        val hostKernelBefore = PluginPlatformKernel.lifecycleSnapshot()
        val hostRoleBefore = hostKernelBefore.optString("runtime_role", "legacy_host")
        val coreBefore = ResidentCoreController.status(app)
        val coreWasAvailable = coreBefore.optBoolean("available", false)
        val coreWasBusinessOwner = coreWasAvailable && coreBefore.optBoolean("business_attached", false)
        val fenceBefore = ResidentBusinessTakeoverFence.snapshot(app)
        val policyBefore = ResidentPolicyStateHandoff.snapshot(app)
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
            val pid = coreBefore.optInt("pid", -1)
            if (coreWasAvailable && pid > 0 && pid != Process.myPid()) {
                runCatching { Process.killProcess(pid) }
                val deadline = android.os.SystemClock.elapsedRealtime() + CORE_FORCE_STOP_TIMEOUT_MS
                while (
                    (pidExists(pid) || !ResidentCoreController.bootstrapLeaseIsFree(app)) &&
                    android.os.SystemClock.elapsedRealtime() < deadline
                ) {
                    delay(50L)
                }
                forcedCoreStop = !pidExists(pid) && ResidentCoreController.bootstrapLeaseIsFree(app)
                if (forcedCoreStop) degraded += "Core process required forced termination"
                else failures += "Core process/lease remained after forced OFF"
            } else if (coreWasAvailable) {
                failures += "Core graceful stop failed and no safe Core PID was available for forced OFF"
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
        if (!waitForHostWakeRelease()) failures += "Host Resident PARTIAL_WAKE_LOCK did not release"

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
            val hostWake = readKeyValues(hostWakeLockStateFile())
            val hostPid = hostWake["pid"]?.toIntOrNull()
            val hostWakeHeld =
                hostWake["held"] == "true" && hostPid == Process.myPid() && pidExists(hostPid)
            val core = ResidentCoreController.status(app)
            val fence = ResidentBusinessTakeoverFence.snapshot(app)
            val policyHandoff = ResidentPolicyStateHandoff.snapshot(app)
            val hostKernel = PluginPlatformKernel.lifecycleSnapshot()
            val hostRole = hostKernel.optString("runtime_role", "legacy_host")
            val enabled = isEnabled()
            val coreAvailable = core.optBoolean("available", false)
            val coreOwned = coreAvailable && core.optBoolean("business_attached", false)
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
            val lastError = prefs.getString(KEY_LAST_ERROR, null)
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
                .put("core_running", coreAvailable)
                .put("host_attach_complete", hostAttachComplete)
                .put("host_attach", uiProxy ?: JSONObject.NULL)
                .put("pid", probe.pid ?: JSONObject.NULL)
                .put("uid", probe.uid ?: JSONObject.NULL)
                .put("ppid", probe.ppid ?: JSONObject.NULL)
                .put("protocol_version", probe.protocolVersion ?: JSONObject.NULL)
                .put("oom_score_adj", probe.oomScoreAdj ?: JSONObject.NULL)
                .put("cgroup", probe.cgroup ?: JSONObject.NULL)
                .put("session_id", probe.sessionId ?: JSONObject.NULL)
                .put("started_wall_ms", probe.startedWallMs ?: JSONObject.NULL)
                .put("last_heartbeat", lastHeartbeat() ?: JSONObject.NULL)
                // Wake-lock effectiveness against Samsung freezer/LEV is still a later real-device gate.
                .put("continuous_work", false)
                .put("continuous_work_state", "unverified")
                .put("cpu_wake_effective", JSONObject.NULL)
                .put("cpu_wake_lock", if (hostWakeHeld) "held" else "not_held")
                .put("cpu_wake_lock_backend", "host_service_power_manager")
                .put("host_pid", hostPid ?: JSONObject.NULL)
                .put("host_wake_lock_reason", hostWake["reason"] ?: JSONObject.NULL)
                .put("host_wake_lock_detail", hostWake["detail"] ?: JSONObject.NULL)
                .put("host_guardian", guardian["host_guardian"] ?: JSONObject.NULL)
                .put("last_host_touch_wall_ms", guardian["last_host_touch_wall_ms"]?.toLongOrNull() ?: JSONObject.NULL)
                .put("last_host_touch_ok", guardian["last_host_touch_ok"]?.toBooleanStrictOrNull() ?: JSONObject.NULL)
                .put("guardian_state", guardian["state"] ?: JSONObject.NULL)
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
                "$MAIN_CLASS ${quote(stateDir().absolutePath)} ${quote(app.packageName)}"
        val asApp =
            "exec /system/bin/run-as ${quote(app.packageName)} /system/bin/sh -c ${quote(inner)}"
        return "trap '' HUP; rm -f ${quote(shellLogPath())}; " +
            "/system/bin/setsid /system/bin/sh -c ${quote(asApp)} " +
            "> ${quote(shellLogPath())} 2>&1 < /dev/null &"
    }

    private fun localProbe(): LocalProbe {
        val meta = readKeyValues(metaFile())
        val pid = meta["pid"]?.toIntOrNull() ?: return LocalProbe()
        val expectedUid = meta["uid"]?.toIntOrNull() ?: Process.myUid()
        if (expectedUid != Process.myUid() || !pidExists(pid)) return LocalProbe()

        val cmdline = readProcText(pid, "cmdline")?.replace('\u0000', ' ')?.trim().orEmpty()
        val comm = readProcText(pid, "comm")?.trim().orEmpty()
        if (PROCESS_NAME !in cmdline && PROCESS_NAME !in comm && MAIN_CLASS !in cmdline) {
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
            ppid = status["PPid"]?.trim()?.toIntOrNull(),
            oomScoreAdj = readProcText(pid, "oom_score_adj")?.trim()?.toIntOrNull(),
            cgroup = readProcText(pid, "cgroup")?.trim()?.replace('\n', ';'),
            sessionId = meta["session_id"],
            startedWallMs = meta["started_wall_ms"]?.toLongOrNull()
        )
    }

    private fun pidExists(pid: Int): Boolean =
        runCatching {
            Os.kill(pid, 0)
            true
        }.getOrDefault(false)

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
    private fun stopRequestFile(): File = File(stateDir(), "stop.request")
    private fun shellLogPath(): String = "/data/local/tmp/ail_resident_${Process.myUid()}.log"


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

    private suspend fun waitForHostWakeRelease(timeoutMs: Long = 2_000L): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val state = readKeyValues(hostWakeLockStateFile())
            val pid = state["pid"]?.toIntOrNull()
            val heldByCurrentHost = state["held"] == "true" && pid == Process.myPid() && pidExists(pid)
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
        val ppid: Int? = null,
        val oomScoreAdj: Int? = null,
        val cgroup: String? = null,
        val sessionId: String? = null,
        val startedWallMs: Long? = null
    )
}
