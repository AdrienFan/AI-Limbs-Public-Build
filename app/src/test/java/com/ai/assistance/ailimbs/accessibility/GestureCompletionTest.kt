package com.ai.assistance.ailimbs.accessibility

import org.junit.Assert.*
import org.junit.Test

class GestureCompletionTest {
    @Test fun completionAndCancellationHaveDifferentTerminalResults() {
        for (outcome in listOf(GestureOutcome.COMPLETED, GestureOutcome.CANCELLED, GestureOutcome.REJECTED)) {
            val completion = GestureCompletion()
            completion.complete(outcome)
            assertEquals(outcome, completion.await(1))
        }
    }
    @Test fun admittedGestureWithoutCallbackIsNotReportedCompleted() {
        val completion = GestureCompletion()
        assertEquals(GestureOutcome.TIMEOUT, completion.await(1))
        completion.complete(GestureOutcome.COMPLETED)
        assertEquals(GestureOutcome.TIMEOUT, completion.await(1))
    }
    @Test fun cancellationCannotBeOverwrittenByALateCompletion() {
        val completion = GestureCompletion()
        completion.complete(GestureOutcome.CANCELLED)
        completion.complete(GestureOutcome.COMPLETED)
        assertEquals(GestureOutcome.CANCELLED, completion.await(1))
    }
}
