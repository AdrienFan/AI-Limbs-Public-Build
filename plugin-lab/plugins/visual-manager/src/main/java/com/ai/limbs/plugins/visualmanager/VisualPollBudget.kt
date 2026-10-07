package com.ai.limbs.plugins.visualmanager

/** Admission budget, not cancellation: an in-flight Host call must complete and release its files. */
internal class VisualPollBudget(private val deadline: Long) {
    var longestPollMs = 0L
        private set
    fun completed(started: Long, ended: Long) {
        longestPollMs = maxOf(longestPollMs, (ended - started).coerceAtLeast(0))
    }
    fun canStart(now: Long): Boolean {
        val remaining = deadline - now
        if (remaining <= 0) return false
        if (longestPollMs == 0L) return true
        return remaining > longestPollMs + minOf(50L, maxOf(1L, longestPollMs / 10))
    }
}
