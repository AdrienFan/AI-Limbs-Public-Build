package com.ai.assistance.operit.plugins.lab

import com.ai.assistance.operit.plugins.center.PluginCapabilityExecutor
import com.ai.assistance.operit.plugins.center.PluginCapabilitySpec
import com.ai.assistance.operit.plugins.center.PluginInstallException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LabCapabilityRegistryTest {
    @Test
    fun registeredCapabilityIsRevokedWithItsHandle() = runTest {
        val registry = LabCapabilityRegistry()
        val handle = registry.register(
            ownerPluginId = "plugin.test",
            capabilityId = "plugin.test.echo",
            capability = PluginCapabilitySpec(
                displayName = "Echo",
                description = "test",
                executor = PluginCapabilityExecutor { parameters ->
                    JSONObject(parameters.toString())
                }
            )
        )

        assertEquals("hello", registry.invokePlugin(
            "plugin.test.echo",
            JSONObject().put("value", "hello")
        ).getString("value"))

        handle.close()
        try {
            registry.invokePlugin("plugin.test.echo")
            fail("revoked capability remained callable")
        } catch (error: PluginInstallException) {
            assertEquals("CAPABILITY_NOT_ACTIVE", error.code)
        }
    }

    @Test
    fun hostCapabilityRequiresApprovedScope() = runTest {
        val registry = LabCapabilityRegistry()
        val invoker = registry.create("plugin.test", emptySet())

        assertEquals("AI Limbs Plugin Lab", invoker.invoke(
            "core.runtime.info",
            JSONObject()
        ).getString("kernel"))

        try {
            invoker.invoke("core.logs.read", JSONObject())
            fail("scoped host capability was callable without approval")
        } catch (error: PluginInstallException) {
            assertEquals("PLUGIN_SCOPE_DENIED", error.code)
        }
    }
}
