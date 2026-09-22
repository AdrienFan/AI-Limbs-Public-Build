package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.plugins.center.isolation.ExtensionContributionTransportCodecRegistry
import com.ai.assistance.operit.plugins.center.isolation.ExtensionProtocolType
import com.ai.assistance.operit.plugins.center.isolation.ServiceContributionTransportCodec
import com.ai.assistance.operit.plugins.center.isolation.ServiceProxyProtocol
import com.ai.limbs.plugin.runtime.ChildExtensionTarget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito

class ServiceExtensionChildCanonicalTest {
    private val unseenPluginId = "plugin.test.unseen.stagec"
    private val serviceId = "service.test.unseen.rpc"

    @Test
    fun unseenPluginServiceUsesRegistrarAndCanonicalRpcTransport() {
        val registry = PluginContributionRegistry()
        val handles = mutableListOf<AutoCloseable>()
        val surfacePolicy = Mockito.mock(HostSurfacePolicy::class.java)
        val registrar = PluginRegistrar(
            manifest = manifest(),
            registry = registry,
            extensionRouter = ExtensionRouter(ExtensionPointRegistry(), surfacePolicy),
            capabilityBinder = Mockito.mock(PluginCapabilityBinder::class.java),
            surfacePolicy = surfacePolicy,
            track = handles::add
        )
        val endpoint = PluginServiceEndpoint { operation, parameters ->
            JSONObject().put("operation", operation).put("parameters", parameters)
        }

        registrar.registerService(serviceId, 3, endpoint, mapOf("probe" to "stage-c"))
        val hostRecord = checkNotNull(registry.find(PluginContributionKind.SERVICE, serviceId))
        val envelope = ServiceContributionTransportCodec.decode(
            ServiceContributionTransportCodec.encode(hostRecord)
        )

        assertEquals(ServiceProxyProtocol.RPC, envelope.protocol)
        assertEquals(unseenPluginId, envelope.contract.ownerPluginId)
        assertEquals(3, envelope.contract.apiVersion)

        val residentRegistry = PluginContributionRegistry()
        val residentHandles = mutableListOf<AutoCloseable>()
        val restore = CanonicalContributionRestoreSink(
            expectedOwnerPluginId = unseenPluginId,
            registry = residentRegistry,
            track = residentHandles::add
        )
        restore.registerService(envelope.contract, endpoint)
        assertSame(endpoint, residentRegistry.find(PluginContributionKind.SERVICE, serviceId)?.payload)

        residentHandles.single().close()
        handles.single().close()
        assertNull(residentRegistry.find(PluginContributionKind.SERVICE, serviceId))
    }

    @Test
    fun extensionCodecRegistryKnowsProtocolButNotPluginIdentity() {
        val screenId = "screen.test.unseen"
        val contract = CanonicalContributionContracts.extension(
            ownerPluginId = unseenPluginId,
            point = PluginExtensionPoints.UI_SCREEN,
            id = screenId,
            apiVersion = 2,
            metadata = mapOf("probe" to "screen")
        )
        val screen = PluginScreenSpec(
            ownerPluginId = unseenPluginId,
            id = screenId,
            title = "Synthetic Screen",
            description = null,
            schemaId = "schema.synthetic",
            documentJson = """{"type":"column"}"""
        )
        val encoded = ExtensionContributionTransportCodecRegistry.encode(
            PluginContributionRecord(contract, screen)
        )
        val envelope = ExtensionContributionTransportCodecRegistry.decode(encoded)

        assertEquals(ExtensionProtocolType.UI_SCREEN, envelope.protocol)
        assertEquals(contract, envelope.contract)
        assertEquals(screen, envelope.payload)
        assertFalse(encoded.toString().contains("plugin.system.", ignoreCase = true))
        assertFalse(encoded.toString().contains("extension_hub", ignoreCase = true))

        val points = ExtensionPointRegistry().apply {
            register(ExtensionPointDefinition(PluginExtensionPoints.UI_SCREEN, 2) { AutoCloseable { } })
        }
        val restore = CanonicalContributionRestoreSink(
            expectedOwnerPluginId = unseenPluginId,
            registry = PluginContributionRegistry(),
            extensionRouter = ExtensionRouter(points, Mockito.mock(HostSurfacePolicy::class.java)),
            track = { }
        )
        restore.registerExtension(envelope.contract, envelope.payload)
    }

    @Test
    fun themeProtocolRoundTripsAtDeclaredVersion() {
        val contract = CanonicalContributionContracts.extension(
            ownerPluginId = unseenPluginId,
            point = PluginExtensionPoints.UI_THEME,
            id = "theme.test.unseen",
            apiVersion = 1
        )
        val theme = PluginThemeSpec(
            ownerPluginId = unseenPluginId,
            id = contract.id,
            mode = PluginThemeMode.DARK,
            pureBlack = true,
            colors = mapOf("accent" to "#ffffff"),
            backgroundGradient = listOf("#000000", "#111111")
        )
        val envelope = ExtensionContributionTransportCodecRegistry.decode(
            ExtensionContributionTransportCodecRegistry.encode(
                PluginContributionRecord(contract, theme)
            )
        )
        assertEquals(ExtensionProtocolType.THEME, envelope.protocol)
        assertEquals(theme, envelope.payload)
    }

    @Test
    fun arbitraryParentPointAndChildContributionsShareOneCanonicalTarget() {
        val target = ChildExtensionTarget(
            parentPluginId = "plugin.test.future.parent",
            point = "vendor.future.slot",
            apiVersion = 7
        )
        val descriptors = listOf(
            CanonicalChildDescriptors.parentPoint(
                ownerPluginId = target.parentPluginId,
                point = target.point,
                apiVersion = target.apiVersion,
                metadata = mapOf("title" to "Future Slot")
            ),
            CanonicalChildDescriptors.childBinding(
                extensionId = "extension.test.future.child",
                target = target,
                metadata = mapOf("version" to "1.0.0")
            ),
            CanonicalChildDescriptors.capability(
                extensionId = "extension.test.future.child",
                capabilityId = "plugin.child.future_action",
                target = target
            ),
            CanonicalChildDescriptors.ui(
                extensionId = "extension.test.future.child",
                contributionId = "future_ui",
                target = target,
                metadata = mapOf(
                    "screen_id" to "future",
                    "component_id" to "root",
                    "slot_id" to "main"
                )
            )
        )

        descriptors.forEach { descriptor ->
            val decoded = CanonicalChildDescriptorCodec.decode(
                CanonicalChildDescriptorCodec.encode(descriptor)
            )
            assertEquals(target, decoded.target)
            assertEquals(descriptor, decoded)
        }
        assertEquals(
            setOf(
                CanonicalChildDescriptorKind.PARENT_POINT,
                CanonicalChildDescriptorKind.CHILD_BINDING,
                CanonicalChildDescriptorKind.CAPABILITY,
                CanonicalChildDescriptorKind.UI_CONTRIBUTION
            ),
            descriptors.map { it.kind }.toSet()
        )
    }

    private fun manifest(): PluginManifest =
        PluginManifest(
            format = PluginAbi.FORMAT,
            schemaVersion = PluginAbi.SCHEMA_VERSION,
            pluginId = unseenPluginId,
            version = "1.0.0",
            apiTarget = PluginAbi.CURRENT_API,
            apiMin = PluginAbi.CURRENT_API,
            display = PluginDisplaySpec(name = "Stage C Synthetic"),
            roles = emptySet(),
            activationMode = PluginActivationMode.HOT,
            runtime = PluginRuntimeSpec(kind = "android_inprocess"),
            dependencies = PluginDependencies(),
            permissions = PluginPermissionSpec(),
            provides = PluginProvidesSpec(
                services = setOf(serviceId),
                extensions = listOf(
                    PluginExtensionSpec(
                        point = PluginExtensionPoints.UI_SCREEN,
                        id = "screen.test.unseen",
                        apiVersion = 2
                    )
                )
            ),
            uiRawJson = null,
            integrity = null,
            signature = null
        )
}
