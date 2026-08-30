package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.plugins.center.PluginCapabilityExecutor
import com.ai.assistance.operit.plugins.center.PluginCapabilityParameterSpec
import com.ai.assistance.operit.plugins.center.PluginCapabilitySpec
import com.ai.assistance.operit.plugins.center.PluginInstallException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiLimbsPluginCapabilityRegistryTest {
    @Test
    fun pluginTestEchoRegistersAndDisappearsImmediately() = runBlocking {
        val handle =
            AiLimbsPluginCapabilityRegistry.register(
                ownerPluginId = "test.plugin",
                capabilityId = "plugin.test.echo",
                capability = echoCapability()
            )
        try {
            assertTrue(AiLimbsCapabilityRegistry.isRegisteredInvokeName("plugin.test.echo"))
            assertTrue(AiLimbsCapabilityRegistry.isRegisteredInvokeName("plugin.echo"))

            val managed = AiLimbsCapabilityRegistry.registrationForInvokeName("plugin.test.echo")
            assertTrue(managed is AiLimbsCapabilityRegistration.Plugin)
            val registration = (managed as AiLimbsCapabilityRegistration.Plugin).registration
            assertEquals("test.plugin", registration.ownerPluginId)
            assertEquals("plugin:test.plugin", registration.catalogEntry.sourceName)
            assertEquals("plugin://test.plugin/plugin.test.echo", registration.catalogEntry.sourceLocator)
            assertNotNull(
                AiLimbsCapabilityRegistry.mergeInto(emptyList())
                    .firstOrNull { it.targetToolName == "plugin.test.echo" }
            )

        } finally {
            handle.close()
        }

        assertFalse(AiLimbsCapabilityRegistry.isRegisteredInvokeName("plugin.test.echo"))
        assertFalse(AiLimbsCapabilityRegistry.isRegisteredInvokeName("plugin.echo"))
    }

    @Test
    fun pluginCapabilityCannotLeaveReservedNamespace() {
        try {
            AiLimbsPluginCapabilityRegistry.register(
                ownerPluginId = "test.plugin",
                capabilityId = "read_file_full",
                capability = echoCapability()
            )
            fail("Plugin capability must stay inside plugin.*")
        } catch (error: PluginInstallException) {
            assertEquals("PLUGIN_CAPABILITY_NAMESPACE_REQUIRED", error.code)
        }
    }

    @Test
    fun reservedPluginNamespaceCannotBeShadowedByRuntimeHostCatalog() {
        val fakeHostEntry =
            com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry(
                targetToolName = "plugin.test.echo",
                displayName = "Host Shadow",
                description = "Must never shadow a mounted plugin capability.",
                parameterHints = emptyList(),
                sourceKind = com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind.INTERNAL,
                sourceName = "host-shadow"
            )
        val handle = AiLimbsPluginCapabilityRegistry.register(
            ownerPluginId = "test.plugin",
            capabilityId = "plugin.test.echo",
            capability = echoCapability()
        )
        try {
            val matches = AiLimbsCapabilityRegistry.mergeInto(listOf(fakeHostEntry))
                .filter { it.targetToolName == "plugin.test.echo" }
            assertEquals(1, matches.size)
            assertEquals("plugin:test.plugin", matches.single().sourceName)
        } finally {
            handle.close()
        }
    }

    @Test
    fun pluginCapabilityPolicyUsesSharedToolPermissionPath() {
        val spec = AiLimbsExecutionPolicyDescriptor.specForPluginCapability()
        assertEquals(AiLimbsEffect.EXTERNAL_CAPABILITY, spec.effect)
        assertEquals(AiLimbsDomain.PLUGIN, spec.domain)
        assertEquals(AiLimbsPermissionMode.TOOL_PERMISSION, spec.permissionMode)
        assertFalse(spec.hostPermissionEnforced)
        assertTrue(AiLimbsRequiredReceipt.CUSTOM_ACCESS_PROMPT in spec.requiredReceipts)
    }

    private fun echoCapability(): PluginCapabilitySpec =
        PluginCapabilitySpec(
            displayName = "Plugin Test Echo",
            description = "Echoes the provided text for capability bus contract tests.",
            invokeAliases = listOf("plugin.echo"),
            keywords = listOf("echo", "plugin", "test"),
            parameters =
                listOf(
                    PluginCapabilityParameterSpec(
                        name = "text",
                        description = "Text to echo"
                    )
                ),
            executor =
                PluginCapabilityExecutor { parameters ->
                    JSONObject().put("echo", parameters.optString("text"))
                }
        )
}
