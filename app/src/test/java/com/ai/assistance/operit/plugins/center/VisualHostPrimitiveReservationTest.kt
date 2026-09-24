package com.ai.assistance.operit.plugins.center

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualHostPrimitiveReservationTest {
    @Test
    fun visualPrimitivesAreBoundAndRequestable() {
        val ids = listOf(
            "host.screen.capture@1",
            "host.screen.session@1",
            "host.camera.capture@1",
            "host.camera.session@1"
        )
        ids.forEach { id ->
            val primitive = requireNotNull(AiLimbsHostPrimitiveCatalog.find(id))
            assertEquals(HostPrimitiveExposure.BOUND, primitive.exposure)
            assertEquals(HostPrimitiveMaturity.CONFIRMED, primitive.maturity)
            assertTrue(primitive.requestableScope)
            assertTrue(HostPrimitiveGatewayBindings.isCallable(id))
        }
    }

    @Test
    fun visualSessionOperationsUseHostFrameworkBindings() {
        assertEquals(
            listOf("frame", "list_targets", "start", "status", "stop"),
            HostPrimitiveGatewayBindings.operationNames("host.screen.session@1")
        )
        assertEquals(
            listOf("configure", "frame", "list_sources", "start", "status", "stop"),
            HostPrimitiveGatewayBindings.operationNames("host.camera.session@1")
        )
        assertEquals(
            HostGatewayExecutionAffinity.HOST_FRAMEWORK,
            HostPrimitiveGatewayBindings.primitiveAffinity("host.screen.session@1")
        )
        assertEquals(
            HostGatewayExecutionAffinity.HOST_FRAMEWORK,
            HostPrimitiveGatewayBindings.primitiveAffinity("host.camera.capture@1")
        )
        assertEquals(
            HostGatewayExecutionAffinity.HOST_FRAMEWORK,
            HostPrimitiveGatewayBindings.primitiveAffinity("host.camera.session@1")
        )
    }

    @Test
    fun persistentAndCameraVisualExecutionIsHostOwned() {
        listOf(
            "host.screen.session@1",
            "host.camera.capture@1",
            "host.camera.session@1"
        ).forEach { id ->
            assertEquals(
                CapabilityExecutionOwner.HOST,
                CapabilityRegistry.requireDescriptor(id).executionOwner
            )
        }
    }
}
