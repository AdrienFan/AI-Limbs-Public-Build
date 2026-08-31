package com.ai.assistance.operit.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The only logger owned by the Plugin Lab base. Plugin access is mediated through
 * the scoped core.logs.read host capability; no Android Context is exposed.
 */
object AppLogger {
    @Volatile private var appContext: Context? = null
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "plugin-lab-log-writer").apply { isDaemon = true }
    }
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @JvmStatic fun bindContext(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic fun getLogFile(): File? =
        appContext?.let { File(it.filesDir, "logs/plugin-lab.log") }

    @JvmStatic fun resetLogFile() {
        getLogFile()?.let { file ->
            writer.execute {
                file.parentFile?.mkdirs()
                file.writeText("")
            }
        }
    }

    fun d(tag: String, message: String): Int = emit(Log.DEBUG, tag, message, null)
    fun i(tag: String, message: String): Int = emit(Log.INFO, tag, message, null)
    fun w(tag: String, message: String): Int = emit(Log.WARN, tag, message, null)
    fun w(tag: String, message: String, error: Throwable): Int = emit(Log.WARN, tag, message, error)
    fun e(tag: String, message: String): Int = emit(Log.ERROR, tag, message, null)
    fun e(tag: String, message: String, error: Throwable): Int = emit(Log.ERROR, tag, message, error)

    private fun emit(priority: Int, tag: String, message: String, error: Throwable?): Int {
        val result = if (error == null) Log.println(priority, tag, message)
        else Log.println(priority, tag, "$message\n${error.stackTraceToString()}")
        val time = synchronized(timestamp) { timestamp.format(Date()) }
        val level = when (priority) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            else -> "E"
        }
        val suffix = error?.let { "\n${it.stackTraceToString()}" }.orEmpty()
        val line = "$time $level/$tag: $message$suffix\n"
        getLogFile()?.let { file ->
            writer.execute {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.appendText(line)
                }
            }
        }
        return result
    }
}
