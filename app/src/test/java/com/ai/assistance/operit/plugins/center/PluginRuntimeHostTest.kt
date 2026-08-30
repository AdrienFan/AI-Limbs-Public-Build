package com.ai.assistance.operit.plugins.center

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginRuntimeHostTest {
    @Test
    fun mountSuccessSealsScopeAndStopRevokesAllOwnedResources() = runBlocking {
        val fixture = fixture()
        var stopped = false
        val runtime =
            fixture.host.mount("test", fixture.scope) {
                fixture.scope.registrar.registerProvider("during", "provider")
                fixture.scope.registrar.registerExtension(TEST_POINT, "sample", "extension")
                handle { stopped = true }
            }

        assertTrue(fixture.contributions.listByOwner(PLUGIN_ID).isNotEmpty())
        assertEquals(1, fixture.router.listBindingsByOwner(PLUGIN_ID).size)
        assertEquals(setOf("sample"), fixture.downstream)
        try {
            fixture.scope.registrar.registerProvider("late", "late")
            fail("ACTIVE mount scope must reject late registrations")
        } catch (error: PluginInstallException) {
            assertEquals("MOUNT_SCOPE_CLOSED", error.code)
        }
        assertNull(fixture.contributions.find(PluginContributionKind.PROVIDER, "late"))

        val stop = fixture.host.stop(runtime)
        assertEquals(PluginRuntimeStopOutcome.STOPPED, stop.outcome)
        assertTrue(stopped)
        assertTrue(fixture.contributions.listByOwner(PLUGIN_ID).isEmpty())
        assertTrue(fixture.router.listBindingsByOwner(PLUGIN_ID).isEmpty())
        assertTrue(fixture.downstream.isEmpty())
    }

    @Test
    fun mountExceptionRevokesRegistrationsAndBindings() = runBlocking {
        val fixture = fixture()
        try {
            fixture.host.mount("broken", fixture.scope) {
                fixture.scope.registrar.registerProvider("during", "provider")
                fixture.scope.registrar.registerExtension(TEST_POINT, "sample", "extension")
                throw IllegalStateException("boom")
            }
            fail("Mount exception must fail the runtime transaction")
        } catch (error: PluginInstallException) {
            assertEquals("RUNTIME_MOUNT_FAILED", error.code)
        }
        assertTrue(fixture.contributions.listByOwner(PLUGIN_ID).isEmpty())
        assertTrue(fixture.router.listBindingsByOwner(PLUGIN_ID).isEmpty())
    }

    @Test
    fun mountTimeoutRevokesRegistrationsAndBindings() = runBlocking {
        val fixture = fixture(mountTimeoutMs = 25L)
        try {
            fixture.host.mount("slow", fixture.scope) {
                fixture.scope.registrar.registerProvider("during", "provider")
                fixture.scope.registrar.registerExtension(TEST_POINT, "sample", "extension")
                delay(150L)
                handle { }
            }
            fail("Mount timeout must fail the runtime transaction")
        } catch (error: PluginInstallException) {
            assertEquals("RUNTIME_MOUNT_TIMEOUT", error.code)
        }
        assertTrue(fixture.contributions.listByOwner(PLUGIN_ID).isEmpty())
        assertTrue(fixture.router.listBindingsByOwner(PLUGIN_ID).isEmpty())
    }

    @Test
    fun stopExceptionRevokesBeforeReportingFailure() = runBlocking {
        val fixture = fixture()
        val runtime = mountedRuntime(fixture) { throw IllegalStateException("stop boom") }
        val result = fixture.host.stop(runtime)
        assertEquals(PluginRuntimeStopOutcome.FAILED, result.outcome)
        assertEquals("RUNTIME_STOP_FAILED", result.errorCode)
        assertTrue(fixture.contributions.listByOwner(PLUGIN_ID).isEmpty())
        assertTrue(fixture.router.listBindingsByOwner(PLUGIN_ID).isEmpty())
    }

    @Test
    fun stopTimeoutRevokesBeforeReportingTimeout() = runBlocking {
        val fixture = fixture(stopTimeoutMs = 25L)
        val runtime = mountedRuntime(fixture) { delay(150L) }
        val result = fixture.host.stop(runtime)

        assertEquals(PluginRuntimeStopOutcome.TIMEOUT, result.outcome)
        assertEquals("RUNTIME_STOP_TIMEOUT", result.errorCode)
        assertTrue(fixture.contributions.listByOwner(PLUGIN_ID).isEmpty())
        assertTrue(fixture.router.listBindingsByOwner(PLUGIN_ID).isEmpty())
    }

    private suspend fun mountedRuntime(
        fixture: Fixture,
        stop: suspend () -> Unit
    ): HostedPluginRuntime =
        fixture.host.mount("test", fixture.scope) {
            fixture.scope.registrar.registerProvider("during", "provider")
            fixture.scope.registrar.registerExtension(TEST_POINT, "sample", "extension")
            handle(stop)
        }

    private fun fixture(
        mountTimeoutMs: Long = 500L,
        stopTimeoutMs: Long = 500L
    ): Fixture {
        val downstream = linkedSetOf<String>()
        val points = ExtensionPointRegistry().apply {
            register(
                ExtensionPointDefinition(
                    point = TEST_POINT,
                    apiVersion = 1,
                    binder = { record ->
                        downstream += record.id
                        AutoCloseable { downstream -= record.id }
                    }
                )
            )
        }
        val contributions = PluginContributionRegistry()
        val router = ExtensionRouter(points)
        val scope = PluginMountScope(
            manifest = manifest(),
            registry = contributions,
            extensionRouter = router,
            capabilityBinder = NoopCapabilityBinder
        )
        return Fixture(
            host = PluginRuntimeHost(PluginRuntimeTimeouts(mountTimeoutMs, stopTimeoutMs)),
            contributions = contributions,
            router = router,
            scope = scope,
            downstream = downstream
        )
    }

    private fun manifest(): PluginManifest =
        PluginManifest(
            format = PluginAbi.FORMAT,
            schemaVersion = PluginAbi.SCHEMA_VERSION,
            pluginId = PLUGIN_ID,
            version = "1.0.0",
            apiTarget = PluginAbi.CURRENT_API,
            apiMin = PluginAbi.CURRENT_API,
            display = PluginDisplaySpec(name = "Runtime Host Test"),
            roles = emptySet(),
            activationMode = PluginActivationMode.HOT,
            runtime = PluginRuntimeSpec(kind = "test"),
            dependencies = PluginDependencies(),
            permissions = PluginPermissionSpec(),
            provides = PluginProvidesSpec(
                providers = setOf("during", "late"),
                extensions = listOf(PluginExtensionSpec(TEST_POINT, "sample", 1))
            ),
            uiRawJson = null,
            signature = null
        )

    private fun handle(stop: suspend () -> Unit): PluginRuntimeHandle =
        object : PluginRuntimeHandle {
            override suspend fun stop() = stop()
        }

    private data class Fixture(
        val host: PluginRuntimeHost,
        val contributions: PluginContributionRegistry,
        val router: ExtensionRouter,
        val scope: PluginMountScope,
        val downstream: Set<String>
    )

    private object NoopCapabilityBinder : PluginCapabilityBinder {
        override fun register(
            ownerPluginId: String,
            capabilityId: String,
            capability: PluginCapabilitySpec
        ): AutoCloseable = AutoCloseable { }
    }

    private companion object {
        const val PLUGIN_ID = "test.runtime.plugin"
        const val TEST_POINT = "ai_limbs.test.runtime_provider"
    }
}
