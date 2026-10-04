package com.ai.limbs.extensions.sentinelx.runtime

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentinelXBackgroundJobTest {
    @Test
    fun successBuildsSucceededJobEvent() {
        val started = Instant.parse("2026-10-04T00:00:00Z")
        val finished = Instant.parse("2026-10-04T00:00:01.250Z")
        val response = JSONObject()
            .put("ok", true)
            .put(
                "result",
                JSONObject()
                    .put("returncode", 0)
                    .put("output", "done")
            )

        val event = buildSentinelXJobCompletedEvent(
            jobId = "job_test",
            op = "script_run",
            hostId = "host_test",
            response = response,
            startedAt = started,
            finishedAt = finished
        )

        assertEquals("event", event.getString("type"))
        assertEquals("job_completed", event.getString("kind"))
        val data = event.getJSONObject("data")
        assertEquals("job_test", data.getString("job_id"))
        assertEquals("script_run", data.getString("tool"))
        assertEquals("host_test", data.getString("host"))
        assertEquals("succeeded", data.getString("status"))
        assertEquals(0, data.getInt("exit_code"))
        assertEquals("done", data.getString("output"))
        assertEquals(1.25, data.getDouble("duration_s"), 0.001)
        assertFalse(data.getBoolean("output_truncated"))
        assertTrue(data.isNull("error"))
    }

    @Test
    fun failureBuildsFailedJobEvent() {
        val at = Instant.parse("2026-10-04T00:00:00Z")
        val response = JSONObject()
            .put("ok", false)
            .put(
                "error",
                JSONObject()
                    .put("code", "permission_denied")
                    .put("message", "denied")
            )

        val data = buildSentinelXJobCompletedEvent(
            jobId = "job_failed",
            op = "exec",
            hostId = "host_test",
            response = response,
            startedAt = at,
            finishedAt = at
        ).getJSONObject("data")

        assertEquals("failed", data.getString("status"))
        assertTrue(data.isNull("exit_code"))
        assertEquals("", data.getString("output"))
        assertEquals("denied", data.getString("error"))
    }

    @Test
    fun largeOutputIsByteBounded() {
        val at = Instant.parse("2026-10-04T00:00:00Z")
        val response = JSONObject()
            .put("ok", true)
            .put(
                "result",
                JSONObject()
                    .put("returncode", 0)
                    .put("output", "🥰".repeat(80_000))
            )

        val data = buildSentinelXJobCompletedEvent(
            jobId = "job_large",
            op = "script_run",
            hostId = "host_test",
            response = response,
            startedAt = at,
            finishedAt = at
        ).getJSONObject("data")

        assertTrue(data.getBoolean("output_truncated"))
        assertTrue(data.getString("output").toByteArray(Charsets.UTF_8).size <= 256 * 1024)
    }
}
