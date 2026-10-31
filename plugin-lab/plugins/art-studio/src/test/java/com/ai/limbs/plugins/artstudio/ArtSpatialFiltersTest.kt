package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtSpatialFiltersTest {
    private fun process(id:String,s:FloatArray,w:Int,h:Int,p:JSONObject=JSONObject())=
        ArtSpatialFilters.process("filter.$id",s,w,h,p)
    @Test fun pixelizeUsesImmutablePremultipliedBlockAverageAndClipsEdgeBlocks() {
        val s=floatArrayOf(8f,0f,0f,1f, 0f,0f,0f,0f, 2f,0f,0f,.5f);val copy=s.copyOf()
        val out=process("pixelize",s,3,1,JSONObject().put("pixelWidth",2).put("pixelHeight",1))
        assertArrayEquals(floatArrayOf(4f,0f,0f,.5f, 4f,0f,0f,.5f, 2f,0f,0f,.5f),out,0f)
        assertArrayEquals(copy,s,0f)
    }
    @Test fun areaReducedTilesPreserveHdrAndLeaveOnlyDeclaredRemainderTransparent() {
        val s=FloatArray(5*2*4) {floatArrayOf(8f,-1f,.5f,1f)[it%4]}
        val out=process("small_tiles",s,5,2,JSONObject().put("numberOfTiles",2))
        for(y in 0..1) {
            for(x in 0..3)assertArrayEquals(floatArrayOf(8f,-1f,.5f,1f),out.copyOfRange((y*5+x)*4,(y*5+x+1)*4),0f)
            assertArrayEquals(FloatArray(4),out.copyOfRange((y*5+4)*4,(y*5+5)*4),0f)
        }
    }
    @Test fun tileAreaIntegrationRemovesAlternatingSubpixelPattern() {
        val s=floatArrayOf(0f,0f,0f,1f, 1f,1f,1f,1f, 0f,0f,0f,1f, 1f,1f,1f,1f)
        // 4x1 cannot have 2 tiles vertically; use a 4x2 copy of the row.
        val out=process("small_tiles",s+s,4,2,JSONObject().put("numberOfTiles",2))
        for(i in out.indices step 4)assertEquals(.5f,out[i],1e-6f)
    }
    @Test fun waveZeroIsIdentityAndHalfPixelSamplingDoesNotCreateBlackFringe() {
        val s=floatArrayOf(8f,0f,0f,1f, 0f,0f,0f,0f)
        val p=JSONObject().put("amplitudeX",0).put("amplitudeY",0)
        assertArrayEquals(s,process("wave",s,2,1,p),0f)
        p.put("amplitudeX",.5).put("phaseX",90)
        val out=process("wave",s,2,1,p)
        assertEquals(.5f,out[3],1e-6f);assertEquals(8f,out[0]/out[3],1e-6f)
    }
    @Test fun waveBorderModesAreExplicitAndTriangleIsPeriodic() {
        val s=floatArrayOf(1f,0f,0f,1f, 0f,1f,0f,1f)
        val p=JSONObject().put("amplitudeX",2).put("phaseX",90).put("amplitudeY",0).put("shapeX","triangle")
        assertArrayEquals(s,process("wave",s,2,1,JSONObject(p.toString()).put("border","wrap")),1e-6f)
        assertTrue(process("wave",s,2,1,JSONObject(p.toString()).put("border","transparent")).all {kotlin.math.abs(it)<1e-6f})
        assertArrayEquals(floatArrayOf(0f,1f,0f,1f),process("wave",s,2,1,JSONObject(p.toString()).put("border","repeat")).copyOfRange(0,4),1e-6f)
    }
    @Test fun noiseIsSeededCanFillTransparencyAndGrayscaleHasEqualChannels() {
        val s=FloatArray(8*3*4);val p=JSONObject().put("level",100).put("opacity",100).put("seed",713).put("grayscale",true)
        val a=process("random_noise",s,8,3,p)
        assertArrayEquals(a,process("random_noise",s,8,3,p),0f)
        assertFalse(a.contentEquals(process("random_noise",s,8,3,JSONObject(p.toString()).put("seed",714))))
        for(i in a.indices step 4) {assertEquals(1f,a[i+3],0f);assertEquals(a[i],a[i+1],0f);assertEquals(a[i],a[i+2],0f)}
        assertArrayEquals(s,process("random_noise",s,8,3,JSONObject(p.toString()).put("alpha",false)),0f)
    }
    @Test fun zeroNoiseProbabilityAndPickWindowAreIdentityEvenForSignedHdr() {
        val s=FloatArray(12) {floatArrayOf(8f,-1f,.25f,.5f)[it%4]}
        assertArrayEquals(s,process("random_noise",s,3,1,JSONObject().put("level",0)),0f)
        assertArrayEquals(s,process("random_pick",s,3,1,JSONObject().put("level",100).put("windowsize",0)),0f)
    }
    @Test fun oilpaintDominantBucketPreservesCenterOpacityAndHdrConstants() {
        val row=floatArrayOf(0f,0f,0f,1f, .75f,.75f,.75f,1f, 0f,0f,0f,1f)
        val out=process("oilpaint",row,3,1,JSONObject().put("brushSize",1).put("smooth",30))
        assertArrayEquals(floatArrayOf(0f,0f,0f,1f),out.copyOfRange(4,8),0f)
        val flat=FloatArray(3*3*4) {floatArrayOf(4f,-.5f,1f,.5f)[it%4]}
        assertArrayEquals(flat,process("oilpaint",flat,3,3),1e-6f)
    }
    @Test fun roundCornerScalesPremultipliedColorAndOpacityTogether() {
        val s=FloatArray(8*8*4) {floatArrayOf(8f,0f,0f,1f)[it%4]}
        val p=JSONObject().put("radius",4)
        val out=process("round_corners",s,8,8,p)
        assertEquals(0f,out[3],0f);assertEquals(1f,out[(4*8+4)*4+3],0f)
        var partial=false
        for(i in out.indices step 4)if(out[i+3]>0 && out[i+3]<1) {partial=true;assertEquals(8f,out[i]/out[i+3],1e-6f)}
        assertTrue(partial)
        assertArrayEquals(s,process("round_corners",s,8,8,JSONObject().put("radius",0)),0f)
    }
    @Test fun rainIsDeterministicOnPortraitAndLandscapeWithoutMutatingInput() {
        for((w,h) in listOf(9 to 27,27 to 9)) {
            val s=FloatArray(w*h*4) {if(it%4==3)1f else (it%19)/19f};val copy=s.copyOf()
            val p=JSONObject().put("seed",41).put("dropSize",8).put("number",3)
            val out=process("raindrops",s,w,h,p)
            assertArrayEquals(copy,s,0f);assertArrayEquals(out,process("raindrops",s,w,h,p),0f)
            assertFalse(s.contentEquals(out));assertTrue(out.all {it.isFinite()})
            assertArrayEquals(s,process("raindrops",s,w,h,JSONObject(p.toString()).put("number",0)),0f)
        }
    }
    @Test fun invalidGeometryBudgetsAndInterruptionReject() {
        val s=floatArrayOf(1f,1f,1f,1f)
        assertThrows(IllegalArgumentException::class.java) {process("small_tiles",s,1,1)}
        assertThrows(IllegalArgumentException::class.java) {process("wave",s,1,1,JSONObject().put("periodX",0))}
        assertThrows(IllegalArgumentException::class.java) {process("pixelize",s,1,1,JSONObject().put("pixelWidth",1.5))}
        assertThrows(IllegalArgumentException::class.java) {ArtSpatialFilters.process("filter.wave",s,1,1,JSONObject(),95)}
        Thread.currentThread().interrupt()
        try {assertThrows(java.util.concurrent.CancellationException::class.java) {process("wave",s,1,1)}}finally {Thread.interrupted()}
    }
}
