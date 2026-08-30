package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd.TriggerCmdBridgeDecodeResult
import com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd.TriggerCmdBridgeProtocol
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerCmdBridgeProtocolTest {
    @Test
    fun decodesStructuredJsonRequest() {
        val decoded = TriggerCmdBridgeProtocol.decode(
            """{"protocol":"AIL_TRIGGER_BRIDGE_V1","request_id":"req-1","tool":"capability.search","args":{"query":"Ubuntu"}}"""
        ) as TriggerCmdBridgeDecodeResult.Success

        assertEquals("req-1", decoded.request.requestId)
        assertEquals("capability.search", decoded.request.tool)
        assertEquals("Ubuntu", decoded.request.args.getString("query"))
    }

    @Test
    fun decodesBase64UrlEnvelope() {
        val json = """{"protocol":"AIL_TRIGGER_BRIDGE_V1","request_id":"req-2","tool":"ubuntu.status","args":{}}"""
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        val decoded = TriggerCmdBridgeProtocol.decode("b64:$encoded") as TriggerCmdBridgeDecodeResult.Success

        assertEquals("req-2", decoded.request.requestId)
        assertEquals("ubuntu.status", decoded.request.tool)
    }

    @Test
    fun rejectsNonObjectArgs() {
        val decoded = TriggerCmdBridgeProtocol.decode(
            """{"protocol":"AIL_TRIGGER_BRIDGE_V1","request_id":"req-3","tool":"capability.search","args":"bad"}"""
        ) as TriggerCmdBridgeDecodeResult.Failure

        assertEquals("INVALID_ARGS", decoded.code)
    }

    @Test
    fun completedEnvelopePreservesDispatcherFailure() {
        val request = (
            TriggerCmdBridgeProtocol.decode(
                """{"protocol":"AIL_TRIGGER_BRIDGE_V1","request_id":"req-4","tool":"ubuntu.status","args":{}}"""
            ) as TriggerCmdBridgeDecodeResult.Success
        ).request
        val response = JSONObject(
            TriggerCmdBridgeProtocol.completed(
                request,
                JSONObject().put("success", false).put("error_code", "TEST_FORBID")
            )
        )

        assertFalse(response.getBoolean("ok"))
        assertEquals("TEST_FORBID", response.getString("code"))
        assertTrue(response.has("result"))
    }

    @Test
    fun transportInvocationContainsDecodableEnvelope() {
        val invocation =
            AiLimbsTriggerCmdContract.transportInvocation(
                tool = "capability.search",
                args = JSONObject().put("query", "Ubuntu")
            )
        val decoded =
            TriggerCmdBridgeProtocol.decode(invocation.getString("parameters"))
                as TriggerCmdBridgeDecodeResult.Success

        assertEquals(AiLimbsTriggerCmdContract.COMMAND_NAME, invocation.getString("command"))
        assertTrue(decoded.request.requestId.startsWith("req-"))
        assertEquals("capability.search", decoded.request.tool)
        assertEquals("Ubuntu", decoded.request.args.getString("query"))
    }
}
