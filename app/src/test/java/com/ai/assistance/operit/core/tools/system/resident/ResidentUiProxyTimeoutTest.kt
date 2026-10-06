package com.ai.assistance.operit.core.tools.system.resident

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResidentUiProxyTimeoutTest {
    @Test fun commandAllowsTheFullAndroidConsentAndBusinessBudget() {
        val budget = ResidentUiProxyTimeout.forOperation("command")
        // The Host activity-result broker permits 120 s for consent; processing follows it.
        assertTrue(budget > 120_000)
        assertEquals(180_000, budget)
    }
    @Test fun snapshotAndEventPollingKeepTheShortControlBudget() {
        for (operation in listOf("attach", "snapshot", "events", "component_poll", "component_result")) {
            assertEquals(operation, 5_000, ResidentUiProxyTimeout.forOperation(operation))
        }
    }
}
