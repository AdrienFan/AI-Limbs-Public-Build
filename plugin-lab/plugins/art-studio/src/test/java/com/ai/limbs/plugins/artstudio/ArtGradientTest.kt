package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Pure math/contract checks for later cloud execution; Android masks and UI need deployment validation. */
class ArtGradientTest {
    @Test fun bilinearReflectsAndSquareRotatesWithTheDragVector() {
        assertEquals(0.5,ArtGradientMath.value("bilinear",-5.0,99.0,0.0,0.0,10.0,0.0),1e-9)
        assertEquals(-0.5,ArtGradientMath.value("linear",-5.0,99.0,0.0,0.0,10.0,0.0),1e-9)
        assertEquals(0.5,ArtGradientMath.value("square",0.0,10.0,0.0,0.0,10.0,10.0),1e-9)
    }
    @Test fun symmetricConeMirrorsBothSidesAndSpiralsWindInOppositeDirections() {
        val top=ArtGradientMath.value("symmetric_conical",0.0,-1.0,0.0,0.0,1.0,0.0)
        val bottom=ArtGradientMath.value("symmetric_conical",0.0,1.0,0.0,0.0,1.0,0.0)
        assertEquals(top,bottom,1e-9)
        val forward=ArtGradientMath.value("spiral",0.0,1.0,0.0,0.0,1.0,0.0)
        val reverse=ArtGradientMath.value("reverse_spiral",0.0,1.0,0.0,0.0,1.0,0.0)
        assertEquals(0.25,ArtGradientMath.repeat(forward,"forward"),1e-9)
        assertEquals(0.75,ArtGradientMath.repeat(reverse,"forward"),1e-9)
    }
    @Test fun repeatHandlesNegativeCoordinatesIntegerSeamsAndSpiralAlternation() {
        assertEquals(0.75,ArtGradientMath.repeat(-0.25,"forward"),0.0)
        assertEquals(0.0,ArtGradientMath.repeat(1.0,"forward"),0.0)
        assertEquals(0.25,ArtGradientMath.repeat(-0.25,"alternate"),0.0)
        assertEquals(0.75,ArtGradientMath.repeat(1.25,"alternate"),0.0)
        assertEquals(0.5,ArtGradientMath.repeat(1.25,"alternate",true),0.0)
    }
    @Test fun transparentStopsDoNotInjectInvisibleBlueIntoRedEdges() {
        val stops=ArtGradient.stops(JSONArray("[[0,\"#FFFF0000\"],[1,\"#000000FF\"]]"))
        val color=ArtGradient.interpolate(stops,0.5,"srgb")
        assertEquals(127.5,color[0],0.0);assertEquals(255.0,color[1],0.0);assertEquals(0.0,color[3],0.0)
    }
    @Test fun middleColorStopIsRespectedAndLinearRgbIsDifferentFromEncodedSrgb() {
        val stops=ArtGradient.stops(JSONArray("[[0,\"#FF000000\"],[0.3,\"#FFFF0000\"],[1,\"#FFFFFFFF\"]]"))
        val atStop=ArtGradient.interpolate(stops,0.3,"srgb")
        assertEquals(255.0,atStop[1],0.0);assertEquals(0.0,atStop[2],0.0)
        val bw=ArtGradient.stops(JSONArray("[[0,\"#FF000000\"],[1,\"#FFFFFFFF\"]]"))
        assertTrue(ArtGradient.interpolate(bw,0.5,"linear_rgb")[1]>ArtGradient.interpolate(bw,0.5,"srgb")[1])
    }
    @Test fun distanceFieldKeepsRectangleCenterAndBoundaryAndRecognizesHoles() {
        val rectangle=ArtGradientMath.contour(ByteArray(25) {255.toByte()},5,5)
        assertEquals(0f,rectangle[12],0f);assertEquals(1f,rectangle[0],0f)
        val hole=ByteArray(81) {255.toByte()};hole[40]=0
        val contour=ArtGradientMath.contour(hole,9,9)
        assertEquals(1f,contour[39],0f);assertTrue(contour.any {it==0f})
    }
    @Test fun disconnectedSmallAndLargeIslandsEachGetTheirOwnDeepestColor() {
        val mask=ByteArray(55)
        for(y in 0..4)for(x in 0..4)mask[y*11+x]=255.toByte()
        for(y in 1..3)for(x in 7..9)mask[y*11+x]=255.toByte()
        val contour=ArtGradientMath.contour(mask,11,5)
        assertEquals(0f,contour[2*11+2],0f);assertEquals(0f,contour[2*11+8],0f)
    }
    @Test fun noiseIsDeterministicBoundedAndSeedDependent() {
        val n=ArtGradientMath.noise(-3,12,7,1)
        assertEquals(n,ArtGradientMath.noise(-3,12,7,1),0.0)
        assertTrue(n>=-0.5&&n<0.5)
        assertNotEquals(n,ArtGradientMath.noise(-3,12,8,1),0.0)
    }
    @Test(expected=IllegalArgumentException::class)
    fun duplicateOrUnsortedStopsAreRejected() {ArtGradient.stops(JSONArray("[[0,\"#FF000000\"],[0,\"#FFFFFFFF\"],[1,\"#FFFFFFFF\"]]"))}
}
