package com.ai.assistance.operit.plugins.center

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginContextSecurityTest {
    @Test
    fun payloadContextDoesNotExposeAndroidContextOrRawFile() {
        val exposedTypes = PluginContext::class.java.declaredFields.map { it.type.name }.toSet()
        assertFalse("android.content.Context" in exposedTypes)
        assertFalse("java.io.File" in exposedTypes)
    }

    @Test
    fun sandboxDirectoryConfinesPluginToItsOwnRoot() {
        val root = Files.createTempDirectory("plugin-data-test").toFile()
        try {
            val sandbox = FilePluginSandboxDirectory(root)
            sandbox.writeBytes("nested/value.bin", byteArrayOf(1, 2, 3))
            assertTrue(sandbox.exists("nested/value.bin"))
            assertEquals(listOf<Byte>(1, 2, 3), sandbox.readBytes("nested/value.bin").toList())
            listOf("../escape", "/tmp/escape", "C:/escape", "nested/../escape").forEach { path ->
                try {
                    sandbox.writeBytes(path, byteArrayOf(9))
                    fail("Sandbox must reject path: $path")
                } catch (error: PluginInstallException) {
                    assertEquals("PLUGIN_STORAGE_PATH_INVALID", error.code)
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun eventSubscriptionsAreOwnedByMountScope() {
        val dataRoot = Files.createTempDirectory("plugin-context-data").toFile()
        val cacheRoot = Files.createTempDirectory("plugin-context-cache").toFile()
        try {
            val contributions = PluginContributionRegistry()
            val scope = PluginMountScope(
                manifest = manifest(),
                registry = contributions,
                extensionRouter = ExtensionRouter(ExtensionPointRegistry()),
                capabilityBinder = NoopCapabilityBinder
            )
            val eventHost = PluginEventBusHost()
            val context = contextFactory(contributions, eventHost)
                .create(manifest(), scope, dataRoot, cacheRoot)

            var deliveries = 0
            context.eventBus.subscribe("runtime.ready") { deliveries++ }
            assertEquals(1, context.eventBus.publish("runtime.ready", JSONObject().put("ok", true)))
            assertEquals(1, deliveries)

            scope.revokeAll()
            assertEquals(0, context.eventBus.publish("runtime.ready", JSONObject()))
            assertEquals(1, deliveries)
            assertNull(context.secrets.get("missing.token"))
        } finally {
            dataRoot.deleteRecursively()
            cacheRoot.deleteRecursively()
        }
    }

    private fun contextFactory(
        contributions: PluginContributionRegistry,
        eventHost: PluginEventBusHost
    ): PluginContextFactory =
        PluginContextFactory(
            contributions = contributions,
            eventBusHost = eventHost,
            capabilityInvokerFactory =
                object : PluginCapabilityInvokerFactory {
                    override fun create(ownerPluginId: String): PluginCapabilityInvoker =
                        PluginCapabilityInvoker { _, _ -> JSONObject().put("success", true) }
                },
            secretBroker = NoApprovedPluginSecretBroker
        )

    private fun manifest(): PluginManifest =
        PluginManifest(
            format = PluginAbi.FORMAT,
            schemaVersion = PluginAbi.SCHEMA_VERSION,
            pluginId = "test.context.plugin",
            version = "1.0.0",
            apiTarget = PluginAbi.CURRENT_API,
            apiMin = PluginAbi.CURRENT_API,
            display = PluginDisplaySpec(name = "Context Security Test"),
            roles = emptySet(),
            activationMode = PluginActivationMode.HOT,
            runtime = PluginRuntimeSpec(kind = "test"),
            dependencies = PluginDependencies(),
            permissions = PluginPermissionSpec(),
            provides = PluginProvidesSpec(),
            uiRawJson = null,
            signature = null
        )

    private object NoopCapabilityBinder : PluginCapabilityBinder {
        override fun register(
            ownerPluginId: String,
            capabilityId: String,
            capability: PluginCapabilitySpec
        ): AutoCloseable = AutoCloseable { }
    }
}
