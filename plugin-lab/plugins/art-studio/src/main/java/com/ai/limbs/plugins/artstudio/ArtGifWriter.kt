package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import java.io.OutputStream
import kotlin.math.roundToInt

/** Shared learned palette, dictionary LZW and explicit GIF89a canvas disposal semantics. */
internal class ArtGifWriter(private val out: OutputStream, private val width: Int, private val height: Int,
    loop: Boolean, private val palette: ArtGifPalette, private val transparent: Boolean) {
    private var pending: ByteArray? = null
    private var previous: ByteArray? = null
    private var pendingDelay = 0L
    private var finished = false
    var encodedFrames = 0
        private set
    private fun byte(n: Int) = out.write(n and 255)
    private fun word(n: Int) { byte(n); byte(n shr 8) }
    init {
        require(width in 1..65535 && height in 1..65535 && width.toLong() * height <= 1024L * 1024)
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        word(width); word(height); byte(0xF7); byte(0); byte(0)
        byte(0); byte(0); byte(0)
        for (index in 1..255) {
            val color = if (index <= palette.colors.size) palette.colors[index - 1] else 0
            byte(color shr 16); byte(color shr 8); byte(color)
        }
        if (loop) {
            byte(0x21); byte(0xFF); byte(11); out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            byte(3); byte(1); word(0); byte(0)
        }
    }
    fun frame(bitmap: Bitmap, delayCentiseconds: Int) {
        require(bitmap.width == width && bitmap.height == height)
        pixels(delayCentiseconds) { y, row -> bitmap.getPixels(row, 0, width, 0, y, width, 1) }
    }
    fun pixels(delayCentiseconds: Int, readRow: (Int, IntArray) -> Unit) {
        check(!finished)
        require(delayCentiseconds in 1..65535)
        val indexed = ByteArray(width * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            readRow(y, row)
            for (x in row.indices) {
                val index = palette.index(row[x])
                require(transparent || index != 0) { "不透明动画包含未声明的透明像素" }
                indexed[y * width + x] = index.toByte()
            }
        }
        if (pending?.contentEquals(indexed) == true) pendingDelay += delayCentiseconds
        else { flushPending(); pending = indexed; pendingDelay = delayCentiseconds.toLong() }
    }
    private fun flushPending() {
        val frame = pending ?: return
        while (pendingDelay > 0) {
            val delay = minOf(pendingDelay, 65535L).toInt()
            writeFrame(frame, delay)
            pendingDelay -= delay
            previous = frame
        }
        pending = null
    }
    private fun writeFrame(frame: ByteArray, delay: Int) {
        var left = 0; var top = 0; var right = width - 1; var bottom = height - 1
        val old = previous
        // Transparent frames clear the full canvas; opaque frames retain it and update a rectangle.
        if (!transparent && old != null) {
            left = width; top = height; right = -1; bottom = -1
            for (index in frame.indices) if (frame[index] != old[index]) {
                val x = index % width; val y = index / width
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
            if (right < 0) { left = 0; top = 0; right = 0; bottom = 0 } // Long holds require a valid image block.
        }
        byte(0x21); byte(0xF9); byte(4); byte(if (transparent) 9 else 4)
        word(delay); byte(0); byte(0)
        byte(0x2C); word(left); word(top); word(right - left + 1); word(bottom - top + 1); byte(0)
        byte(8)
        val block = ByteArray(255); var used = 0; var bits = 0; var bitCount = 0
        fun flush() { if (used > 0) { byte(used); out.write(block, 0, used); used = 0 } }
        var codeSize = 9; var decoderNext = 258; var hasPrevious = false
        fun emit(code: Int) {
            bits = bits or (code shl bitCount); bitCount += codeSize
            while (bitCount >= 8) {
                block[used++] = (bits and 255).toByte()
                if (used == 255) flush()
                bits = bits ushr 8; bitCount -= 8
            }
            // Decoder's first dictionary allocation follows its second data code.
            if (code == 256) { codeSize = 9; decoderNext = 258; hasPrevious = false }
            else if (code != 257) {
                if (hasPrevious && decoderNext < 4096) {
                    decoderNext++
                    if (decoderNext == (1 shl codeSize) && codeSize < 12) codeSize++
                }
                hasPrevious = true
            }
        }
        val dictionary = HashMap<Int, Int>(4096)
        var next = 258; var prefix = -1
        emit(256)
        for (y in top..bottom) for (x in left..right) {
            val value = frame[y * width + x].toInt() and 255
            if (prefix < 0) { prefix = value; continue }
            val key = (prefix shl 8) or value
            val known = dictionary[key]
            if (known != null) prefix = known
            else {
                emit(prefix)
                if (next < 4096) dictionary[key] = next++
                else { emit(256); dictionary.clear(); next = 258 }
                prefix = value
            }
        }
        emit(prefix); emit(257)
        if (bitCount > 0) { block[used++] = (bits and 255).toByte(); if (used == 255) flush() }
        flush(); byte(0)
        encodedFrames++
    }
    fun finish() {
        check(!finished && pending != null) { "GIF没有待写入画面或已完成" }
        flushPending(); byte(0x3B); finished = true
    }
    companion object {
        fun delay(index: Int, fps: Int): Int = spanDelay(index, 1, fps)
        fun spanDelay(index: Int, frames: Int, fps: Int): Int {
            require(index >= 0 && frames > 0 && fps in 1..60)
            return (((index.toLong() + frames) * 100.0 / fps).roundToInt() - (index * 100.0 / fps).roundToInt()).coerceAtLeast(1)
        }
    }
}
