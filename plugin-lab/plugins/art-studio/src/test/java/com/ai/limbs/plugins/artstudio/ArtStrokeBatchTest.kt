package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtStrokeBatchTest {
    private fun request(items: JSONArray) = JSONObject().put("layerId", "layer")
        .put("defaults", JSONObject().put("color", "#FF245364").put("width", 6).put("tool", "ink"))
        .put("strokes", items)
    private fun path(x: Double) = JSONArray().put(JSONArray().put(x).put(0)).put(JSONArray().put(x + 2).put(0))
    private fun prepared(part: ArtStrokeBatch.Part): JSONObject = ArtBrush.prepare(part.stroke,
        ArtBrush.settings(part.stroke.getString("tool"), part.stroke.optJSONObject("brush") ?: JSONObject()),
        part.stroke.getInt("brushSeed"))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (expected: IllegalArgumentException) { }
    }

    @Test fun distantSegmentsDoNotGenerateTransitDabsAndHaveIndependentInputs() {
        val input = request(JSONArray().put(JSONObject().put("segments", JSONArray().put(path(0.0)).put(path(999998.0)))))
        val before = input.toString()
        val parts = ArtStrokeBatch.expand(input)
        assertEquals(2, parts.size)
        assertEquals(listOf(0, 1), parts.map { it.segmentIndex })
        val strokes = parts.map { prepared(it) }
        val budget = ArtStrokeBatch.budget(strokes)
        assertTrue(budget.getLong("estimatedDabs") < 20)
        assertEquals(0.0, strokes[0].getJSONArray("brushInput").getJSONArray(0).getDouble(0), 0.0)
        assertEquals(999998.0, strokes[1].getJSONArray("brushInput").getJSONArray(0).getDouble(0), 0.0)
        assertEquals(before, input.toString())
        // The old continuous path still includes its transit. Stored painting appearance is unchanged.
        val connected = request(JSONArray().put(JSONObject().put("points", JSONArray().put(JSONArray().put(0).put(0)).put(JSONArray().put(1000000).put(0)))))
        try { prepared(ArtStrokeBatch.expand(connected).single()); fail("Must reject a huge continuous stroke") }
        catch (error: ArtStrokeBudgetExceeded) {
            assertTrue(error.lowerBound)
            assertEquals(60001L, error.dabs)
            assertEquals(60000, error.dabLimit)
            assertFalse(error.response().getBoolean("operationApplied"))
        }
    }

    @Test fun randomDynamicsHaveStablePreviewSeedsAndStylesDoNotLeakBetweenParts() {
        val brush = JSONObject("""{"dynamics":{"size":{"enabled":true,"sensor":"random","curve":[[0,0.1],[1,1]]}}}""")
        val input = request(JSONArray().put(JSONObject().put("points", path(0.0)).put("brush", brush).put("width", 12))
            .put(JSONObject().put("segments", JSONArray().put(path(20.0)).put(path(40.0)))))
        fun budget() = ArtStrokeBatch.budget(ArtStrokeBatch.expand(input).map { prepared(it) })
        val first = budget(); val second = budget()
        assertEquals(first.toString(), second.toString())
        val parts = ArtStrokeBatch.expand(input)
        assertEquals(12.0, parts[0].stroke.getDouble("width"), 0.0)
        assertEquals(6.0, parts[1].stroke.getDouble("width"), 0.0)
        assertEquals(128, parts[1].stroke.getInt("brushSeed"))
        parts[0].stroke.put("width", 99)
        assertEquals(6.0, parts[2].stroke.getDouble("width"), 0.0)
    }

    @Test fun explicitBoundariesRejectAmbiguousGeometryUnknownOptionsAndTooManyParts() {
        rejected { ArtStrokeBatch.expand(request(JSONArray().put(JSONObject().put("points", path(0.0)).put("segments", JSONArray().put(path(0.0)))))) }
        rejected { ArtStrokeBatch.expand(request(JSONArray().put(JSONObject().put("points", path(0.0)).put("tool", "mirror")))) }
        rejected { ArtStrokeBatch.expand(request(JSONArray().put(JSONObject().put("points", path(0.0)).put("documentId", "wrong")))) }
        rejected { ArtStrokeBatch.expand(request(JSONArray().put(JSONObject().put("segments", JSONArray(List(129) { path(it.toDouble()) }))))) }
    }

    @Test fun aggregateBudgetReportsExactTotalAndIndividualParticleOverflowReportsLowerBound() {
        val stroke = JSONObject().put("tool", "ink").put("color", "#FF000000").put("width", 1)
            .put("points", JSONArray().put(JSONArray().put(0).put(0)).put(JSONArray().put(10000).put(0)))
        val prepared = ArtBrush.prepare(stroke, ArtBrush.defaults("ink"), 0)
        try { ArtStrokeBatch.budget(List(3) { prepared }); fail("Must enforce aggregate limit") }
        catch (error: ArtStrokeBudgetExceeded) {
            assertEquals("batch", error.scope); assertFalse(error.lowerBound)
            assertEquals(120003L, error.dabs); assertEquals(120000, error.dabLimit)
        }
        val particles = JSONObject(stroke.toString()).put("points", JSONArray().put(JSONArray().put(0).put(0)).put(JSONArray().put(1200).put(0)))
        val brush = ArtBrush.settings("ink", JSONObject().put("count", 64))
        try { ArtBrush.prepare(particles, brush, 0); fail("Must enforce particle limit") }
        catch (error: ArtStrokeBudgetExceeded) {
            assertEquals(240064L, error.particles); assertEquals(240000, error.particleLimit)
            assertTrue(error.lowerBound)
        }
    }

    @Test fun candidateValidationFailureCannotPublishEarlierMembersAndSuccessWritesOnce() {
        fun doc() = JSONObject().put("operations", JSONArray().put(JSONObject().put("id", "old")))
        val events = listOf(JSONObject().put("id", "first"), JSONObject().put("id", "second"))
        var writes = 0
        var saved = doc().toString()
        val previous = saved
        try {
            ArtStrokeBatch.commit(doc(), events, { candidate ->
                assertEquals(3, candidate.getJSONArray("operations").length())
                error("second member invalid")
            }, { bytes -> writes++; saved = bytes.toString(Charsets.UTF_8) })
            fail("Must propagate validation failure")
        } catch (expected: IllegalStateException) { assertEquals("second member invalid", expected.message) }
        assertEquals(0, writes); assertEquals(previous, saved)
        val result = ArtStrokeBatch.commit(doc(), events, { candidate ->
            JSONObject().put("revision", candidate.getJSONArray("operations").length())
        }, { bytes -> writes++; saved = bytes.toString(Charsets.UTF_8) })
        assertEquals(1, writes); assertEquals(3, result.getInt("revision"))
        assertEquals("second", JSONObject(saved).getJSONArray("operations").getJSONObject(2).getString("id"))
    }
}
