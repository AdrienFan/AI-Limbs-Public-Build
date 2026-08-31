package com.ai.assistance.operit.plugins.runtime

import android.os.SystemClock
import com.ai.assistance.operit.util.AppLogger

internal fun runtimeTimingNow(): Long = SystemClock.elapsedRealtime()

internal fun logRuntimeTiming(
    stage: String,
    startTimeMs: Long,
    details: String? = null
) {
    val elapsed = SystemClock.elapsedRealtime() - startTimeMs
    val suffix = details?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
    AppLogger.d("PluginRuntimeTiming", "$stage elapsed=${elapsed}ms$suffix")
}
