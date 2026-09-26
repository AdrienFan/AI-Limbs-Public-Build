package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.plugins.center.isolation.ProviderContributionTransportCodec
import com.ai.assistance.operit.plugins.center.isolation.ProviderProxyProtocol
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessMetadataOnlyProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ProviderContributionTransportTest {
    private val unseenPluginId = "plugin.test.unseen.provider_probe"
    private val providerId = "provider.test.synthetic_echo"

    @Test
    fun unseenPluginProviderKeepsOneCanonicalIdentityInHostAndResident() {
        val hostRegistry = PluginContributionRegistry()
        val hostHandles = mutableListOf<AutoCloseable>()
        val hostRegistrar = PluginRegistrar(
            manifest = syntheticManifest(providerId),
            registry = hostRegistry,
            extensionRouter = Mockito.mock(ExtensionRouter::class.java),
            capabilityBinder = Mockito.mock(PluginCapabilityBinder::class.java),
            surfacePolicy = Mockito.mock(HostSurfacePolicy::class.java),
            track = hostHandles::add
        )
        val executor = InProcessCapabilityExecutor { parametersJson -> parametersJson }

        hostRegistrar.registerProvider(providerId, executor, mapOf("probe" to "true"))
        val hostRecord = checkNotNull(hostRegistry.find(PluginContributionKind.PROVIDER, providerId))
        assertEquals(unseenPluginId, hostRecord.ownerPluginId)
        assertEquals(mapOf("probe" to "true"), hostRecord.metadata)
        assertSame(executor, hostRecord.payload)

        val wire = ProviderContributionTransportCodec.encode(hostRecord)
        val envelope = ProviderContributionTransportCodec.decode(JSONObject(wire.toString()))
        assertEquals(unseenPluginId, envelope.contract.ownerPluginId)
        assertEquals(providerId, envelope.contract.id)
        assertEquals(ProviderProxyProtocol.CAPABILITY_EXECUTOR, envelope.protocol)

        val residentRegistry = PluginContributionRegistry()
        val residentHandles = mutableListOf<AutoCloseable>()
        val restore = CanonicalContributionRestoreSink(
            expectedOwnerPluginId = unseenPluginId,
            registry = residentRegistry,
            track = residentHandles::add
        )
        val residentProxy = InProcessCapabilityExecutor { "{}" }
        restore.registerProvider(envelope.contract, residentProxy)

        val restored = residentRegistry.find(PluginContributionKind.PROVIDER, providerId)
        assertEquals(hostRecord.contract, restored?.contract)
        assertSame(residentProxy, restored?.payload)

        residentHandles.single().close()
        assertNull(residentRegistry.find(PluginContributionKind.PROVIDER, providerId))
        hostHandles.single().close()
        assertNull(hostRegistry.find(PluginContributionKind.PROVIDER, providerId))
    }

    private fun syntheticManifest(providerId: String): PluginManifest =
        PluginManifest(
            format = PluginAbi.FORMAT,
            schemaVersion = PluginAbi.SCHEMA_VERSION,
            pluginId = unseenPluginId,
            version = "1.0.0",
            apiTarget = PluginAbi.CURRENT_API,
            apiMin = PluginAbi.CURRENT_API,
            display = PluginDisplaySpec(name = "Unseen Provider Probe"),
            roles = emptySet(),
            activationMode = PluginActivationMode.HOT,
            runtime = PluginRuntimeSpec(kind = "android_inprocess"),
            dependencies = PluginDependencies(),
            permissions = PluginPermissionSpec(),
            provides = PluginProvidesSpec(providers = setOf(providerId)),
            uiRawJson = null,
            integrity = null,
            signature = null
        )

    @Test
    fun metadataOnlyProviderCrossesResidentBoundaryWithoutPluginPayload() {
        val registry = PluginContributionRegistry()
        val handles = mutableListOf<AutoCloseable>()
        val registrar = PluginRegistrar(
            manifest = syntheticManifest(providerId),
            registry = registry,
            extensionRouter = Mockito.mock(ExtensionRouter::class.java),
            capabilityBinder = Mockito.mock(PluginCapabilityBinder::class.java),
            surfacePolicy = Mockito.mock(HostSurfacePolicy::class.java),
            track = handles::add
        )

        registrar.registerProvider(
            providerId,
            InProcessMetadataOnlyProvider,
            mapOf("kind" to "descriptor", "config_id" to "example")
        )
        val record = checkNotNull(registry.find(PluginContributionKind.PROVIDER, providerId))
        val envelope = ProviderContributionTransportCodec.decode(
            JSONObject(ProviderContributionTransportCodec.encode(record).toString())
        )

        assertEquals(ProviderProxyProtocol.METADATA_ONLY, envelope.protocol)
        assertEquals("descriptor", envelope.contract.metadata["kind"])
        assertEquals("example", envelope.contract.metadata["config_id"])

        val resident = PluginContributionRegistry()
        val residentHandles = mutableListOf<AutoCloseable>()
        CanonicalContributionRestoreSink(
            expectedOwnerPluginId = unseenPluginId,
            registry = resident,
            track = residentHandles::add
        ).registerProvider(envelope.contract, InProcessMetadataOnlyProvider)

        val restored = checkNotNull(resident.find(PluginContributionKind.PROVIDER, providerId))
        assertSame(InProcessMetadataOnlyProvider, restored.payload)
        assertEquals(record.contract, restored.contract)

        residentHandles.single().close()
        handles.single().close()
    }

    @Test
    fun residentRestoreRejectsOnlyGenericSessionOwnerMismatch() {
        val registry = PluginContributionRegistry()
        val restore = CanonicalContributionRestoreSink(
            expectedOwnerPluginId = unseenPluginId,
            registry = registry,
            track = { it.close() }
        )
        val foreign = CanonicalContributionContracts.provider(
            ownerPluginId = "plugin.test.other",
            id = providerId
        )

        val failure = runCatching {
            restore.registerProvider(foreign, "proxy")
        }.exceptionOrNull()

        assertTrue(failure is PluginInstallException)
        assertTrue(failure?.message.orEmpty().contains("does not match"))
        assertNull(registry.find(PluginContributionKind.PROVIDER, providerId))
    }
}
