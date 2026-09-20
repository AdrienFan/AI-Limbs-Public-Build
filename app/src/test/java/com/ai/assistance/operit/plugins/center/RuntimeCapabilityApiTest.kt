package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.plugins.system.SystemHostGatewayV1
import com.ai.assistance.operit.plugins.system.SystemHostPrimitiveAvailability
import com.ai.assistance.operit.plugins.system.SystemHostPrimitiveDescriptor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class RuntimeCapabilityApiTest {
    @Test
    fun `legacy adapter delegates the existing host gateway unchanged`() = runBlocking {
        val gateway = RecordingGateway()
        val api = RuntimeCapabilityApi.fromLegacyHostGateway(gateway)
        val parameters = JSONObject().put("value", 7)

        val result = api.invoke(
            RuntimeCapabilityCall(
                capabilityId = "host.ui.layout@1",
                operation = "status",
                parameters = parameters
            )
        )

        assertEquals("host.ui.layout@1", gateway.lastId)
        assertEquals("status", gateway.lastOperation)
        assertSame(parameters, gateway.lastParameters)
        assertEquals(true, result.payload.getBoolean("legacy"))
    }

    @Test
    fun `legacy adapter preserves gateway exception semantics`() = runBlocking {
        val gateway = RecordingGateway().apply { failure = IllegalStateException("legacy failure") }
        val api = RuntimeCapabilityApi.fromLegacyHostGateway(gateway)

        try {
            api.invoke(RuntimeCapabilityCall("host.ui.layout@1", "status"))
            fail("Expected legacy gateway exception")
        } catch (error: IllegalStateException) {
            assertEquals("legacy failure", error.message)
        }
    }

    private class RecordingGateway : SystemHostGatewayV1 {
        var lastId: String? = null
        var lastOperation: String? = null
        var lastParameters: JSONObject? = null
        var failure: RuntimeException? = null

        override fun listHostPrimitives(): List<SystemHostPrimitiveDescriptor> = emptyList()
        override fun describeHostPrimitive(id: String): SystemHostPrimitiveDescriptor? = null
        override fun listHostPrimitiveOperations(id: String): List<String> = emptyList()
        override fun availabilityHostPrimitive(id: String, operation: String?): SystemHostPrimitiveAvailability =
            SystemHostPrimitiveAvailability(id, operation, true, true, true)

        override suspend fun invokeHostPrimitive(id: String, parameters: JSONObject): JSONObject {
            error("Operation overload is required by the Stage-2 contract")
        }

        override suspend fun invokeHostPrimitive(
            id: String,
            operation: String,
            parameters: JSONObject
        ): JSONObject {
            failure?.let { throw it }
            lastId = id
            lastOperation = operation
            lastParameters = parameters
            return JSONObject().put("legacy", true)
        }
    }
}
