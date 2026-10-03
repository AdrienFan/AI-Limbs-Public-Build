package com.ai.assistance.operit.plugins.center.isolation

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

/** Keep page continuations available while a serial business request waits for its receipt. */
internal class PluginRuntimeRequestGate {
    private val business = ReentrantLock(true)
    private val lifecycle = ReentrantReadWriteLock(true)

    fun <T> dispatch(operation: String, isStopping: () -> Boolean, action: () -> T): T {
        fun active(): T {
            check(!isStopping()) { "Plugin runtime is stopping" }
            return action()
        }
        return when (operation) {
            // Health reads immutable process identity only, including during mount/retirement.
            "status", "ping" -> active()
            "provider_ui_event", "child_ui_event", "snapshot_plugin", "child_snapshot" ->
                lifecycle.read { active() }
            "mount", "stop_plugin", "start_children", "stop_children",
            "child_control", "child_install", "stop" ->
                business.withLock {
                    // Acquire business first: a queued writer must not prevent the UI callback
                    // that releases the business request currently waiting for a page receipt.
                    lifecycle.write { active() }
                }
            else -> business.withLock { lifecycle.read { active() } }
        }
    }
}
