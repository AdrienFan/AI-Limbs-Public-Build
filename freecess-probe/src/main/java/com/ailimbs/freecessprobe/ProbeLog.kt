package com.ailimbs.freecessprobe

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProbeLog {
    private const val TAG = "FreecessProbe"
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, "freecess-probe.log")
    }

    fun d(scope: String, message: String) = write("D", scope, message, null)
    fun i(scope: String, message: String) = write("I", scope, message, null)
    fun w(scope: String, message: String, error: Throwable? = null) = write("W", scope, message, error)
    fun e(scope: String, message: String, error: Throwable? = null) = write("E", scope, message, error)

    private fun write(level: String, scope: String, message: String, error: Throwable?) {
        val line = "${formatter.format(Date())} $level/$scope $message"
        when (level) {
            "E" -> Log.e(TAG, "$scope $message", error)
            "W" -> Log.w(TAG, "$scope $message", error)
            "D" -> Log.d(TAG, "$scope $message")
            else -> Log.i(TAG, "$scope $message")
        }
        ProbeRuntime.addLog(line)
        runCatching {
            logFile?.appendText(buildString {
                append(line).append('\n')
                if (error != null) append(Log.getStackTraceString(error)).append('\n')
            })
        }
    }

    fun clearFile() {
        runCatching { logFile?.writeText("") }
    }
}
