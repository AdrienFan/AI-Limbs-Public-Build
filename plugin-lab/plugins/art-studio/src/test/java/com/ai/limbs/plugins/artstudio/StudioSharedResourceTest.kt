package com.ai.limbs.plugins.artstudio

import org.junit.Assert.*
import org.junit.Test

class StudioSharedResourceTest {
    @Test fun replacingARevisionKeepsTheBorrowedPixelsUntilBothFramesRetire() {
        var disposed = 0
        val pixels = StudioSharedResource("canvas") { disposed++ }
        val reused = pixels.retain()
        assertSame(pixels.value, reused.value)
        pixels.release()
        assertEquals(0, disposed)
        reused.release()
        assertEquals(1, disposed)
    }
    @Test fun discardedPendingFramesCannotDisposeDisplayedPixels() {
        var disposed = 0
        val displayed = StudioSharedResource("canvas") { disposed++ }
        val pending = displayed.retain()
        val nextRevision = displayed.retain()
        pending.release(); displayed.release()
        assertEquals(0, disposed)
        nextRevision.release()
        assertEquals(1, disposed)
        try { displayed.retain(); fail("Retained already recycled pixels") }
        catch (_: IllegalStateException) { assertEquals(1, disposed) }
    }
}
