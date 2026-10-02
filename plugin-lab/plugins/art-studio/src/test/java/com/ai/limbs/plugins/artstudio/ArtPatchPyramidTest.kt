package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure array cases for later cloud execution. No Android rendering or local test execution required. */
class ArtPatchPyramidTest {
    @Test fun oddPyramidDimensionsPreserveKnownEdgeAndMaskAnyUnknownChild() {
        val pixels=IntArray(15) {0xff204060.toInt()};val holes=BooleanArray(15);holes[1]=true
        val down=ArtPatchPyramid.downsample(ArtPatchPyramid.Frame(5,3,pixels,holes))
        assertEquals(3,down.width);assertEquals(2,down.height);assertEquals(2,down.scale)
        assertTrue(down.hole[0]);assertEquals(0,down.pixels[0]);assertEquals(pixels[14],down.pixels[5])
    }
    @Test fun transparentHiddenRgbDoesNotPollutePremultipliedPyramidColor() {
        val down=ArtPatchPyramid.downsample(ArtPatchPyramid.Frame(2,1,
            intArrayOf(0xffff0000.toInt(),0x000000ff),BooleanArray(2)))
        assertEquals(128,down.pixels[0] ushr 24)
        assertEquals(255,(down.pixels[0] ushr 16) and 255);assertEquals(0,down.pixels[0] and 255)
    }
    @Test fun donorCentersNeverUsePatchesIntersectingTheErasedObject() {
        val hole=BooleanArray(81);hole[40]=true
        val valid=ArtPatchPyramid.donors(ArtPatchPyramid.Frame(9,9,IntArray(81) {-1},hole),1)
        assertFalse(valid[40]);assertFalse(valid[30]);assertTrue(valid[10]);assertFalse(valid[0])
    }
    @Test fun coarseToFineOutputHasNativeSizeAndKnownPixelsStayExact() {
        val w=64;val h=64;val source=IntArray(w*h) {0xff508090.toInt()};val hole=BooleanArray(w*h)
        for(y in 20..39)for(x in 20..39) {hole[y*w+x]=true;source[y*w+x]=0xff000000.toInt()}
        val result=ArtPatchPyramid.repair(source,hole,w,h,1,32,1,3,0,7)
        assertEquals(source.size,result.pixels.size);assertEquals(3,result.levels.size)
        assertEquals(1,result.levels.last().scale);assertEquals(w,result.levels.last().width)
        for(i in source.indices)assertEquals(if(hole[i])0xff508090.toInt() else source[i],result.pixels[i])
    }
    @Test fun originalSingleScaleIsExplicitAndSameSeedIsRepeatable() {
        val w=20;val source=IntArray(400) {if(it%w%4<2)0xff407060.toInt() else 0xff608040.toInt()}
        val hole=BooleanArray(400);for(y in 8..11)for(x in 8..11)hole[y*w+x]=true
        val first=ArtPatchPyramid.repair(source,hole,w,w,1,16,1,1,1,8)
        val second=ArtPatchPyramid.repair(source,hole,w,w,1,16,1,1,1,8)
        assertArrayEquals(first.pixels,second.pixels);assertEquals(first.work,second.work)
        assertEquals(1,first.levels.size);assertEquals(1,first.levels[0].refinementStep)
    }
    @Test fun maskAboveOld32KLimitCanBeRepairedWithoutShrinkingOutput() {
        val w=256;val source=IntArray(w*w) {0xff487860.toInt()};val hole=BooleanArray(w*w)
        for(y in 36 until 220)for(x in 36 until 220)hole[y*w+x]=true
        assertTrue(hole.count {it}>32768)
        val result=ArtPatchPyramid.repair(source,hole,w,w,1,128,1,4,8,0)
        assertEquals(w*w,result.pixels.size);assertEquals(hole.count {it},result.levels.last().maskPixels)
        assertTrue(result.pixels.all {it==0xff487860.toInt()});assertTrue(result.work<=ArtPatchPyramid.MAX_WORK)
    }
    @Test fun spacingScalesExplicitRequestsAndAutoSpacingRespondsToAccuracy() {
        assertEquals(2,ArtPatchPyramid.spacing(100,100,40,8,4))
        assertEquals(1,ArtPatchPyramid.spacing(100,100,40,1,4))
        assertTrue(ArtPatchPyramid.spacing(1024,1024,1,0,1)>ArtPatchPyramid.spacing(1024,1024,100,0,1))
    }
    @Test fun extendedOptionsExposeLargeSearchAndIndependentPlanningControls() {
        val o=ArtSmartPatch.options(JSONObject("{\"width\":48,\"searchRadius\":1024,\"levels\":6,\"refinementStep\":16,\"seed\":7}"))
        assertEquals(1024,o.search);assertEquals(6,o.levels);assertEquals(16,o.refinementStep);assertEquals(7,o.seed)
        assertTrue(ArtSmartPatch.MAX_MASK_PIXELS>32768);assertTrue(ArtSmartPatch.MAX_REGION_PIXELS>1048576)
    }
    @Test(expected=IllegalArgumentException::class)
    fun noCleanDonorFailsRatherThanProducingAZeroPatch() {
        ArtPatchPyramid.repair(IntArray(64) {-1},BooleanArray(64) {true},8,8,1,16,1,1)
    }
    @Test(expected=IllegalArgumentException::class)
    fun requestedImpossibleCoarseDepthIsRejected() {
        val hole=BooleanArray(64);hole[27]=true
        ArtPatchPyramid.repair(IntArray(64) {-1},hole,8,8,1,16,1,6)
    }
    @Test(expected=IllegalArgumentException::class)
    fun fractionalLevelCountIsRejected() {ArtSmartPatch.options(JSONObject().put("width",32).put("levels",2.5))}
}
