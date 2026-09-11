package com.ai.assistance.operit.integrations.ailimbs

import org.junit.Assert.assertEquals
import org.junit.Test

class AiLimbsSubsystemDiscoveryLedgerTest {
    @Test
    fun `overlapping ingresses stay blocked until the whole wave drains`() {
        val ledger = AiLimbsSubsystemDiscoveryLedger()
        val extensionId = "test.subsystem.concurrent"

        ledger.beginInvocation()
        ledger.beginInvocation()
        assertEquals(
            AiLimbsSubsystemDiscoveryDecision.DELIVER,
            ledger.decision(extensionId)
        )
        assertEquals(
            AiLimbsSubsystemDiscoveryDecision.BLOCK,
            ledger.decision(extensionId)
        )

        ledger.endInvocation()
        ledger.beginInvocation()
        assertEquals(
            AiLimbsSubsystemDiscoveryDecision.BLOCK,
            ledger.decision(extensionId)
        )

        ledger.endInvocation()
        ledger.endInvocation()

        ledger.beginInvocation()
        assertEquals(
            AiLimbsSubsystemDiscoveryDecision.ALLOW,
            ledger.decision(extensionId)
        )
        ledger.endInvocation()
    }

    @Test
    fun `context reset rearms discovery without resetting active ingress count`() {
        val ledger = AiLimbsSubsystemDiscoveryLedger()
        val extensionId = "test.subsystem.reset"

        ledger.beginInvocation()
        assertEquals(
            AiLimbsSubsystemDiscoveryDecision.DELIVER,
            ledger.decision(extensionId)
        )
        ledger.resetForContextBoundary()
        assertEquals(
            AiLimbsSubsystemDiscoveryDecision.DELIVER,
            ledger.decision(extensionId)
        )

        ledger.endInvocation()
        ledger.beginInvocation()
        assertEquals(
            AiLimbsSubsystemDiscoveryDecision.ALLOW,
            ledger.decision(extensionId)
        )
        ledger.endInvocation()
    }

    @Test
    fun `untracked direct dispatcher path still delivers once then allows retry`() {
        val ledger = AiLimbsSubsystemDiscoveryLedger()

        assertEquals(
            AiLimbsSubsystemDiscoveryDecision.DELIVER,
            ledger.decision("Test.Subsystem.Direct")
        )
        assertEquals(
            AiLimbsSubsystemDiscoveryDecision.ALLOW,
            ledger.decision("test.subsystem.direct")
        )
    }
}
