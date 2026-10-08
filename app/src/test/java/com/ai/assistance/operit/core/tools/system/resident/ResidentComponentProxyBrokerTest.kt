package com.ai.assistance.operit.core.tools.system.resident

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResidentComponentProxyBrokerTest {
    private fun broker() = ResidentComponentProxyBroker { System.nanoTime() / 1_000_000L }

    @Test fun requestWakesAnIdleHostAndCompletesExactlyOnce() {
        val b = broker()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val entered = CountDownLatch(1)
            val poll = workers.submit<org.json.JSONArray> {
                b.poll("host", waitMs = 2000) { entered.countDown(); true }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val result = workers.submit<JSONObject> {
                b.request(ResidentComponentProxyBroker.KIND_UI_AUTOMATION_PRESENTATION, JSONObject(), 2000)
            }
            val requests = poll.get(1, TimeUnit.SECONDS)
            assertEquals(1, requests.length())
            val r = requests.getJSONObject(0)
            assertTrue(r.getLong("claimed_elapsed_ms") >= r.getLong("created_elapsed_ms"))
            val id = r.getString("request_id")
            assertTrue(b.complete("host", id, JSONObject().put("ok", true)))
            assertFalse(b.complete("host", id, JSONObject().put("ok", false)))
            assertTrue(result.get(1, TimeUnit.SECONDS).getBoolean("ok"))
            assertEquals(0, b.poll("host").length())
        } finally { workers.shutdownNow() }
    }

    @Test fun replacedHostCannotClaimRequestsAfterTheIdleWait() {
        val b = broker()
        val workers = Executors.newSingleThreadExecutor()
        val current = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        try {
            val poll = workers.submit<org.json.JSONArray> {
                b.poll("old", waitMs = 2000) { entered.countDown(); current.get() }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            current.set(false)
            b.cancelClaims("old", "HOST_INSTANCE_REPLACED")
            try { poll.get(1, TimeUnit.SECONDS); fail("Retired Host must be rejected") }
            catch (error: java.util.concurrent.ExecutionException) {
                assertTrue(error.cause is IllegalStateException)
                assertTrue(error.cause!!.message!!.contains("Stale"))
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun cancellationWakesIdlePollingWithoutDeliveringACancelledRequest() {
        val b = broker()
        val workers = Executors.newSingleThreadExecutor()
        try {
            val result = workers.submit<JSONObject> {
                b.request(ResidentComponentProxyBroker.KIND_UI_AUTOMATION_PRESENTATION, JSONObject(), 2000)
            }
            val first = b.poll("host", waitMs = 2000)
            assertEquals(1, first.length())
            b.cancelAll("STOPPED")
            assertFalse(b.complete("host", first.getJSONObject(0).getString("request_id"), JSONObject().put("ok", true)))
            assertEquals("STOPPED", result.get(1, TimeUnit.SECONDS).getString("error"))
            assertEquals(0, b.poll("host").length())
        } finally { workers.shutdownNow() }
    }
}
