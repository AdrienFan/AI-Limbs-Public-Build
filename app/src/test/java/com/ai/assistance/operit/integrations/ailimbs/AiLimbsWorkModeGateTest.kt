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
    fun nonWorkUsesSlidingIdleWindow() {
        var now = 1_000L
        val gate = AiLimbsWorkModeGate(
            nonWorkIdleTimeoutMsProvider = { 30_000L },
            clockMs = { now }
        )

        assertEquals(AiLimbsWorkGateState.SELECTION_REQUIRED, gate.state())
        assertEquals(
            AiLimbsWorkGateState.NON_WORK_ACTIVE,
            gate.select(AiLimbsWorkMode.NON_WORK)
        )

        assertTrue(gate.claimNormalExecution())
        gate.onNormalExecutionFinished()

        now += 29_999L
        assertEquals(AiLimbsWorkGateState.NON_WORK_ACTIVE, gate.state())
        assertTrue(gate.claimNormalExecution())
        gate.onNormalExecutionFinished()

        now += 30_000L
        assertEquals(AiLimbsWorkGateState.SELECTION_REQUIRED, gate.state())
        assertFalse(gate.claimNormalExecution())
    }

    @Test
    fun repeatedNonWorkSelectionRefreshesIdleWindowWithoutStacking() {
        var now = 2_000L
        val gate = AiLimbsWorkModeGate(
            nonWorkIdleTimeoutMsProvider = { 30_000L },
            clockMs = { now }
        )

        gate.select(AiLimbsWorkMode.NON_WORK)
        now += 25_000L
        assertEquals(
            AiLimbsWorkGateState.NON_WORK_ACTIVE,
            gate.select(AiLimbsWorkMode.NON_WORK)
        )

        now += 25_000L
        assertEquals(AiLimbsWorkGateState.NON_WORK_ACTIVE, gate.state())

        now += 5_000L
        assertEquals(AiLimbsWorkGateState.SELECTION_REQUIRED, gate.state())
    }

    @Test
    fun longRunningNonWorkExecutionDoesNotExpireMidAction() {
        var now = 3_000L
        val gate = AiLimbsWorkModeGate(
            nonWorkIdleTimeoutMsProvider = { 30_000L },
            clockMs = { now }
        )

        gate.select(AiLimbsWorkMode.NON_WORK)
        assertTrue(gate.claimNormalExecution())

        now += 120_000L
        assertEquals(AiLimbsWorkGateState.NON_WORK_ACTIVE, gate.state())

        gate.onNormalExecutionFinished()
        now += 29_999L
        assertEquals(AiLimbsWorkGateState.NON_WORK_ACTIVE, gate.state())

        now += 1L
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
