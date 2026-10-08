package com.ai.assistance.ailimbs.accessibility

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal enum class GestureOutcome { COMPLETED, CANCELLED, REJECTED, TIMEOUT }

/** Admission is not completion. A terminal result is immutable even if a callback arrives late. */
internal class GestureCompletion {
    private val outcome = AtomicReference<GestureOutcome?>(null)
    private val latch = CountDownLatch(1)

    fun complete(result: GestureOutcome) {
        if (outcome.compareAndSet(null, result)) latch.countDown()
    }

    fun await(timeoutMs: Long): GestureOutcome {
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) complete(GestureOutcome.TIMEOUT)
        return checkNotNull(outcome.get())
    }
}
