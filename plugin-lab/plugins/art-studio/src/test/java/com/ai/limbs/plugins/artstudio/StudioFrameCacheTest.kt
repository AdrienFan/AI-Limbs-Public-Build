package com.ai.limbs.plugins.artstudio

import org.junit.Assert.*
import org.junit.Test

class StudioFrameCacheTest {
    private class Frame(val name: String)

    @Test fun completedCanvasSurvivesLeavingAndReenteringThePage() {
        val disposed = mutableListOf<String>()
        val cache = StudioFrameCache<Frame> { disposed.add(it.name) }
        val frame = Frame("canvas")
        val page = requireNotNull(cache.replace(frame))
        page.close()
        assertTrue(disposed.isEmpty())
        val returned = requireNotNull(cache.acquire())
        assertSame(frame, returned.frame)
        returned.close()
        assertTrue(disposed.isEmpty())
        cache.close()
        assertEquals(listOf("canvas"), disposed)
    }

    @Test fun overlappingFullscreenPagesCannotRecycleEachOthersPixels() {
        val disposed = mutableListOf<String>()
        val cache = StudioFrameCache<Frame> { disposed.add(it.name) }
        val old = Frame("old")
        val normal = requireNotNull(cache.replace(old))
        val fullscreen = requireNotNull(cache.acquire())
        val next = requireNotNull(cache.replace(Frame("next")))
        assertTrue(disposed.isEmpty())
        normal.close()
        assertTrue(disposed.isEmpty())
        fullscreen.close()
        assertEquals(listOf("old"), disposed)
        val reentered = requireNotNull(cache.acquire())
        assertSame(next.frame, reentered.frame)
        next.close()
        reentered.close()
        cache.close()
        assertEquals(listOf("old", "next"), disposed)
    }

    @Test fun explicitDocumentCloseRemovesTheRetainedCanvas() {
        val disposed = mutableListOf<String>()
        val cache = StudioFrameCache<Frame> { disposed.add(it.name) }
        val page = requireNotNull(cache.replace(Frame("closed")))
        assertNull(cache.replace(null))
        assertNull(cache.acquire())
        assertTrue(disposed.isEmpty())
        page.close()
        assertEquals(listOf("closed"), disposed)
        cache.close()
        assertEquals(listOf("closed"), disposed)
    }

    @Test fun providerRetirementWaitsForTheLastViewBorrow() {
        val disposed = mutableListOf<String>()
        val cache = StudioFrameCache<Frame> { disposed.add(it.name) }
        val page = requireNotNull(cache.replace(Frame("retired")))
        cache.close()
        assertTrue(disposed.isEmpty())
        try {
            cache.acquire()
            fail("A retired Provider accepted a new page")
        } catch (error: IllegalStateException) {
            assertEquals("画室页面 Provider 已停用", error.message)
        } finally {
            page.close()
        }
        assertEquals(listOf("retired"), disposed)
    }

    @Test fun repeatedLeaseAndProviderCloseRecycleExactlyOnce() {
        val disposed = mutableListOf<String>()
        val cache = StudioFrameCache<Frame> { disposed.add(it.name) }
        val page = requireNotNull(cache.replace(Frame("once")))
        page.close()
        page.close()
        cache.close()
        cache.close()
        assertEquals(listOf("once"), disposed)
    }

    @Test fun republishingTheSameFrameDoesNotCreateASecondDisposalOwner() {
        val disposed = mutableListOf<String>()
        val cache = StudioFrameCache<Frame> { disposed.add(it.name) }
        val frame = Frame("shared")
        val first = requireNotNull(cache.replace(frame))
        val second = requireNotNull(cache.replace(frame))
        first.close()
        assertTrue(disposed.isEmpty())
        cache.close()
        assertTrue(disposed.isEmpty())
        second.close()
        assertEquals(listOf("shared"), disposed)
    }
}
