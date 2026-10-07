package com.ai.limbs.plugins.visualmanager

import org.junit.Assert.*
import org.junit.Test

class VisualPollBudgetTest {
    @Test fun wholePollCostPreventsStartingTheKnownDeadlineOverrun() {
        val budget = VisualPollBudget(1800)
        assertTrue(budget.canStart(0))
        budget.completed(0, 640)
        assertTrue(budget.canStart(740))
        budget.completed(740, 1420)
        assertFalse(budget.canStart(1520)) // The former loop started a third poll here.
        assertEquals(680L, budget.longestPollMs)
    }
    @Test fun slowerObservedPollKeepsItsReservationWhenLaterPollsAreFast() {
        val budget = VisualPollBudget(2500)
        budget.completed(0, 750)
        budget.completed(850, 1350)
        assertFalse(budget.canStart(1800))
        assertTrue(budget.canStart(1600))
        assertFalse(budget.canStart(2500))
    }
    @Test fun firstHostCallMayOverrunAndMustNotBeMisrepresentedAsAHardDeadline() {
        val budget = VisualPollBudget(100)
        assertTrue(budget.canStart(0))
        budget.completed(0, 700)
        assertFalse(budget.canStart(700))
        assertEquals(700L, budget.longestPollMs)
    }
}
