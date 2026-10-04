package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtFootprintDeleteTest {
    private fun event(id:String,type:String,p:JSONObject)=JSONObject().put("id",id)
        .put("actor","AWEI").put("type",type).put("parameters",p)
    private fun stroke(id:String)=JSONObject().put("id",id).put("layerId","paint")
        .put("tool","pencil").put("color","#FF000000")
    private fun fixture():Pair<JSONObject,JSONObject> {
        val paint=JSONObject().put("id","paint").put("kind","paint").put("locked",false).put("visible",true).put("opacity",1.0)
            .put("strokes",JSONArray(listOf(stroke("one"),stroke("two"),stroke("three"))))
            .put("contentOrder",JSONArray(listOf("one","two","three").map {
                JSONObject().put("kind","stroke").put("id",it)
            }))
        val vector=JSONObject().put("id","vector").put("kind","vector").put("locked",false).put("visible",true).put("opacity",1.0)
            .put("strokes",JSONArray()).put("shapes",JSONArray().put(JSONObject().put("id","shape").put("locked",false)))
        val ops=JSONArray(listOf("one","two","three").map {event("event-$it","STROKE_ADD",stroke(it))})
        val doc=JSONObject().put("id","document").put("createdBy","AWEI").put("operations",ops)
        return doc to JSONObject().put("layers",JSONArray().put(paint).put(vector))
    }
    @Test fun middleStrokeResolvesOneObjectWithoutChangingLaterContentOrHistory() {
        val (doc,state)=fixture();val before=state.toString();val history=doc.toString()
        val target=ArtFootprintDelete.resolve(doc,state,"event-two")
        assertEquals("STROKE_ERASE",target.getString("type"))
        assertEquals("two",target.getJSONObject("parameters").getString("strokeId"))
        assertEquals(before,state.toString());assertEquals(history,doc.toString())
        assertEquals(listOf("event-one","event-two","event-three"),ArtHistory.stacks(doc.getJSONArray("operations")).first)
    }
    @Test fun projectionExplainsInactiveAndInitialSteps() {
        val (doc,state)=fixture()
        doc.getJSONArray("operations").put(event("undo","REVERT",JSONObject().put("targetId","event-three")))
        val history=ArtFootprintDelete.project(doc,state,ArtHistory.describe(doc))
        val rows=history.getJSONArray("timeline")
        assertFalse(rows.getJSONObject(0).getBoolean("canDelete"))
        assertTrue(rows.getJSONObject(2).getBoolean("canDelete"))
        assertFalse(rows.getJSONObject(3).getBoolean("canDelete"))
        assertTrue(rows.getJSONObject(3).getString("deleteReason").contains("未应用"))
    }
    @Test fun pixelMoveRejectsPriorStrokesButAllowsLaterNewStroke() {
        val (doc,state)=fixture();val layer=state.getJSONArray("layers").getJSONObject(0)
        layer.put("contentOrder",JSONArray().put(JSONObject().put("kind","stroke").put("id","one"))
            .put(JSONObject().put("kind","move_pixels"))
            .put(JSONObject().put("kind","stroke").put("id","two")))
        reject(doc,state,"event-one","像素")
        assertEquals("two",ArtFootprintDelete.resolve(doc,state,"event-two").getJSONObject("parameters").getString("strokeId"))
    }
    @Test fun missingAndLockedObjectsCannotDeleteOtherContent() {
        val (doc,state)=fixture();val layer=state.getJSONArray("layers").getJSONObject(0)
        layer.getJSONArray("strokes").remove(1)
        reject(doc,state,"event-two","像素")
        layer.put("locked",true)
        reject(doc,state,"event-one","锁定")
    }
    private fun addShape(doc:JSONObject) {
        doc.getJSONArray("operations").put(event("shape-event","SHAPE_CREATE",JSONObject().put("layerId","vector")
            .put("shape",JSONObject().put("id","shape").put("kind","path").put("points",JSONArray()))))
    }
    @Test fun vectorCreationDeletesExactlyOneShape() {
        val (doc,state)=fixture();addShape(doc)
        val target=ArtFootprintDelete.resolve(doc,state,"shape-event")
        assertEquals("SHAPE_DELETE",target.getString("type"))
        val ids=target.getJSONObject("parameters").getJSONArray("ids")
        assertEquals(1,ids.length());assertEquals("shape",ids.getString(0))
    }
    @Test fun joinedPathsAreBlockedUntilJoinIsUndone() {
        val (doc,state)=fixture();addShape(doc)
        doc.getJSONArray("operations").put(event("join","SHAPE_FREEHAND",JSONObject().put("layerId","vector")
            .put("startEndpoint",JSONObject().put("id","shape"))))
        reject(doc,state,"shape-event","接续")
        reject(doc,state,"join","接续")
        doc.getJSONArray("operations").put(event("undo-join","REVERT",JSONObject().put("targetId","join")))
        assertTrue(ArtFootprintDelete.resolve(doc,state,"shape-event").getBoolean("allowed"))
    }
    @Test fun duplicateAnimationCelDoesNotReceiveOriginalFootprintDeletion() {
        val (doc,state)=fixture();val layer=state.getJSONArray("layers").getJSONObject(0)
        layer.put("animationKeys",JSONArray().put(JSONObject().put("time",0)).put(JSONObject().put("time",10)))
        state.put("animation",JSONObject().put("current",10))
        doc.getJSONArray("operations").getJSONObject(1).put("animationFrameKeys",JSONObject().put("paint",0))
        reject(doc,state,"event-two","关键帧")
        state.getJSONObject("animation").put("current",5)
        assertTrue(ArtFootprintDelete.resolve(doc,state,"event-two").getBoolean("allowed"))
    }
    private fun reject(doc:JSONObject,state:JSONObject,id:String,reason:String) {
        try {ArtFootprintDelete.resolve(doc,state,id);fail("Should reject $id")}
        catch(e:IllegalArgumentException) {assertTrue(e.message.orEmpty().contains(reason))}
    }
}
