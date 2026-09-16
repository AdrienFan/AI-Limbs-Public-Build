package com.ai.assistance.operit.core.application

import android.content.Context

/**
 * Process-local application Context for runtimes that do not instantiate [OperitApplication].
 *
 * Resident Core is launched through app_process and deliberately must not construct a second
 * OperitApplication. Business code that only needs an application Context should depend on this
 * registry instead of assuming the Android Host Application exists in every process.
 */
internal object OperitProcessContext {
    private val lock = Any()

    @Volatile
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        val candidate = context.applicationContext
        synchronized(lock) {
            val current = applicationContext
            if (current == null) {
                applicationContext = candidate
                return
            }
            check(current.packageName == candidate.packageName) {
                "Process application Context package changed: ${current.packageName} -> ${candidate.packageName}"
            }
            check(current.filesDir.canonicalFile == candidate.filesDir.canonicalFile) {
                "Process application Context filesDir changed"
            }
        }
    }

    fun require(): Context = checkNotNull(applicationContext) {
        "Process application Context has not been initialized"
    }

    fun currentOrNull(): Context? = applicationContext
}
