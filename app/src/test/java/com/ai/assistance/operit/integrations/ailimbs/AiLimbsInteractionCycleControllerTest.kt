package com.ai.assistance.operit.integrations.ailimbs

import org.junit.Assert.assertFalse
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
