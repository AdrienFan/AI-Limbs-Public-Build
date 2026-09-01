package com.ai.limbs.extensions.rdc

import android.util.Log

internal object RdcLogger {
    fun d(tag: String, message: String): Int = Log.d(tag, message)
    fun i(tag: String, message: String): Int = Log.i(tag, message)
    fun w(tag: String, message: String): Int = Log.w(tag, message)
    fun w(tag: String, message: String, error: Throwable): Int = Log.w(tag, message, error)
    fun e(tag: String, message: String): Int = Log.e(tag, message)
    fun e(tag: String, message: String, error: Throwable): Int = Log.e(tag, message, error)
}
