package com.ai.assistance.operit.plugins.center

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCapabilityRoutingTest {
    private fun marker(path: String, received: ((RuntimeCapabilityCall) -> Unit)? = null) =
        RuntimeCapabilityTransport { call ->
            received?.invoke(call)
            RuntimeCapabilityResult(JSONObject().put("path", path))
        }

    private fun router(
        host: RuntimeCapabilityTransport,
        business: RuntimeCapabilityTransport = marker("business"),
        pluginRuntime: RuntimeCapabilityTransport = marker("plugin-runtime"),
        externalDaemon: RuntimeCapabilityTransport = marker("external-daemon")
    ): RuntimeCapabilityRouter =
        RuntimeCapabilityRouter(
            RuntimeCapabilityTransportSet.explicit(
                host = host,
                business = business,
                pluginRuntime = pluginRuntime,
                externalDaemon = externalDaemon
            )
        )

    @Test
    fun `host descriptor resolves through HOST transport with canonical schema`() = runBlocking {
        var received: RuntimeCapabilityCall? = null
        val parameters = JSONObject().put("surface_id", "toolbox")
        val api: RuntimeCapabilityApi = router(
            host = marker("local-host") { received = it }
        )

        val result = api.invoke(
            RuntimeCapabilityCall(
                capabilityId = " HOST.UI.LAYOUT@1 ",
                operation = "status",
                parameters = parameters
            )
        )

        assertEquals("local-host", result.payload.getString("path"))
        assertEquals("host.ui.layout@1", received?.capabilityId)
        assertEquals("status", received?.operation)
        assertSame(parameters, received?.parameters)
        assertEquals(
            CapabilityExecutionOwner.HOST,
            CapabilityRegistry.requireDescriptor("host.ui.layout@1").executionOwner
        )
    }

    @Test
    fun `business descriptor resolves through BUSINESS transport without capability whitelist`() = runBlocking {
        var received: RuntimeCapabilityCall? = null
        val api: RuntimeCapabilityApi = router(
            host = marker("host"),
            business = marker("business") { received = it }
        )

        val result = api.invoke(
            RuntimeCapabilityCall(
                capabilityId = "HOST.RESIDENT.RUNTIME@1",
                operation = "status"
            )
        )

        assertEquals("business", result.payload.getString("path"))
        assertEquals("host.resident.runtime@1", received?.capabilityId)
        assertEquals(
            CapabilityExecutionOwner.BUSINESS,
            CapabilityRegistry.requireDescriptor("host.resident.runtime@1").executionOwner
        )
    }

    @Test
    fun `remote host transport preserves the same capability envelope`() = runBlocking {
        var receivedKind = ""
        var receivedPayload: JSONObject? = null
        val remote = RemoteHostTransport("plugin.test") { kind, payload ->
            receivedKind = kind
            receivedPayload = JSONObject(payload.toString())
            JSONObject()
                .put("ok", true)
                .put("result", JSONObject().put("path", "remote-host"))
        }
        val api: RuntimeCapabilityApi = router(host = remote)

        val result = api.invoke(
            RuntimeCapabilityCall(
                capabilityId = "host.ui.layout@1",
                operation = "status",
                parameters = JSONObject().put("surface_id", "toolbox")
            )
        )

        assertEquals("remote-host", result.payload.getString("path"))
        assertEquals("host_primitive", receivedKind)
        assertEquals("plugin.test", receivedPayload?.getString("owner_plugin_id"))
        assertEquals("host.ui.layout@1", receivedPayload?.getString("primitive_id"))
        assertEquals("status", receivedPayload?.getString("operation"))
        assertEquals(
            "toolbox",
            receivedPayload?.getJSONObject("parameters")?.getString("surface_id")
        )
    }

    @Test
    fun `privileged runtime preserves permission service owner across HOST transport`() = runBlocking {
        var receivedPayload: JSONObject? = null
        val remote = RemoteHostTransport("plugin.system.permission_service") { _, payload ->
            receivedPayload = JSONObject(payload.toString())
            JSONObject()
                .put("ok", true)
                .put("result", JSONObject().put("running", true))
        }
        val api: RuntimeCapabilityApi = router(host = remote)

        val result = api.invoke(
            RuntimeCapabilityCall(
                capabilityId = " HOST.PRIVILEGED.RUNTIME@1 ",
                operation = "status"
            )
        )

        assertTrue(result.payload.getBoolean("running"))
        assertEquals(
            "plugin.system.permission_service",
            receivedPayload?.getString("owner_plugin_id")
        )
        assertEquals(
            "host.privileged.runtime@1",
            receivedPayload?.getString("primitive_id")
        )
    }

    @Test
    fun `plugin runtime and external daemon owners use explicit adapter slots`() = runBlocking {
        var pluginCall: RuntimeCapabilityCall? = null
        var daemonCall: RuntimeCapabilityCall? = null
        val pluginAdapter = PluginRuntimeTransportAdapter(marker("plugin") { pluginCall = it })
        val daemonAdapter = ExternalDaemonTransportAdapter(marker("daemon") { daemonCall = it })
        val transports = RuntimeCapabilityTransportSet.explicit(
            host = marker("host"),
            business = marker("business"),
            pluginRuntime = pluginAdapter,
            externalDaemon = daemonAdapter
        )

        val pluginResult = transports.resolve(CapabilityExecutionOwner.PLUGIN_RUNTIME)
            .invoke(RuntimeCapabilityCall("plugin.synthetic@1", "invoke"))
        val daemonResult = transports.resolve(CapabilityExecutionOwner.EXTERNAL_DAEMON)
            .invoke(RuntimeCapabilityCall("daemon.synthetic@1", "invoke"))

        assertEquals("plugin", pluginResult.payload.getString("path"))
        assertEquals("daemon", daemonResult.payload.getString("path"))
        assertEquals("plugin.synthetic@1", pluginCall?.capabilityId)
        assertEquals("daemon.synthetic@1", daemonCall?.capabilityId)
    }
}
