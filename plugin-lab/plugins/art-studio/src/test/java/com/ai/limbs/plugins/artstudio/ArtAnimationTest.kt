package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Cloud JVM cases. Real Android rendering/gestures remain installation acceptance checks. */
class ArtAnimationTest {
    private fun layer(id:String="paint",kind:String="paint")=JSONObject()
        .put("id",id).put("kind",kind).put("name",id).put("parentId","")
        .put("visible",true).put("locked",false).put("asset","")
        .put("x",0.0).put("y",0.0).put("scale",1.0).put("rotation",0.0)
        .put("opacity",1.0).put("blend","normal").put("strokes",JSONArray().put(JSONObject().put("id","original")))
        .apply {if(kind=="vector")put("shapes",JSONArray())}
    private fun state()=JSONObject().put("layers",JSONArray().put(layer())).put("selectedLayerId","paint")
    private fun key(state:JSONObject,time:Int,action:String,extra:JSONObject=JSONObject()) {
        val p=JSONObject().put("layerId","paint").put("frame",time).put("action",action)
        extra.keys().forEach {p.put(it,extra.get(it))}
        ArtAnimation.edit(state,"ANIMATION_KEY",ArtAnimation.prepareKey(state,p))
    }
    private fun paint(state:JSONObject)=state.getJSONArray("layers").getJSONObject(0)
    private fun rejects(block:()->Unit) {
        try {block();fail("Expected rejection")} catch(expected:IllegalArgumentException) {assertTrue(expected.message.orEmpty().isNotBlank())}
    }
    @Test fun firstBlankFramePreservesOriginalAnchorAndNativeContents() {
        val state=state();key(state,5,"blank")
        assertEquals(listOf(0,5),(0..1).map {ArtAnimation.keys(paint(state))!!.getJSONObject(it).getInt("time")})
        ArtAnimation.resolve(state,0)
        assertEquals("original",paint(state).getJSONArray("strokes").getJSONObject(0).getString("id"))
        ArtAnimation.resolve(state,5)
        assertEquals(0,paint(state).getJSONArray("strokes").length())
        assertEquals("",paint(state).getString("asset"))
    }
    @Test fun heldFrameEditsOnlyItsSourceCelAndKeepsLayerMetadataShared() {
        val state=state();key(state,5,"blank");ArtAnimation.resolve(state,7)
        val before=ArtAnimation.before(state)
        paint(state).getJSONArray("strokes").put(JSONObject().put("id","new"))
        paint(state).put("name","renamed")
        ArtAnimation.capture(state,7,before)
        ArtAnimation.resolve(state,0)
        assertEquals("original",paint(state).getJSONArray("strokes").getJSONObject(0).getString("id"))
        ArtAnimation.resolve(state,8)
        assertEquals("new",paint(state).getJSONArray("strokes").getJSONObject(0).getString("id"))
        assertEquals("renamed",paint(state).getString("name"))
        assertEquals(5,ArtAnimation.active(paint(state),8)!!.getInt("time"))
    }
    @Test fun duplicateHasIndependentEditableGeometryAndNoAliasToSource() {
        val state=state();key(state,5,"duplicate")
        ArtAnimation.resolve(state,5)
        key(state,10,"duplicate",JSONObject().put("sourceFrame",5))
        ArtAnimation.resolve(state,10);val before=ArtAnimation.before(state)
        paint(state).getJSONArray("strokes").getJSONObject(0).put("id","edited")
        ArtAnimation.capture(state,10,before)
        ArtAnimation.resolve(state,5)
        assertEquals("original",paint(state).getJSONArray("strokes").getJSONObject(0).getString("id"))
        ArtAnimation.resolve(state,10)
        assertEquals("edited",paint(state).getJSONArray("strokes").getJSONObject(0).getString("id"))
    }
    @Test fun moveAndRemoveChangeExplicitPositionsWithoutSortingAwayEmptyCels() {
        val state=state();key(state,5,"blank");key(state,9,"duplicate")
        key(state,5,"move",JSONObject().put("targetFrame",7))
        assertEquals(0,ArtAnimation.active(paint(state),6)!!.getInt("time"))
        assertEquals(7,ArtAnimation.active(paint(state),8)!!.getInt("time"))
        key(state,7,"remove")
        assertEquals(0,ArtAnimation.active(paint(state),8)!!.getInt("time"))
        assertEquals(9,ArtAnimation.active(paint(state),9)!!.getInt("time"))
    }
    @Test fun rejectsOverwritingAnchorChangesUnsupportedTracksAndLockedParents() {
        val state=state();key(state,5,"blank")
        rejects {key(state,5,"duplicate")}
        rejects {key(state,0,"remove")}
        rejects {key(state,0,"move",JSONObject().put("targetFrame",1))}
        rejects {key(state,5,"move",JSONObject().put("targetFrame",0))}
        val locked=state();paint(locked).put("locked",true)
        rejects {key(locked,5,"blank")}
        val grouped=state();paint(grouped).put("parentId","group")
        grouped.getJSONArray("layers").put(layer("group","group").put("locked",true))
        rejects {key(grouped,5,"blank")}
        val text=state();paint(text).put("kind","text")
        rejects {key(text,5,"blank")}
    }
    @Test fun disableBakesDisplayedNativeCelAndSettingsKeepInclusiveRange() {
        val state=state();key(state,5,"blank")
        ArtAnimation.edit(state,"ANIMATION_TIME",JSONObject().put("frame",7))
        key(state,7,"disable")
        assertNull(ArtAnimation.keys(paint(state)))
        assertEquals(0,paint(state).getJSONArray("strokes").length())
        ArtAnimation.edit(state,"ANIMATION_SETTINGS",JSONObject().put("fps",24).put("start",3).put("end",8))
        assertEquals(24,ArtAnimation.settings(state).getInt("fps"))
        assertEquals(3,ArtAnimation.settings(state).getInt("start"))
        assertEquals(8,ArtAnimation.settings(state).getInt("end"))
        rejects {ArtAnimation.edit(state,"ANIMATION_SETTINGS",JSONObject().put("fps",0))}
        rejects {ArtAnimation.edit(state,"ANIMATION_SETTINGS",JSONObject().put("end",2))}
        rejects {ArtAnimation.edit(state,"ANIMATION_SETTINGS",JSONObject().put("start",0).put("end",600))}
    }
    @Test fun framePreviewDoesNotMutateDocumentOrOriginalCel() {
        val state=state();key(state,5,"blank")
        val snapshot=JSONObject().put("id","doc").put("revision",2).put("state",state)
        val before=snapshot.toString()
        val preview=ArtAnimation.frame(snapshot,8)
        assertEquals(0,paint(preview.getJSONObject("state")).getJSONArray("strokes").length())
        assertEquals(before,snapshot.toString())
        assertEquals(0,ArtAnimation.settings(state).getInt("current"))
    }
    @Test fun copiedTrackRemapsInactiveCelIdentitiesAndPreservesSource() {
        val state=state();key(state,5,"blank");key(state,9,"duplicate")
        val source=paint(state)
        val before=source.toString()
        val copy=JSONObject(source.toString()).put("id","copied")
        copy.getJSONArray("strokes").getJSONObject(0).put("id","fresh").put("layerId","copied")
        ArtAnimation.remapKeys(copy,source)
        assertEquals(before,source.toString())
        for(time in listOf(0,9)) {
            val stroke=ArtAnimation.active(copy,time)!!.getJSONObject("content").getJSONArray("strokes").getJSONObject(0)
            assertEquals("fresh",stroke.getString("id"));assertEquals("copied",stroke.getString("layerId"))
        }
        assertEquals(0,ArtAnimation.active(copy,5)!!.getJSONObject("content").getJSONArray("strokes").length())
    }
    @Test fun committedDuplicateKeepsCapturedSourceEvenWhenEarlierContentChanges() {
        val state=state();key(state,5,"duplicate")
        val prepared=ArtAnimation.prepareKey(state,JSONObject().put("layerId","paint").put("frame",10)
            .put("action","duplicate").put("sourceFrame",5))
        ArtAnimation.resolve(state,5);val before=ArtAnimation.before(state)
        paint(state).getJSONArray("strokes").getJSONObject(0).put("id","changed-source")
        ArtAnimation.capture(state,5,before)
        ArtAnimation.edit(state,"ANIMATION_KEY",prepared)
        ArtAnimation.resolve(state,10)
        assertEquals("original",paint(state).getJSONArray("strokes").getJSONObject(0).getString("id"))
    }
    @Test fun selectiveUndoCannotRedirectLaterFrameEditIntoAnotherCel() {
        val state=state();key(state,5,"blank")
        key(state,5,"remove");ArtAnimation.resolve(state,7)
        val before=ArtAnimation.before(state)
        paint(state).getJSONArray("strokes").put(JSONObject().put("id","new"))
        rejects {ArtAnimation.capture(state,7,before,JSONObject().put("paint",5))}
    }
    @Test fun timeNavigationKeepsRedoWithoutCreatingUndoFootprintSteps() {
        fun event(id:String,type:String,p:JSONObject=JSONObject())=JSONObject().put("id",id).put("type",type)
            .put("parameters",p).put("actor","LANER")
        val events=JSONArray().put(event("a","LAYER_CREATE")).put(event("u","REVERT",JSONObject().put("targetId","a")))
            .put(event("time","ANIMATION_TIME",JSONObject().put("frame",7)))
        val doc=JSONObject().put("id","doc").put("createdBy","AWEI").put("operations",events)
        val history=ArtHistory.describe(doc)
        assertEquals(0,history.getInt("position"))
        assertTrue(history.getBoolean("canRedo"))
        assertEquals(2,history.getJSONArray("timeline").length())
        assertEquals(1,history.getJSONObject("historyStats").getInt("editCount"))
    }
}
