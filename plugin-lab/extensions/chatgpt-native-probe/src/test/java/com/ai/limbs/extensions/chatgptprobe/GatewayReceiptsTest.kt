package com.ai.limbs.extensions.chatgptprobe

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GatewayReceiptsTest {
    @Test fun duplicateSurvivesRestartAndRefreshesDeliveryToken() {
        val store = MemoryGatewayStore()
        val ledger = GatewayReceipts(store)
        val command = command()
        val (id, fresh) = ledger.claim("binding", command)
        assertTrue(fresh)
        ledger.ready(id, GatewayProtocol.delivery(command, GatewayProtocol.success(1, JSONObject().put("done", true))))
        ledger.delivered(id, "first")
        val recovered = GatewayReceipts(store)
        val duplicate = recovered.claim("binding", command().put("shard_token", "second"))
        assertEquals(id, duplicate.first)
        assertFalse(duplicate.second)
        assertTrue(recovered.list("binding", "READY").single().second.getJSONObject("response").getJSONObject("resp_json").getJSONObject("result").getBoolean("done"))
        recovered.delivered(id, "first")
        assertEquals(1, recovered.list("binding", "READY").size)
        recovered.delivered(id, "second")
        assertEquals(1, recovered.list("binding", "ACKED").size)
    }

    @Test fun requestIdentityIncludesTunnelAndRejectsChangedArguments() {
        val ledger = GatewayReceipts(MemoryGatewayStore())
        val first = ledger.claim("a", command())
        assertTrue(ledger.claim("b", command()).second)
        val changed = command().apply { getJSONObject("jsonrpc").put("method", "different") }
        assertThrows(IllegalArgumentException::class.java) { ledger.claim("a", changed) }
        assertEquals(first.first, ledger.list("a", "EXECUTING").single().first)
    }

    @Test fun persistFailureNeverClaimsAnExecutionOrLosesItsUncertainReceipt() {
        val store = MemoryGatewayStore()
        val ledger = GatewayReceipts(store)
        store.failNextWrite = true
        assertThrows(java.io.IOException::class.java) { ledger.claim("a", command()) }
        assertEquals(0, ledger.counts().length())
        val id = ledger.claim("a", command()).first
        store.failNextWrite = true
        assertThrows(java.io.IOException::class.java) { ledger.ready(id, JSONObject()) }
        assertEquals(id, GatewayReceipts(store).list("a", "EXECUTING").single().first)
        assertFalse(GatewayReceipts(store).claim("a", command()).second)
    }

    @Test fun canonicalInputIgnoresObjectKeyOrder() {
        val a = JSONObject().put("b", JSONObject().put("y", 2).put("x", 1)).put("a", true)
        val b = JSONObject().put("a", true).put("b", JSONObject().put("x", 1).put("y", 2))
        assertEquals(canonicalJson(a), canonicalJson(b))
    }

    @Test fun acknowledgedImagesKeepHandlesAndDiscardBinaryReceiptBodies() {
        val store = MemoryGatewayStore()
        val ledger = GatewayReceipts(store)
        val command = command()
        val id = ledger.claim("binding", command).first
        val result = GatewayResults(store, binding = "binding").adapt(JSONObject().put("success", true)
            .put("mcp_content", org.json.JSONArray().put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", GatewayResultsTest.PNG))))
        ledger.ready(id, GatewayProtocol.delivery(command, GatewayProtocol.success(1, result)))
        assertTrue(store.read("receipts")!!.contains(GatewayResultsTest.PNG))
        ledger.delivered(id, "first")
        assertFalse(store.read("receipts")!!.contains(GatewayResultsTest.PNG))
        ledger.claim("binding", command().put("shard_token", "second"))
        val replay = ledger.list("binding", "READY").single().second.getJSONObject("response").getJSONObject("resp_json").getJSONObject("result")
        assertTrue(replay.getJSONArray("content").getJSONObject(1).getString("text").contains("ai_limbs_media_read"))
    }

    @Test fun invalidRpcIdsAndNotificationsAreDistinct() {
        assertTrue(GatewayProtocol.validRequest(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")))
        assertFalse(GatewayProtocol.validRequest(JSONObject().put("jsonrpc", "2.0").put("method", "ping").put("id", JSONObject.NULL)))
        assertFalse(GatewayProtocol.validRequest(JSONObject().put("jsonrpc", "1.0").put("method", "ping").put("id", 1)))
        assertFalse(GatewayProtocol.validRequest(JSONObject().put("jsonrpc", "2.0").put("method", "ping").put("id", JSONObject())))
    }

    private fun command() = JSONObject().put("request_id", "req-1").put("channel", "main").put("shard_token", "first")
        .put("command_type", "jsonrpc").put("jsonrpc", JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping"))
}
