package com.ai.assistance.operit.plugins.center

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuntimeCapabilityRoutingTest {
    @Test
    fun `ui layout resolves through registry and local host transport unchanged`() = runBlocking {
        var received: RuntimeCapabilityCall? = null
        val transport = object : RuntimeCapabilityTransport {
            override suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult {
                received = call
                return RuntimeCapabilityResult(JSONObject().put("path", "local-host"))
            }
        }
        val api: RuntimeCapabilityApi = RuntimeCapabilityRouter(transport)
        val parameters = JSONObject().put("surface_id", "toolbox")

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
    fun `remote host transport preserves the same capability envelope`() = runBlocking {
        var receivedKind = ""
        var receivedPayload: JSONObject? = null
        val transport = RemoteHostTransport("plugin.test") { kind, payload ->
            receivedKind = kind
            receivedPayload = JSONObject(payload.toString())
            JSONObject()
                .put("ok", true)
                .put("result", JSONObject().put("path", "remote-host"))
        }
        val api: RuntimeCapabilityApi = RuntimeCapabilityRouter(transport)

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
    fun `privileged runtime preserves permission service owner across remote host transport`() = runBlocking {
        var receivedPayload: JSONObject? = null
        val transport = RemoteHostTransport("plugin.system.permission_service") { _, payload ->
            receivedPayload = JSONObject(payload.toString())
            JSONObject()
                .put("ok", true)
                .put("result", JSONObject().put("running", true))
        }
        val api: RuntimeCapabilityApi = RuntimeCapabilityRouter(transport)

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
        assertEquals("status", receivedPayload?.getString("operation"))
        assertEquals(
            CapabilityExecutionOwner.HOST,
            CapabilityRegistry.requireDescriptor("host.privileged.runtime@1").executionOwner
        )
    }

    @Test
    fun `stage six router rejects non migrated host capabilities`() = runBlocking {
        val transport = object : RuntimeCapabilityTransport {
            override suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult {
                fail("Transport must not be reached for an unmigrated capability")
                error("unreachable")
            }
        }
        val api: RuntimeCapabilityApi = RuntimeCapabilityRouter(transport)

        val error = runCatching {
            api.invoke(RuntimeCapabilityCall("host.resident.runtime@1", "status"))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("not migrated"))
    }
}
