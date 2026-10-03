package com.ai.limbs.plugins.artstudio

/** Main-thread tickets: polling must not invalidate a frame that is still being rendered. */
internal class StudioRenderRequests {
    private var generation = 0L
    private var refresh: Long? = null

    fun beginRefresh(): Long? {
        if (refresh != null) return null
        return invalidate().also { refresh = it }
    }
    fun invalidate(): Long = ++generation
    fun isCurrent(ticket: Long): Boolean = ticket == generation
    fun finishRefresh(ticket: Long) {
        check(refresh == ticket) { "Refresh ticket does not own the active render" }
        refresh = null
    }
}
