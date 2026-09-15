package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import org.json.JSONObject

/**
 * Persistent no-fallback fence for the Plugin Kernel owner handoff.
 *
 * Once Core arms a takeover, a restarted Android Host must not silently recreate a LEGACY_HOST
 * Plugin Kernel. The fence is cleared only by the still-owning Host cancelling before retirement or
 * by an explicit Core stop. A failed Core takeover deliberately leaves an observable failed fence.
 */
internal object ResidentBusinessTakeoverFence {
    private const val FILE_NAME = "business_takeover.json"

    fun arm(context: Context, coreSession: String, corePid: Int, hostPid: Int) {
        require(coreSession.length in 1..64)
        check(corePid == Process.myPid()) { "Only Resident Core may arm its takeover fence" }
        check(hostPid > 0 && hostPid != corePid) { "Invalid Host PID for takeover" }
        check(!file(context).exists()) { "Resident takeover fence already exists; explicit recovery is required" }
        write(context, JSONObject()
            .put("state", "armed")
            .put("core_session", coreSession)
            .put("core_pid", corePid)
            .put("host_pid", hostPid)
            .put("updated_wall_ms", System.currentTimeMillis()))
    }

    fun markOwned(context: Context, coreSession: String) {
        val current = requireCurrent(context, coreSession)
        check(current.getString("state") == "armed") { "Only an armed takeover may become owned" }
        write(context, current
            .put("state", "owned")
            .put("updated_wall_ms", System.currentTimeMillis()))
    }

    fun markFailed(context: Context, coreSession: String, error: Throwable) {
        val current = runCatching { requireCurrent(context, coreSession) }.getOrElse {
            JSONObject()
                .put("core_session", coreSession)
                .put("core_pid", Process.myPid())
        }
        write(context, current
            .put("state", "failed")
            .put("error", error.toString().take(1024))
            .put("updated_wall_ms", System.currentTimeMillis()))
    }

    fun cancelByHost(context: Context, coreSession: String, hostPid: Int) {
        val current = requireCurrent(context, coreSession)
        check(current.getString("state") == "armed") { "Takeover can only be cancelled before Host retirement" }
        check(current.getInt("host_pid") == hostPid) { "Only the Host that armed takeover may cancel it" }
        check(file(context).delete()) { "Could not clear cancelled takeover fence" }
    }

    fun clearForExplicitCoreStop(context: Context, coreSession: String) {
        val target = file(context)
        if (!target.isFile) return
        val current = readBounded(target)
        check(current.getString("core_session") == coreSession) { "Refusing to clear another Core session's takeover fence" }
        check(target.delete()) { "Could not clear Resident takeover fence" }
    }

    fun assertLegacyHostStartAllowed(context: Context) {
        val target = file(context)
        if (!target.isFile) return
        val current = readBounded(target)
        error(
            "Resident Core takeover fence is ${current.optString("state", "unknown")} " +
                "for session=${current.optString("core_session", "unknown")}; " +
                "LEGACY_HOST business startup is forbidden until an explicit stop/cancel clears it"
        )
    }

    fun snapshot(context: Context): JSONObject? =
        file(context).takeIf { it.isFile }?.let(::readBounded)

    fun clearAfterVerifiedOwnerLoss(context: Context) {
        val target = file(context)
        if (!target.isFile) return
        val current = readBounded(target)
        val corePid = current.optInt("core_pid", -1)
        check(corePid > 0 && !processExists(corePid)) {
            "Refusing to clear takeover fence while recorded Core PID is still alive"
        }
        check(target.delete()) { "Could not clear stale Resident takeover fence" }
    }

    private fun processExists(pid: Int): Boolean =
        try {
            Os.kill(pid, 0)
            true
        } catch (error: android.system.ErrnoException) {
            if (error.errno == OsConstants.ESRCH) false else throw error
        }

    private fun requireCurrent(context: Context, coreSession: String): JSONObject {
        val current = readBounded(file(context))
        check(current.getString("core_session") == coreSession) { "Resident takeover session changed" }
        check(current.getInt("core_pid") == Process.myPid()) { "Resident takeover Core PID changed" }
        return current
    }

    private fun write(context: Context, value: JSONObject) {
        val target = file(context)
        check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
        val staged = File(target.parentFile, "$FILE_NAME.tmp.${Process.myPid()}")
        staged.writeText(value.toString())
        Os.rename(staged.absolutePath, target.absolutePath)
    }

    private fun file(context: Context): File =
        File(context.filesDir, "ai_limbs/runtime_owner/$FILE_NAME")

    private fun readBounded(file: File): JSONObject {
        check(file.isFile && file.length() in 1L..4096L) { "Resident takeover fence is invalid" }
        return JSONObject(file.readText())
    }
}
