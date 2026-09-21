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
    fun `stage four router does not migrate privileged runtime yet`() = runBlocking {
        val transport = object : RuntimeCapabilityTransport {
            override suspend fun invoke(call: RuntimeCapabilityCall): RuntimeCapabilityResult {
                fail("Transport must not be reached for an unmigrated capability")
                error("unreachable")
            }
        }
        val api: RuntimeCapabilityApi = RuntimeCapabilityRouter(transport)

        val error = runCatching {
            api.invoke(RuntimeCapabilityCall("host.privileged.runtime@1", "status"))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("not migrated"))
    }
}
