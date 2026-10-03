package com.ai.limbs.plugins.artstudio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ArtGifWriterTest {
    @Test fun independentGifDecoderReadsAllFramesAcrossLzwResetsAndTransparency() {
        val bytes=ByteArrayOutputStream()
        val palette = ArtGifPalette.Builder().apply { addRow(intArrayOf(0,0xFFFF0000.toInt(),0xFF0000FF.toInt(),0xFFFFFFFF.toInt(),0xFF000000.toInt())) }.build()
        val writer=ArtGifWriter(bytes,240,4,true,palette,true)
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
        assertEquals(listOf(8,9), descriptors(bytes.toByteArray()).map { it.delay })
    }
    @Test fun dictionaryGrowthAndClearsAreDecodedByIndependentJdkReader() {
        val colors = IntArray(255) { 0xFF000000.toInt() or (it shl 16) or ((254-it) shl 8) or ((it*37) and 255) }
        val palette = ArtGifPalette.Builder().apply { addRow(colors) }.build()
        val random = java.util.Random(52)
        val pixels = IntArray(200*100) { colors[random.nextInt(colors.size)] }
        val bytes = ByteArrayOutputStream()
        val writer = ArtGifWriter(bytes,200,100,false,palette,false)
        writer.pixels(10) { y,row -> pixels.copyInto(row,0,y*200,(y+1)*200) }
        writer.finish()
        assertArrayEquals(pixels, decodeWithJdkImageIo(bytes.toByteArray()).single().pixels)
    }
    @Test fun duplicateHoldsAreCoalescedAndFlatImageUsesRealCompression() {
        val color = 0xFF14263A.toInt()
        val palette = ArtGifPalette.Builder().apply { addRow(intArrayOf(color)) }.build()
        val bytes = ByteArrayOutputStream(); val writer = ArtGifWriter(bytes,128,128,true,palette,false)
        repeat(12) { writer.pixels(10) { _,row -> row.fill(color) } }
        writer.finish()
        assertEquals(1,writer.encodedFrames)
        assertEquals(120,descriptors(bytes.toByteArray()).single().delay)
        assertTrue(bytes.size() < 2000)
        assertTrue(decodeWithJdkImageIo(bytes.toByteArray()).single().pixels.all { it==color })
    }
    @Test fun opaqueDeltaRectangleErasesOldPositionAndPreservesAllOtherPixels() {
        val black=0xFF000000.toInt(); val red=0xFFFF0000.toInt()
        val palette=ArtGifPalette.Builder().apply { addRow(intArrayOf(black,red)) }.build()
        val bytes=ByteArrayOutputStream(); val writer=ArtGifWriter(bytes,8,4,false,palette,false)
        for(position in listOf(3,4))writer.pixels(10) { y,row -> row.fill(black);if(y==2)row[position]=red }
        writer.finish()
        val descriptions=descriptors(bytes.toByteArray()); val second=descriptions[1]
        assertEquals(3,second.left);assertEquals(2,second.top);assertEquals(2,second.width);assertEquals(1,second.height)
        assertEquals(1,second.disposal)
        val pixels=compose(bytes.toByteArray(),8,4)[1]
        assertEquals(black,pixels[2*8+3]);assertEquals(red,pixels[2*8+4])
        assertTrue(pixels.filterIndexed { index,_ -> index!=2*8+4 }.all {it==black})
    }
    @Test fun transparentFramesClearTheCanvasWithoutTrailsAndLongHoldsStayTimed() {
        val color=0xFF294367.toInt()
        val palette=ArtGifPalette.Builder().apply { addRow(intArrayOf(0,color)) }.build()
        val bytes=ByteArrayOutputStream();val writer=ArtGifWriter(bytes,8,4,false,palette,true)
        for(position in listOf(3,4))writer.pixels(10) {y,row->row.fill(0);if(y==2)row[position]=color}
        writer.finish()
        assertTrue(descriptors(bytes.toByteArray()).all {it.disposal==2 && it.width==8 && it.height==4})
        val pixels=compose(bytes.toByteArray(),8,4)[1]
        assertEquals(0,pixels[2*8+3]);assertEquals(color,pixels[2*8+4])
        val hold=ByteArrayOutputStream();val longWriter=ArtGifWriter(hold,1,1,false,palette,false)
        repeat(2){longWriter.pixels(60000){_,row->row[0]=color}}
        longWriter.finish()
        assertEquals(120000,descriptors(hold.toByteArray()).sumOf {it.delay})
        assertEquals(2,decodeWithJdkImageIo(hold.toByteArray()).size)
    }
    private data class Descriptor(val left:Int,val top:Int,val width:Int,val height:Int,val delay:Int,val disposal:Int)
    private fun descriptors(bytes:ByteArray):List<Descriptor> {
        fun value(index:Int)=bytes[index].toInt() and 255
        fun word(index:Int)=value(index) or (value(index+1) shl 8)
        var cursor=13
        if((value(10) and 128) != 0)cursor+=3*(1 shl ((value(10) and 7)+1))
        var delay=0;var disposal=0
        val frames=mutableListOf<Descriptor>()
        fun blocks(){while(true){val size=value(cursor++);if(size==0)return;cursor+=size}}
        while(true)when(value(cursor++)) {
            0x3B->return frames
            0x21->{val label=value(cursor++);if(label==0xF9){assertEquals(4,value(cursor++));disposal=(value(cursor) ushr 2) and 7;delay=word(cursor+1);cursor+=4;assertEquals(0,value(cursor++))}else blocks()}
            0x2C->{val frame=Descriptor(word(cursor),word(cursor+2),word(cursor+4),word(cursor+6),delay,disposal);val packed=value(cursor+8);cursor+=9;if((packed and 128)!=0)cursor+=3*(1 shl ((packed and 7)+1));cursor++;blocks();frames.add(frame)}
            else->error("Unexpected GIF block")
        }
    }
    private fun compose(bytes:ByteArray,width:Int,height:Int):List<IntArray> {
        val descriptions=descriptors(bytes);val images=decodeWithJdkImageIo(bytes)
        val canvas=IntArray(width*height);val result=mutableListOf<IntArray>()
        for(index in images.indices){
            if(index>0 && descriptions[index-1].disposal==2){val previous=descriptions[index-1];for(y in previous.top until previous.top+previous.height)for(x in previous.left until previous.left+previous.width)canvas[y*width+x]=0}
            val frame=descriptions[index];val image=images[index]
            assertEquals(frame.width,image.width);assertEquals(frame.height,image.height)
            for(y in 0 until frame.height)for(x in 0 until frame.width){val pixel=image.pixels[y*frame.width+x];if((pixel ushr 24)!=0)canvas[(frame.top+y)*width+frame.left+x]=pixel}
            result.add(canvas.copyOf())
        }
        return result
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
