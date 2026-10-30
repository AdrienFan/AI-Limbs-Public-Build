package com.ai.limbs.plugins.artstudio

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ArtHdrModelsTest {
    private fun reference(name:String)=requireNotNull(javaClass.getResourceAsStream("/hdr/"+name)).use {it.readBytes()}
    @Test fun allFiniteHalfValuesRoundTripExactly() {
        for(bits in 0..65535)if(bits and 0x7c00!=0x7c00) {
            val value=bits.toShort();assertEquals(bits,ArtHalf.half(ArtHalf.float(value)).toInt() and 65535)
        }
        assertEquals(0,ArtHalf.half(Math.scalb(1f,-25)).toInt())
        assertEquals(2,ArtHalf.half(Math.scalb(3f,-25)).toInt())
    }
    @Test fun officialOpenExrFloatZipFixtureKeepsHDRNegativeAndPremultipliedAlpha() {
        val image=ArtOpenExr.decode(reference("reference-float-zip.exr"),1000)
        assertEquals(23,image.header.width);assertEquals(19,image.header.height)
        for(y in 0 until 19)for(x in 0 until 23) {
            val i=(y*23+x)*4
            assertEquals(4+x/8f,image.pixels[i],1e-6f)
            assertEquals(-.25f+y/16f,image.pixels[i+1],1e-6f)
            assertEquals(.001f*(1+x+y),image.pixels[i+2],1e-6f)
            assertEquals(if((x+y)%3==0).5f else 1f,image.pixels[i+3],0f)
        }
    }
    @Test fun chromaticitiesConvertDisplayP3ToLinearRec709WithoutClipping() {
        val image=ArtOpenExr.decode(reference("reference-p3-zips.exr"),1)
        assertEquals(4.67482f,image.pixels[0],.0001f)
        assertEquals(.87383f,image.pixels[1],.0001f)
        assertTrue(image.pixels[2]<.5f);assertEquals(1f,image.pixels[3],0f)
    }
    @Test fun halfZipOutputPreservesSignedAndEmissiveSamplesAndWritesInteroperabilityArtifact() {
        val pixels=FloatArray(5*19*4) {i->when(i%4) {0->4f+i/4*.125f;1->-.25f;2->.03125f;else->if(i/4%3==0)0f else .5f}}
        val bytes=ArtOpenExr.encode(5,19,pixels,1024*1024)
        val restored=ArtOpenExr.decode(bytes,100)
        assertArrayEquals(pixels,restored.pixels,.0001f)
        val directory=File(System.getProperty("artstudio.hdr.output","build/hdr-tests")).apply {check(mkdirs() || isDirectory)}
        File(directory,"from-kotlin.exr").writeBytes(bytes)
    }
    @Test fun explicitReducedImportAllocatesOnlyRequestedResolution() {
        val image=ArtOpenExr.decode(reference("reference-float-zip.exr"),12,4,3)
        assertEquals(4*3*4,image.pixels.size)
        assertTrue(image.pixels[0]>4f)
    }
    @Test fun oversizedInvalidAndUnsupportedExrInputsAreRejected() {
        val bytes=reference("reference-float-zip.exr")
        assertThrows(IllegalArgumentException::class.java) {ArtOpenExr.decode(bytes,1)}
        val badVersion=bytes.copyOf();ByteBuffer.wrap(badVersion).order(ByteOrder.LITTLE_ENDIAN).putInt(4,514)
        assertThrows(IllegalArgumentException::class.java) {ArtOpenExr.header(badVersion)}
        assertThrows(IllegalArgumentException::class.java) {ArtOpenExr.decode(bytes.copyOf(bytes.size-1),1000)}
        assertThrows(IllegalArgumentException::class.java) {ArtOpenExr.encode(1,1,floatArrayOf(Float.NaN,0f,0f,1f),10000)}
        assertThrows(IllegalArgumentException::class.java) {ArtOpenExr.encode(1,1,floatArrayOf(2f,0f,0f,1f),10)}
    }
    @Test fun floatPatchMatchDoesNotQuantizeHighlightsAndLeavesDonorsUntouched() {
        val w=15;val h=13;val hole=BooleanArray(w*h)
        for(y in 5..7)for(x in 6..8)hole[y*w+x]=true
        val input=FloatArray(w*h*4) {i->when(i%4) {0->8f;1->-.125f;2->2f;else->.5f}}
        for(i in hole.indices)if(hole[i])for(c in 0..3)input[i*4+c]=0f
        val original=input.copyOf()
        val result=ArtFloatPatchPyramid.repair(input,hole,w,h,1,16,20,1,1,9)
        assertArrayEquals(original,input,0f)
        for(i in hole.indices)for(c in 0..3)assertEquals(if(hole[i])floatArrayOf(8f,-.125f,2f,.5f)[c] else original[i*4+c],result.pixels[i*4+c],.00001f)
    }
    @Test fun magneticEdgesDetectTwoBrightHDRValuesAcrossTileBoundaries() {
        val w=129;val h=65
        val pixels=FloatArray(w*h*4) {i->if(i%4==3)1f else if(i/4%w<64)4f else 8f}
        for(model in listOf("sobel","log")) {
            val edges=ArtMagneticEdges(pixels,w,h,1,model)
            assertTrue(edges.at(63,32) {false}>0f)
            assertEquals(edges.at(63,32) {false},edges.at(64,32) {false},.0001f)
            assertEquals(0f,edges.at(128,64) {false},.0001f)
        }
    }
}
