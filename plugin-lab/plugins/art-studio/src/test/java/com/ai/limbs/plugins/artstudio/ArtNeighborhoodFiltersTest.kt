package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtNeighborhoodFiltersTest {
    private fun p()=JSONObject()
    @Test fun blurPreservesSignedHdrConstantsAndDoesNotMutateInput() {
        val input=FloatArray(4*5*3) {floatArrayOf(4f,-.125f,1f,.5f)[it%4]};val original=input.copyOf()
        for(id in listOf("filter.gaussian_blur","filter.blur","filter.motion_blur","filter.lens_blur")) {
            val result=ArtNeighborhoodFilters.process(id,input,5,3,p())
            assertArrayEquals(original,input,0f);assertArrayEquals(original,result,1e-5f)
        }
    }
    @Test fun transparentEdgeKeepsStraightColorWithoutBlackFringe() {
        val input=floatArrayOf(8f,0f,0f,1f, 0f,0f,0f,0f, 0f,0f,0f,0f)
        val result=ArtNeighborhoodFilters.process("filter.gaussian_blur",input,3,1,p().put("sigmaX",1).put("sigmaY",0))
        assertTrue(result[7]>0f);assertEquals(8f,result[4]/result[7],1e-5f)
        assertTrue(result[3]<1f);assertEquals(0f,result[5],0f)
    }
    @Test fun gaussianImpulseMatchesIndependentDiscreteKernel() {
        val input=FloatArray(7*4);for(x in 0..6)input[x*4+3]=1f;input[3*4]=4f
        val result=ArtNeighborhoodFilters.process("filter.gaussian_blur",input,7,1,p().put("sigmaX",1).put("sigmaY",0))
        val norm=(-3..3).sumOf {kotlin.math.exp(-it*it/2.0)}
        assertEquals((4/norm).toFloat(),result[12],1e-6f)
        assertEquals((4*kotlin.math.exp(-.5)/norm).toFloat(),result[8],1e-6f)
        assertEquals(result[8],result[16],0f)
    }
    @Test fun channelSelectionPreservesUnselectedStraightChannelsWhenAlphaChanges() {
        val input=floatArrayOf(4f,1f,.5f,1f, 0f,0f,0f,0f, 0f,0f,0f,0f)
        val result=ArtNeighborhoodFilters.process("filter.gaussian_blur",input,3,1,p()
            .put("sigmaX",1).put("sigmaY",0).put("green",false).put("blue",false))
        assertEquals(1f,result[1]/result[3],1e-6f);assertEquals(.5f,result[2]/result[3],1e-6f)
        assertEquals(0f,result[5],0f)
        val keepAlpha=ArtNeighborhoodFilters.process("filter.gaussian_blur",input,3,1,p().put("alpha",false))
        assertEquals(1f,keepAlpha[3],0f);assertEquals(0f,keepAlpha[7],0f)
    }
    @Test fun sharpeningIsASeparateInputConvolutionAndPreservesHdr() {
        val source=floatArrayOf(2f,0f,0f,1f, 4f,0f,0f,1f, 2f,0f,0f,1f)
        val result=ArtNeighborhoodFilters.process("filter.sharpen",source,3,1,p())
        // Repeated vertical border: middle = (7*4-2*2-2*2)/3.
        assertEquals(20f/3,result[4],1e-6f);assertEquals(2f,source[0],0f)
        assertEquals(1f,result[3],0f)
    }
    @Test fun highPassHasNeutralLinearMidpointOnFlatFields() {
        val input=FloatArray(12) {floatArrayOf(8f,2f,-1f,1f)[it%4]}
        val result=ArtNeighborhoodFilters.process("filter.gaussian_high_pass",input,3,1,p())
        for(i in result.indices)assertEquals(if(i%4==3)1f else .5f,result[i],1e-5f)
    }
    @Test fun budgetsAndInvalidParametersRejectWithoutChangingSource() {
        val source=floatArrayOf(4f,0f,0f,1f);val copy=source.copyOf()
        assertThrows(IllegalArgumentException::class.java) {ArtNeighborhoodFilters.process("filter.gaussian_blur",source,1,1,p().put("sigmaX",-1))}
        assertThrows(IllegalArgumentException::class.java) {ArtNeighborhoodFilters.process("filter.gaussian_blur",source,1,1,p(),1)}
        assertThrows(IllegalArgumentException::class.java) {ArtNeighborhoodFilters.process("filter.lens_blur",source,1,1,p().put("sides",3.5))}
        assertThrows(IllegalArgumentException::class.java) {ArtNeighborhoodFilters.process("filter.blur",source,1,1,p().put("shape","unknown"))}
        assertArrayEquals(copy,source,0f)
    }
    @Test fun interruptionCancelsBeforeReturningOutput() {
        Thread.currentThread().interrupt()
        try {assertThrows(java.util.concurrent.CancellationException::class.java) {
            ArtNeighborhoodFilters.process("filter.gaussian_blur",floatArrayOf(1f,1f,1f,1f),1,1,p())
        }} finally {Thread.interrupted()}
    }
}
