package com.ai.assistance.operit.plugins.center

import org.junit.Assert.assertEquals
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
    fun screenCapturePilotUsesMediaProjectionHostHandler() {
        assertEquals(
            HostGatewayHostExecution.MEDIA_PROJECTION_SCREEN_CAPTURE,
            HostPrimitiveGatewayBindings
                .operations("host.screen.capture@1")
                .getValue("capture")
                .hostExecution
        )
    }

    @Test
    fun residentRuntimeIsHostOwnedAtDescriptorLevel() {
        assertEquals(
            HostGatewayExecutionAffinity.HOST_FRAMEWORK,
            HostPrimitiveGatewayBindings.primitiveAffinity("host.resident.runtime@1")
        )
        assertEquals(
            CapabilityExecutionOwner.HOST,
            CapabilityRegistry.requireDescriptor("host.resident.runtime@1").executionOwner
        )
    }

    @Test
    fun chatPublishAssistantReturnsToAndroidHostWhileReadsStayBusinessOwned() {
        assertTrue(HostPrimitiveGatewayBindings.affinityEnforced("host.chat@1"))
        assertEquals(
            HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND,
            HostPrimitiveGatewayBindings.primitiveAffinity("host.chat@1")
        )
        assertEquals(
            HostGatewayExecutionAffinity.CORE_SAFE,
            HostPrimitiveGatewayBindings.operations("host.chat@1").getValue("messages").affinity
        )
        assertFalse(HostPrimitiveGatewayBindings.requiresAndroidHost("host.chat@1", "messages"))
        assertEquals(
            HostGatewayExecutionAffinity.HOST_SERVICE,
            HostPrimitiveGatewayBindings
                .operations("host.chat@1")
                .getValue("publish_assistant")
                .affinity
        )
        assertTrue(
            HostPrimitiveGatewayBindings.requiresAndroidHost(
                "host.chat@1",
                "publish_assistant"
            )
        )
    }

    @Test
    fun mixedPrimitiveWaitsForItsOwnMigrationStage() {
        assertFalse(HostPrimitiveGatewayBindings.affinityEnforced("host.filesystem@1"))
        assertFalse(HostPrimitiveGatewayBindings.requiresAndroidHost("host.filesystem@1", "open"))
    }
}
