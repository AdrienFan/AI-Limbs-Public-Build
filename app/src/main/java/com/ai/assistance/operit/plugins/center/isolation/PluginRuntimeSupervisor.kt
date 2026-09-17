package com.ai.assistance.operit.plugins.center.isolation

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Core-owned supervisor for the isolated plugin runtime process.
 *
 * Worker failure is a plugin-layer failure, never a Resident Core failure. During the process-wall
 * phase the worker carries no business code, so launch failure is diagnostic-only. Once dynamic
 * runtimes migrate behind this boundary the same invariant remains: Core keeps Policy/Dispatcher
 * authority and can report/restart a failed plugin runtime without terminating itself.
 */
internal class PluginRuntimeSupervisor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diagnostic = AtomicReference(
        JSONObject()
            .put("supervisor_running", false)
            .put("phase", "stopped")
            .put("restart_count", 0)
            .toString()
    )

    @Volatile
    private var monitorJob: Job? = null
    private var restartCount = 0
    private var lastObservedPid: Int? = null

    fun start() {
        if (monitorJob?.isActive == true) return
        diagnostic.set(
            snapshotBase("starting")
                .put("supervisor_running", true)
                .toString()
        )
        monitorJob = scope.launch {
            while (isActive) {
                val state = try {
                    ensureRuntime()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failureSnapshot(error)
                }
                diagnostic.set(state.put("supervisor_running", true).toString())
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    fun snapshot(): JSONObject = JSONObject(diagnostic.get())

    suspend fun stop(stopWorker: Boolean = true) {
        val job = monitorJob
        monitorJob = null
        if (job != null) {
            job.cancelAndJoin()
        }
        val stopped = if (stopWorker) {
            runCatching { PluginRuntimeController.stop(appContext) }
                .getOrElse { error ->
                    failureSnapshot(error)
                        .put("phase", "stop_failed")
                }
        } else {
            runCatching { PluginRuntimeController.status(appContext) }
                .getOrElse(::failureSnapshot)
        }
        diagnostic.set(
            JSONObject(stopped.toString())
                .put("supervisor_running", false)
                .put("restart_count", restartCount)
                .toString()
        )
    }

    private suspend fun ensureRuntime(): JSONObject {
        val current = runCatching { PluginRuntimeController.status(appContext) }
            .getOrElse { error ->
                return failureSnapshot(error).put("phase", "status_failed")
            }
        if (current.optBoolean("available", false) && current.optBoolean("consistent", false)) {
            val pid = current.optInt("pid", -1).takeIf { it > 0 }
            lastObservedPid = pid
            return decorate(current)
        }

        val launched = PluginRuntimeController.probe(appContext)
        val pid = launched.optInt("pid", -1).takeIf { it > 0 }
        if (pid != null && pid != lastObservedPid) {
            restartCount += 1
            lastObservedPid = pid
        }
        return decorate(launched)
            .put("last_restart_elapsed_ms", SystemClock.elapsedRealtime())
    }

    private fun decorate(state: JSONObject): JSONObject =
        JSONObject(state.toString())
            .put("restart_count", restartCount)
            .put("last_observed_pid", lastObservedPid ?: JSONObject.NULL)

    private fun failureSnapshot(error: Throwable): JSONObject =
        snapshotBase("degraded")
            .put("error", error.toString().take(1024))
            .put("last_observed_pid", lastObservedPid ?: JSONObject.NULL)

    private fun snapshotBase(phase: String): JSONObject =
        JSONObject()
            .put("available", false)
            .put("consistent", false)
            .put("process_alive", false)
            .put("phase", phase)
            .put("restart_count", restartCount)
            .put("last_observed_pid", lastObservedPid ?: JSONObject.NULL)

    private companion object {
        const val MONITOR_INTERVAL_MS = 1_000L
    }
}
