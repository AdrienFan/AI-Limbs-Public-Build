package com.ai.assistance.operit.plugins.center

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPrimitiveAffinityRoutingTest {
    @Test
    fun screenCapturePilotRequiresAndroidHost() {
        assertTrue(HostPrimitiveGatewayBindings.affinityEnforced("host.screen.capture@1"))
        assertTrue(HostPrimitiveGatewayBindings.requiresAndroidHost("host.screen.capture@1", "capture"))
    }

    @Test
    fun coreSafePrimitiveRemainsLocalToBusinessOwner() {
        assertFalse(HostPrimitiveGatewayBindings.affinityEnforced("host.chat@1"))
        assertFalse(HostPrimitiveGatewayBindings.requiresAndroidHost("host.chat@1", "messages"))
    }

    @Test
    fun mixedPrimitiveWaitsForItsOwnMigrationStage() {
        assertFalse(HostPrimitiveGatewayBindings.affinityEnforced("host.filesystem@1"))
        assertFalse(HostPrimitiveGatewayBindings.requiresAndroidHost("host.filesystem@1", "open"))
    }
}
