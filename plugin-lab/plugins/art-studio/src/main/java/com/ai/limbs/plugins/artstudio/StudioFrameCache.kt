package com.ai.limbs.plugins.artstudio

import java.util.concurrent.atomic.AtomicBoolean

/** One plugin-owned completed frame survives page detach; Views and page callbacks do not. */
internal class StudioFrameCache<T : Any>(private val dispose: (T) -> Unit) : AutoCloseable {
    private class Record<T>(val frame: T, var references: Int = 1)

    class Lease<T> internal constructor(val frame: T, private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    private var current: Record<T>? = null
    private var closed = false

    @Synchronized
    fun acquire(): Lease<T>? {
        check(!closed) { "画室页面 Provider 已停用" }
        return current?.let(::retain)
    }

    @Synchronized
    fun replace(frame: T?): Lease<T>? {
        check(!closed) { "画室页面 Provider 已停用" }
        val previous = current
        if (frame != null && previous != null && previous.frame === frame) return retain(previous)
        current = frame?.let { Record(it) }
        previous?.let(::release)
        return current?.let(::retain)
    }

    private fun retain(record: Record<T>): Lease<T> {
        record.references++
        return Lease(record.frame) { release(record) }
    }

    @Synchronized
    private fun release(record: Record<T>) {
        check(record.references > 0) { "渲染帧借用已释放" }
        if (--record.references == 0) dispose(record.frame)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val previous = current
        current = null
        previous?.let(::release)
    }
}
