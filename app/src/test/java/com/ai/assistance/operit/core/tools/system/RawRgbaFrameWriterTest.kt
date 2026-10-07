package com.ai.assistance.operit.core.tools.system

import java.nio.ByteBuffer
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RawRgbaFrameWriterTest {
    @Test fun packsRowsWithoutPaddingAndPreservesSourcePosition() {
        val directory = Files.createTempDirectory("raw-frame").toFile()
        try {
            val bytes = ByteArray(29) { it.toByte() }
            val buffer = ByteBuffer.wrap(bytes).apply { position(5) }
            val file = java.io.File(directory, "frame.rgba")
            assertEquals(16L, RawRgbaFrameWriter.write(buffer, 2, 2, 16, 4, file))
            assertArrayEquals(bytes.copyOfRange(5, 13) + bytes.copyOfRange(21, 29), file.readBytes())
            assertEquals(5, buffer.position())
        } finally { directory.deleteRecursively() }
    }
    @Test fun rejectsTruncatedPlaneAndInvalidPixelStrideBeforeWriting() {
        val directory = Files.createTempDirectory("raw-frame").toFile()
        try {
            val file = java.io.File(directory, "frame.rgba")
            assertThrows(IllegalArgumentException::class.java) { RawRgbaFrameWriter.write(ByteBuffer.allocate(23), 2, 2, 16, 4, file) }
            assertThrows(IllegalArgumentException::class.java) { RawRgbaFrameWriter.write(ByteBuffer.allocate(24), 2, 2, 16, 8, file) }
            assertThrows(IllegalArgumentException::class.java) { RawRgbaFrameWriter.write(ByteBuffer.allocate(24), 2, 2, 7, 4, file) }
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }
}
