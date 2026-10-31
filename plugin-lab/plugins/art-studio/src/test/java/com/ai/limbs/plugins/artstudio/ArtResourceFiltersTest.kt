package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ArtResourceFiltersTest {
    private fun run(id:String,s:FloatArray,w:Int,h:Int,p:JSONObject,hidden:FloatArray?=null)=
        ArtResourceFilters.process("filter.$id",s,w,h,p,hidden)
    private fun palette()=JSONObject().put("palette",JSONArray("[\"#FF000000\",\"#FFFFFFFF\"]")).put("distance","linear")
    @Test fun allPropagationMetricsChooseARealNearestSeedOnNonSquareCanvas() {
        val w=7;val h=5;val seeds=listOf(1 to 0,5 to 3,0 to 4)
        val source=FloatArray(w*h*4)
        for((k,xy) in seeds.withIndex()) {val i=(xy.second*w+xy.first)*4;source[i]=(k+1).toFloat();source[i+3]=1f}
        for(metric in listOf("euclidean","cityBlock","chessboard")) {
            val p=JSONObject().put("distanceMetric",metric).put("expansionMode","bounded").put("expansionAmount",2.3)
            val out=run("propagate_colors",source,w,h,p).pixels
            for(y in 0 until h)for(x in 0 until w) {
                val distances=seeds.map {val dx=abs(x-it.first).toDouble();val dy=abs(y-it.second).toDouble()
                    when(metric) {"cityBlock"->dx+dy;"chessboard"->max(dx,dy);else->hypot(dx,dy)}}
                val minimum=distances.minOrNull()!!;val i=(y*w+x)*4
                if(minimum>2.3)assertEquals(0f,out[i+3],0f)
                else {assertEquals(1f,out[i+3],0f);assertTrue(seeds.indices.any {distances[it]==minimum && out[i]==it+1f})}
            }
        }
    }
    @Test fun preservePropagationDoesNotTurnHiddenOrEmissiveColorIntoVisiblePixels() {
        val s=floatArrayOf(1f,.1f,-.5f,.5f, 0f,0f,0f,0f, 4f,0f,0f,0f);val copy=s.copyOf()
        val result=run("propagate_colors",s,3,1,JSONObject().put("alphaMode","preserve"))
        assertArrayEquals(copy,s,0f);assertArrayEquals(copy,result.pixels,0f)
        val hidden=requireNotNull(result.hidden)
        assertEquals(2f,hidden[4],0f);assertEquals(.2f,hidden[5],1e-6f);assertEquals(-1f,hidden[6],0f)
        assertEquals(1f,hidden[7],0f)
        val expanded=run("propagate_colors",s,3,1,JSONObject().put("useHiddenSeeds",true),hidden).pixels
        assertArrayEquals(floatArrayOf(2f,.2f,-1f,1f),expanded.copyOfRange(4,8),1e-6f)
        assertArrayEquals(copy.copyOfRange(8,12),expanded.copyOfRange(8,12),0f)
    }
    @Test fun hiddenSeedsCanBeRevealedAndEuclideanTransformHandlesMissingRows() {
        val source=FloatArray(5*3*4);val hidden=FloatArray(source.size)
        hidden[(1*5+2)*4]=4f;hidden[(1*5+2)*4+3]=1f
        val out=run("propagate_colors",source,5,3,JSONObject().put("useHiddenSeeds",true),hidden).pixels
        for(i in out.indices step 4) {assertEquals(4f,out[i],0f);assertEquals(1f,out[i+3],0f)}
        assertArrayEquals(source,run("propagate_colors",source,5,3,JSONObject(),hidden).pixels,0f)
    }
    @Test fun zeroRadiusExpandsOnlySourceOpacityAndNoSeedsIsIdentity() {
        val s=floatArrayOf(.5f,0f,0f,.5f,0f,0f,0f,0f)
        val p=JSONObject().put("expansionMode","bounded").put("expansionAmount",0)
        assertArrayEquals(floatArrayOf(1f,0f,0f,1f,0f,0f,0f,0f),run("propagate_colors",s,2,1,p).pixels,0f)
        assertArrayEquals(FloatArray(8),run("propagate_colors",FloatArray(8),1,2,p).pixels,0f)
    }
    @Test fun paletteDistanceAndAlphaIndexKeepOriginalPaletteIndices() {
        val p=JSONObject().put("palette",JSONArray("[\"#FFFF0000\",\"#FFFF0000\",\"#FF00FF00\"]"))
            .put("distance","linear").put("alphaMode","index").put("alphaIndex",2)
        val out=run("palettize",floatArrayOf(0f,1f,0f,1f),1,1,p)
        assertArrayEquals(FloatArray(4),out.pixels,0f)
        assertArrayEquals(floatArrayOf(0f,1f,0f,1f),requireNotNull(out.hidden),0f)
    }
    @Test fun orderedAndNearestDitheringHaveKnownFourByFourCoverage() {
        val middle=FloatArray(16*4) {if(it%4==3)1f else .5f}
        val ordered=run("palettize",middle,4,4,palette().put("dither","ordered").put("ditherScale",1)).pixels
        assertEquals(8,(0 until 16).count {ordered[it*4]==1f})
        val quarter=FloatArray(16*4) {if(it%4==3)1f else .25f}
        val nearest=run("palettize",quarter,4,4,palette().put("dither","nearest")).pixels
        assertEquals(4,(0 until 16).count {nearest[it*4]==1f})
        val duplicate=JSONObject().put("palette",JSONArray("[\"#FF000000\",\"#FF000000\"]")).put("dither","nearest")
        assertTrue(run("palettize",middle,4,4,duplicate).pixels.all {it.isFinite()})
    }
    @Test fun paletteAlphaCreatesIndependentHiddenRgbAndDitherRetainsCoverage() {
        val p=JSONObject().put("palette",JSONArray("[\"#00FF0000\",\"#FF000000\"]")).put("alphaMode","palette")
        val out=run("palettize",floatArrayOf(.75f,0f,0f,.75f),1,1,p)
        assertEquals(0f,out.pixels[3],0f);assertEquals(1f,requireNotNull(out.hidden)[0],0f)
        val middle=FloatArray(16*4) {if(it%4==3).5f else .25f}
        val dither=run("palettize",middle,4,4,palette().put("alphaMode","dither")).pixels
        assertEquals(8,(0 until 16).count {dither[it*4+3]==1f})
    }
    @Test fun indexRampInterpolationAndAlphaQuantizationAreIndependent() {
        val rgb=ArtFilterColorSpace.fromLab(doubleArrayOf(50.0,0.0,0.0))
        val p=JSONObject().put("ramps",JSONArray("[[\"#FF000000\",\"#FFFFFFFF\",null,null]]"))
            .put("gradientSteps",JSONArray("[1,0,0]")).put("alphaSteps",0)
        val s=floatArrayOf(rgb[0].toFloat(),rgb[1].toFloat(),rgb[2].toFloat(),1f)
        assertArrayEquals(s,run("index_colors",s,1,1,p).pixels,2e-6f)
        val faint=floatArrayOf(.25f,.25f,.25f,.25f)
        val out=run("index_colors",faint,1,1,p.put("alphaSteps",1))
        assertEquals(0f,out.pixels[3],0f);assertEquals(1f,requireNotNull(out.hidden)[3],0f)
    }
    @Test fun indexReductionLimitsGeneratedOutputColors() {
        val p=JSONObject().put("ramps",JSONArray("[[\"#FF000000\",\"#FFFFFFFF\",null,null]]"))
            .put("gradientSteps",JSONArray("[8,0,0]")).put("reduceColors",true).put("colorLimit",2).put("alphaSteps",0)
        val source=FloatArray(20*4) {if(it%4==3)1f else (it/4)/19f}
        val out=run("index_colors",source,20,1,p).pixels
        assertTrue((0 until 20).map {out[it*4]}.distinct().size<=2)
    }
    @Test fun alphaWeightedLabStatisticsIgnoreFullyTransparentEmissivePixels() {
        val s=floatArrayOf(0f,0f,0f,.25f, .75f,.75f,.75f,.75f, 100f,100f,100f,0f)
        val stats=ArtResourceFilters.statistics(s)
        assertEquals(1.0,stats.weight,0.0);assertEquals(75.0,stats.mean[0],1e-4)
        assertEquals(sqrt(1875.0),stats.std[0],1e-4)
    }
    @Test fun colorTransferUsesFrozenStatisticsAndConstantAxesMapToTargetMean() {
        val source=floatArrayOf(.1f,.2f,.3f,1f, 2f,-.1f,1f,1f)
        val same=ArtResourceFilters.statistics(source).json()
        assertArrayEquals(source,run("color_transfer",source,2,1,JSONObject().put("referenceStats",same)).pixels,2e-6f)
        val reference=ArtResourceFilters.statistics(floatArrayOf(.25f,.25f,.25f,1f)).json()
        val p=JSONObject().put("referenceStats",reference)
        val flat=run("color_transfer",floatArrayOf(1f,0f,0f,1f),1,1,p).pixels
        assertArrayEquals(floatArrayOf(.25f,.25f,.25f,1f),flat,2e-6f)
    }
    private fun pattern(mode:String)=JSONObject().put("mode",mode)
        .put("pattern",JSONObject().put("kind","lines").put("size",4).put("angle",0).put("offset",JSONArray("[0,0]")))
    @Test fun halftoneHasExactBlackWhiteExtremesAndPreservesSourceOpacity() {
        val source=floatArrayOf(0f,0f,0f,.5f, .5f,.5f,.5f,.5f)
        val out=run("halftone",source,2,1,pattern("intensity")).pixels
        assertArrayEquals(source,out,0f)
        val hdr=floatArrayOf(2f,0f,0f,0f)
        assertArrayEquals(hdr,run("halftone",hdr,1,1,pattern("alpha")).pixels,0f)
    }
    @Test fun halftoneChannelsAndAlphaModesAreExecutableAndSpatiallyPeriodic() {
        val source=FloatArray(8*4*4) {if(it%4==3).5f else .25f}
        for(mode in listOf("intensity","channels","alpha")) {
            val p=pattern(mode).put("space","linear").put("channelAngles",JSONArray("[0,0,0]"))
            val out=run("halftone",source,8,4,p).pixels
            for(y in 0..3)for(x in 0..3)for(c in 0..3)assertEquals(out[(y*8+x)*4+c],out[(y*8+x+4)*4+c],0f)
            assertTrue(out.all {it.isFinite()})
            if(mode!="alpha")for(i in out.indices step 4)assertEquals(.5f,out[i+3],0f)
        }
    }
    @Test fun malformedResourcesRejectBeforeChangingInput() {
        val source=floatArrayOf(1f,1f,1f,1f);val copy=source.copyOf()
        assertThrows(IllegalArgumentException::class.java) {run("palettize",source,1,1,palette().put("weights",JSONArray("[0,0,0]")))}
        assertThrows(IllegalArgumentException::class.java) {run("color_transfer",source,1,1,JSONObject().put("referenceStats",JSONObject("{\"model\":\"icc\",\"mean\":[0,0,0],\"std\":[1,1,1]}")))}
        assertThrows(IllegalArgumentException::class.java) {run("halftone",source,1,1,pattern("intensity").put("pattern",JSONObject("{\"kind\":\"unknown\"}")))}
        assertThrows(IllegalArgumentException::class.java) {ArtResourceFilters.statistics(FloatArray(4))}
        assertArrayEquals(copy,source,0f)
    }
    @Test fun budgetsAndCancellationAreCheckedBeforeReturningOutput() {
        val source=floatArrayOf(1f,1f,1f,1f)
        assertThrows(IllegalArgumentException::class.java) {ArtResourceFilters.process("filter.propagate_colors",source,1,1,JSONObject(),null,127)}
        Thread.currentThread().interrupt()
        try {assertThrows(java.util.concurrent.CancellationException::class.java) {run("propagate_colors",source,1,1,JSONObject())}}
        finally {Thread.interrupted()}
    }
}
