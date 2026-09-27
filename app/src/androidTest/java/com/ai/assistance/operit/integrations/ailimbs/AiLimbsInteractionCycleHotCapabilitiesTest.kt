package com.ai.assistance.operit.integrations.ailimbs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiLimbsInteractionCycleHotCapabilitiesTest {
    private fun newState(): AiLimbsInteractionCycleRuntimeState {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        return AiLimbsInteractionCycleRuntimeState(context)
    }

    @Test
    fun hotCapabilitiesCanBeClaimedOncePerGeneration() {
        val state = newState()
        val firstGeneration = state.currentGeneration()

        assertTrue(state.claimHotCapabilities(firstGeneration))
        assertFalse(state.claimHotCapabilities(firstGeneration))
        assertFalse(state.claimHotCapabilities(firstGeneration + 1L))

        val reset = state.resetInteractionCycle()
        assertTrue(reset.appliedImmediately)
        assertEquals(firstGeneration + 1L, state.currentGeneration())
        assertTrue(state.claimHotCapabilities(state.currentGeneration()))
        assertFalse(state.claimHotCapabilities(state.currentGeneration()))
    }

    @Test
    fun residentHandoffPreservesDeliveredGeneration() {
        val source = newState()
        val generation = source.currentGeneration()
        assertTrue(source.claimHotCapabilities(generation))

        source.beginResidentHandoffFreeze()
        val handoff = source.exportFrozenHandoffState()
        assertEquals(
            generation,
            handoff.getLong("hot_capabilities_delivered_generation")
        )

        val restored = newState()
        restored.restoreHandoffState(handoff)
        assertEquals(generation, restored.currentGeneration())
        assertFalse(restored.claimHotCapabilities(generation))
    }
}
