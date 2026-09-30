package com.ai.assistance.operit.plugins.center

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class ResidentUiProxyPollingTest {
    @Test
    fun hostRetirementCompletesWhileSnapshotWaitsForLifecycleLock() = runBlocking {
        val snapshotWaiting = CompletableDeferred<Unit>()
        val hostResourcesReleased = CompletableDeferred<Unit>()
        val snapshotRefreshed = CompletableDeferred<Unit>()
        val loops = launchResidentUiProxyLoops(
            refreshPresentation = {
                // A version switch owns the Core lifecycle lock until Host resources are released.
                snapshotWaiting.complete(Unit)
                hostResourcesReleased.await()
                snapshotRefreshed.complete(Unit)
            },
            executeHostComponents = {
                snapshotWaiting.await()
                hostResourcesReleased.complete(Unit)
            }
        )
        try {
            withTimeout(3_000L) { snapshotRefreshed.await() }
            assertTrue(hostResourcesReleased.isCompleted)
        } finally {
            loops.cancelAndJoin()
        }
    }

    @Test
    fun retiringClientCancelsBothPollingLoops() = runBlocking {
        val presentationStarted = CompletableDeferred<Unit>()
        val componentStarted = CompletableDeferred<Unit>()
        val presentationStopped = CompletableDeferred<Unit>()
        val componentStopped = CompletableDeferred<Unit>()
        val loops = launchResidentUiProxyLoops(
            refreshPresentation = {
                presentationStarted.complete(Unit)
                try { awaitCancellation() } finally { presentationStopped.complete(Unit) }
            },
            executeHostComponents = {
                componentStarted.complete(Unit)
                try { awaitCancellation() } finally { componentStopped.complete(Unit) }
            }
        )
        try {
            withTimeout(3_000L) {
                presentationStarted.await()
                componentStarted.await()
            }
        } finally {
            loops.cancelAndJoin()
        }
        assertTrue(presentationStopped.isCompleted)
        assertTrue(componentStopped.isCompleted)
    }
}
