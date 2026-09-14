package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Process
import android.system.Os
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.privilege.PrivilegeRuntime
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutor
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
import com.ai.assistance.operit.util.AppLogger
import java.io.File
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
        check(prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()) {
            "Could not persist resident setting"
        }
        if (enabled) {
            startLocked()
        } else {
            try {
                stopLocked()
            } finally {
                notifyHostResidentDisabled()
            }
        }
    }

    private suspend fun start(): JSONObject = lifecycleMutex.withLock {
        startLocked()
    }

    private suspend fun stop(): JSONObject = lifecycleMutex.withLock {
        stopLocked()
    }

    private suspend fun startLocked(): JSONObject {
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
        // A diagnostic failure must not prevent the existing Guardian shutdown.
        val coreStopError = try {
            ResidentCoreController.stop(app)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.w(TAG, "Resident Core stop was not confirmed", error)
            error.toString()
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
            val fallback = "/system/bin/run-as ${quote(app.packageName)} " +
                "/system/bin/kill -9 ${probe.pid}"
            executor.executeCommand(fallback)
            for (attempt in 0 until 8) {
                delay(100L)
                probe = localProbe()
                if (!probe.running) break
            }
        }

        if (!probe.running) {
            metaFile().delete()
            stopRequestFile().delete()
            clearError()
            AppLogger.i(TAG, "Resident stopped")
        } else {
            recordError("Resident process is still alive after stop request")
        }
        if (coreStopError != null) recordError("Resident Core stop not confirmed: $coreStopError")
        status(probe).put("core_stop_error", coreStopError ?: JSONObject.NULL)
    }

    private suspend fun status(probe: LocalProbe = localProbe()): JSONObject =
        withContext(Dispatchers.IO) {
            val guardian = readKeyValues(guardianFile())
            val hostWake = readKeyValues(hostWakeLockStateFile())
            val hostPid = hostWake["pid"]?.toIntOrNull()
            val hostWakeHeld =
                hostWake["held"] == "true" &&
                    hostPid == Process.myPid()
            JSONObject()
                .put("available", true)
                .put("api", 3)
                .put("mode", "lockscreen_continuous")
                .put("runtime_phase", "guardian")
                .put("runtime_owner", "android_host")
                .put("plugins_migrated", false)
                .put("enabled", isEnabled())
                .put("running", probe.running)
                .put("pid", probe.pid ?: JSONObject.NULL)
                .put("uid", probe.uid ?: JSONObject.NULL)
                .put("ppid", probe.ppid ?: JSONObject.NULL)
                .put("protocol_version", probe.protocolVersion ?: JSONObject.NULL)
                .put("oom_score_adj", probe.oomScoreAdj ?: JSONObject.NULL)
                .put("cgroup", probe.cgroup ?: JSONObject.NULL)
                .put("session_id", probe.sessionId ?: JSONObject.NULL)
                .put("started_wall_ms", probe.startedWallMs ?: JSONObject.NULL)
                .put("last_heartbeat", lastHeartbeat() ?: JSONObject.NULL)
                // A held client token is not proof that PowerManager honors the wake lock.
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
                .put("last_error", prefs.getString(KEY_LAST_ERROR, null) ?: JSONObject.NULL)
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


    private fun notifyHostResidentDisabled() {
        runCatching {
            app.startService(
                Intent(ACTION_RESIDENT_STATE_CHANGED)
                    .setClassName(app.packageName, HOST_SERVICE_CLASS)
            )
        }.onFailure { error ->
            AppLogger.w(TAG, "Could not refresh host service after disabling Resident", error)
        }
    }

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
