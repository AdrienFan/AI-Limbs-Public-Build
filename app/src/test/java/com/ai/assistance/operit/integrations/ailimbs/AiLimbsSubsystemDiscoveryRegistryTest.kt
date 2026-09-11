package com.ai.assistance.operit.integrations.ailimbs

import com.ai.limbs.plugin.runtime.ChildAiIngressDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsSubsystemDiscoveryRegistryTest {
    @Test
    fun publishBindsTrustedExtensionIdentityUntilClosed() {
        val extensionId = "test.subsystem.registry"
        val discovery = ChildAiIngressDiscovery(
            schemaId = "test.discovery.v1",
            payloadJson = "{\"kind\":\"tool-index\"}"
        )
        val handle = AiLimbsSubsystemDiscoveryRegistry.publish(extensionId, discovery)
        try {
            val resolved = AiLimbsSubsystemDiscoveryRegistry.resolve(extensionId)
            assertNotNull(resolved)
            val binding = requireNotNull(resolved)
            assertEquals(extensionId, binding.extensionId)
            assertEquals("test.discovery.v1", binding.schemaId)
            assertEquals("tool-index", binding.payload().getString("kind"))

            var duplicateRejected = false
            try {
                AiLimbsSubsystemDiscoveryRegistry.publish(extensionId, discovery)
            } catch (_: IllegalStateException) {
                duplicateRejected = true
            }
            assertTrue(duplicateRejected)
        } finally {
            handle.close()
        }
        assertNull(AiLimbsSubsystemDiscoveryRegistry.resolve(extensionId))
    }
}
