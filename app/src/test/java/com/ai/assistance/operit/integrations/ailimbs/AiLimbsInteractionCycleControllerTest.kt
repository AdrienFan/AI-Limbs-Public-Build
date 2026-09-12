package com.ai.assistance.operit.integrations.ailimbs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsInteractionCycleControllerTest {
    @Test
    fun `continuous activity can exceed timeout without rotating`() {
        var now = 0L
        val controller = AiLimbsInteractionCycleController(
            timeoutProvider = { 60_000L },
            clockMs = { now }
        )

        assertFalse(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()

        repeat(10) {
            now += 30_000L
            assertFalse(controller.beginInvocation().startedNewCycle)
            controller.endInvocation()
        }

        now += 60_000L
        assertTrue(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()
    }

    @Test
    fun `long invocation completion starts a fresh inactivity window`() {
        var now = 0L
        val controller = AiLimbsInteractionCycleController(
            timeoutProvider = { 60_000L },
            clockMs = { now }
        )

        assertFalse(controller.beginInvocation().startedNewCycle)
        now = 120_000L
        controller.endInvocation()

        assertFalse(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()

        now = 179_999L
        assertFalse(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()

        now = 239_999L
        assertTrue(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()
    }

    @Test
    fun `overlapping work never rotates until a real idle window follows`() {
        var now = 0L
        val controller = AiLimbsInteractionCycleController(
            timeoutProvider = { 60_000L },
            clockMs = { now }
        )

        assertFalse(controller.beginInvocation().startedNewCycle)
        now = 120_000L
        assertFalse(controller.beginInvocation().startedNewCycle)

        controller.endInvocation()
        now = 180_000L
        controller.endInvocation()

        assertFalse(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()

        now = 240_000L
        assertTrue(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()
    }

    @Test
    fun `invalid configured timeout falls back to hard default inactivity window`() {
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

        now += AiLimbsInteractionCyclePolicyStore.DEFAULT_TIMEOUT_MS
        assertTrue(controller.beginInvocation().startedNewCycle)
        controller.endInvocation()
    }
}
