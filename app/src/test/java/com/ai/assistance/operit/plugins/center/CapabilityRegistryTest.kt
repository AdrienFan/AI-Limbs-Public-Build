package com.ai.assistance.operit.plugins.center

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRegistryTest {
    @Test
    fun `registry covers every host primitive exactly once`() {
        val catalogIds = AiLimbsHostPrimitiveCatalog.all.map { it.id }.toSet()
        val descriptorIds = CapabilityRegistry.all.map { it.id }

        assertEquals(AiLimbsHostPrimitiveCatalog.all.size, CapabilityRegistry.all.size)
        assertEquals(descriptorIds.size, descriptorIds.toSet().size)
        assertEquals(catalogIds, descriptorIds.toSet())
    }

    @Test
    fun `descriptor schema and version are queryable`() {
        val descriptor = CapabilityRegistry.requireDescriptor("host.ui.layout@1")

        assertEquals(1, descriptor.version)
        assertNotNull(descriptor.requestSchema)
        assertNotNull(descriptor.responseSchema)
        assertEquals(1, descriptor.requestSchema.version)
        assertEquals(1, descriptor.responseSchema.version)
    }

    @Test
    fun `test nine host ownership includes migrated resident runtime`() {
        val hostOwned = CapabilityRegistry
            .descriptorsOwnedBy(CapabilityExecutionOwner.HOST)
            .map { it.id }
            .toSet()

        assertEquals(
            setOf(
                "host.ui.layout@1",
                "host.privileged.runtime@1",
                "host.resident.runtime@1"
            ),
            hostOwned
        )
        assertTrue(
            CapabilityRegistry
                .descriptorsOwnedBy(CapabilityExecutionOwner.PLUGIN_RUNTIME)
                .isEmpty()
        )
        assertTrue(
            CapabilityRegistry
                .descriptorsOwnedBy(CapabilityExecutionOwner.EXTERNAL_DAEMON)
                .isEmpty()
        )
    }
}
