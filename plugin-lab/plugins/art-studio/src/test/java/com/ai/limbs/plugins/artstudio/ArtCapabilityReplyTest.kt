package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Run in cloud CI; no Android render or on-device mutation is needed for projection checks. */
class ArtCapabilityReplyTest {
    private fun rejected(block: () -> Unit) {
        try { block() } catch (error: IllegalArgumentException) { return }
        fail("Expected rejected input")
    }
    private fun snapshot(): JSONObject = JSONObject().put("id", "document").put("revision", 7)
        .put("state", JSONObject().put("layers", JSONArray()).put("name", "雨夜🌧️").put("width", 640).put("height", 480))
        .put("operations", JSONArray()).put("timeline", JSONArray()).put("otherBranches", JSONArray())

    @Test fun oversizedSnapshotHasSmallReceiptAndUnmodifiedLegacyResult() {
        val result = snapshot()
        val huge = "x".repeat(2 * 1024 * 1024)
        result.getJSONObject("state").put("cels", huge)
        result.getJSONArray("operations").put(JSONObject().put("source", huge))
        result.getJSONArray("timeline").put(JSONObject().put("parameters", huge))
        result.getJSONArray("otherBranches").put(JSONObject().put("parameters", huge))
        result.put("lastOperationId", "operation").put("canUndo", true)
            .put("thumbnail", JSONObject().put("status", "error").put("operationApplied", true))
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size > 1024 * 1024)
        assertSame(result, ArtCapabilityReply.format(result, ArtCapabilityReply.mode(JSONObject())))
        val receipt = ArtCapabilityReply.format(result, "receipt")
        assertTrue(receipt.toString().toByteArray(Charsets.UTF_8).size < 4096)
        assertEquals("operation", receipt.getString("lastOperationId"))
        assertTrue(receipt.getBoolean("historyWritten"))
        assertSame(result.getJSONObject("thumbnail"), receipt.getJSONObject("thumbnail"))
        for (key in listOf("state", "operations", "timeline", "otherBranches")) {
            assertFalse(receipt.has(key)); assertTrue(result.has(key))
        }
    }

    @Test fun nonSnapshotBusinessResultsAndNoOpEvidenceArePreserved() {
        val exported = JSONObject().put("path", "/export.gif")
        assertSame(exported, ArtCapabilityReply.format(exported, "receipt"))
        assertFalse(ArtCapabilityReply.format(snapshot(), "receipt").getBoolean("historyWritten"))
        rejected { ArtCapabilityReply.mode(JSONObject().put("responseMode", "tiny")) }
    }

    @Test fun preprojectedSnapshotNeedsNoDiscardedHistoryAndKeepsCommitEvidenceAndImages() {
        val full=snapshot().put("lastOperationId","committed").put("canUndo",true)
            .put("historyStats",JSONObject().put("eventCount",7)).put("mcp_content",JSONArray()
                .put(JSONObject().put("type","image").put("data","CURRENT"))
                .put(JSONObject().put("type","image").put("data","FRAMES")))
        val expected=ArtCapabilityReply.format(full,"receipt")
        val projected=JSONObject(full.toString())
        for(key in listOf("operations","timeline","otherBranches"))projected.remove(key)
        val actual=ArtCapabilityReply.format(projected,"receipt")
        assertEquals(expected.keys().asSequence().toSet(),actual.keys().asSequence().toSet())
        expected.keys().forEach {key->assertEquals(expected.get(key).toString(),actual.get(key).toString())}
        assertFalse(actual.has("state"));assertTrue(actual.getBoolean("historyWritten"))
        assertEquals(2,actual.getJSONArray("mcp_content").length())
        assertSame(projected,ArtCapabilityReply.format(projected,"full"))
    }

    @Test fun summaryDoesNotRequireMaterializedHistoryAndRetainsMetadataContract() {
        val full=snapshot().put("dirty",true).put("canUndo",true).put("undoLabel","编辑")
            .put("historyStats",JSONObject().put("eventCount",7))
        val expected=ArtCapabilityReply.summary(full)
        val compact=JSONObject(full.toString())
        for(key in listOf("operations","timeline","otherBranches"))compact.remove(key)
        val summary=ArtCapabilityReply.summary(compact)
        assertEquals(expected.keys().asSequence().toSet(),summary.keys().asSequence().toSet())
        expected.keys().forEach {key->assertEquals(expected.get(key).toString(),summary.get(key).toString())}
        for(key in listOf("state","operations","timeline","otherBranches"))assertFalse(summary.has(key))
        assertEquals(7,summary.getInt("revision"));assertTrue(summary.getBoolean("dirty"))
        assertEquals("编辑",summary.getString("undoLabel"));assertTrue(summary.getBoolean("snapshotOmitted"))
        assertFalse(summary.getBoolean("historyWritten"))
    }

    @Test fun pagesReconstructExactJsonAndRespectUnicodeBoundariesAndIdentity() {
        val snapshot = snapshot().apply { getJSONObject("state").put("content", "夜🌧️".repeat(500)) }
        val expected = snapshot.toString()
        val assembled = StringBuilder()
        var offset = 0
        var digest: String? = null
        while (true) {
            val page = ArtCapabilityReply.page(snapshot, "document", 7, offset, 3)
            val source = page.getString("source")
            assertFalse(source.first().isLowSurrogate())
            assertFalse(source.last().isHighSurrogate())
            assembled.append(source)
            if (digest == null) digest = page.getString("sha256") else assertEquals(digest, page.getString("sha256"))
            if (page.getBoolean("complete")) break
            assertTrue(page.getInt("nextOffset") > offset)
            offset = page.getInt("nextOffset")
        }
        assertEquals(expected, assembled.toString())
        rejected { ArtCapabilityReply.page(snapshot, "other", 7, 0, 100) }
        rejected { ArtCapabilityReply.page(snapshot, "document", 8, 0, 100) }
        rejected { ArtCapabilityReply.page(snapshot, "document", 7, 0, 16385) }
        rejected { ArtCapabilityReply.page(snapshot, "document", 7, expected.indexOf("🌧") + 1, 100) }
    }

    @Test fun historicalRequestIsVerifiableWithoutReturningItsPayloadAndCannotBeReused() {
        val requestId = "b62fce44-508d-4a11-8ef8-6db01ad74cdc"
        val operation = JSONObject().put("id", "operation").put("requestId", requestId)
            .put("actor", "LANER").put("type", "ANIMATION_KEY").put("timestamp", 123L)
            .put("parameters", JSONObject().put("source", "x".repeat(2 * 1024 * 1024)))
        val doc = JSONObject().put("id", "document").put("operations", JSONArray().put(operation))
        val status = ArtOperationReceipt.status(doc, requestId)
        assertEquals("committed", status.getString("status"))
        assertEquals(1, status.getInt("revision"))
        assertEquals("operation", status.getString("operationId"))
        assertFalse(status.has("parameters"))
        rejected { ArtOperationReceipt.requireUnused(doc, requestId) }
        assertEquals("not_found", ArtOperationReceipt.status(doc, "76841e4f-4e0b-48b3-a3ce-169b2d72564a").getString("status"))
        rejected { ArtOperationReceipt.validate("bad") }
        rejected { ArtOperationReceipt.validate(requestId.uppercase()) }
    }
}
