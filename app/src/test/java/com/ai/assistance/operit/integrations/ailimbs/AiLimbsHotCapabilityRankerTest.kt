package com.ai.assistance.operit.integrations.ailimbs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsHotCapabilityRankerTest {
    @Test
    fun staleCapabilitiesAreFilteredBeforeTopFiveIsFinalized() {
        val now = 10_000L
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
        val buckets =
            usage.map { stats ->
                AiLimbsCapabilityUsageBucket(
                    invokeId = stats.invokeId,
                    dayStartEpochMs = 0L,
                    successCount = stats.useCount,
                    lastUsedAtEpochMs = now
                )
            }
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
                usageBuckets = buckets,
                discoverableCapabilities = discoverable,
                halfLifeMs = 7.0 * DAY_MS.toDouble(),
                nowEpochMs = now,
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

    @Test
    fun lifecycleCapabilitiesNeverEnterHotRanking() {
        val now = 1_000L
        val discoverable =
            listOf(
                AiLimbsDiscoverableCapability(
                    capabilityId = "plugin.test.runtime.command",
                    displayName = "Run command",
                    invokeId = "plugin.test.runtime.command",
                    role = AiLimbsCapabilityRole.BUSINESS
                ),
                AiLimbsDiscoverableCapability(
                    capabilityId = "plugin.test.runtime.start",
                    displayName = "Start runtime",
                    invokeId = "plugin.test.runtime.start",
                    role = AiLimbsCapabilityRole.LIFECYCLE
                )
            )
        val stats =
            listOf(
                AiLimbsCapabilityUsageStats("plugin.test.runtime.command", 2L, now),
                AiLimbsCapabilityUsageStats("plugin.test.runtime.start", 100L, now)
            )
        val buckets =
            stats.map {
                AiLimbsCapabilityUsageBucket(
                    invokeId = it.invokeId,
                    dayStartEpochMs = 0L,
                    successCount = it.useCount,
                    lastUsedAtEpochMs = now
                )
            }

        val ranked =
            AiLimbsHotCapabilityRanker.rank(
                usageStats = stats,
                usageBuckets = buckets,
                discoverableCapabilities = discoverable,
                halfLifeMs = 7.0 * DAY_MS.toDouble(),
                nowEpochMs = now,
                limit = 5
            )

        assertEquals(listOf("plugin.test.runtime.command"), ranked.map { it.invokeId })
    }

    @Test
    fun changingHalfLifeReinterpretsSameUsageHistory() {
        val now = 40L * DAY_MS
        val oldInvokeId = "plugin.test.old_heavy"
        val recentInvokeId = "plugin.test.recent_light"
        val discoverable =
            listOf(oldInvokeId, recentInvokeId).map { invokeId ->
                AiLimbsDiscoverableCapability(
                    capabilityId = invokeId,
                    displayName = invokeId,
                    invokeId = invokeId
                )
            }
        val stats =
            listOf(
                AiLimbsCapabilityUsageStats(oldInvokeId, 100L, now - 28L * DAY_MS),
                AiLimbsCapabilityUsageStats(recentInvokeId, 8L, now)
            )
        val buckets =
            listOf(
                AiLimbsCapabilityUsageBucket(
                    invokeId = oldInvokeId,
                    dayStartEpochMs = 0L,
                    successCount = 100L,
                    lastUsedAtEpochMs = now - 28L * DAY_MS
                ),
                AiLimbsCapabilityUsageBucket(
                    invokeId = recentInvokeId,
                    dayStartEpochMs = now,
                    successCount = 8L,
                    lastUsedAtEpochMs = now
                )
            )

        val sevenDay =
            AiLimbsHotCapabilityRanker.rank(
                usageStats = stats,
                usageBuckets = buckets,
                discoverableCapabilities = discoverable,
                halfLifeMs = 7.0 * DAY_MS.toDouble(),
                nowEpochMs = now,
                limit = 2
            )
        val thirtyDay =
            AiLimbsHotCapabilityRanker.rank(
                usageStats = stats,
                usageBuckets = buckets,
                discoverableCapabilities = discoverable,
                halfLifeMs = 30.0 * DAY_MS.toDouble(),
                nowEpochMs = now,
                limit = 2
            )

        assertEquals(recentInvokeId, sevenDay.first().invokeId)
        assertEquals(oldInvokeId, thirtyDay.first().invokeId)
        assertTrue(sevenDay.first().heatScore > sevenDay.last().heatScore)
        assertTrue(thirtyDay.first().heatScore > thirtyDay.last().heatScore)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
