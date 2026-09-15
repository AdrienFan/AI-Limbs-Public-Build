package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutor
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Called under AiLimbsResidentRuntime's lifecycle mutex for all mutations. */
internal object ResidentCoreController {
    private const val MAIN_CLASS =
        "com.ai.assistance.operit.core.tools.system.resident.ResidentCoreMain"

    suspend fun status(context: Context): JSONObject = withContext(Dispatchers.IO) {
        try {
            ResidentCoreWire.request("status")
                .also { it.put("build_matches", it.getInt("build_code") == BuildConfig.VERSION_CODE) }
        } catch (error: IOException) {
            JSONObject()
                .put("available", false)
                .put("phase", if (leaseIsFree(context)) "stopped" else "unresponsive_or_starting")
                .put("continuous_work", false)
                .put("error", error.toString().take(512))
        }
    }

    suspend fun probe(context: Context, executor: ShellExecutor): JSONObject = withContext(Dispatchers.IO) {
        val existing = status(context)
        if (existing.getBoolean("available")) {
            check(existing.getBoolean("build_matches")) {
                "Stop the previous Core build before probing the installed build"
            }
            requireRuntimeSkeletonRunning(existing)
            return@withContext existing
        }
        check(existing.getString("phase") == "stopped") {
            "Core owns its lease but IPC is not ready; stop it before another probe"
        }
        val directory = directory(context)
        check(directory.mkdirs() || directory.isDirectory)
        val launchId = UUID.randomUUID().toString()
        val requestFile = File(directory, "launch.request")
        requestFile.writeText(launchId)
        var ready = false
        try {
            val inner = "export CLASSPATH=" + quote(context.applicationInfo.sourceDir) + "; " +
                "exec /system/bin/app_process /system/bin --nice-name=ail_resident_core " +
                MAIN_CLASS + " " + quote(context.packageName) + " " +
                quote(directory.absolutePath) + " " + quote(launchId)
            val asApp = "exec /system/bin/run-as " + quote(context.packageName) +
                " /system/bin/sh -c " + quote(inner + " > " +
                    quote(File(directory, "bootstrap.log").absolutePath) + " 2>&1 < /dev/null")
            val command = "trap '' HUP; /system/bin/setsid /system/bin/sh -c " +
                quote(asApp) + " > /dev/null 2>&1 < /dev/null &"
            val result = executor.executeCommand(command)
            check(result.success) { "Core launch failed: " + result.stderr.take(1000) }
            val readyDeadline = SystemClock.elapsedRealtime() + 6_000L
            while (SystemClock.elapsedRealtime() < readyDeadline) {
                delay(150L)
                val state = status(context)
                if (state.getBoolean("available")) {
                    check(state.getBoolean("build_matches")) { "Core build mismatch" }
                    check(state.getString("launch_id") == launchId) { "Core launch identity mismatch" }
                    requireRuntimeSkeletonRunning(state)
                    ready = true
                    return@withContext state
                }
            }
            error("Core did not become ready. " + readLogTail(File(directory, "bootstrap.log")))
        } finally {
            // A delayed app_process must not start after a failed/cancelled probe.
            if (!ready) requestFile.delete()
        }
    }

    suspend fun stop(context: Context): JSONObject = withContext(Dispatchers.IO + NonCancellable) {
        val request = File(directory(context), "launch.request")
        check(!request.exists() || request.delete()) { "Cannot cancel pending Core launch" }
        var state = status(context)
        // An in-flight bootstrap holds the lease before checking launch.request.
        val startupDeadline = SystemClock.elapsedRealtime() + 6_000L
        while (!state.getBoolean("available") && state.getString("phase") != "stopped" &&
            SystemClock.elapsedRealtime() < startupDeadline) {
            delay(150L)
            state = status(context)
        }
        if (!state.getBoolean("available")) {
            check(state.getString("phase") == "stopped") {
                "Core is unresponsive; stop has not been confirmed"
            }
            return@withContext JSONObject().put("stopped", true).put("phase", "stopped")
        }
        val pid = state.getInt("pid")
        // Do not require a matching build to stop: an app update may leave an older Core alive.
        ResidentCoreWire.request("stop", state.getString("session_id"))
        repeat(40) {
            delay(100L)
            if (leaseIsFree(context) && !pidExists(pid)) {
                val result = JSONObject().put("stopped", true).put("pid", pid)
                    .put("backend_release_confirmed", JSONObject.NULL)
                val report = File(directory(context), "shutdown.result.json")
                try {
                    if (report.isFile && report.length() in 1L..4096L) {
                        val outcome = JSONObject(report.readText())
                        if (outcome.getInt("pid") == pid && outcome.getString("session_id") == state.getString("session_id")) {
                            result.put("backend_release_confirmed", outcome.getBoolean("backend_release_confirmed"))
                            if (outcome.has("backend_release_error")) result.put("backend_release_error", outcome.getString("backend_release_error"))
                        }
                    }
                } catch (error: Exception) {
                    // PID exit and lease release are already confirmed; report metadata failure
                    // separately instead of misreporting a still-running Core.
                    result.put("shutdown_report_error", error.toString().take(512))
                }
                return@withContext result
            }
        }
        error("Core accepted stop but process exit has not been confirmed")
    }

    /** Prepare only. Old Host retirement and process exit must precede Core kernel activation. */
    suspend fun prepareHandoff(context: Context, executor: ShellExecutor): ResidentPermissionHandoff =
        withContext(Dispatchers.IO) {
            var state = probe(context, executor)
            check(!state.getBoolean("business_attached")) {
                "Resident Core already owns the Plugin Kernel; a second handoff is forbidden"
            }
            val session = state.getString("session_id")
            val deadline = SystemClock.elapsedRealtime() + 6_000L
            while (state.getJSONObject("backend").getString("state") == "connecting" &&
                SystemClock.elapsedRealtime() < deadline) {
                delay(100L)
                state = ResidentCoreWire.request("status", session)
            }
            val backend = state.getJSONObject("backend")
            check(backend.getString("state") == "ready" || backend.getString("state") == "prepared") {
                "Core permission backend is not ready for handoff: $backend"
            }
            var prepared = ResidentCoreWire.request("prepare_handoff", session)
            val prepareDeadline = SystemClock.elapsedRealtime() + 6_000L
            while (prepared.getJSONObject("backend").getString("state") == "preparing" &&
                SystemClock.elapsedRealtime() < prepareDeadline) {
                delay(100L)
                prepared = ResidentCoreWire.request("status", session)
            }
            ResidentPermissionHandoff.fromPreparedCore(prepared)
        }

    suspend fun armBusinessTakeover(coreSession: String): JSONObject = withContext(Dispatchers.IO) {
        val armed = ResidentCoreWire.request("activate_business", coreSession)
        check(armed.getString("business_phase") == "waiting_for_host_exit") {
            "Core did not arm Plugin Kernel takeover: $armed"
        }
        armed
    }

    suspend fun quiesceBusiness(context: Context): JSONObject =
        withContext(Dispatchers.IO + NonCancellable) {
            val state = status(context)
            if (!state.optBoolean("available", false) || !state.optBoolean("business_attached", false)) {
                return@withContext JSONObject()
                    .put("drain_required", false)
                    .put("drain_success", true)
                    .put("phase", state.optString("phase", "stopped"))
            }
            val session = state.getString("session_id")
            val quiesced = ResidentCoreWire.request("quiesce_business", session)
            val dispatcher = quiesced.getJSONObject("dispatcher")
            val businessPhase = quiesced.getString("business_phase")
            JSONObject()
                .put("drain_required", true)
                .put("drain_success", businessPhase == "drained" && dispatcher.optBoolean("drained", false))
                .put("business_phase", businessPhase)
                .put("dispatcher", dispatcher)
                .put("core_session", session)
        }

    suspend fun cancelBusinessTakeover(coreSession: String): JSONObject = withContext(Dispatchers.IO) {
        ResidentCoreWire.request("cancel_business_activation", coreSession)
    }

    private fun requireRuntimeSkeletonRunning(state: JSONObject) {
        check(state.getString("phase") == "running") {
            "Core IPC is reachable but runtime phase is not running: ${state.getString("phase")}"
        }
        val runtime = state.getJSONObject("core_runtime")
        check(runtime.getBoolean("runtime_skeleton_ready") && runtime.getBoolean("main_looper_ready")) {
            "Core runtime reported running before its main-Looper startup barrier completed"
        }
        check(state.getBoolean("business_attached") == runtime.getBoolean("business_attached")) {
            "Core business ownership snapshot is inconsistent"
        }
        check(state.getBoolean("bridge_ingress_prepared") == runtime.getBoolean("bridge_ingress_prepared")) {
            "Core Bridge ingress snapshot is inconsistent"
        }
        check(state.getBoolean("plugin_services_prepared") == runtime.getBoolean("plugin_services_prepared") &&
            state.getBoolean("ubuntu_control_ready") == runtime.getBoolean("ubuntu_control_ready")) {
            "Core plugin service / Ubuntu ownership snapshot is inconsistent"
        }
        if (state.getBoolean("business_attached")) {
            check(state.getString("runtime_owner") == "resident_core" &&
                runtime.getBoolean("plugin_kernel_started") &&
                runtime.getBoolean("bridge_ingress_prepared") &&
                runtime.getBoolean("plugin_services_prepared") &&
                runtime.getBoolean("ubuntu_control_ready")) {
                "Core claims business ownership before Kernel / Bridge / plugin services / Ubuntu are prepared"
            }
            val dispatcher = state.getJSONObject("dispatcher")
            check(dispatcher.getBoolean("running") &&
                dispatcher.getString("dispatcher_owner") == "resident_core" &&
                dispatcher.getInt("owner_pid") == state.getInt("pid")) {
                "Core claims business ownership without the authoritative Dispatcher: $dispatcher"
            }
            val policyRuntime = dispatcher.getJSONObject("runtime")
            check(policyRuntime.getString("policy_owner") == "resident_core" &&
                policyRuntime.getInt("owner_pid") == state.getInt("pid")) {
                "Core Dispatcher does not own the policy plane: $policyRuntime"
            }
        }
        check(state.getBoolean("plugins_migrated") == runtime.getBoolean("plugin_services_prepared")) {
            "Core plugin migration marker is inconsistent with the business runtime"
        }
        check(!state.getBoolean("continuous_work")) {
            "Core must not claim continuous work before the power/freezer stage is validated"
        }
    }

    internal fun bootstrapLeaseIsFree(context: Context): Boolean =
        leaseIsFree(context)

    private fun directory(context: Context): File =
        File(context.filesDir, "ai_limbs/resident_core")

    private fun leaseIsFree(context: Context): Boolean {
        val lease = ResidentRuntimeLease.tryAcquire(directory(context), "bootstrap") ?: return false
        lease.close()
        return true
    }

    private fun pidExists(pid: Int): Boolean = try {
        Os.kill(pid, 0)
        true
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ESRCH) false else throw error
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

    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
