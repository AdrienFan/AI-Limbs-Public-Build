package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.plugins.center.isolation.ExtensionContributionTransportCodecRegistry
import com.ai.assistance.operit.plugins.center.isolation.ProviderContributionTransportCodec
import com.ai.assistance.operit.plugins.center.isolation.ServiceContributionTransportCodec
import com.ai.limbs.plugin.runtime.ChildExtensionTarget
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class UnknownPluginUnifiedReceiverTest {
    private val pluginId = "plugin.test.unknown_abc"
    private val providerId = "provider.test.unknown_abc.echo"
    private val serviceId = "service.test.unknown_abc.rpc"
    private val capabilityId = "plugin.unknown_abc.echo"
    private val extensionPoint = PluginExtensionPoints.UI_THEME
    private val extensionId = "theme.test.unknown_abc"
    private val childPoint = "vendor.unknown_abc.slot"
    private val childId = "extension.test.unknown_child"

    @Test
    fun unknownPluginHostResidentHostRoundTripNeedsNoBaseIdentityBranch() = runBlocking {
        val hostRegistry = PluginContributionRegistry()
        val hostCapabilityBinder = RecordingCapabilityBinder()
        val hostRouter = extensionRouter()
        val hostHandles = mutableListOf<AutoCloseable>()
        val hostRegistrar = PluginRegistrar(
            manifest = manifest(),
            registry = hostRegistry,
            extensionRouter = hostRouter,
            capabilityBinder = hostCapabilityBinder,
            surfacePolicy = Mockito.mock(HostSurfacePolicy::class.java),
            track = hostHandles::add
        )

        val hostProvider = InProcessCapabilityExecutor { raw ->
            JSONObject().put("owner", pluginId).put("echo", JSONObject(raw)).toString()
        }
        val hostService = PluginServiceEndpoint { operation, parameters ->
            JSONObject()
                .put("owner", pluginId)
                .put("operation", operation)
                .put("echo", JSONObject(parameters.toString()))
        }
        val hostCapability = PluginCapabilitySpec(
            displayName = "Unknown echo",
            description = "Synthetic unknown-plugin capability",
            executor = PluginCapabilityExecutor { parameters ->
                JSONObject().put("owner", pluginId).put("echo", JSONObject(parameters.toString()))
            }
        )
        val hostTheme = PluginThemeSpec(
            ownerPluginId = pluginId,
            id = extensionId,
            mode = PluginThemeMode.DARK,
            pureBlack = false,
            colors = mapOf("accent" to "#123456"),
            backgroundGradient = emptyList()
        )

        hostRegistrar.registerProvider(providerId, hostProvider, mapOf("probe" to "unknown"))
        hostRegistrar.registerService(serviceId, 7, hostService, mapOf("probe" to "unknown"))
        hostRegistrar.registerCapability(capabilityId, hostCapability, mapOf("probe" to "unknown"))
        hostRegistrar.registerExtension(extensionPoint, extensionId, hostTheme, mapOf("probe" to "unknown"))

        assertEquals(
            setOf(
                PluginContributionKind.PROVIDER,
                PluginContributionKind.SERVICE,
                PluginContributionKind.CAPABILITY,
                PluginContributionKind.EXTENSION
            ),
            hostRegistry.listByOwner(pluginId).map { it.kind }.toSet()
        )
        assertSame(hostCapability, hostCapabilityBinder.bound[capabilityId])

        val residentRegistry = PluginContributionRegistry()
        val residentCapabilityBinder = RecordingCapabilityBinder()
        val residentRouter = extensionRouter()
        val residentHandles = mutableListOf<AutoCloseable>()
        val restore = CanonicalContributionRestoreSink(
            expectedOwnerPluginId = pluginId,
            registry = residentRegistry,
            extensionRouter = residentRouter,
            track = residentHandles::add
        )

        val providerEnvelope = ProviderContributionTransportCodec.decode(
            ProviderContributionTransportCodec.encode(
                checkNotNull(hostRegistry.find(PluginContributionKind.PROVIDER, providerId))
            )
        )
        val residentProvider = InProcessCapabilityExecutor { raw ->
            hostProvider.invoke(raw)
        }
        restore.registerProvider(providerEnvelope.contract, residentProvider)

        val serviceEnvelope = ServiceContributionTransportCodec.decode(
            ServiceContributionTransportCodec.encode(
                checkNotNull(hostRegistry.find(PluginContributionKind.SERVICE, serviceId))
            )
        )
        val residentService = PluginServiceEndpoint { operation, parameters ->
            hostService.invoke(operation, parameters)
        }
        restore.registerService(serviceEnvelope.contract, residentService)

        val extensionEnvelope = ExtensionContributionTransportCodecRegistry.decode(
            ExtensionContributionTransportCodecRegistry.encode(
                checkNotNull(hostRegistry.findExtension(extensionPoint, extensionId))
            )
        )
        restore.registerExtension(extensionEnvelope.contract, extensionEnvelope.payload)

        val residentManifest = manifest()
        val residentRegistrar = PluginRegistrar(
            manifest = residentManifest,
            registry = residentRegistry,
            extensionRouter = residentRouter,
            capabilityBinder = residentCapabilityBinder,
            surfacePolicy = Mockito.mock(HostSurfacePolicy::class.java),
            track = residentHandles::add
        )
        val residentCapability = hostCapability.copy(
            executor = PluginCapabilityExecutor { parameters ->
                checkNotNull(hostCapabilityBinder.bound[capabilityId]).executor.execute(parameters)
            }
        )
        residentRegistrar.registerCapability(capabilityId, residentCapability, mapOf("probe" to "unknown"))

        val providerBack = checkNotNull(
            residentRegistry.find(PluginContributionKind.PROVIDER, providerId)?.payload as? InProcessCapabilityExecutor
        )
        val providerResult = JSONObject(providerBack.invoke("""{"value":"provider"}"""))
        assertEquals(pluginId, providerResult.getString("owner"))
        assertEquals("provider", providerResult.getJSONObject("echo").getString("value"))

        val serviceBack = checkNotNull(
            residentRegistry.find(PluginContributionKind.SERVICE, serviceId)?.payload as? PluginServiceEndpoint
        )
        val serviceResult = serviceBack.invoke("round_trip", JSONObject().put("value", "service"))
        assertEquals(pluginId, serviceResult.getString("owner"))
        assertEquals("round_trip", serviceResult.getString("operation"))
        assertEquals("service", serviceResult.getJSONObject("echo").getString("value"))

        val capabilityBack = checkNotNull(residentCapabilityBinder.bound[capabilityId])
        val capabilityResult = capabilityBack.executor.execute(JSONObject().put("value", "capability"))
        assertEquals(pluginId, capabilityResult.getString("owner"))
        assertEquals("capability", capabilityResult.getJSONObject("echo").getString("value"))

        val residentExtension = residentRegistry.findExtension(extensionPoint, extensionId)
        assertNotNull(residentExtension)
        assertEquals(pluginId, residentExtension?.ownerPluginId)
        assertEquals(pluginId, (residentExtension?.payload as PluginThemeSpec).ownerPluginId)

        val target = ChildExtensionTarget(pluginId, childPoint, 7)
        val hostChildDescriptors = listOf(
            CanonicalChildDescriptors.parentPoint(
                ownerPluginId = pluginId,
                point = childPoint,
                apiVersion = 7,
                metadata = mapOf("title" to "Unknown child slot")
            ),
            CanonicalChildDescriptors.childBinding(
                extensionId = childId,
                target = target,
                metadata = mapOf("version" to "1.0.0")
            ),
            CanonicalChildDescriptors.capability(
                extensionId = childId,
                capabilityId = "plugin.unknown_child.echo",
                target = target,
                metadata = mapOf("probe" to "unknown")
            )
        )

        val residentChildDescriptors = hostChildDescriptors.map { descriptor ->
            CanonicalChildDescriptorEnvelopeCodec.decode(
                CanonicalChildDescriptorEnvelopeCodec.encode(
                    CanonicalChildDescriptorEnvelope(
                        descriptor = descriptor,
                        payload = JSONObject().put("owner", descriptor.ownerId)
                    )
                )
            )
        }
        residentChildDescriptors.forEach { envelope ->
            assertEquals(target, envelope.descriptor.target)
        }
        assertEquals(pluginId, residentChildDescriptors.first().descriptor.ownerId)
        assertEquals(childId, residentChildDescriptors[1].descriptor.ownerId)

        val hostAgain = residentChildDescriptors.map { envelope ->
            CanonicalChildDescriptorEnvelopeCodec.decode(
                CanonicalChildDescriptorEnvelopeCodec.encode(envelope)
            ).descriptor
        }
        assertEquals(hostChildDescriptors, hostAgain)

        val allWire = buildString {
            append(ProviderContributionTransportCodec.encode(checkNotNull(hostRegistry.find(PluginContributionKind.PROVIDER, providerId))))
            append(ServiceContributionTransportCodec.encode(checkNotNull(hostRegistry.find(PluginContributionKind.SERVICE, serviceId))))
            append(ExtensionContributionTransportCodecRegistry.encode(checkNotNull(hostRegistry.findExtension(extensionPoint, extensionId))))
            residentChildDescriptors.forEach { append(CanonicalChildDescriptorEnvelopeCodec.encode(it)) }
        }
        assertTrue(allWire.contains(pluginId))
        assertFalse(allWire.contains("plugin.system.", ignoreCase = true))
        assertFalse(allWire.contains("extension_hub", ignoreCase = true))
    }

    private fun manifest(): PluginManifest = PluginManifest(
        format = PluginAbi.FORMAT,
        schemaVersion = PluginAbi.SCHEMA_VERSION,
        pluginId = pluginId,
        version = "1.0.0",
        apiTarget = PluginAbi.CURRENT_API,
        apiMin = PluginAbi.CURRENT_API,
        display = PluginDisplaySpec(name = "Unknown ABC"),
        roles = emptySet(),
        activationMode = PluginActivationMode.HOT,
        runtime = PluginRuntimeSpec(kind = "android_inprocess"),
        dependencies = PluginDependencies(),
        permissions = PluginPermissionSpec(),
        provides = PluginProvidesSpec(
            capabilities = setOf(capabilityId),
            services = setOf(serviceId),
            providers = setOf(providerId),
            extensions = listOf(
                PluginExtensionSpec(
                    point = extensionPoint,
                    id = extensionId,
                    apiVersion = 1
                )
            )
        ),
        uiRawJson = null,
        integrity = null,
        signature = null
    )

    private fun extensionRouter(): ExtensionRouter {
        val points = ExtensionPointRegistry().apply {
            register(ExtensionPointDefinition(extensionPoint, 1) { AutoCloseable { } })
        }
        return ExtensionRouter(points, Mockito.mock(HostSurfacePolicy::class.java))
    }

    private class RecordingCapabilityBinder : PluginCapabilityBinder {
        val bound = linkedMapOf<String, PluginCapabilitySpec>()

        override fun register(
            ownerPluginId: String,
            capabilityId: String,
            capability: PluginCapabilitySpec
        ): AutoCloseable {
            bound[capabilityId] = capability
            return AutoCloseable { bound.remove(capabilityId) }
        }
    }
}
