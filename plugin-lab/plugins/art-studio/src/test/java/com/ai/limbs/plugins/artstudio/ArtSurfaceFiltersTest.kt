package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class ArtSurfaceFiltersTest {
    private fun process(id:String,s:FloatArray,w:Int,h:Int,p:JSONObject=JSONObject())=
        ArtSurfaceFilters.process("filter.$id",s,w,h,p)
    @Test fun derivativeOfRampMatchesAnalyticSlopeForAllKernels() {
        val s=FloatArray(3*3*4) {i->if(i%4==3)1f else .2f+.2f*((i/4)%3)}
        for(type in listOf("sobel","prewitt","simple")) {
            val out=process("edge_detection",s,3,3,JSONObject().put("type",type).put("heightChannel","red").put("output","xGrowth"))
            assertEquals(.2f,out[16],1e-6f)
            val falling=process("edge_detection",s,3,3,JSONObject().put("type",type).put("output","xFall"))
            assertEquals(0f,falling[16],0f)
        }
    }
    @Test fun normalMapIsUnitLengthAndSwizzleControlsAxisAndSign() {
        val s=FloatArray(3*3*4) {i->if(i%4==3)1f else .2f+.2f*((i/4)%3)}
        val p=JSONObject().put("heightChannel","red").put("strength",2)
        val out=process("height_to_normal_map",s,3,3,p)
        val nx=2*out[16]-1;val ny=2*out[17]-1;val nz=2*out[18]-1
        assertEquals(1f,nx*nx+ny*ny+nz*nz,1e-6f)
        assertEquals((-.4/sqrt(1.16)).toFloat(),nx,1e-6f);assertEquals(0f,ny,1e-6f)
        val swapped=process("height_to_normal_map",s,3,3,p.put("swizzle",JSONArray("[\"z+\",\"x-\",\"y+\"]")))
        assertEquals(out[18],swapped[16],0f);assertEquals(1-out[16],swapped[17],1e-6f)
    }
    @Test fun flatNormalReceivesExpectedHeadOnPhongLightAndKeepsHdrIntensity() {
        val s=floatArrayOf(.25f,.25f,.5f,.5f)
        val p=JSONObject().put("normalInput",true).put("ambient",.1).put("diffuse",.7).put("specular",.2)
            .put("lights",JSONArray("[{\"azimuth\":0,\"elevation\":90,\"color\":\"#FFFFFFFF\",\"intensity\":2}]"))
        val out=process("phong_bumpmap",s,1,1,p)
        assertEquals(1.9f,out[0]/out[3],1e-6f);assertEquals(.5f,out[3],0f)
        assertThrows(IllegalArgumentException::class.java) {process("phong_bumpmap",floatArrayOf(.5f,.5f,.5f,1f),1,1,p)}
    }
    @Test fun haarDiscardsFineCheckerboardWithoutDiscardingMeanOrAlpha() {
        val s=FloatArray(16) {i->if(i%4==3)1f else if(i/4 in listOf(1,2))1f else 0f}
        val out=process("wavelet_noise_reducer",s,2,2,JSONObject().put("threshold",2))
        for(i in out.indices)assertEquals(if(i%4==3)1f else .5f,out[i],1e-6f)
    }
    @Test fun haarSupportsSingleAxisNonPowerOfTwoAndZeroThresholdIsExact() {
        val s=floatArrayOf(0f,0f,0f,1f, 1f,1f,1f,1f, 0f,0f,0f,1f)
        for((w,h) in listOf(3 to 1,1 to 3)) {
            val out=process("wavelet_noise_reducer",s,w,h,JSONObject().put("threshold",16))
            for(i in out.indices step 4)assertEquals(.25f,out[i],1e-6f)
            assertArrayEquals(s,process("wavelet_noise_reducer",s,w,h,JSONObject().put("threshold",0)),0f)
        }
    }
    @Test fun denoisersPreserveSignedHdrConstantsAndChannelMask() {
        val s=FloatArray(3*5*4) {floatArrayOf(4f,-1f,.5f,.5f)[it%4]};val copy=s.copyOf()
        for(id in listOf("wavelet_noise_reducer","gaussian_noise_reduction"))assertArrayEquals(s,process(id,s,3,5),1e-5f)
        assertArrayEquals(copy,s,0f)
        val p=JSONObject().put("red",false)
        val out=process("edge_detection",s,3,5,p)
        assertEquals(4f,out[0],0f);assertEquals(0f,out[1],1e-6f)
    }
    @Test fun gaussianNoiseThresholdControlsReplacementAndRetainsOpacity() {
        val s=floatArrayOf(0f,0f,0f,1f, 1f,1f,1f,1f, 0f,0f,0f,1f)
        val out=process("gaussian_noise_reduction",s,3,1,JSONObject().put("threshold",.1))
        assertTrue(out[4]<1f);assertTrue(out[0]>0f);assertEquals(1f,out[7],0f)
        assertArrayEquals(s,process("gaussian_noise_reduction",s,3,1,JSONObject().put("threshold",2)),0f)
    }
    @Test fun malformedLightsAndPaddedMemoryLimitRejectWithoutChangingInput() {
        val s=FloatArray(5*5*4) {if(it%4==3)1f else .5f};val copy=s.copyOf()
        assertThrows(IllegalArgumentException::class.java) {ArtSurfaceFilters.process("filter.wavelet_noise_reducer",s,5,5,JSONObject(),2500)}
        assertThrows(IllegalArgumentException::class.java) {process("phong_bumpmap",s,5,5,JSONObject().put("lights",JSONArray("[{\"typo\":1}]")))}
        assertArrayEquals(copy,s,0f)
    }
}
