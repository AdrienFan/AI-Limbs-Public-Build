package com.ai.limbs.plugins.artstudio

import java.io.OutputStream

/** 24-bit BI_RGB with bottom-up, four-byte-aligned BGR rows. The caller composites alpha. */
internal object ArtBmpWriter {
    fun write(out: OutputStream, width: Int, height: Int, readRow: (Int, IntArray) -> Unit) {
        require(width in 1..16384 && height in 1..16384) { "BMP尺寸须为1–16384像素" }
        val stride = (width * 3 + 3) and -4
        val size = stride.toLong() * height
        require(size + 54 <= Int.MAX_VALUE) { "BMP文件超过大小限制" }
        fun word(value: Int) { out.write(value and 255); out.write((value ushr 8) and 255) }
        fun dword(value: Int) { word(value); word(value ushr 16) }
        out.write('B'.code); out.write('M'.code); dword((size + 54).toInt())
        dword(0); dword(54); dword(40); dword(width); dword(height)
        word(1); word(24); dword(0); dword(size.toInt()); dword(0); dword(0); dword(0); dword(0)
        val pixels = IntArray(width); val row = ByteArray(stride)
        for (y in height - 1 downTo 0) {
            readRow(y, pixels)
            pixels.forEachIndexed { x, pixel ->
                row[x * 3] = pixel.toByte(); row[x * 3 + 1] = (pixel ushr 8).toByte(); row[x * 3 + 2] = (pixel ushr 16).toByte()
            }
            out.write(row)
        }
    }
}
