package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure seed-contract checks; Android raster effects are validated after deployment. */
class ArtContiguousFillTest {
    private fun drag(vararg points:Pair<Int,Int>)=JSONObject().put("dragMode","any").put("points",
        JSONArray().apply {points.forEach {put(JSONArray().put(it.first).put(it.second))}})

    @Test fun dragVisitsOnePixelRegionsBetweenPointerEvents() {
        val seeds=ArtContiguousFill.seeds(drag(0 to 2,4 to 2),5,5)
        assertEquals(listOf(0 to 2,1 to 2,2 to 2,3 to 2,4 to 2),seeds)
    }

    @Test fun cornersStayOnTheRecordedPolylineAndRepeatedVisitsDoNotMultiplySeeds() {
        val seeds=ArtContiguousFill.seeds(drag(0 to 0,3 to 0,1 to 0,1 to 2),4,4)
        assertEquals(listOf(0 to 0,1 to 0,2 to 0,3 to 0,1 to 1,1 to 2),seeds)
        assertFalse(seeds.contains(2 to 1))
    }

    @Test fun legacySingleSeedUsesTheSameDocumentCoordinates() {
        val p=JSONObject().put("dragMode","off").put("x",7).put("y",9)
        assertEquals(listOf(7 to 9),ArtContiguousFill.seeds(p,10,10))
    }

    @Test(expected=IllegalArgumentException::class)
    fun conflictingSinglePointAndDragInputsAreRejected() {
        ArtContiguousFill.seeds(drag(1 to 1,3 to 3).put("x",1).put("y",1),10,10)
    }

    @Test(expected=IllegalArgumentException::class)
    fun singleClickModeCannotSilentlyDiscardAdditionalPoints() {
        ArtContiguousFill.seeds(drag(1 to 1,3 to 3).put("dragMode","off"),10,10)
    }

    @Test(expected=IllegalArgumentException::class)
    fun longGestureIsRejectedAtTheSeedBudget() {
        ArtContiguousFill.seeds(drag(0 to 0,ArtContiguousFill.MAX_SEEDS to 0),ArtContiguousFill.MAX_SEEDS+1,1)
    }

    @Test(expected=IllegalArgumentException::class)
    fun fractionalCoordinatesAreNotSilentlyTruncated() {
        val p=JSONObject().put("dragMode","any").put("points",JSONArray().put(JSONArray().put(1.25).put(2)))
        ArtContiguousFill.seeds(p,10,10)
    }

    @Test(expected=IllegalArgumentException::class)
    fun canvasEdgeIsOutsideTheWritablePixelRange() {
        ArtContiguousFill.seeds(drag(10 to 0),10,10)
    }
}
