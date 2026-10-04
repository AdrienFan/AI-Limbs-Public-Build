package com.ai.limbs.plugins.artstudio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ArtBmpWriterTest {
    @Test fun bmpHasDeclaredDimensionsAndBottomUpBgrRowsWithZeroPadding() {
        val output = ByteArrayOutputStream()
        val rows = arrayOf(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()),
            intArrayOf(0xFF0000FF.toInt(), 0xFFFFFFFF.toInt()))
        ArtBmpWriter.write(output, 2, 2) { y, row -> rows[y].copyInto(row) }
        val bytes = output.toByteArray()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("BM", String(bytes, 0, 2, Charsets.US_ASCII))
        assertEquals(70, bytes.size); assertEquals(bytes.size, header.getInt(2))
        assertEquals(54, header.getInt(10)); assertEquals(40, header.getInt(14))
        assertEquals(2, header.getInt(18)); assertEquals(2, header.getInt(22))
        assertEquals(24, header.getShort(28).toInt()); assertEquals(0, header.getInt(30))
        assertEquals(16, header.getInt(34))
        assertArrayEquals(byteArrayOf(-1, 0, 0, -1, -1, -1, 0, 0,
            0, 0, -1, 0, -1, 0, 0, 0), bytes.copyOfRange(54, bytes.size))
    }
    @Test fun invalidDimensionsAreRejectedBeforeWritingAHeader() {
        val output = ByteArrayOutputStream()
        try { ArtBmpWriter.write(output, 0, 2) { _, _ -> fail("No pixel reads") }; fail("Expected rejection") }
        catch (expected: IllegalArgumentException) { assertEquals(0, output.size()) }
    }
}
