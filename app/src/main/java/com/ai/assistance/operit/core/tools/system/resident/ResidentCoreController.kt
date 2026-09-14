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
                return@withContext JSONObject().put("stopped", true).put("pid", pid)
            }
        }
        error("Core accepted stop but process exit has not been confirmed")
    }

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
