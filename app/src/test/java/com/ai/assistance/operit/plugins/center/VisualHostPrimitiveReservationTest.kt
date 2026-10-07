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
            listOf("frame", "geometry", "list_targets", "start", "status", "stop", "wait_frame"),
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
        listOf("frame", "geometry", "wait_frame").forEach { operation ->
            val binding = requireNotNull(HostPrimitiveGatewayBindings.operations("host.screen.session@1")[operation])
            assertEquals(HostGatewayRouteKind.KERNEL, binding.kind)
            assertEquals(HostGatewayExecutionAffinity.HOST_FRAMEWORK, binding.affinity)
        }
        assertEquals(
            listOf("capture", "capture_frame"),
            HostPrimitiveGatewayBindings.operationNames("host.screen.capture@1")
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

    @Test
    fun pageSnapshotPrimitiveCanBeRequestedAtInstallation() {
        val id = "host.ui.automation@1"
        val primitive = requireNotNull(AiLimbsHostPrimitiveCatalog.find(id))
        assertEquals(HostPrimitiveExposure.BOUND, primitive.exposure)
        assertTrue(primitive.requestableScope)
        AiLimbsHostPrimitiveCatalog.requireInstallableScopes(setOf(id))

        val snapshot = requireNotNull(HostPrimitiveGatewayBindings.operations(id)["snapshot"])
        assertEquals(HostGatewayRouteKind.HOST_TOOL, snapshot.kind)
        assertEquals("get_page_info", snapshot.target)
        assertEquals(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, snapshot.affinity)
        assertEquals(
            listOf("click", "key", "long_press", "set_text", "snapshot", "swipe", "tap"),
            HostPrimitiveGatewayBindings.operationNames(id)
        )
    }

    @Test(expected = PluginInstallException::class)
    fun unboundPrimitiveStillCannotBeRequestedAtInstallation() {
        AiLimbsHostPrimitiveCatalog.requireInstallableScopes(setOf("host.clipboard@1"))
    }
}
