package com.ai.limbs.plugins.artstudio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class ArtGifWriterTest {
    @Test fun independentGifDecoderReadsAllFramesAcrossLzwResetsAndTransparency() {
        val bytes=ByteArrayOutputStream()
        val writer=ArtGifWriter(bytes,240,4,true)
        writer.pixels(8) {_,row->for(i in row.indices)row[i]=when(i%4) {
            0->0;1->0xFFFF0000.toInt();2->0xFF0000FF.toInt();else->0xFFFFFFFF.toInt()
        }}
        writer.pixels(9) {_,row->row.fill(0xFF000000.toInt())}
        writer.finish()
        val input=ImageIO.createImageInputStream(ByteArrayInputStream(bytes.toByteArray()))
        val reader=ImageIO.getImageReadersByFormatName("gif").next()
        try {
            reader.input=input
            assertEquals(2,reader.getNumImages(true))
            val first=reader.read(0)
            assertEquals(240,first.width);assertEquals(4,first.height)
            assertEquals(0,first.getRGB(0,0) ushr 24)
            assertEquals(0xFFFF0000.toInt(),first.getRGB(1,0))
            assertEquals(0xFF0000FF.toInt(),first.getRGB(2,0))
            assertEquals(0xFFFFFFFF.toInt(),first.getRGB(3,0))
            assertEquals(0xFF000000.toInt(),reader.read(1).getRGB(239,3))
        } finally {reader.dispose();input.close()}
    }
    @Test fun centisecondQuantizationKeepsOneSecondAcrossSupportedFrameRates() {
        for(fps in 1..60) {
            assertEquals(100,(0 until fps).sumOf {ArtGifWriter.delay(it,fps)})
            assertTrue((0 until fps).all {ArtGifWriter.delay(it,fps)>=1})
        }
    }
}
