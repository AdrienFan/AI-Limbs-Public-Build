package com.ai.limbs.plugins.artstudio

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class ArtInputStreamTest {
    @Test fun boundDoesNotConsumeTheNextArchiveEntryByte() {
        val stream=ByteArrayInputStream(byteArrayOf(1,2,3,4))
        assertArrayEquals(byteArrayOf(1,2,3),stream.artReadUpTo(3));assertEquals(4,stream.read())
    }
    @Test fun shortReadsAndEofKeepExactBytes() {
        val source=ByteArrayInputStream(ByteArray(9000) {it.toByte()})
        val short=object:InputStream() {
            override fun read()=source.read()
            override fun read(b:ByteArray,off:Int,len:Int)=source.read(b,off,minOf(3,len))
        }
        assertArrayEquals(ByteArray(9000) {it.toByte()},short.artReadUpTo(10000))
    }
    @Test fun zeroLimitLeavesInputUntouched() {
        val stream=ByteArrayInputStream(byteArrayOf(7))
        assertEquals(0,stream.artReadUpTo(0).size);assertEquals(7,stream.read())
    }
}
