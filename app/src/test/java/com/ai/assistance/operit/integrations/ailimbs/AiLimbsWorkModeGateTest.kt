package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
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

    @Test
    fun releasedCycleDoesNotReSelectModesOrRequestManual() = runBlocking {
        val context = Mockito.mock(Context::class.java)
        val preferences = Mockito.mock(SharedPreferences::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(File("build/test-ai-limbs-documents"))
        Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.eq(Context.MODE_PRIVATE)))
            .thenReturn(preferences)
        val gate = AiLimbsAccessGate(context)

        gate.releaseForCurrentCycle()

        assertEquals(AiLimbsWorkGateState.CYCLE_RELEASED, gate.selectWorkMode(AiLimbsWorkMode.WORK))
        assertEquals(AiLimbsWorkGateState.CYCLE_RELEASED, gate.selectWorkMode(AiLimbsWorkMode.NON_WORK))
        assertEquals("CYCLE_RELEASED", gate.snapshot().getString("work_gate_state"))
        assertFalse(gate.snapshot().getBoolean("work_manual_receipt"))
        assertTrue(gate.claimNormalExecution())

        gate.resetForContextBoundary()
        assertEquals(AiLimbsWorkGateState.SELECTION_REQUIRED, gate.workGateState())
    }
}
