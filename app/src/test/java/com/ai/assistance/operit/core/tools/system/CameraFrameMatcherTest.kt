package com.ai.assistance.operit.core.tools.system

import org.junit.Assert.*
import org.junit.Test

class CameraFrameMatcherTest {
    @Test fun startedFirstRejectsPreviousCaptureAndMatchesOnlyThisRequest() {
        val matcher = CameraFrameMatcher()
        assertNull(matcher.expect(200L))
        assertNull(matcher.offer(100L, byteArrayOf(1)))
        assertArrayEquals(byteArrayOf(2), matcher.offer(200L, byteArrayOf(2)))
        assertEquals(200L, matcher.timestampNs)
    }
    @Test fun imageFirstMatchesOwnTimestampRegardlessOfArrivalOrder() {
        val matcher = CameraFrameMatcher()
        assertNull(matcher.offer(200L, byteArrayOf(2)))
        assertNull(matcher.offer(100L, byteArrayOf(1)))
        assertArrayEquals(byteArrayOf(2), matcher.expect(200L))
    }
    @Test fun queuedOldFrameCannotSatisfyNewCapture() {
        val matcher = CameraFrameMatcher()
        matcher.offer(100L, byteArrayOf(1))
        assertNull(matcher.expect(200L))
        assertNull(matcher.offer(100L, byteArrayOf(1)))
    }
}
