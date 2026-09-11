package com.ai.assistance.operit.integrations.ailimbs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsWorkModeGateTest {
    @Test
    fun nonWorkGrantsExactlyOneNormalExecution() {
        val gate = AiLimbsWorkModeGate()

        assertEquals(AiLimbsWorkGateState.SELECTION_REQUIRED, gate.state())
        assertEquals(
            AiLimbsWorkGateState.NON_WORK_ONCE,
            gate.select(AiLimbsWorkMode.NON_WORK)
        )
        assertTrue(gate.claimNormalExecution())
        assertEquals(AiLimbsWorkGateState.SELECTION_REQUIRED, gate.state())
        assertFalse(gate.claimNormalExecution())
    }

    @Test
    fun repeatedNonWorkSelectionDoesNotAccumulatePermits() {
        val gate = AiLimbsWorkModeGate()

        gate.select(AiLimbsWorkMode.NON_WORK)
        gate.select(AiLimbsWorkMode.NON_WORK)
        assertTrue(gate.claimNormalExecution())
        assertFalse(gate.claimNormalExecution())
        assertEquals(AiLimbsWorkGateState.SELECTION_REQUIRED, gate.state())
    }

    @Test
    fun workRequiresManualThenUnlocksForCycle() {
        val gate = AiLimbsWorkModeGate()

        assertEquals(
            AiLimbsWorkGateState.WORK_MANUAL_REQUIRED,
            gate.select(AiLimbsWorkMode.WORK)
        )
        assertFalse(gate.claimNormalExecution())

        gate.onWorkManualRead()
        assertEquals(AiLimbsWorkGateState.WORK_UNLOCKED, gate.state())
        assertTrue(gate.claimNormalExecution())
        assertTrue(gate.claimNormalExecution())

        assertEquals(
            AiLimbsWorkGateState.WORK_UNLOCKED,
            gate.select(AiLimbsWorkMode.NON_WORK)
        )
        assertTrue(gate.claimNormalExecution())
    }

    @Test
    fun resetReturnsGateToFreshCycle() {
        val gate = AiLimbsWorkModeGate()
        gate.select(AiLimbsWorkMode.WORK)
        gate.onWorkManualRead()

        gate.reset()

        assertEquals(AiLimbsWorkGateState.SELECTION_REQUIRED, gate.state())
        assertFalse(gate.claimNormalExecution())
    }
}
