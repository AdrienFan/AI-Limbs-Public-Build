package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.os.SystemClock
import com.ai.assistance.operit.BuildConfig
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
        // bootstrap.lock is the Core process-ownership fact. If the lease is free,
        // there cannot be a live Core owner, so do not touch the Core IPC socket.
        // This also avoids an unbounded LocalSocket.connect when no Core exists.
        if (leaseIsFree(context)) {
            return@withContext JSONObject()
                .put("available", false)
                .put("consistent", false)
                .put("process_alive", false)
                .put("phase", "stopped")
                .put("continuous_work", false)
        }
        try {
            requestCore(context, "status").also { state ->
                val buildCodeMatches = state.getInt("build_code") == BuildConfig.VERSION_CODE
                val sourceApkMatches =
                    state.optString("source_apk", "") == context.applicationInfo.sourceDir
                val buildMatches = buildCodeMatches && sourceApkMatches
                val isolation = ResidentProcessIsolation.snapshot(state.getInt("pid"))
                val isolatedFromAdbd = isolation.optBoolean("isolated_from_adbd", false)
                state.put("build_code_matches", buildCodeMatches)
                    .put("source_apk_matches", sourceApkMatches)
                    .put("build_matches", buildMatches)
                    .put("process_alive", true)
                    .put("process_isolation", isolation)
                if (!buildMatches) {
                    state.put("consistent", false)
                        .put("consistency_error", "Resident Core build does not match the installed Host build")
                } else if (!isolatedFromAdbd) {
                    state.put("consistent", false)
                        .put("consistency_error", "Resident Core is still attached to the adbd cgroup")
                } else {
                    val consistencyError =
                        runCatching { requireSnapshotConsistent(state) }.exceptionOrNull()
                    state.put("consistent", consistencyError == null)
                    if (consistencyError != null) {
                        state.put("consistency_error", consistencyError.toString().take(1024))
                    }
                }
            }
        } catch (error: IOException) {
            val stopped = leaseIsFree(context)
            JSONObject()
                .put("available", false)
                .put("consistent", false)
                .put("process_alive", !stopped)
                .put("phase", if (stopped) "stopped" else "unresponsive_or_starting")
                .put("continuous_work", false)
                .put("error", error.toString().take(512))
        }
    }

    suspend fun probe(context: Context): JSONObject = withContext(Dispatchers.IO) {
        var existing = status(context)
        if (existing.getBoolean("available")) {
            if (!existing.getBoolean("build_matches")) {
                check(!existing.optBoolean("business_attached", false)) {
                    "Previous Core build still owns business; explicit OFF recovery is required"
                }
                stop(context)
                existing = status(context)
                check(!existing.getBoolean("available") && existing.getString("phase") == "stopped") {
                    "Previous Core build did not retire before probing the installed build"
                }
            } else {
                requireRuntimeSkeletonRunning(existing)
                return@withContext existing
            }
        }
        check(existing.getString("phase") == "stopped") {
            "Core owns its lease but IPC is not ready; stop it before another probe"
        }
        val directory = directory(context)
        check(directory.mkdirs() || directory.isDirectory)
        ResidentCoreEndpoint.clear(directory)
        val launchId = UUID.randomUUID().toString()
        val requestFile = File(directory, "launch.request")
        requestFile.writeText(launchId)
        var ready = false
        try {
            val nativeLibraryDir = context.applicationInfo.nativeLibraryDir?.trim().orEmpty()
            check(nativeLibraryDir.isNotBlank() && File(nativeLibraryDir).isDirectory) {
                "Resident Core native library directory is unavailable: $nativeLibraryDir"
            }
            ResidentProcessHostService.launchCore(context, launchId)
            var lastStatus = existing
            val readyDeadline = SystemClock.elapsedRealtime() + 6_000L
            while (SystemClock.elapsedRealtime() < readyDeadline) {
                delay(150L)
                val state = status(context)
                lastStatus = state
                if (state.getBoolean("available")) {
                    check(state.getBoolean("build_matches")) { "Core build mismatch" }
                    check(state.getString("launch_id") == launchId) { "Core launch identity mismatch" }
                    requireRuntimeSkeletonRunning(state)
                    try {
                        state.put(
                            "process_isolation",
                            ResidentProcessIsolation.requireDetachedFromAdbd(
                                state.getInt("pid"),
                                "Resident Core"
                            )
                        )
                    } catch (error: Throwable) {
                        runCatching { requestCore(context, "stop", state.getString("session_id")) }
                        throw error
                    }
                    ready = true
                    return@withContext state
                }
            }
            error("Core did not become ready. Last status: $lastStatus. " +
                readLogTail(File(directory, "bootstrap.log")))
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
        requestCore(context, "stop", state.getString("session_id"))
        repeat(40) {
            delay(100L)
            if (leaseIsFree(context) && !ResidentProcessLiveness.exists(pid)) {
                val result = JSONObject().put("stopped", true).put("pid", pid)
                    .put("backend_release_confirmed", JSONObject.NULL)
                val report = File(directory(context), "shutdown.result.json")
                try {
                    if (report.isFile && report.length() in 1L..16384L) {
                        val outcome = JSONObject(report.readText())
                        if (outcome.getInt("pid") == pid && outcome.getString("session_id") == state.getString("session_id")) {
                            result.put("backend_release_confirmed", outcome.getBoolean("backend_release_confirmed"))
                            if (outcome.has("backend_release_error")) result.put("backend_release_error", outcome.getString("backend_release_error"))
                            if (outcome.has("continuous_resource_release_confirmed")) {
                                result.put("continuous_resource_release_confirmed",
                                    outcome.getBoolean("continuous_resource_release_confirmed"))
                            }
                            if (outcome.has("continuous_resource_release")) {
                                result.put("continuous_resource_release", outcome.getJSONObject("continuous_resource_release"))
                            }
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
    suspend fun prepareHandoff(context: Context): ResidentPermissionHandoff =
        withContext(Dispatchers.IO) {
            var state = probe(context)
            check(!state.getBoolean("business_attached")) {
                "Resident Core already owns the Plugin Kernel; a second handoff is forbidden"
            }
            val session = state.getString("session_id")
            val deadline = SystemClock.elapsedRealtime() + 6_000L
            while (state.getJSONObject("backend").getString("state") == "connecting" &&
                SystemClock.elapsedRealtime() < deadline) {
                delay(100L)
                state = requestCore(context, "status", session)
            }
            val backend = state.getJSONObject("backend")
            check(backend.getString("state") == "ready" || backend.getString("state") == "prepared") {
                "Core permission backend is not ready for handoff: $backend"
            }
            var prepared = requestCore(context, "prepare_handoff", session)
            val prepareDeadline = SystemClock.elapsedRealtime() + 6_000L
            while (prepared.getJSONObject("backend").getString("state") == "preparing" &&
                SystemClock.elapsedRealtime() < prepareDeadline) {
                delay(100L)
                prepared = requestCore(context, "status", session)
            }
            ResidentPermissionHandoff.fromPreparedCore(prepared)
        }

    suspend fun armBusinessTakeover(context: Context, coreSession: String): JSONObject = withContext(Dispatchers.IO) {
        val armed = requestCore(context, "activate_business", coreSession)
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
            val quiesced = requestCore(context, "quiesce_business", session)
            val dispatcher = quiesced.getJSONObject("dispatcher")
            val businessPhase = quiesced.getString("business_phase")
            JSONObject()
                .put("drain_required", true)
                .put("drain_success", businessPhase == "drained" && dispatcher.optBoolean("drained", false))
                .put("business_phase", businessPhase)
                .put("dispatcher", dispatcher)
                .put("core_session", session)
        }

    suspend fun cancelBusinessTakeover(context: Context, coreSession: String): JSONObject = withContext(Dispatchers.IO) {
        requestCore(context, "cancel_business_activation", coreSession)
    }

    private fun requireRuntimeSkeletonRunning(state: JSONObject) {
        check(state.getString("phase") == "running") {
            "Core IPC is reachable but runtime phase is not running: ${state.getString("phase")}"
        }
        requireSnapshotConsistent(state)
        val runtime = state.getJSONObject("core_runtime")
        check(runtime.getBoolean("runtime_skeleton_ready") && runtime.getBoolean("main_looper_ready")) {
            "Core runtime reported running before its main-Looper startup barrier completed"
        }
        check(runtime.optBoolean("business_preflight_ready", false) &&
            state.optBoolean("business_preflight_ready", false)) {
            "Core runtime is reachable but business dependency preflight is not ready: ${runtime.opt("business_preflight")}"
        }
    }

    /**
     * Validate ownership identity independently from whether Dispatcher admission is currently open.
     * During OFF, QUIESCING/DRAINED is a valid Core-owned state with running=false/accepting=false.
     */
    private fun requireSnapshotConsistent(state: JSONObject) {
        val runtime = state.getJSONObject("core_runtime")
        check(state.optBoolean("business_preflight_ready", false) ==
            runtime.optBoolean("business_preflight_ready", false)) {
            "Core business dependency preflight snapshot is inconsistent"
        }
        check(state.getBoolean("business_attached") == runtime.getBoolean("business_attached")) {
            "Core business ownership snapshot is inconsistent"
        }
        check(
            state.getBoolean("foundational_runtime_ready") ==
                runtime.getBoolean("foundational_runtime_ready")
        ) {
            "Core foundational runtime readiness snapshot is inconsistent"
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
                runtime.getBoolean("foundational_runtime_ready") &&
                runtime.getBoolean("bridge_ingress_prepared") &&
                runtime.getBoolean("plugin_services_prepared") &&
                runtime.getBoolean("ubuntu_control_ready")) {
                "Core claims business ownership before Kernel / foundational runtime / Bridge / plugin services / Ubuntu are prepared"
            }
            val kernel = runtime.getJSONObject("plugin_kernel")
            val foundational = kernel.getJSONObject("foundational_runtime")
            check(
                kernel.getBoolean("foundational_runtime_ready") &&
                    foundational.getBoolean("started") &&
                    foundational.getBoolean("ready") &&
                    !foundational.getBoolean("stopped")
            ) {
                "Core claims business ownership without a READY foundational control-plane: $foundational"
            }
            val resources = state.getJSONObject("continuous_resources")
            check(resources.getString("state") == "active" &&
                resources.getString("owner") == "resident_core" &&
                resources.getString("owner_session") == state.getString("session_id") &&
                resources.getInt("owner_pid") == state.getInt("pid") &&
                resources.getBoolean("cpu_wake_requested") &&
                resources.getBoolean("cpu_wake_token_held") &&
                resources.getBoolean("network_callback_registered")) {
                "Core claims business ownership without session-bound CPU/network resources: $resources"
            }

            val dispatcher = state.getJSONObject("dispatcher")
            check(dispatcher.getString("dispatcher_owner") == "resident_core" &&
                dispatcher.getInt("owner_pid") == state.getInt("pid")) {
                "Core business owner lost the authoritative Dispatcher identity: $dispatcher"
            }
            val businessPhase = state.getString("business_phase")
            when (businessPhase) {
                "running" -> check(dispatcher.getBoolean("running") && dispatcher.getBoolean("accepting")) {
                    "Core RUNNING business must have an accepting Dispatcher: $dispatcher"
                }
                "quiescing", "drained", "quiesce_failed" ->
                    check(!dispatcher.getBoolean("accepting")) {
                        "Core $businessPhase business must not accept new Dispatcher work: $dispatcher"
                    }
                else -> Unit
            }
            if (businessPhase == "drained") {
                check(dispatcher.getBoolean("drained")) {
                    "Core DRAINED business must report a drained Dispatcher: $dispatcher"
                }
            }
            val policyRuntime = dispatcher.optJSONObject("runtime")
            check(policyRuntime != null &&
                policyRuntime.getString("policy_owner") == "resident_core" &&
                policyRuntime.getInt("owner_pid") == state.getInt("pid")) {
                "Core Dispatcher does not own the policy plane: $dispatcher"
            }
        }
        check(state.getBoolean("plugins_migrated") == runtime.getBoolean("plugin_services_prepared")) {
            "Core plugin migration marker is inconsistent with the business runtime"
        }
        check(!state.getBoolean("continuous_work")) {
            "Core must not claim continuous work before real-device power/freezer validation"
        }
    }

    internal fun requestCore(context: Context, operation: String, sessionId: String? = null): JSONObject =
        ResidentCoreWire.request(operation, sessionId, ResidentCoreEndpoint.candidates(directory(context)))

    internal fun bootstrapLeaseIsFree(context: Context): Boolean =
        leaseIsFree(context)

    private fun directory(context: Context): File =
        File(context.filesDir, "ai_limbs/resident_core")

    private fun leaseIsFree(context: Context): Boolean {
        val lease = ResidentRuntimeLease.tryAcquire(directory(context), "bootstrap") ?: return false
        lease.close()
        return true
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
