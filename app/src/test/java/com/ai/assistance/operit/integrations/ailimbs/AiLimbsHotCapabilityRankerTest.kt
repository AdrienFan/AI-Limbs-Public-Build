package com.ai.assistance.operit.integrations.ailimbs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AiLimbsHotCapabilityRankerTest {
    @Test
    fun staleCapabilitiesAreFilteredBeforeTopFiveIsFinalized() {
        val usage =
            listOf(
                AiLimbsCapabilityUsageStats("plugin.test.removed", 100L, 9_999L),
                AiLimbsCapabilityUsageStats("plugin.test.a", 10L, 1_000L),
                AiLimbsCapabilityUsageStats("plugin.test.b", 10L, 2_000L),
                AiLimbsCapabilityUsageStats("plugin.test.c", 9L, 3_000L),
                AiLimbsCapabilityUsageStats("plugin.test.d", 8L, 4_000L),
                AiLimbsCapabilityUsageStats("plugin.test.e", 7L, 5_000L),
                AiLimbsCapabilityUsageStats("plugin.test.f", 6L, 6_000L)
            )
        val discoverable =
            listOf("a", "b", "c", "d", "e", "f").map { suffix ->
                AiLimbsDiscoverableCapability(
                    capabilityId = "plugin.test.$suffix",
                    displayName = "Capability $suffix",
                    invokeId = "plugin.test.$suffix"
                )
            }

        val ranked =
            AiLimbsHotCapabilityRanker.rank(
                usageStats = usage,
                discoverableCapabilities = discoverable,
                limit = 5
            )

        assertEquals(
            listOf(
                "plugin.test.b",
                "plugin.test.a",
                "plugin.test.c",
                "plugin.test.d",
                "plugin.test.e"
            ),
            ranked.map { it.invokeId }
        )
        assertFalse(ranked.any { it.invokeId == "plugin.test.removed" })
        assertEquals(listOf(10L, 10L, 9L, 8L, 7L), ranked.map { it.useCount })
    }
}
