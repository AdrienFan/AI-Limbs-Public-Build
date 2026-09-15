package com.ai.assistance.operit.core.tools.system.resident

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants

/**
 * Conservative liveness check across Host and run-as SELinux domains.
 * A denied signal probe is not evidence of exit. Keep waiting/holding the ownership fence until
 * ESRCH is observed; leases and authenticated IPC still establish the actual runtime owner.
 */
internal object ResidentProcessLiveness {
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
}
