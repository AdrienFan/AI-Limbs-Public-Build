package com.ai.assistance.operit.core.tools.system

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/** Generic packed RGBA frame transport. No resizing, image codec or visual policy lives here. */
internal object RawRgbaFrameWriter {
    fun write(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, file: File): Long {
        require(width in 1..8192 && height in 1..8192) { "Invalid raw frame dimensions" }
        require(pixelStride == 4) { "Raw frame requires RGBA8888 pixels" }
        val rowBytes = width * 4
        require(rowStride >= rowBytes) { "Raw frame row stride is too short" }
        val needed = (height - 1L) * rowStride + rowBytes
        require(needed <= buffer.remaining().toLong()) { "Raw frame plane is truncated" }
        val source = buffer.duplicate()
        val origin = source.position()
        val row = ByteArray(rowBytes)
        BufferedOutputStream(FileOutputStream(file), 65_536).use { output ->
            repeat(height) { y ->
                source.position(origin + y * rowStride)
                source.get(row)
                output.write(row)
            }
        }
        return width.toLong() * height * 4L
    }
}
