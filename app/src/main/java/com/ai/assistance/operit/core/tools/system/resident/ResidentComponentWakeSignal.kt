package com.ai.assistance.operit.core.tools.system.resident

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A generation avoids losing a request arriving between an empty queue check and waiting. */
internal class ResidentComponentWakeSignal {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var generation = 0L

    fun version(): Long = lock.withLock { generation }

    fun signal() = lock.withLock {
        generation++
        changed.signalAll()
    }

    fun awaitChange(observedVersion: Long, waitMs: Long) = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(waitMs)
        while (generation == observedVersion && remaining > 0L) {
            remaining = changed.awaitNanos(remaining)
        }
    }
}
