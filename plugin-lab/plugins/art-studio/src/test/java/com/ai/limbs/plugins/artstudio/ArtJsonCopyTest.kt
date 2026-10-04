package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtJsonCopyTest {
    @Test fun editsCannotMutateCapturedTracksOrTheirInactiveCels() {
        val source=JSONObject().put("animationKeys",JSONArray().put(JSONObject().put("time",0)
            .put("content",JSONObject().put("shapes",JSONArray().put(JSONObject().put("id","shape")
                .put("matrix",JSONArray().put(1.0).put(0).put(0).put(1).put(23.25).put(-4)))))))
            .put("nullValue",JSONObject.NULL).put("name","潮声\n\"灯火\"").put("visible",true)
        val encoded=source.toString()
        val copy=ArtJsonCopy.objectValue(source)
        copy.getJSONArray("animationKeys").getJSONObject(0).getJSONObject("content")
            .getJSONArray("shapes").getJSONObject(0).getJSONArray("matrix").put(4,500)
        copy.put("name","edited").put("visible",false)
        assertEquals(encoded,source.toString())
        assertTrue(copy.isNull("nullValue"))
        assertEquals(23.25,source.getJSONArray("animationKeys").getJSONObject(0).getJSONObject("content")
            .getJSONArray("shapes").getJSONObject(0).getJSONArray("matrix").getDouble(4),0.0)
    }

    @Test fun copiedCoordinatesAndUnicodeSurviveTheExistingJsonWireFormat() {
        val source=JSONObject().put("coordinates",JSONArray().put(-0.0).put(1.0).put(23.25).put(9999))
            .put("holes",JSONArray().put(2,"kept")).put("caption","☂️ 雨里的灯火").put("empty",JSONObject()).put("nothing",JSONObject.NULL)
        val decoded=JSONObject(ArtJsonCopy.objectValue(source).toString())
        val oldDecoded=JSONObject(source.toString())
        for(index in 0..3)assertEquals(oldDecoded.getJSONArray("coordinates").getDouble(index),
            decoded.getJSONArray("coordinates").getDouble(index),0.0)
        assertEquals(source.getString("caption"),decoded.getString("caption"))
        assertEquals(0,decoded.getJSONObject("empty").length());assertTrue(decoded.isNull("nothing"))
        assertTrue(decoded.getJSONArray("holes").isNull(0));assertTrue(decoded.getJSONArray("holes").isNull(1))
        assertEquals("kept",decoded.getJSONArray("holes").getString(2))
    }
}
