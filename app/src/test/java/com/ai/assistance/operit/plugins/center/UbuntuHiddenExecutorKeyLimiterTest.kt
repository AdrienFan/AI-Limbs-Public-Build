package com.ai.assistance.operit.plugins.center

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuHiddenExecutorKeyLimiterTest {
    @Test
    fun sameRequestedKeyUsesStableSlot() {
        val first = normalized("build61-review")
        val second = normalized("build61-review")
        assertEquals(first, second)
    }

    @Test
    fun arbitraryRequestedKeysAreBoundedToFourSlots() {
        val slots = (0 until 256).map { normalized("executor-$it") }.toSet()
        assertTrue("Expected at most four persistent hidden executor slots, got $slots", slots.size <= 4)
    }

    @Test
    fun defaultAndBaseSharePrimarySlot() {
        assertEquals(normalized("default"), normalized("base"))
    }

    @Test
    fun unrelatedCapabilityParametersRemainUntouched() {
        val input = JSONObject().put("executor_key", "keep-me").put("value", 7)
        val output = UbuntuHiddenExecutorKeyLimiter.normalize("plugin.other.command", input)
        assertEquals("keep-me", output.getString("executor_key"))
        assertEquals(7, output.getInt("value"))
    }

    private fun normalized(key: String): String =
        UbuntuHiddenExecutorKeyLimiter.normalize(
            "plugin.ubuntu.command",
            JSONObject().put("executor_key", key)
        ).getString("executor_key")
}
