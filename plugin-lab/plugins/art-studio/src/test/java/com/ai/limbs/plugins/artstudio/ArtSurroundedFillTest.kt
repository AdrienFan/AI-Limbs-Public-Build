package com.ai.limbs.plugins.artstudio

import org.junit.Assert.*
import org.junit.Test

/** Pixel-mask regressions for the next unified build; no Android renderer is needed. */
class ArtSurroundedFillTest {
    private fun ring():ByteArray = ByteArray(81).also {mask->
        for(y in 2..6)for(x in 2..6)if(x==2 || x==6 || y==2 || y==6)mask[y*9+x]=255.toByte()
    }
    private fun select(barrier:ByteArray,includeSurrounding:Boolean=false,includeContour:Boolean=false)=
        ArtEncloseFill.surrounded(ByteArray(81) {255.toByte()},barrier,9,9,0,includeSurrounding,includeContour).first
    private fun at(mask:ByteArray,x:Int,y:Int)=mask[y*9+x].toInt() and 255

    @Test fun enclosingRingSeparatesSameColoredInteriorAndExterior() {
        val result=select(ring())
        assertEquals(255,at(result,4,4))
        assertEquals(0,at(result,0,0))
        assertEquals(0,at(result,4,2))
        assertEquals(9,result.count {it.toInt()!=0})
    }
    @Test fun enclosingColorCanBeIncludedWithoutSelectingTheExterior() {
        val result=select(ring(),includeSurrounding=true)
        assertEquals(255,at(result,4,2))
        assertEquals(255,at(result,4,4))
        assertEquals(0,at(result,0,0))
        assertEquals(25,result.count {it.toInt()!=0})
    }
    @Test fun openingInRingConnectsInteriorToExterior() {
        val barrier=ring();barrier[2*9+4]=0
        assertTrue(select(barrier).all {it.toInt()==0})
    }
    @Test fun noBoundaryColorDoesNotSelectTheEntireEnclosingArea() {
        assertTrue(select(ByteArray(81),includeContour=true).all {it.toInt()==0})
    }
    @Test fun partialBoundaryCoverageIsComplementedWhenExcluded() {
        val barrier=ring();barrier[2*9+4]=64
        val result=select(barrier)
        assertEquals(191,at(result,4,2))
        assertEquals(255,at(result,4,4))
        assertEquals(0,at(result,4,0))
    }
    @Test fun contourRegionRequiresExplicitInclusion() {
        val barrier=ByteArray(81) {255.toByte()}
        assertTrue(select(barrier,includeSurrounding=true).all {it.toInt()==0})
        assertTrue(select(barrier,includeSurrounding=true,includeContour=true).all {(it.toInt() and 255)==255})
    }
    @Test fun surroundingBackgroundAtContourDoesNotDiscardEnclosedIsland() {
        val barrier=ByteArray(81) {255.toByte()}
        for(y in 3..5)for(x in 3..5)barrier[y*9+x]=0
        val result=select(barrier,includeSurrounding=true)
        assertEquals(255,at(result,4,4))
        assertEquals(0,at(result,0,0))
        assertEquals(9,result.count {it.toInt()!=0})
    }
}
