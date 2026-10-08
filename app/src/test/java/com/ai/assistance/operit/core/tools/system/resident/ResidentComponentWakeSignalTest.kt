package com.ai.assistance.operit.core.tools.system.resident

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ResidentComponentWakeSignalTest {
    @Test fun requestBetweenEmptyCheckAndWaitIsNotLost() {
        val signal = ResidentComponentWakeSignal()
        val version = signal.version()
        signal.signal()
        val returned = CountDownLatch(1)
        val worker = Thread { signal.awaitChange(version, 2000); returned.countDown() }
        worker.start()
        try { assertTrue("A previously signalled request must not wait for the idle timeout", returned.await(500, TimeUnit.MILLISECONDS)) }
        finally { worker.interrupt(); worker.join(1000) }
    }
    @Test fun idlePollWakesWhenARequestArrives() {
        val signal = ResidentComponentWakeSignal()
        val version = signal.version()
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val worker = Thread { entered.countDown(); signal.awaitChange(version, 2000); returned.countDown() }
        worker.start()
        try {
            assertTrue(entered.await(500, TimeUnit.MILLISECONDS))
            signal.signal()
            assertTrue(returned.await(500, TimeUnit.MILLISECONDS))
        } finally { worker.interrupt(); worker.join(1000) }
    }
    @Test fun idleWaitIsBoundedAndInterruptible() {
        val signal = ResidentComponentWakeSignal()
        signal.awaitChange(signal.version(), 1)
        val stopped = CountDownLatch(1)
        val version = signal.version()
        val worker = Thread {
            try { signal.awaitChange(version, 2000) } catch (_: InterruptedException) { stopped.countDown() }
        }
        worker.start(); worker.interrupt()
        assertTrue(stopped.await(500, TimeUnit.MILLISECONDS)); worker.join(1000)
    }
}
