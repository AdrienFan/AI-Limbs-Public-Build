package com.ai.limbs.extensions.sentinelx.runtime

import java.time.Instant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentinelXOfficialOpsTest {
    @Test
    fun environmentResolverMatchesAiLimbsNamespaces() {
        assertEquals("linux", SentinelXEnvironmentResolver.resolve("/root/laner/project"))
        assertEquals("linux", SentinelXEnvironmentResolver.resolve("/home/user/file.txt"))
        assertEquals("linux", SentinelXEnvironmentResolver.resolve("/etc/hosts"))
        assertEquals("android", SentinelXEnvironmentResolver.resolve("/sdcard/Download/file.txt"))
        assertEquals("android", SentinelXEnvironmentResolver.resolve("/data/user/0/app/file.txt"))
        assertEquals(
            "linux",
            SentinelXEnvironmentResolver.resolve(
                "/sdcard/Download/file.txt",
                org.json.JSONObject().put("target", "ubuntu")
            )
        )
    }

    @Test
    fun globMatchingUsesBasenameStyleWildcards() {
        assertTrue(globMatches("main.py", "*.py"))
        assertTrue(globMatches("config.toml", "config.*"))
        assertFalse(globMatches("main.kt", "*.py"))
        assertTrue(globMatches("a1.txt", "a?.txt"))
    }

    @Test
    fun literalAndBlockReplacementRespectCount() {
        assertEquals("x-b-a", replaceLiteral("a-b-a", "a", "x", 1))
        assertEquals("x-b-x", replaceLiteral("a-b-a", "a", "x", 0))
        assertEquals(
            "before NEW after",
            replaceBlocks("before <s>old</e> after", "<s>", "</e>", "NEW", 1)
        )
    }

    @Test
    fun linePrefixStrippingAndUtf8ClipStayTextSafe() {
        assertEquals("alpha\nbeta", stripNumberPrefixes("  1| alpha\n2| beta"))
        val text = "a".repeat(5) + "🥰" + "尾巴"
        val clipped = clipUtf8(text, 9)
        assertEquals("aaaaa🥰", clipped)
        assertFalse(clipped.endsWith("\uFFFD"))
    }

    @Test
    fun nullableBridgeTextDoesNotPromoteJsonNullToString() {
        assertEquals(null, bridgeErrorText(org.json.JSONObject().put("error", "")))
        assertEquals(null, bridgeErrorText(org.json.JSONObject().put("error", org.json.JSONObject.NULL)))
        assertEquals(null, bridgeErrorText(org.json.JSONObject().put("error", "null")))
        assertEquals("boom", bridgeErrorText(org.json.JSONObject().put("error", "boom")))

        val policy = org.json.JSONObject().put("reason_code", org.json.JSONObject.NULL)
        assertEquals(null, jsonTextOrNull(policy, "reason_code"))
        assertEquals(null, jsonTextOrNull(org.json.JSONObject(), "reason_code"))
        assertEquals(
            "permission_denied",
            jsonTextOrNull(
                org.json.JSONObject().put("reason_code", "permission_denied"),
                "reason_code"
            )
        )
    }

    @Test
    fun unsupportedServiceOpsAreNotAdvertised() {
        assertFalse(SentinelXOfficialOps.NATIVE_OPS.contains("service"))
        assertFalse(SentinelXOfficialOps.NATIVE_OPS.contains("restart"))
    }

    @Test
    fun backgroundCompletionEventMatchesSentinelXJobSchema() {
        val started = Instant.parse("2026-10-04T00:00:00Z")
        val finished = Instant.parse("2026-10-04T00:00:01Z")
        val response = SentinelXProtocol.success(
            "req",
            org.json.JSONObject()
                .put("ok", true)
                .put("output", "done")
                .put("returncode", 0)
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
        assertFalse(data.getBoolean("output_truncated"))
        assertTrue(data.isNull("error"))
    }

    @Test
    fun backgroundCompletionEventMapsFailure() {
        val response = SentinelXProtocol.failure("req", "permission_denied", "blocked")
        val event = buildSentinelXJobCompletedEvent(
            jobId = "job_failed",
            op = "script_run",
            hostId = "host_test",
            response = response,
            startedAt = Instant.parse("2026-10-04T00:00:00Z"),
            finishedAt = Instant.parse("2026-10-04T00:00:02Z")
        )
        val data = event.getJSONObject("data")
        assertEquals("failed", data.getString("status"))
        assertTrue(data.isNull("exit_code"))
        assertEquals("blocked", data.getString("error"))
    }

    @Test
    fun protocolPublishesNativeOpsAndTargets() {
        val config = SentinelXBridgeConfig(
            configured = true,
            secureStorageAvailable = true,
            hostId = "host_test",
            hubUrl = "https://mcp.sentinelx.app",
            deviceName = "test-device"
        )
        val capabilities = SentinelXProtocol.capabilities(config)
        val ops = capabilities.getJSONArray("supported_ops")
        for (op in SentinelXOfficialOps.NATIVE_OPS) {
            assertTrue("missing $op", (0 until ops.length()).any { ops.getString(it) == op })
        }
        assertEquals("explicit-target-with-path-namespace-fallback", capabilities.getJSONObject("execution_targets").getString("strategy"))
        assertEquals("AI Limbs Dispatcher / Policy Engine",
            capabilities.getJSONObject("native_adapter").getString("policy_authority"))
    }
}
