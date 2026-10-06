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
        assertTrue(store.read("receipt_" + id)!!.contains(GatewayResultsTest.PNG))
        ledger.delivered(id, "first")
        assertFalse(store.read("receipt_" + id)!!.contains(GatewayResultsTest.PNG))
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

    @Test fun legacyMigrationPreservesReceiptAndNeverReexecutesIt() {
        val store = MemoryGatewayStore()
        val original = GatewayReceipts(store)
        val id = original.claim("binding", command()).first
        original.ready(id, GatewayProtocol.delivery(command(), GatewayProtocol.success(1, JSONObject().put("done", true))))
        val legacy = JSONObject().put(id, JSONObject(store.read("receipt_" + id)!!))
        store.write("receipts", legacy.toString())
        store.delete("receipt_" + id)
        store.failNextWrite = true
        assertThrows(java.io.IOException::class.java) { GatewayReceipts(store) }
        assertNotNull(store.read("receipts"))
        val recovered = GatewayReceipts(store)
        assertNull(store.read("receipts"))
        assertFalse(recovered.claim("binding", command().put("shard_token", "second")).second)
        assertTrue(recovered.list("binding", "READY").single().second.getJSONObject("response")
            .getJSONObject("resp_json").getJSONObject("result").getBoolean("done"))
    }

    @Test fun migratedStateWinsOverLegacyAndOldShardCannotOverwriteIt() {
        val store = MemoryGatewayStore()
        val ledger = GatewayReceipts(store)
        val id = ledger.claim("binding", command()).first
        ledger.ready(id, JSONObject())
        store.write("receipts", JSONObject().put(id, JSONObject(store.read("receipt_" + id)!!)).toString())
        ledger.claim("binding", command().put("shard_token", "second"))
        val recovered = GatewayReceipts(store)
        assertFalse(recovered.delivered(id, "first"))
        recovered.mark(id, "DELIVERY_FAILED", "first")
        assertEquals(1, recovered.list("binding", "READY").size)
        assertTrue(recovered.delivered(id, "second"))
        assertEquals(1, GatewayReceipts(store).list("binding", "ACKED").size)
    }

    @Test fun transitionWriteCostDoesNotGrowWithHistory() {
        val delegate = MemoryGatewayStore()
        val writes = mutableListOf<Pair<String, Int>>()
        val store = object : GatewayBlobStore by delegate {
            override fun write(name: String, value: String) {
                writes += name to value.toByteArray(Charsets.UTF_8).size
                delegate.write(name, value)
            }
        }
        val ledger = GatewayReceipts(store)
        val id = ledger.claim("binding", command()).first
        ledger.ready(id, JSONObject())
        val initial = writes.last().second
        repeat(100) { ledger.claim("binding", command().put("request_id", "history-$it")) }
        writes.clear()
        ledger.ready(id, JSONObject())
        assertEquals(listOf("receipt_" + id to initial), writes)
    }

    @Test fun expirationDeletesOnlyCompletedRecordsAndRecoversAtomicBackups() {
        var time = 1000L
        val store = MemoryGatewayStore()
        val ledger = GatewayReceipts(store, now = { time })
        val id = ledger.claim("binding", command()).first
        ledger.ready(id, JSONObject())
        ledger.delivered(id, "first")
        val pending = ledger.claim("binding", command().put("request_id", "pending")).first
        time += GatewayReceipts.RETENTION_MS + 1
        ledger.claim("binding", command().put("request_id", "new"))
        assertNull(store.read("receipt_" + id))
        assertNotNull(store.read("receipt_" + pending))
        assertEquals(listOf("receipt_a", "receipt_b"), gatewayCommittedRecordNames(
            listOf("receipt_a.bak", "receipt_b", "receipt_b.bak", "receipt_c.new")))
    }

    private fun command() = JSONObject().put("request_id", "req-1").put("channel", "main").put("shard_token", "first")
        .put("command_type", "jsonrpc").put("jsonrpc", JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping"))
}
