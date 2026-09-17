package com.ai.assistance.operit.util

import android.content.Context
import java.io.File

object CrashRecoveryState {
    private const val PREFS_NAME = "crash_recovery_state"
    private const val KEY_PRESERVE_LOGS_FOR_CRASH_REPORT = "preserve_logs_for_crash_report"
    private const val LAST_CRASH_FILE_NAME = "last_crash.txt"
    private const val MAX_LAST_CRASH_CHARS = 48_000

    fun markPendingCrashReportLaunch(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRESERVE_LOGS_FOR_CRASH_REPORT, true)
            .commit()
    }

    /**
     * Persist the formatted exception independently from operit.log. The crash UI runs in :crash
     * and the main process may restart more than once; a dedicated bounded file keeps the original
     * failure available even if a later normal startup rotates the ordinary application log.
     */
    fun persistLastCrash(context: Context, stackTrace: String) {
        runCatching {
            val dir = File(context.applicationContext.filesDir, "logs")
            check(dir.mkdirs() || dir.isDirectory)
            File(dir, LAST_CRASH_FILE_NAME).writeText(stackTrace.take(MAX_LAST_CRASH_CHARS))
        }
    }

    fun readLastCrash(context: Context): String? =
        runCatching {
            File(context.applicationContext.filesDir, "logs/$LAST_CRASH_FILE_NAME")
                .takeIf { it.isFile && it.length() in 1L..(MAX_LAST_CRASH_CHARS * 4L) }
                ?.readText()
        }.getOrNull()

    fun consumePendingCrashReportLaunch(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val shouldPreserveLogs = prefs.getBoolean(KEY_PRESERVE_LOGS_FOR_CRASH_REPORT, false)
        if (shouldPreserveLogs) {
            prefs.edit().remove(KEY_PRESERVE_LOGS_FOR_CRASH_REPORT).commit()
        }
        return shouldPreserveLogs
    }
}
