package com.ai.assistance.operit.plugins.center.isolation

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginRuntimeRequestGateTest {
    @Test
    fun waitingCapabilityCanReceivePageReceiptAndSnapshots() = withClients { clients ->
        val gate = PluginRuntimeRequestGate()
        val capabilityEntered = CountDownLatch(1)
        val receipt = CountDownLatch(1)
        val capability = clients.submit(Callable {
            gate.dispatch("invoke_capability", { false }) {
                capabilityEntered.countDown()
                await(receipt)
                "applied"
            }
        })
        await(capabilityEntered)
        try {
            // Reproduce the Core's ensureMounted -> status -> snapshot -> UI event sequence.
            assertEquals("running", clients.submit(Callable {
                gate.dispatch("status", { false }) { "running" }
            }).get(5, TimeUnit.SECONDS))
            for (operation in listOf("snapshot_plugin", "child_snapshot")) {
                assertEquals("snapshot", clients.submit(Callable {
                    gate.dispatch(operation, { false }) { "snapshot" }
                }).get(5, TimeUnit.SECONDS))
            }
            assertEquals("receipt", clients.submit(Callable {
                gate.dispatch("provider_ui_event", { false }) {
                    receipt.countDown()
                    "receipt"
                }
            }).get(5, TimeUnit.SECONDS))
            assertEquals("applied", capability.get(5, TimeUnit.SECONDS))
        } finally {
            receipt.countDown()
        }
    }

    @Test
    fun queuedRetirementDoesNotBlockReceiptForActiveBusiness() = withClients { clients ->
        val gate = PluginRuntimeRequestGate()
        val entered = CountDownLatch(1)
        val receipt = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val command = clients.submit(Callable {
            gate.dispatch("invoke_capability", { false }) {
                entered.countDown()
                await(receipt)
                completed.set(true)
            }
        })
        await(entered)
        try {
            val retirementThread = AtomicReference<Thread>()
            val submitted = CountDownLatch(1)
            val retirement = clients.submit(Callable {
                retirementThread.set(Thread.currentThread())
                submitted.countDown()
                gate.dispatch("stop_plugin", { false }) {
                    assertTrue("Retirement must wait for active business", completed.get())
                    "stopped"
                }
            })
            await(submitted)
            awaitParked(retirementThread.get())
            clients.submit(Callable {
                gate.dispatch("child_ui_event", { false }) { receipt.countDown() }
            }).get(5, TimeUnit.SECONDS)
            command.get(5, TimeUnit.SECONDS)
            assertEquals("stopped", retirement.get(5, TimeUnit.SECONDS))
        } finally {
            receipt.countDown()
        }
    }

    @Test
    fun businessRequestsRemainSerial() = withClients { clients ->
        val gate = PluginRuntimeRequestGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = AtomicBoolean(false)
        val first = clients.submit(Callable {
            gate.dispatch("invoke_capability", { false }) {
                entered.countDown()
                await(release)
            }
        })
        await(entered)
        try {
            val thread = AtomicReference<Thread>()
            val submitted = CountDownLatch(1)
            val second = clients.submit(Callable {
                thread.set(Thread.currentThread())
                submitted.countDown()
                gate.dispatch("service_invoke", { false }) { secondEntered.set(true) }
            })
            await(submitted)
            awaitParked(thread.get())
            assertFalse(secondEntered.get())
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertTrue(secondEntered.get())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun lifecycleExcludesProviderAndSnapshotsButNotHealth() = withClients { clients ->
        val gate = PluginRuntimeRequestGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lifecycle = clients.submit(Callable {
            gate.dispatch("mount", { false }) {
                entered.countDown()
                await(release)
            }
        })
        await(entered)
        try {
            val blocked = listOf(
                "provider_ui_event", "child_ui_event", "snapshot_plugin", "child_snapshot"
            ).map { operation ->
                val thread = AtomicReference<Thread>()
                val submitted = CountDownLatch(1)
                val request = clients.submit(Callable {
                    thread.set(Thread.currentThread())
                    submitted.countDown()
                    gate.dispatch(operation, { false }) { operation }
                })
                await(submitted)
                awaitParked(thread.get())
                request
            }
            assertEquals("healthy", clients.submit(Callable {
                gate.dispatch("ping", { false }) { "healthy" }
            }).get(5, TimeUnit.SECONDS))
            release.countDown()
            lifecycle.get(5, TimeUnit.SECONDS)
            blocked.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun queuedRequestsRecheckStoppingInsideTheirLocks() = withClients { clients ->
        val gate = PluginRuntimeRequestGate()
        val stopping = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stop = clients.submit(Callable {
            gate.dispatch("stop", stopping::get) {
                entered.countDown()
                await(release)
                stopping.set(true)
            }
        })
        await(entered)
        try {
            val queued = listOf("provider_ui_event", "invoke_capability").map { operation ->
                val thread = AtomicReference<Thread>()
                val submitted = CountDownLatch(1)
                val request = clients.submit(Callable {
                    thread.set(Thread.currentThread())
                    submitted.countDown()
                    gate.dispatch(operation, stopping::get) { fail("Retired worker was accessed") }
                })
                await(submitted)
                awaitParked(thread.get())
                request
            }
            release.countDown()
            stop.get(5, TimeUnit.SECONDS)
            queued.forEach(::assertStopping)
            assertStopping(clients.submit(Callable {
                gate.dispatch("status", stopping::get) { fail("Retired identity reported running") }
            }))
        } finally {
            release.countDown()
        }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("Timed out waiting for test participant", latch.await(5, TimeUnit.SECONDS))
    }

    // Confirm the competing request actually reached a gate lock; do not rely on sleeps or
    // a merely submitted task when checking that a queued lifecycle writer cannot block receipts.
    private fun awaitParked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(Thread.State.WAITING, thread.state)
    }

    private fun assertStopping(request: Future<*>) {
        try {
            request.get(5, TimeUnit.SECONDS)
            fail("Request to a retired worker succeeded")
        } catch (error: ExecutionException) {
            assertTrue(error.cause is IllegalStateException)
            assertEquals("Plugin runtime is stopping", error.cause?.message)
        }
    }

    private fun <T> withClients(action: (ExecutorService) -> T): T {
        val clients = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "runtime-gate-test").apply { isDaemon = true }
        }
        return try {
            action(clients)
        } finally {
            clients.shutdownNow()
            assertTrue("Test clients did not retire", clients.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
