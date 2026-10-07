package com.ai.limbs.plugins.visualmanager

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RawScreenFrameTest {
    private fun frame() = JSONObject().put("format", "rgba8888").put("width", 2).put("height", 2)
        .put("pixel_stride", 4).put("row_stride", 8).put("byte_count", 16L)
    @Test fun validatesPackedRepresentationAndExactByteCount() {
        val file = Files.createTempFile("frame", ".rgba").toFile()
        try {
            file.writeBytes(ByteArray(16))
            assertEquals(RawScreenFrame(2, 2), RawScreenFrame.read(frame(), file))
            assertThrows(IllegalArgumentException::class.java) { RawScreenFrame.read(frame().put("row_stride", 12), file) }
            assertThrows(IllegalArgumentException::class.java) { RawScreenFrame.read(frame().put("byte_count", 15L), file) }
            assertThrows(IllegalArgumentException::class.java) { RawScreenFrame.read(frame().put("format", "png"), file) }
            file.writeBytes(ByteArray(15))
            assertThrows(IllegalArgumentException::class.java) { RawScreenFrame.read(frame(), file) }
        } finally { file.delete() }
    }
    @Test fun rejectsOversizedDimensionsWithoutAllocatingPixels() {
        val file = Files.createTempFile("frame", ".rgba").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) { RawScreenFrame.read(frame().put("width", 8192).put("height", 8192), file) }
        } finally { file.delete() }
    }
}
