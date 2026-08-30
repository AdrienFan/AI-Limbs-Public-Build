package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.integrations.ailimbs.AiLimbsCapabilityRegistry
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsPluginCapabilityRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCapabilityMountScopeTest {
    @Test
    fun revokeAllRemovesPluginCapabilityFromAggregateRegistryImmediately() {
        val capabilityId = "plugin.test.mount_echo"
        val contributions = PluginContributionRegistry()
        val scope =
            PluginMountScope(
                manifest = manifestFor(capabilityId),
                registry = contributions,
                extensionRouter = ExtensionRouter(ExtensionPointRegistry()),
                capabilityBinder = AiLimbsPluginCapabilityRegistry
            )

        scope.registrar.registerCapability(
            id = capabilityId,
            capability =
                PluginCapabilitySpec(
                    displayName = "Mount Echo",
                    description = "Mount scope capability revocation test",
                    executor = PluginCapabilityExecutor { parameters -> JSONObject(parameters.toString()) }
                )
        )
        scope.seal()

        assertTrue(AiLimbsCapabilityRegistry.isRegisteredInvokeName(capabilityId))
        assertNotNull(contributions.find(PluginContributionKind.CAPABILITY, capabilityId))

        scope.revokeAll()

        assertFalse(AiLimbsCapabilityRegistry.isRegisteredInvokeName(capabilityId))
        assertNull(contributions.find(PluginContributionKind.CAPABILITY, capabilityId))
    }

    private fun manifestFor(capabilityId: String): PluginManifest =
        PluginManifest(
            format = PluginAbi.FORMAT,
            schemaVersion = PluginAbi.SCHEMA_VERSION,
            pluginId = "test.mount.plugin",
            version = "1.0.0",
            apiTarget = PluginAbi.CURRENT_API,
            apiMin = PluginAbi.CURRENT_API,
            display = PluginDisplaySpec(name = "Mount Test Plugin"),
            roles = emptySet(),
            activationMode = PluginActivationMode.HOT,
            runtime = PluginRuntimeSpec(kind = "none"),
            dependencies = PluginDependencies(),
            permissions = PluginPermissionSpec(),
            provides = PluginProvidesSpec(capabilities = setOf(capabilityId)),
            uiRawJson = null,
            signature = null
        )
}
