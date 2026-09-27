package com.ai.assistance.operit.integrations.ailimbs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsCapabilityHotContractTest {
    @Test
    fun hotIsAReadOnlyResolverControlPlaneCapability() {
        val registration =
            requireNotNull(
                AiLimbsCoreCapabilityRegistry.registrationForInvokeName("capability.hot")
            )

        assertEquals(AiLimbsCapabilityRole.CONTROL_PLANE, registration.role)
        assertEquals("capability.hot", registration.catalogEntry.targetToolName)
        assertTrue(registration.catalogEntry.parameters.isEmpty())
        assertTrue(
            registration.route ==
                AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.CAPABILITY_HOT)
        )
    }
}
