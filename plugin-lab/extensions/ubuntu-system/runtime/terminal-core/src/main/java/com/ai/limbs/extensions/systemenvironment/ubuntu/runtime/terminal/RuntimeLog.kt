package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal

import android.util.Log as AndroidLog
import com.ai.limbs.plugin.runtime.InProcessRuntimeLogger

internal object RuntimeLog {
    @Volatile private var hostLogger: InProcessRuntimeLogger? = null
    fun bind(logger: InProcessRuntimeLogger?) { hostLogger = logger }
    fun d(tag: String, message: String): Int = hostLogger?.d(tag, message) ?: AndroidLog.d(tag, message)
    fun d(tag: String, message: String, error: Throwable): Int = hostLogger?.let { it.d(tag, message + "\n" + error.stackTraceToString()) } ?: AndroidLog.d(tag, message, error)
    fun i(tag: String, message: String): Int = hostLogger?.i(tag, message) ?: AndroidLog.i(tag, message)
    fun i(tag: String, message: String, error: Throwable): Int = hostLogger?.let { it.i(tag, message + "\n" + error.stackTraceToString()) } ?: AndroidLog.i(tag, message, error)
    fun w(tag: String, message: String): Int = hostLogger?.w(tag, message) ?: AndroidLog.w(tag, message)
    fun w(tag: String, message: String, error: Throwable): Int = hostLogger?.w(tag, message, error) ?: AndroidLog.w(tag, message, error)
    fun w(tag: String, error: Throwable): Int = hostLogger?.w(tag, error.message ?: error::class.java.simpleName, error) ?: AndroidLog.w(tag, error)
    fun e(tag: String, message: String): Int = hostLogger?.e(tag, message) ?: AndroidLog.e(tag, message)
    fun e(tag: String, message: String, error: Throwable): Int = hostLogger?.e(tag, message, error) ?: AndroidLog.e(tag, message, error)
}
