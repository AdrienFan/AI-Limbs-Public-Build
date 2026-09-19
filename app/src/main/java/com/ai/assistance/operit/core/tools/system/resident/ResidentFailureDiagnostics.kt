package com.ai.assistance.operit.core.tools.system.resident

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.RandomAccessFile
import kotlin.system.exitProcess
import org.json.JSONArray
import org.json.JSONObject

/** Bounded forensic records only; these files never grant ownership or authorize recovery. */
internal object ResidentFailureDiagnostics {
    private const val TAG = "ResidentDiagnostics"
    private const val TAIL_BYTES = 32 * 1024

    fun captureBlockedHost(context: Context, attachment: ResidentHostRuntimeAttachment) {
        runCatching {
            val fence = ResidentBusinessTakeoverFence.snapshot(context) ?: return
            captureOwnerLoss(context, attachment.snapshot(), fence)
        }.onFailure { AppLogger.w(TAG, "Could not capture blocked Host evidence", it) }
    }

    fun captureOwnerLoss(context: Context, core: JSONObject, fence: JSONObject) {
        runCatching {
            val root = File(context.filesDir, "ai_limbs")
            val directory = File(root, "resident_diagnostics")
            check(directory.mkdirs() || directory.isDirectory)
            val target = File(directory, "owner-loss.json")
            val coreSession = fence.optString("core_session")
            // Repeated checks of one lost owner must not replace its first incident evidence.
            if (coreSession.isNotBlank() && target.isFile &&
                runCatching { JSONObject(target.readText()).optString("core_session") }.getOrNull() == coreSession
            ) return
            val record = identity("host_owner_loss")
                .put("core_session", coreSession)
                .put("runtime_observation", core)
                .put("takeover_fence", fence)
                .put("guardian_state", tail(File(root, "resident/guardian.state")))
                .put("guardian_heartbeat_tail", tail(File(root, "resident/heartbeat.log")))
                .put("guardian_action_tail", tail(File(root, "resident/host-action.log")))
                .put("core_bootstrap_tail", tail(File(root, "resident_core/bootstrap.log")))
                .put("host_log_tail", tail(File(context.filesDir, "logs/operit.log")))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val exits = JSONArray()
                try {
                    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    manager.getHistoricalProcessExitReasons(context.packageName, 0, 16).forEach { info ->
                        exits.put(JSONObject()
                            .put("timestamp", info.timestamp)
                            .put("pid", info.pid)
                            .put("process", info.processName)
                            .put("reason", info.reason)
                            .put("status", info.status)
                            .put("importance", info.importance)
                            .put("description", info.description?.take(1024) ?: JSONObject.NULL))
                    }
                    record.put("android_process_exits", exits)
                } catch (error: Exception) {
                    record.put("android_process_exits_error", error.toString().take(1024))
                }
            }
            writeRotated(target, record)
        }.onFailure { AppLogger.w(TAG, "Could not preserve Resident owner-loss evidence", it) }
    }

    fun installUncaughtHandler(directory: File, role: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            recordFailure(directory, role, thread.name, error)
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(1)
            }
        }
    }

    fun recordFailure(directory: File, role: String, thread: String, error: Throwable) {
        runCatching {
            writeRotated(File(directory, "last-failure.json"), identity(role)
                .put("thread", thread)
                .put("error", error.stackTraceToString().take(TAIL_BYTES)))
        }.onFailure {
            System.err.println("Resident failure evidence could not be written: $it")
        }
    }

    private fun identity(role: String): JSONObject = JSONObject()
        .put("role", role)
        .put("wall_ms", System.currentTimeMillis())
        .put("elapsed_ms", SystemClock.elapsedRealtime())
        .put("uptime_ms", SystemClock.uptimeMillis())
        .put("pid", Process.myPid())
        .put("uid", Process.myUid())
        .put("build_code", BuildConfig.VERSION_CODE)

    private fun tail(file: File): String {
        if (!file.isFile) return ""
        return RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val bytes = ByteArray(minOf(length, TAIL_BYTES.toLong()).toInt())
            input.seek(length - bytes.size)
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }

    @Synchronized
    private fun writeRotated(target: File, record: JSONObject) {
        val staged = File(target.parentFile, target.name + ".tmp")
        staged.writeText(record.toString(2))
        if (target.isFile) {
            val previous = File(target.parentFile, target.name + ".previous")
            check(!previous.exists() || previous.delete())
            check(target.renameTo(previous))
        }
        check(staged.renameTo(target))
    }
}
