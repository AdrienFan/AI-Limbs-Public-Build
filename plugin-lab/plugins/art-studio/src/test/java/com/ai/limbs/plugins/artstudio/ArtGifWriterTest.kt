package com.ai.limbs.plugins.artstudio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
        val frames=decodeWithJdkImageIo(bytes.toByteArray())
        assertEquals(2,frames.size)
        val first=frames[0]
        assertEquals(240,first.width);assertEquals(4,first.height)
        assertEquals(0,first.pixels[0] ushr 24)
        assertEquals(0xFFFF0000.toInt(),first.pixels[1])
        assertEquals(0xFF0000FF.toInt(),first.pixels[2])
        assertEquals(0xFFFFFFFF.toInt(),first.pixels[3])
        assertEquals(0xFF000000.toInt(),frames[1].pixels[239+3*240])
    }
    private data class DecodedFrame(val width:Int,val height:Int,val pixels:IntArray)

    // Android's test compiler uses Android APIs, which exclude java.desktop.
    // Resolve the cloud JDK's independent GIF decoder at test runtime; missing
    // ImageIO still fails the test. No desktop classes enter the plugin payload.
    private fun decodeWithJdkImageIo(bytes:ByteArray):List<DecodedFrame> {
        val io=Class.forName("javax.imageio.ImageIO")
        val readerType=Class.forName("javax.imageio.ImageReader")
        val streamType=Class.forName("javax.imageio.stream.ImageInputStream")
        val imageType=Class.forName("java.awt.image.BufferedImage")
        val input=io.getMethod("createImageInputStream",Any::class.java)
            .invoke(null,ByteArrayInputStream(bytes))!!
        val readers=io.getMethod("getImageReadersByFormatName",String::class.java)
            .invoke(null,"gif") as Iterator<*>
        val reader=readers.next()!!
        try {
            readerType.getMethod("setInput",Any::class.java).invoke(reader,input)
            val count=readerType.getMethod("getNumImages",Boolean::class.javaPrimitiveType!!)
                .invoke(reader,true) as Int
            val intType=Int::class.javaPrimitiveType!!
            val rgb=imageType.getMethod("getRGB",intType,intType,intType,intType,
                IntArray::class.java,intType,intType)
            return (0 until count).map {index->
                val image=readerType.getMethod("read",intType).invoke(reader,index)
                val width=imageType.getMethod("getWidth").invoke(image) as Int
                val height=imageType.getMethod("getHeight").invoke(image) as Int
                DecodedFrame(width,height,rgb.invoke(image,0,0,width,height,null,0,width) as IntArray)
            }
        } finally {
            try {readerType.getMethod("dispose").invoke(reader)}
            finally {streamType.getMethod("close").invoke(input)}
        }
    }
    @Test fun centisecondQuantizationKeepsOneSecondAcrossSupportedFrameRates() {
        for(fps in 1..60) {
            assertEquals(100,(0 until fps).sumOf {ArtGifWriter.delay(it,fps)})
            assertTrue((0 until fps).all {ArtGifWriter.delay(it,fps)>=1})
        }
    }
}
