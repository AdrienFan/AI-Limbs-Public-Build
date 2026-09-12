package com.ai.assistance.operit.integrations.ailimbs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsInteractionCycleControllerTest {
    @Test
    fun `elapsed timeout rotates only at a later ingress boundary`() {
        var now = 0L
        val controller = AiLimbsInteractionCycleController(
            timeoutProvider = { 60_000L },
            clockMs = { now }
        )

        assertFalse(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()

        now = 59_999L
        assertFalse(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()

        now = 60_000L
        assertTrue(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()
    }

    @Test
    fun `invocation crossing timeout is never interrupted`() {
        var now = 0L
        val controller = AiLimbsInteractionCycleController(
            timeoutProvider = { 60_000L },
            clockMs = { now }
        )

        assertFalse(controller.beginInvocation().startedNewCycle)
        now = 120_000L
        controller.endInvocation()

        assertTrue(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()
    }

    @Test
    fun `overlapping work stays in old generation until all active invocations finish`() {
        var now = 0L
        val controller = AiLimbsInteractionCycleController(
            timeoutProvider = { 60_000L },
            clockMs = { now }
        )

        assertFalse(controller.beginInvocation().startedNewCycle)
        now = 120_000L
        assertFalse(controller.beginInvocation().startedNewCycle)

        controller.endInvocation()
        controller.endInvocation()

        assertTrue(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()
    }

    @Test
    fun `manual reset restarts timeout from reset instant`() {
        var now = 0L
        val controller = AiLimbsInteractionCycleController(
            timeoutProvider = { 60_000L },
            clockMs = { now }
        )

        assertEquals(1L, controller.beginInvocation().generation)
        controller.endInvocation()
        now = 50_000L
        val reset = controller.resetFromNow()
        assertTrue(reset.appliedImmediately)
        assertEquals(2L, reset.generation)
        assertEquals(50_000L, reset.cycleStartedAtMs)

        now = 109_999L
        assertFalse(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()
        now = 110_000L
        assertTrue(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()
    }

    @Test
    fun `manual reset waits for active work but keeps click time as new clock origin`() {
        var now = 0L
        val controller = AiLimbsInteractionCycleController(
            timeoutProvider = { 60_000L },
            clockMs = { now }
        )

        assertEquals(1L, controller.beginInvocation().generation)
        now = 30_000L
        val reset = controller.resetFromNow()
        assertFalse(reset.appliedImmediately)
        assertEquals(2L, reset.generation)
        assertEquals(30_000L, reset.cycleStartedAtMs)

        now = 40_000L
        val blocked = controller.beginInvocation()
        assertFalse(blocked.admitted)
        assertEquals(2L, blocked.generation)
        val completed = controller.endInvocation()
        assertNotNull(completed)
        assertEquals(2L, completed!!.generation)
        assertEquals(30_000L, completed.cycleStartedAtMs)

        now = 89_999L
        assertFalse(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()
        now = 90_000L
        assertTrue(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()
    }

    @Test
    fun `invalid configured timeout falls back to hard default`() {
        var now = 0L
        val controller = AiLimbsInteractionCycleController(
            timeoutProvider = { -1L },
            clockMs = { now }
        )

        assertFalse(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()

        now = AiLimbsInteractionCyclePolicyStore.DEFAULT_TIMEOUT_MS - 1L
        assertFalse(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()

        now = AiLimbsInteractionCyclePolicyStore.DEFAULT_TIMEOUT_MS
        assertTrue(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()
    }
}
