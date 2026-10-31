package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class ArtColorFiltersTest {
    private fun process(id:String,source:FloatArray,p:JSONObject=JSONObject())=
        ArtColorFilters.process("filter.$id",source,source.size/4,1,p)
    @Test fun neutralAdjustmentsPreserveSignedHdrAndDoNotMutateInput() {
        val source=floatArrayOf(2f,-.125f,.25f,.5f);val copy=source.copyOf()
        for(id in listOf("slope_offset_power","levels","color_adjustment_curves","hsv_adjustment","color_balance","cross_channel_adjustment_curves"))
            assertArrayEquals(source,process(id,source),1e-6f)
        assertArrayEquals(copy,source,0f)
    }
    @Test fun labHasD65WhiteAndSignedHdrRoundTrip() {
        assertEquals(100.0,ArtFilterColorSpace.lab(doubleArrayOf(1.0,1.0,1.0))[0],1e-4)
        assertEquals(0.0,ArtFilterColorSpace.lab(doubleArrayOf(0.0,0.0,0.0))[0],0.0)
        val rgb=doubleArrayOf(4.0,-.25,.75)
        assertArrayEquals(rgb,ArtFilterColorSpace.fromLab(ArtFilterColorSpace.lab(rgb)),2e-6)
    }
    @Test fun cylindricalModelsHaveKnownPrimariesAndRoundTrip() {
        val rgb=doubleArrayOf(.2,.7,.4)
        for(hsl in listOf(false,true)) {
            assertArrayEquals(rgb,ArtFilterColorSpace.fromCylindrical(ArtFilterColorSpace.cylindrical(rgb,hsl),hsl),1e-12)
            assertEquals(1.0/3,ArtFilterColorSpace.hue(doubleArrayOf(0.0,1.0,0.0)),1e-12)
        }
        val out=process("hsv_adjustment",floatArrayOf(1f,0f,0f,1f),JSONObject().put("hue",120).put("space","linear"))
        assertArrayEquals(floatArrayOf(0f,1f,0f,1f),out,1e-6f)
    }
    @Test fun curveIsMonotoneAndUsesExplicitHdrExtension() {
        val points=JSONArray("[[0,0],[0.2,0.7],[0.6,0.8],[1,1]]")
        val curve=ArtFilterCurve(points)
        var last=curve.at(0.0)
        for(i in 1..1000) {val next=curve.at(i/1000.0);assertTrue(next>=last-1e-12);assertTrue(next in 0.0..1.0);last=next}
        assertEquals(-.35,curve.at(-.1),1e-12)
        assertEquals(1.0,ArtFilterCurve(points,"smooth","constant").at(4.0),0.0)
        assertThrows(IllegalArgumentException::class.java) {ArtFilterCurve(JSONArray("[[0,0],[0,0.5],[1,1]]"))}
    }
    @Test fun crossCurveNeutralIsFlatHalfAndDriverTargetsAnotherChannel() {
        val source=floatArrayOf(.2f,.3f,.4f,1f)
        val p=JSONObject().put("space","linear").put("rules",JSONArray("[{\"driver\":\"red\",\"target\":\"green\",\"points\":[[0,0.5],[1,1]]}]"))
        assertArrayEquals(floatArrayOf(.2f,.5f,.4f,1f),process("cross_channel_adjustment_curves",source,p),1e-6f)
        p.put("rules",JSONArray("[{\"driver\":\"hue\",\"target\":\"green\",\"points\":[[0,0.5],[1,0.5]]}]"))
        val hdr=floatArrayOf(4f,-1f,2f,1f)
        assertArrayEquals(hdr,process("cross_channel_adjustment_curves",hdr,p),1e-6f)
    }
    @Test fun crossRulesObservePreviousRuleAndAlphaIsRepremultipliedOnce() {
        val p=JSONObject().put("space","linear").put("rules",JSONArray("[{\"driver\":\"red\",\"target\":\"alpha\",\"points\":[[0,0.75],[1,0.75]]},{\"driver\":\"alpha\",\"target\":\"red\",\"points\":[[0,0.5],[1,1]]}]"))
        assertArrayEquals(floatArrayOf(.7125f,.225f,.3f,.75f),process("cross_channel_adjustment_curves",floatArrayOf(.05f,.075f,.1f,.25f),p),1e-6f)
    }
    @Test fun dodgeBurnUseReferenceFormulasInSelectedWorkSpace() {
        val source=floatArrayOf(.25f,.5f,.75f,1f)
        val p=JSONObject().put("space","linear").put("exposure",.6).put("range","shadows")
        assertArrayEquals(floatArrayOf(.4f,.6f,.8f,1f),process("dodge",source,p),1e-6f)
        assertArrayEquals(floatArrayOf(.0625f,.375f,.6875f,1f),process("burn",source,p),1e-6f)
        p.put("range","midtones")
        assertEquals(.5.pow(1.2).toFloat(),process("burn",source,p)[1],1e-6f)
    }
    @Test fun slopeOffsetPowerAndLevelsPreserveHdrWithoutImplicitClipping() {
        val p=JSONObject().put("space","linear").put("slope",JSONArray("[2,1,1]"))
            .put("offset",JSONArray("[0.5,0,0]")).put("power",JSONArray("[2,1,1]"))
        assertEquals(20.25f,process("slope_offset_power",floatArrayOf(2f,0f,0f,1f),p)[0],0f)
        val levels=JSONObject().put("space","linear").put("levels",JSONObject("{\"master\":{\"black\":0.25,\"white\":0.75}}"))
        assertEquals(3.5f,process("levels",floatArrayOf(2f,0f,0f,1f),levels)[0],0f)
        levels.put("clipInput",true)
        assertEquals(1f,process("levels",floatArrayOf(2f,0f,0f,1f),levels)[0],0f)
    }
    @Test fun colorToAlphaReconstructsOriginalOverTargetInLinearLight() {
        val source=floatArrayOf(.15f,.225f,.3f,.375f)
        val out=process("color_to_alpha",source,JSONObject().put("color","#FFFFFFFF"))
        for(j in 0..2)assertEquals(source[j]+1-source[3],out[j]+1-out[3],1e-6f)
        assertTrue(out[3]<source[3])
    }
    @Test fun gradientUsesPremultipliedInterpolationAndKeepsHdrStopValues() {
        val p=JSONObject().put("stops",JSONArray("[{\"position\":0,\"rgba\":[100,0,0,0]},{\"position\":1,\"rgba\":[0,4,0,1]}]"))
        val out=process("gradient_map",floatArrayOf(.25f,.25f,.25f,.5f),p)
        assertArrayEquals(floatArrayOf(0f,1f,0f,.25f),out,1e-6f)
    }
    @Test fun normalMapNormalizationAndZeroVectorAreDistinctFromContrastStretch() {
        val out=process("normalize",floatArrayOf(.5f,.25f,.25f,.5f, .25f,.25f,.25f,.5f))
        assertArrayEquals(floatArrayOf(.5f,.25f,.25f,.5f, .25f,.25f,.25f,.5f),out,1e-6f)
        val diagonal=process("normalize",floatArrayOf(1f,1f,1f,1f))
        assertEquals((.5+.5/kotlin.math.sqrt(3.0)).toFloat(),diagonal[0],1e-6f)
    }
    @Test fun alphaCurveRespectsRgbChannelMaskAndAutoContrastLeavesFlatFieldsAlone() {
        val source=floatArrayOf(2f,.125f,.25f,.5f)
        val p=JSONObject().put("space","linear").put("red",false).put("curves",JSONObject("{\"alpha\":[[0,0],[1,0.5]]}"))
        val out=process("color_adjustment_curves",source,p)
        assertEquals(.25f,out[3],0f);assertEquals(4f,out[0]/out[3],0f)
        assertArrayEquals(source,process("auto_contrast",source),1e-6f)
    }
    @Test fun malformedAndUndefinedDomainsRejectEvenForTransparentImages() {
        val blank=FloatArray(4)
        assertThrows(IllegalArgumentException::class.java) {process("levels",blank,JSONObject().put("mode","lightness").put("levels",JSONObject("{\"red\":{}}")))}
        assertThrows(IllegalArgumentException::class.java) {process("color_adjustment_curves",blank,JSONObject().put("curves",JSONObject("{\"typo\":[[0,0],[1,1]]}")))}
        assertThrows(IllegalArgumentException::class.java) {process("hsv_adjustment",floatArrayOf(4f,0f,0f,1f),JSONObject().put("hue",20))}
        assertThrows(IllegalArgumentException::class.java) {process("slope_offset_power",floatArrayOf(-1f,0f,0f,1f),JSONObject().put("power",JSONArray("[0.5,1,1]")))}
    }
    @Test fun interruptionAndBudgetRejectBeforeReturningAnOutput() {
        val source=floatArrayOf(1f,1f,1f,1f)
        assertThrows(IllegalArgumentException::class.java) {ArtColorFilters.process("filter.levels",source,1,1,JSONObject(),1)}
        Thread.currentThread().interrupt()
        try {assertThrows(java.util.concurrent.CancellationException::class.java) {process("normalize",source)}}
        finally {Thread.interrupted()}
    }
}
