package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtGifScheduleTest {
    @Test fun unionOfKeyTimesPreservesHeldImagesInclusiveRangeAndRounding() {
        val settings=ArtAnimation.settings(JSONObject()).put("fps",8).put("start",3).put("end",14)
        fun layer(times:List<Int>)=JSONObject().put("id",times.toString()).put("animationKeys",JSONArray(times.map {JSONObject().put("time",it)}))
        val state=JSONObject().put("animation",settings).put("layers",JSONArray().put(layer(listOf(0,4,10))).put(layer(listOf(0,7,10))))
        val runs=ArtGifSchedule.runs(JSONObject().put("state",state))
        assertEquals(listOf(3,4,7,10),runs.map {it.frame})
        assertEquals(listOf(1,3,3,5),runs.map {it.frames})
        assertEquals(150,runs.sumOf {it.delay})
        for(run in runs)assertEquals((run.frame-3 until run.frame-3+run.frames).sumOf {ArtGifWriter.delay(it,8)},run.delay)
    }
    @Test fun staticSceneRendersOnceAndKeepsAllLogicalFrames() {
        val state=JSONObject().put("animation",ArtAnimation.settings(JSONObject())).put("layers",JSONArray())
        val runs=ArtGifSchedule.runs(JSONObject().put("state",state))
        assertEquals(1,runs.size);assertEquals(24,runs.single().frames);assertEquals(200,runs.single().delay)
    }
}
