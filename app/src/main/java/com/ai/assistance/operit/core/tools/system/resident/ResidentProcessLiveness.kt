package com.ai.assistance.operit.core.tools.system.resident

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Conservative liveness check across Host and run-as SELinux domains.
 * A denied signal probe is not evidence of exit. Keep waiting/holding the ownership fence until
 * ESRCH is observed; leases and authenticated IPC still establish the actual runtime owner.
 */
internal object ResidentProcessLiveness {
    private const val RESIDENT_CORE_PROCESS_NAME = "ail_resident_core"

    fun exists(pid: Int): Boolean {
        require(pid > 0) { "Resident liveness requires a positive PID" }
        return try {
            Os.kill(pid, 0)
            true
        } catch (error: ErrnoException) {
            when (error.errno) {
                OsConstants.ESRCH -> false
                OsConstants.EPERM, OsConstants.EACCES -> true
                else -> throw error
            }
        }
    }

    /**
     * PID values are diagnostic, not ownership facts: Android may recycle them after a Core exits.
     * Forced termination is allowed only when /proc still identifies the target as this app UID's
     * Resident Core. If procfs identity cannot be proven, fail closed and keep the ownership fence.
     */
    fun matchesResidentCore(pid: Int, expectedUid: Int): Boolean {
        if (pid <= 0 || !exists(pid)) return false
        val proc = File("/proc/$pid")
        val uid = runCatching {
            File(proc, "status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Uid:") }
                    ?.substringAfter("Uid:")
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.firstOrNull()
                    ?.toIntOrNull()
            }
        }.getOrNull() ?: return false
        if (uid != expectedUid) return false

        val comm = runCatching { File(proc, "comm").readText().trim() }.getOrNull()
        if (comm == RESIDENT_CORE_PROCESS_NAME) return true
        val cmdline = runCatching {
            String(File(proc, "cmdline").readBytes(), Charsets.UTF_8)
                .replace('\u0000', ' ')
                .trim()
        }.getOrNull()
        return cmdline?.contains(RESIDENT_CORE_PROCESS_NAME) == true
    }
}
