package com.ai.assistance.operit.core.tools.system.resident

import java.io.File

/** Process identity must not rely on PID alone because Android can reuse PIDs after Core death. */
internal object ResidentCoreProcessIdentity {
    private const val CORE_PROCESS_NAME = "ail_resident_core"

    fun isCurrentProcessCore(): Boolean =
        runCatching {
            val bytes = File("/proc/self/cmdline").readBytes()
            val end = bytes.indexOf(0).let { if (it >= 0) it else bytes.size }
            bytes.copyOfRange(0, end).toString(Charsets.UTF_8).trim() == CORE_PROCESS_NAME
        }.getOrDefault(false)
}
