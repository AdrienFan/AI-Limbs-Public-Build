package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure contract checks; the Android mask, compositing and gesture output need device validation. */
class ArtEncloseFillTest {
    @Test fun exactMatchKeepsTransparentAndOpaqueMembershipBinary() {
        assertEquals(255,ArtEncloseFill.ramp(0,0,100))
        assertEquals(0,ArtEncloseFill.ramp(1,0,100))
        assertEquals(255,ArtEncloseFill.ramp(50,50,100))
        assertEquals(0,ArtEncloseFill.ramp(51,50,100))
    }
    @Test fun distanceRampKeepsIntermediateCoverageAndComplementaryConditions() {
        val coverage=ArtEncloseFill.ramp(75,100,50)
        assertEquals(127,coverage)
        assertEquals(128,ArtEncloseFill.condition("not_color",coverage,0))
        assertEquals(200,ArtEncloseFill.condition("color_or_transparent",coverage,200))
        assertEquals(55,ArtEncloseFill.condition("not_color_or_transparent",coverage,200))
        assertEquals(0,ArtEncloseFill.ramp(100,100,50))
    }
    @Test(expected=IllegalArgumentException::class)
    fun softRampRejectsZeroToleranceRatherThanDividingByZero() {ArtEncloseFill.ramp(0,0,50)}

    @Test fun closingCurvePreservesLastOutgoingAndFirstIncomingAbsoluteHandles() {
        val input=JSONArray().put(JSONObject().put("x",20).put("y",20).put("in",JSONArray().put(5).put(30)))
            .put(JSONObject().put("x",120).put("y",20).put("out",JSONArray().put(90).put(70)))
        val nodes=ArtEncloseFill.nodes(input);val geometry=ArtPathGeometry.geometry(nodes,true)
        assertEquals("C",geometry.getJSONArray("commands").getString(1))
        val points=geometry.getJSONArray("points")
        assertEquals(90.0,points.getJSONArray(2).getDouble(0),0.0)
        assertEquals(70.0,points.getJSONArray(2).getDouble(1),0.0)
        assertEquals(5.0,points.getJSONArray(3).getDouble(0),0.0)
        assertEquals(30.0,points.getJSONArray(3).getDouble(1),0.0)
        assertEquals(20.0,points.getJSONArray(4).getDouble(0),0.0)
    }
    @Test(expected=IllegalArgumentException::class)
    fun mixedBezierAndPolylineInputCannotBeSilentlyIgnored() {
        val p=JSONObject().put("shape","bezier").put("nodes",JSONArray()).put("points",JSONArray())
        ArtEncloseFill.geometry(p,JSONObject().put("shape","bezier"))
    }
    @Test(expected=IllegalArgumentException::class)
    fun invalidCurveHandlesAreRejectedBeforeRasterAllocation() {
        val a=JSONArray().put(JSONObject().put("x",20).put("y",20).put("out",JSONArray().put(1000001).put(0)))
            .put(JSONObject().put("x",80).put("y",80))
        ArtEncloseFill.nodes(a)
    }
    @Test(expected=IllegalArgumentException::class)
    fun unknownCompositeCannotBecomeNormalByAccident() {ArtPixelBlend.validate("unknown")}
}
