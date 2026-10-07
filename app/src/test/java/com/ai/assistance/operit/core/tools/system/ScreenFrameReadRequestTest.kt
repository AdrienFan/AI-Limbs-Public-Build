package com.ai.assistance.operit.core.tools.system

import org.junit.Assert.*
import org.junit.Test

class ScreenFrameReadRequestTest {
    @Test fun newerFrameWaitIsBoundToOneProducer() {
        assertEquals(8L, ScreenFrameReadRequest("latest", "source:8", 1000, 2000).afterSequence("source", 12))
        assertThrows(IllegalArgumentException::class.java) {
            ScreenFrameReadRequest("latest", "other:8", 1000, 2000).afterSequence("source", 12)
        }
        for (id in listOf("source:0", "source:13", "source:bad")) {
            assertThrows(IllegalArgumentException::class.java) {
                ScreenFrameReadRequest("latest", id, 1000, 2000).afterSequence("source", 12)
            }
        }
    }

    @Test fun newSurfaceCannotPretendToBeASequenceWait() {
        assertThrows(IllegalArgumentException::class.java) {
            ScreenFrameReadRequest("new_surface", "source:1", 1000, 2000)
        }
        assertEquals(-1L, ScreenFrameReadRequest("new_surface", null, 1000, 2000).afterSequence("source", 12))
    }

    @Test fun waitBoundsDoNotBecomeUnboundedOrNegative() {
        for (timeout in listOf(0L, -1L, 15001L)) {
            assertThrows(IllegalArgumentException::class.java) { ScreenFrameReadRequest("latest", null, 1000, timeout) }
        }
        for (age in listOf(-1L, 60001L)) {
            assertThrows(IllegalArgumentException::class.java) { ScreenFrameReadRequest("latest", null, age, 2000) }
        }
    }
}
