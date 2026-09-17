package com.ai.assistance.operit.plugins.center.isolation

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.core.tools.system.resident.ResidentBusinessTakeoverFence
import com.ai.assistance.operit.core.tools.system.resident.ResidentProcessLiveness
import com.ai.assistance.operit.core.tools.system.resident.ResidentRuntimeLease
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Lifecycle controller for the isolated dynamic-plugin process. */
internal object PluginRuntimeController {
    private const val MAIN_CLASS =
        "com.ai.assistance.operit.plugins.center.isolation.PluginRuntimeMain"

    // The supervisor and business adapters share one launch token and one worker. Serialize the
    // whole lifecycle transaction, including readiness/cleanup, so concurrent callers cannot
    // overwrite launch.request or stop a worker while another caller is attesting its launch.
    private val lifecycleMutex = Mutex()

    suspend fun status(context: Context): JSONObject = withContext(Dispatchers.IO) {
        if (leaseIsFree(context)) {
            return@withContext stoppedSnapshot()
        }
        val identityHint = readCurrentIdentity(context)
        if (identityHint == null) {
            return@withContext JSONObject()
                .put("available", false).put("consistent", false).put("process_alive", true)
                .put("identity_attested", false).put("owner_matches", false)
                .put("phase", "unresponsive_or_starting")
        }
        val sessionHint = identityHint.optString("session_id").takeIf { it.isNotBlank() }
        try {
            PluginRuntimeWire.request("status", sessionHint).also { state ->
                val buildCodeMatches = state.getInt("build_code") == BuildConfig.VERSION_CODE
                val sourceApkMatches = state.optString("source_apk", "") == context.applicationInfo.sourceDir
                val fence = runCatching { ResidentBusinessTakeoverFence.snapshot(context) }.getOrNull()
                val ownerMatches = fence != null &&
                    state.optInt("owner_core_pid", -1) == Process.myPid() &&
                    state.optInt("owner_core_pid", -1) == fence.optInt("core_pid", -2) &&
                    state.optString("owner_core_session") == fence.optString("core_session")
                state.put("build_code_matches", buildCodeMatches)
                    .put("source_apk_matches", sourceApkMatches)
                    .put("owner_matches", ownerMatches)
                    .put("build_matches", buildCodeMatches && sourceApkMatches)
                    .put("process_alive", true)
                    .put("consistent", buildCodeMatches && sourceApkMatches && ownerMatches)
            }
        } catch (error: Exception) {
            val stopped = leaseIsFree(context)
            val identity = if (stopped) null else readCurrentIdentity(context)
            val fence = runCatching { ResidentBusinessTakeoverFence.snapshot(context) }.getOrNull()
            val ownerMatches = identity != null && fence != null &&
                identity.optInt("owner_core_pid", -1) == Process.myPid() &&
                identity.optInt("owner_core_pid", -1) == fence.optInt("core_pid", -2) &&
                identity.optString("owner_core_session") == fence.optString("core_session")
            JSONObject()
                .put("available", false)
                .put("consistent", false)
                .put("process_alive", !stopped)
                .put("identity_attested", identity != null)
                .put("owner_matches", ownerMatches)
                .put("phase", if (stopped) "stopped" else "unresponsive_or_starting")
                .put("pid", identity?.optInt("pid", -1)?.takeIf { it > 0 } ?: JSONObject.NULL)
                .put("session_id", identity?.optString("session_id")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("launch_id", identity?.optString("launch_id")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("error", error.toString().take(512))
        }
    }

    suspend fun probe(context: Context): JSONObject = lifecycleMutex.withLock {
        probeLocked(context)
    }

    private suspend fun probeLocked(context: Context): JSONObject = withContext(Dispatchers.IO) {
        var existing = status(context)
        if (existing.optBoolean("available", false)) {
            if (existing.optBoolean("consistent", false)) return@withContext existing
            // probe already owns lifecycleMutex; do not re-enter the public stop operation.
            stopLocked(context)
            existing = status(context)
        }
        if (existing.optString("phase") != "stopped") {
            if (existing.optBoolean("process_alive", false)) {
                if (existing.optBoolean("identity_attested", false) &&
                    !existing.optBoolean("owner_matches", false)) {
                    val stalePid = existing.optInt("pid", -1).takeIf { it > 0 }
                        ?: return@withContext JSONObject(existing.toString())
                            .put("recovery_deferred", true)
                            .put("recovery_reason", "stale_owner_without_pid")
                    Process.killProcess(stalePid)
                    val deadline = SystemClock.elapsedRealtime() + 3_000L
                    while (!leaseIsFree(context) && SystemClock.elapsedRealtime() < deadline) delay(100L)
                    check(leaseIsFree(context)) { "Stale plugin runtime PID $stalePid did not release its lease" }
                    existing = status(context)
                } else {
                    return@withContext JSONObject(existing.toString())
                        .put("recovery_deferred", true)
                        .put("recovery_reason", "process_alive_unresponsive")
                }
            } else {
                existing = status(context)
            }
        }
        check(existing.optString("phase") == "stopped") {
            "Plugin runtime did not reach stopped state before launch"
        }

        val fence = checkNotNull(ResidentBusinessTakeoverFence.snapshot(context)) { "Plugin runtime requires Resident Core ownership fence" }
        val ownerCorePid = fence.getInt("core_pid")
        val ownerCoreSession = fence.getString("core_session")
        check(ownerCorePid == Process.myPid()) { "Plugin runtime must be launched by the owning Resident Core" }
        check(fence.getString("state") in setOf("armed", "owned")) { "Resident Core ownership is not active" }
        val directory = directory(context)
        check(directory.mkdirs() || directory.isDirectory)
        val launchId = UUID.randomUUID().toString()
        val requestFile = File(directory, "launch.request")
        requestFile.writeText(launchId)
        var ready = false
        try {
            val nativeLibraryDir = context.applicationInfo.nativeLibraryDir?.trim().orEmpty()
            check(nativeLibraryDir.isNotBlank() && File(nativeLibraryDir).isDirectory) {
                "Plugin runtime native library directory is unavailable: $nativeLibraryDir"
            }
            val logFile = File(directory, "bootstrap.log")
            if (logFile.exists()) logFile.delete()
            ProcessBuilder(
                "/system/bin/app_process",
                "-Djava.library.path=$nativeLibraryDir",
                "/system/bin",
                "--nice-name=ail_plugin_runtime",
                MAIN_CLASS,
                context.packageName,
                directory.absolutePath,
                launchId,
                ownerCorePid.toString(),
                ownerCoreSession
            )
                .directory(File("/"))
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectErrorStream(true)
                .apply {
                    environment()["CLASSPATH"] = context.applicationInfo.sourceDir
                    val inheritedLd = System.getenv("LD_LIBRARY_PATH").orEmpty()
                    environment()["LD_LIBRARY_PATH"] =
                        if (inheritedLd.isBlank()) nativeLibraryDir else "$nativeLibraryDir:$inheritedLd"
                }
                .start()

            val deadline = SystemClock.elapsedRealtime() + 6_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(150L)
                val state = status(context)
                if (state.optBoolean("available", false)) {
                    check(state.optBoolean("build_matches", false)) { "Plugin runtime build mismatch" }
                    check(state.getString("launch_id") == launchId) { "Plugin runtime launch identity mismatch" }
                    check(state.optBoolean("owner_matches", false)) { "Plugin runtime owner Core mismatch" }
                    ready = true
                    return@withContext state
                }
            }
            error("Plugin runtime did not become ready. " + readLogTail(File(directory, "bootstrap.log")))
        } finally {
            if (!ready) requestFile.delete()
        }
    }

    suspend fun stop(context: Context): JSONObject = withContext(Dispatchers.IO + NonCancellable) {
        lifecycleMutex.withLock { stopLocked(context) }
    }

    private suspend fun stopLocked(context: Context): JSONObject = withContext(Dispatchers.IO + NonCancellable) {
        var state = status(context)
        if (state.optBoolean("available", false)) {
            val pid = state.getInt("pid")
            val sessionId = state.getString("session_id")
            val graceful = runCatching { PluginRuntimeWire.request("stop", sessionId) }.isSuccess
            if (!graceful) Process.killProcess(pid)
            check(waitForExit(context, pid, 4_000L)) {
                "Plugin runtime PID $pid did not exit after ${if (graceful) "stop" else "forced stop"}"
            }
            clearLaunchArtifacts(context)
            return@withContext JSONObject()
                .put("stopped", true)
                .put("pid", pid)
                .put("phase", "stopped")
                .put("graceful", graceful)
        }

        if (state.optString("phase") == "stopped") {
            clearLaunchArtifacts(context)
            return@withContext JSONObject().put("stopped", true).put("phase", "stopped")
        }

        state.optInt("pid", -1).takeIf { it > 0 }?.let { pid ->
            Process.killProcess(pid)
            check(waitForExit(context, pid, 4_000L)) {
                "Unresponsive plugin runtime PID $pid did not exit after forced stop"
            }
            clearLaunchArtifacts(context)
            return@withContext JSONObject()
                .put("stopped", true)
                .put("pid", pid)
                .put("phase", "stopped")
                .put("graceful", false)
        }

        // A launch may be between lease acquisition and identity publication. Cancel the launch
        // token first, then wait briefly for either lease release or an attested PID to appear.
        val request = File(directory(context), "launch.request")
        if (request.exists()) request.delete()
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(100L)
            state = status(context)
            if (state.optString("phase") == "stopped") {
                clearLaunchArtifacts(context)
                return@withContext JSONObject().put("stopped", true).put("phase", "stopped")
            }
            state.optInt("pid", -1).takeIf { it > 0 }?.let { pid ->
                Process.killProcess(pid)
                check(waitForExit(context, pid, 4_000L)) {
                    "Starting plugin runtime PID $pid did not exit after forced stop"
                }
                clearLaunchArtifacts(context)
                return@withContext JSONObject()
                    .put("stopped", true)
                    .put("pid", pid)
                    .put("phase", "stopped")
                    .put("graceful", false)
            }
        }
        error("Plugin runtime owns its lease but no stoppable process identity became available")
    }

    private suspend fun waitForExit(context: Context, pid: Int, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (leaseIsFree(context) && !ResidentProcessLiveness.exists(pid)) return true
            delay(100L)
        }
        return leaseIsFree(context) && !ResidentProcessLiveness.exists(pid)
    }

    private fun clearLaunchArtifacts(context: Context) {
        val directory = directory(context)
        File(directory, "launch.request").delete()
        File(directory, "runtime.identity").delete()
    }

    private fun stoppedSnapshot(): JSONObject = JSONObject()
        .put("available", false)
        .put("consistent", false)
        .put("process_alive", false)
        .put("phase", "stopped")

    private fun directory(context: Context): File = File(context.filesDir, "ai_limbs/plugin_runtime")

    private fun leaseIsFree(context: Context): Boolean {
        val lease = ResidentRuntimeLease.tryAcquire(directory(context), "bootstrap") ?: return false
        lease.close()
        return true
    }

    internal fun isAttestedWorkerPeer(context: Context, pid: Int, ownerCoreSession: String): Boolean {
        if (pid <= 0 || ownerCoreSession.isBlank()) return false
        val identity = readCurrentIdentity(context) ?: return false
        return identity.optInt("pid", -1) == pid &&
            identity.optInt("owner_core_pid", -1) == Process.myPid() &&
            identity.optString("owner_core_session") == ownerCoreSession &&
            ResidentProcessLiveness.exists(pid)
    }

    private fun readCurrentIdentity(context: Context): JSONObject? {
        val directory = directory(context)
        val launchFile = File(directory, "launch.request")
        val identityFile = File(directory, "runtime.identity")
        if (!launchFile.isFile || !identityFile.isFile) return null
        return runCatching {
            val expectedLaunch = launchFile.readText()
            val identity = JSONObject(identityFile.readText())
            check(identity.getInt("uid") == Process.myUid()) { "Plugin runtime identity UID mismatch" }
            check(identity.getString("launch_id") == expectedLaunch) { "Plugin runtime identity launch mismatch" }
            check(identity.getInt("pid") > 0) { "Plugin runtime identity PID invalid" }
            identity
        }.getOrNull()
    }

    private fun readLogTail(file: File): String {
        if (!file.isFile) return "No bootstrap log was created"
        return RandomAccessFile(file, "r").use { input ->
            val count = minOf(4000L, input.length()).toInt()
            input.seek(input.length() - count)
            val bytes = ByteArray(count)
            input.readFully(bytes)
            bytes.toString(Charsets.UTF_8)
        }
    }
}
