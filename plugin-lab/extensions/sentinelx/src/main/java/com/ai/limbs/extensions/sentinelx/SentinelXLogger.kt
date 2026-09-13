package com.ai.limbs.extensions.sentinelx

import android.util.Log
import com.ai.limbs.plugin.runtime.InProcessRuntimeLogger

internal object SentinelXLogger {
    @Volatile private var hostLogger: InProcessRuntimeLogger? = null

    fun bind(logger: InProcessRuntimeLogger?) { hostLogger = logger }
    fun d(tag: String, message: String): Int = hostLogger?.d(tag, message) ?: Log.d(tag, message)
    fun i(tag: String, message: String): Int = hostLogger?.i(tag, message) ?: Log.i(tag, message)
    fun w(tag: String, message: String): Int = hostLogger?.w(tag, message) ?: Log.w(tag, message)
    fun w(tag: String, message: String, error: Throwable): Int = hostLogger?.w(tag, message, error) ?: Log.w(tag, message, error)
    fun e(tag: String, message: String): Int = hostLogger?.e(tag, message) ?: Log.e(tag, message)
    fun e(tag: String, message: String, error: Throwable): Int = hostLogger?.e(tag, message, error) ?: Log.e(tag, message, error)
}
