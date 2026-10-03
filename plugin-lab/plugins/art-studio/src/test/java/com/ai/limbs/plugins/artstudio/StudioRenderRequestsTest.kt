package com.ai.limbs.plugins.artstudio

import org.junit.Assert.*
import org.junit.Test

class StudioRenderRequestsTest {
    @Test fun anExternalCommitDuringPixelCompositionRejectsOldPixelsAndAllowsOneFreshRender() {
        val requests=StudioRenderRequests();val old=requireNotNull(requests.beginRefresh())
        assertTrue(requests.canAccept(old,"document:before","document:before"))
        // The Resident commits while the Host renders without owning the document lock.
        assertFalse(requests.canAccept(old,"document:before","document:after"))
        assertNull(requests.beginRefresh())
        requests.finishRefresh(old)
        val fresh=requireNotNull(requests.beginRefresh())
        assertTrue(requests.canAccept(fresh,"document:after","document:after"))
        assertFalse(requests.canAccept(old,"document:after","document:after"))
    }

    @Test fun aCapturedEmptyDocumentCannotClearACanvasOpenedLater() {
        val requests=StudioRenderRequests();val empty=requireNotNull(requests.beginRefresh())
        assertTrue(requests.canAccept(empty,"",""))
        assertFalse(requests.canAccept(empty,"","new-document:revision"))
        assertFalse(requests.canAccept(empty,"old-document:revision",""))
        requests.invalidate()
        assertFalse(requests.canAccept(empty,"",""))
        requests.finishRefresh(empty)
    }

    @Test fun aSlowFrameSurvivesRepeatedPollsAndOnlyOneRenderIsQueued() {
        val requests=StudioRenderRequests()
        val frame=requireNotNull(requests.beginRefresh())
        // A two-second render spans five 400ms ticks; a longer queue must also stay bounded.
        repeat(100) { assertNull(requests.beginRefresh());assertTrue(requests.isCurrent(frame)) }
        requests.finishRefresh(frame)
        val next=requireNotNull(requests.beginRefresh())
        assertTrue(requests.isCurrent(next))
        assertFalse(requests.isCurrent(frame))
    }

    @Test fun anOpenOrEditCannotBeOverwrittenByAnEarlierRefresh() {
        val requests=StudioRenderRequests()
        val old=requireNotNull(requests.beginRefresh())
        val opened=requests.invalidate()
        assertFalse(requests.isCurrent(old))
        repeat(5) { assertNull(requests.beginRefresh());assertTrue(requests.isCurrent(opened)) }
        requests.finishRefresh(old)
        assertTrue(requests.isCurrent(opened))
        val latest=requireNotNull(requests.beginRefresh())
        assertTrue(requests.isCurrent(latest))
    }

    @Test fun aFailedOrCancelledRenderReleasesItsSlotWithoutAcceptingStalePixels() {
        val requests=StudioRenderRequests()
        val failed=requireNotNull(requests.beginRefresh())
        requests.invalidate()
        requests.finishRefresh(failed)
        val retry=requireNotNull(requests.beginRefresh())
        assertTrue(requests.isCurrent(retry))
        assertFalse(requests.isCurrent(failed))
    }
}
