package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import java.io.OutputStream
import kotlin.math.roundToInt

/** Streaming GIF89a, fixed 255-colour 3/3/2 palette and one transparent index.
 * Literal LZW runs reset before 9-bit dictionary growth; memory never scales with frame count. */
internal class ArtGifWriter(private val out:OutputStream,private val width:Int,private val height:Int,loop:Boolean) {
    private fun byte(n:Int)=out.write(n and 255)
    private fun word(n:Int) {byte(n);byte(n shr 8)}
    init {
        require(width in 1..65535 && height in 1..65535)
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        word(width);word(height);byte(0xF7);byte(0);byte(0)
        byte(0);byte(0);byte(0)
        for(index in 1..255) {
            val code=if(index==255)255 else index-1
            byte(((code shr 5) and 7)*255/7)
            byte(((code shr 2) and 7)*255/7)
            byte((code and 3)*255/3)
        }
        if(loop) {
            byte(0x21);byte(0xFF);byte(11);out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            byte(3);byte(1);word(0);byte(0)
        }
    }
    fun frame(bitmap:Bitmap,delayCentiseconds:Int) {
        require(bitmap.width==width && bitmap.height==height)
        pixels(delayCentiseconds) {y,row->bitmap.getPixels(row,0,width,0,y,width,1)}
    }
    fun pixels(delayCentiseconds:Int,readRow:(Int,IntArray)->Unit) {
        require(delayCentiseconds in 1..65535)
        byte(0x21);byte(0xF9);byte(4);byte(9) // Restore background + transparent index.
        word(delayCentiseconds);byte(0);byte(0)
        byte(0x2C);word(0);word(0);word(width);word(height);byte(0)
        byte(8)
        val block=ByteArray(255);var used=0;var bits=0;var bitCount=0
        fun flush() {if(used>0){byte(used);out.write(block,0,used);used=0}}
        fun packed(value:Int) {
            bits=bits or (value shl bitCount);bitCount+=9
            while(bitCount>=8) {
                block[used++]=(bits and 255).toByte()
                if(used==255)flush()
                bits=bits ushr 8;bitCount-=8
            }
        }
        val row=IntArray(width);var literals=0
        packed(256)
        for(y in 0 until height) {
            readRow(y,row)
            for(pixel in row) {
                if(literals==200){packed(256);literals=0}
                val code=if((pixel ushr 24)<128)0 else {
                    val palette=((pixel shr 16 and 255)*7/255 shl 5) or
                        ((pixel shr 8 and 255)*7/255 shl 2) or ((pixel and 255)*3/255)
                    (palette+1).coerceAtMost(255)
                }
                packed(code);literals++
            }
        }
        packed(257)
        if(bitCount>0) {
            block[used++]=(bits and 255).toByte()
            if(used==255)flush()
        }
        flush();byte(0)
    }
    fun finish() {byte(0x3B)}
    companion object {
        fun delay(index:Int,fps:Int):Int {
            require(index>=0 && fps in 1..60)
            return (((index+1)*100.0/fps).roundToInt()-(index*100.0/fps).roundToInt()).coerceAtLeast(1)
        }
    }
}
