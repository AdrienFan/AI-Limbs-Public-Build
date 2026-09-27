package com.ai.assistance.operit.integrations.ailimbs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsCapabilityDiscoveryProtocolTest {
    @Test
    fun missingAddressRecoveryUsesCanonicalCapabilitySearchContract() {
        val requested = "plugin.test.unknown_abc"
        val nextAction =
            AiLimbsCapabilityDiscoveryProtocol.searchNextAction(
                queryExample = requested,
                transportInvocation = { name, parameters ->
                    JSONObject()
                        .put("tool", name)
                        .put("args", JSONObject(parameters.toString()))
                }
            )

        assertEquals("CAPABILITY_SEARCH", nextAction.getString("type"))
        assertEquals(
            AiLimbsCapabilityDiscoveryProtocol.SEARCH_INVOKE_ID,
            nextAction.getJSONObject("capability").getString("name")
        )
        assertEquals(
            requested,
            nextAction
                .getJSONObject("capability")
                .getJSONObject("parameters")
                .getString("query")
        )
        assertEquals(
            requested,
            nextAction
                .getJSONObject("transport_invocation")
                .getJSONObject("args")
                .getString("query")
        )

        val schema = nextAction.getJSONObject("schema")
        assertTrue(schema.getJSONObject("properties").has("query"))
        assertTrue(schema.getJSONObject("properties").has("scope"))
        assertTrue(schema.getJSONObject("properties").has("limit"))
    }

    @Test
    fun scopedRecoveryPreservesCanonicalScope() {
        val nextAction =
            AiLimbsCapabilityDiscoveryProtocol.searchNextAction(
                queryExample = "保存工程",
                scope = "plugin:plugin.test.unknown_abc",
                transportInvocation = { name, parameters ->
                    JSONObject()
                        .put("tool", name)
                        .put("args", JSONObject(parameters.toString()))
                }
            )

        assertEquals(
            "plugin:plugin.test.unknown_abc",
            nextAction
                .getJSONObject("capability")
                .getJSONObject("parameters")
                .getString("scope")
        )
    }
}
