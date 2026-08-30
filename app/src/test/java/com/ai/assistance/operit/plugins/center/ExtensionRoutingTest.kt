package com.ai.assistance.operit.plugins.center

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExtensionRoutingTest {
    @Test
    fun typedExtensionBindsAndRevokeRemovesEverything() {
        val downstream = linkedSetOf<String>()
        val points = ExtensionPointRegistry().apply {
            register(
                ExtensionPointDefinition(
                    point = PluginExtensionPoints.TEST_PROVIDER,
                    apiVersion = 1,
                    binder = { record ->
                        downstream += record.id
                        AutoCloseable { downstream -= record.id }
                    }
                )
            )
        }
        val router = ExtensionRouter(points)
        val contributions = PluginContributionRegistry()
        val scope = PluginMountScope(
            manifest = manifestFor(
                PluginExtensionSpec(
                    point = PluginExtensionPoints.TEST_PROVIDER,
                    id = "sample",
                    apiVersion = 1
                )
            ),
            registry = contributions,
            extensionRouter = router,
            capabilityBinder = NoopTestCapabilityBinder
        )

        scope.registrar.registerExtension(
            point = PluginExtensionPoints.TEST_PROVIDER,
            id = "sample",
            payload = "payload"
        )
        scope.seal()

        assertEquals(1, router.listBindings().size)
        assertNotNull(contributions.findExtension(PluginExtensionPoints.TEST_PROVIDER, "sample"))
        assertEquals(setOf("sample"), downstream)

        scope.revokeAll()
        assertTrue(router.listBindings().isEmpty())
        assertNull(contributions.findExtension(PluginExtensionPoints.TEST_PROVIDER, "sample"))
        assertTrue(downstream.isEmpty())
    }

    @Test
    fun unsupportedExtensionPointRollsBackContribution() {
        val points = ExtensionPointRegistry().apply {
            register(
                ExtensionPointDefinition(
                    point = PluginExtensionPoints.TEST_PROVIDER,
                    apiVersion = 1,
                    binder = { AutoCloseable { } }
                )
            )
        }
        val router = ExtensionRouter(points)
        val contributions = PluginContributionRegistry()
        val unsupportedPoint = "ai_limbs.unknown.provider"
        val scope = PluginMountScope(
            manifest = manifestFor(
                PluginExtensionSpec(unsupportedPoint, "sample", 1)
            ),
            registry = contributions,
            extensionRouter = router,
            capabilityBinder = NoopTestCapabilityBinder
        )
        try {
            scope.registrar.registerExtension(
                point = unsupportedPoint,
                id = "sample",
                payload = "payload"
            )
            fail("Unsupported extension point should fail")
        } catch (error: PluginInstallException) {
            assertEquals("EXTENSION_POINT_UNSUPPORTED", error.code)
        }

        assertTrue(router.listBindings().isEmpty())
        assertNull(contributions.findExtension(unsupportedPoint, "sample"))
        scope.revokeAll()
    }

    @Test
    fun sealedMountScopeRejectsLateRegistrationWithoutResidue() {
        val points = ExtensionPointRegistry().apply {
            register(
                ExtensionPointDefinition(
                    point = PluginExtensionPoints.TEST_PROVIDER,
                    apiVersion = 1,
                    binder = { AutoCloseable { } }
                )
            )
        }
        val router = ExtensionRouter(points)
        val contributions = PluginContributionRegistry()
        val scope = PluginMountScope(
            manifest = manifestFor(
                PluginExtensionSpec(PluginExtensionPoints.TEST_PROVIDER, "sample", 1)
            ),
            registry = contributions,
            extensionRouter = router,
            capabilityBinder = NoopTestCapabilityBinder
        )
        scope.seal()

        try {
            scope.registrar.registerExtension(
                point = PluginExtensionPoints.TEST_PROVIDER,
                id = "sample",
                payload = "payload"
            )
            fail("Late registration should be rejected")
        } catch (error: PluginInstallException) {
            assertEquals("MOUNT_SCOPE_CLOSED", error.code)
        }

        assertTrue(router.listBindings().isEmpty())
        assertNull(contributions.findExtension(PluginExtensionPoints.TEST_PROVIDER, "sample"))
    }

    @Test
    fun manifestParsesTypedExtensionDeclaration() {
        val manifest = PluginManifestParser.parse(
            """
            {
              "format":"AIL_PLUGIN_V1",
              "schema_version":1,
              "plugin_id":"test.plugin",
              "version":"1.0.0",
              "api":{"target":1,"min":1},
              "activation":{"mode":"hot"},
              "runtime":{"kind":"none"},
              "provides":{"extensions":[
                {"point":"ai_limbs.test.provider","id":"sample","api":1}
              ]}
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(PluginExtensionSpec(PluginExtensionPoints.TEST_PROVIDER, "sample", 1)),
            manifest.provides.extensions
        )
    }

    private object NoopTestCapabilityBinder : PluginCapabilityBinder {
        override fun register(
            ownerPluginId: String,
            capabilityId: String,
            capability: PluginCapabilitySpec
        ): AutoCloseable = AutoCloseable { }
    }

    private fun manifestFor(extension: PluginExtensionSpec): PluginManifest =
        PluginManifest(
            format = PluginAbi.FORMAT,
            schemaVersion = PluginAbi.SCHEMA_VERSION,
            pluginId = "test.plugin",
            version = "1.0.0",
            apiTarget = PluginAbi.CURRENT_API,
            apiMin = PluginAbi.CURRENT_API,
            display = PluginDisplaySpec(name = "Test Plugin"),
            roles = emptySet(),
            activationMode = PluginActivationMode.HOT,
            runtime = PluginRuntimeSpec(kind = "none"),
            dependencies = PluginDependencies(),
            permissions = PluginPermissionSpec(),
            provides = PluginProvidesSpec(extensions = listOf(extension)),
            uiRawJson = null,
            signature = null
        )
}
