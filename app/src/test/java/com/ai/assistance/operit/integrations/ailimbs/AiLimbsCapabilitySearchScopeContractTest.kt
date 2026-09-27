package com.ai.assistance.operit.integrations.ailimbs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsCapabilitySearchScopeContractTest {
    @Test
    fun scopeIsOptionalAndLegacySearchParametersKeepTheirContract() {
        val registration =
            requireNotNull(
                AiLimbsCoreCapabilityRegistry.registrationForInvokeName("capability.search")
            )
        val parameters = registration.catalogEntry.parameters.associateBy { it.name }

        assertTrue(parameters.getValue("query").required)
        assertFalse(parameters.getValue("scope").required)
        assertEquals("string", parameters.getValue("scope").type)
        assertFalse(parameters.getValue("limit").required)
        assertEquals("8", parameters.getValue("limit").default)
    }
}
