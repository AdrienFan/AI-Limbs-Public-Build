package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtMoveTest {
    private fun layer(id:String,parent:String="",kind:String="paint",opacity:Double=1.0,visible:Boolean=true)=JSONObject()
        .put("id",id).put("parentId",parent).put("kind",kind).put("opacity",opacity).put("visible",visible).put("locked",false)
    private fun state(vararg layers:JSONObject)=JSONObject().put("layers",JSONArray(layers.toList()))
    private fun rejects(block:()->Unit) {
        try {block();fail("Expected explicit rejection")} catch(expected:IllegalArgumentException) { }
    }
    @Test fun physicalUnitsUseToolPpiWithoutChangingDocumentSize() {
        assertEquals(300.0,ArtMove.toPixels(1.0,"in",300.0),0.000001)
        assertEquals(300.0,ArtMove.toPixels(25.4,"mm",300.0),0.000001)
        assertEquals(300.0,ArtMove.toPixels(2.54,"cm",300.0),0.000001)
        assertEquals(300.0,ArtMove.toPixels(72.0,"pt",300.0),0.000001)
        assertEquals(1.0,ArtMove.toPixels(1.0,"px",300.0),0.000001)
    }
    @Test fun switchingDisplayedUnitsCanPreservePixelStep() {
        val pixels=ArtMove.toPixels(0.25,"mm",300.0)
        val inches=ArtMove.fromPixels(pixels,"in",300.0)
        assertEquals(pixels,ArtMove.toPixels(inches,"in",300.0),0.000001)
    }
    @Test fun keyboardDirectionsAndLargeStepStayInDocumentPixels() {
        val options=ArtMove.defaults().put("unit","mm").put("ppi",254).put("step",0.3).put("largeMultiplier",5)
        assertEquals(3.0 to 0.0,ArtMove.nudge("right",false,options))
        assertEquals(0.0 to -15.0,ArtMove.nudge("up",true,options))
        assertEquals(-15.0 to 0.0,ArtMove.nudge("left",true,options))
    }
    @Test fun TinyKeyboardStepIsOnePixelAndOversizeLargeStepIsRejected() {
        assertEquals(0.0 to 1.0,ArtMove.nudge("down",false,ArtMove.defaults().put("step",0.01)))
        rejects {ArtMove.nudge("right",true,ArtMove.defaults().put("step",16384))}
    }
    @Test fun onlyPixelMovesQuantizeFractionalDisplacement() {
        val request=JSONObject().put("dx",0.6).put("dy",-0.6).put("unit","px")
        assertEquals(0.6 to -0.6,ArtMove.delta(request,ArtMove.defaults(),false))
        assertEquals(1.0 to -1.0,ArtMove.delta(request,ArtMove.defaults(),true))
        assertEquals(0.0 to 0.0,ArtMove.delta(request.put("dx",0.1).put("dy",0.1),ArtMove.defaults(),true))
    }
    @Test fun displacementLimitsAreAppliedAfterUnitConversion() {
        rejects {ArtMove.delta(JSONObject().put("dx",100).put("dy",0).put("unit","in").put("ppi",300),ArtMove.defaults(),false)}
        rejects {ArtMove.validateSettings(ArtMove.defaults().put("ppi",0))}
        rejects {ArtMove.validateSettings(ArtMove.defaults().put("ignoreLocked","false"))}
    }
    @Test fun automaticSelectionModeDoesNotTurnAnEmptyMaskIntoAMove() {
        val state=JSONObject()
        assertFalse(ArtMove.selectionMode(state,"auto"))
        state.put("selection",JSONObject().put("width",0).put("height",0))
        assertFalse(ArtMove.selectionMode(state,"auto"));rejects {ArtMove.selectionMode(state,"selection")}
        state.getJSONObject("selection").put("width",10).put("height",20)
        assertTrue(ArtMove.selectionMode(state,"auto"));assertFalse(ArtMove.selectionMode(state,"layer"))
    }
    @Test fun movingSelectionCopiesItsFrameWithoutMutatingTheSource() {
        val original=JSONObject().put("shape","rect").put("x",5).put("y",6).put("width",20).put("height",30)
        val shifted=ArtMove.shiftedSelection(original,-50.0,40.0)
        assertEquals(5,original.getInt("x"));assertEquals(-45,shifted.getInt("x"));assertEquals(46,shifted.getInt("y"))
        assertEquals(20,shifted.getInt("width"));assertEquals(30,shifted.getInt("height"))
    }
    @Test fun parentVisibilityAndOpacityAffectActualContentPicking() {
        val group=layer("g",kind="group",opacity=0.5);val child=layer("c","g",opacity=0.4)
        val state=state(group,child)
        assertEquals(0.2,ArtMove.visibility(state,child),0.000001)
        group.put("visible",false);assertEquals(0.0,ArtMove.visibility(state,child),0.0)
    }
    @Test fun visualDrawOrderTraversesGroupsAtTheirStackPosition() {
        val state=state(layer("bottom"),layer("g",kind="group"),layer("top"),layer("inside","g"))
        assertEquals(listOf("bottom","inside","top"),ArtMove.drawOrder(state).map {it.getString("id")})
    }
    @Test fun groupPickUsesNearestParentAndRootContentRemainsItself() {
        val outer=layer("outer",kind="group");val inner=layer("inner","outer","group");val child=layer("c","inner");val root=layer("root")
        val state=state(outer,inner,child,root)
        assertSame(inner,ArtMove.pickGroup(state,child));assertSame(root,ArtMove.pickGroup(state,root))
    }
    @Test fun selectedCoverageDoesNotCopyUnselectedPixelsOrMakeTransparentPixelsOpaque() {
        assertEquals(0,ArtMovePixels.maskedAlpha(255,0))
        assertEquals(255,ArtMovePixels.maskedAlpha(255,255))
        assertEquals(128,ArtMovePixels.maskedAlpha(255,128))
        assertEquals(64,ArtMovePixels.maskedAlpha(128,128))
        assertEquals(0,ArtMovePixels.maskedAlpha(0,255))
    }
}
