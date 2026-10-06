package com.ai.limbs.extensions.chatgptprobe

import com.ai.assistance.operit.integrations.ailimbs.BridgeRemoteIngress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GatewayEngineTest {
    @Test fun deliveryRetryAndDuplicateDoNotRepeatBusinessAction() = runBlocking {
        Fixture(failFirstPost = true).use { fixture ->
            val calls = AtomicInteger()
            fixture.start { _, _ -> calls.incrementAndGet(); JSONObject().put("success", true).put("value", "completed") }
            val command = toolCommand("request-one", 1)
            fixture.commands.add(command)
            eventually { fixture.responses.any { it.optString("request_id") == "request-one" } }
            fixture.commands.add(JSONObject(command.toString()).put("shard_token", "refreshed-token"))
            eventually { fixture.responses.count { it.optString("request_id") == "request-one" } >= 2 }
            assertEquals(1, calls.get())
            assertEquals(1L, fixture.engine.state.value.access.capabilityInvokeCount)
            assertEquals(1L, fixture.engine.state.value.access.capabilitySuccessCount)
            assertTrue(fixture.postAttempts.get() >= 3)
            assertTrue(fixture.responses.first().getJSONObject("resp_json").getJSONObject("result").getJSONObject("structuredContent").getBoolean("success"))
        }
    }

    @Test fun controlsRemainResponsiveWhileBusinessConcurrencyIsBounded() = runBlocking {
        Fixture().use { fixture ->
            val calls = AtomicInteger()
            val inFlight = AtomicInteger()
            val maximum = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            fixture.start { _, _ ->
                calls.incrementAndGet()
                val count = inFlight.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                try { gate.await(); JSONObject().put("success", true) } finally { inFlight.decrementAndGet() }
            }
            repeat(5) { fixture.commands.add(toolCommand("business-$it", it)) }
            eventually { calls.get() == 4 }
            fixture.commands.add(rpcCommand("control-list", 100, "tools/list"))
            eventually { fixture.responses.any { it.optString("request_id") == "control-list" } }
            val tools = fixture.responses.first { it.optString("request_id") == "control-list" }
                .getJSONObject("resp_json").getJSONObject("result").getJSONArray("tools")
            val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
            assertEquals(6, names.size)
            assertTrue(names.containsAll(listOf("ai_limbs_capability_search", "ai_limbs_capability_describe", "ai_limbs_capability_invoke", "ai_limbs_result_read", "ai_limbs_media_read", "ai_limbs_gateway_status")))
            assertEquals(4, calls.get())
            gate.complete(Unit)
            eventually { fixture.responses.count { it.optString("request_id").startsWith("business-") } == 5 }
            assertEquals(4, maximum.get())
            assertEquals(5, calls.get())
        }
    }

    @Test fun cancellationReachesIngressAndItsDuplicateNeverRestartsTheAction() = runBlocking {
        Fixture().use { fixture ->
            val calls = AtomicInteger()
            val cancelled = CompletableDeferred<Unit>()
            fixture.start { _, _ ->
                calls.incrementAndGet()
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            val command = toolCommand("cancel-me", 31)
            fixture.commands.add(command)
            eventually { calls.get() == 1 }
            fixture.commands.add(rpcCommand("cancel-notify", null, "notifications/cancelled", JSONObject().put("requestId", 31)))
            withTimeout(8000L) { cancelled.await() }
            eventually { fixture.responses.any { it.optString("request_id") == "cancel-me" } }
            val outcome = fixture.responses.first { it.optString("request_id") == "cancel-me" }
                .getJSONObject("resp_json").getJSONObject("result").getJSONObject("structuredContent")
            assertEquals("UNKNOWN", outcome.getString("execution_state"))
            assertFalse(outcome.getBoolean("automatic_reexecution"))
            fixture.commands.add(JSONObject(command.toString()).put("shard_token", "another-token"))
            eventually { fixture.responses.count { it.optString("request_id") == "cancel-me" } >= 2 }
            assertEquals(1, calls.get())
        }
    }

    @Test fun interruptedProcessRecoversAnUncertainResponseInsteadOfAnAction() = runBlocking {
        Fixture().use { fixture ->
            val command = toolCommand("interrupted", 55)
            GatewayReceipts(fixture.store).claim(gatewayHash(fixture.baseUrl + "\n" + "tunnel-test"), command)
            val calls = AtomicInteger()
            fixture.start { _, _ -> calls.incrementAndGet(); JSONObject() }
            eventually { fixture.responses.any { it.optString("request_id") == "interrupted" } }
            assertEquals(0, calls.get())
            assertEquals("PROCESS_INTERRUPTED", fixture.responses.first().getJSONObject("resp_json")
                .getJSONObject("result").getJSONObject("structuredContent").getString("reason"))
        }
    }

    @Test fun unsupportedProtocolIsRejectedAndHealthUsesRealResponses() = runBlocking {
        Fixture().use { fixture ->
            fixture.start { _, _ -> JSONObject() }
            fixture.commands.add(rpcCommand("unsupported", 7, "initialize", JSONObject().put("protocolVersion", "2099-01-01")))
            eventually { fixture.responses.any { it.optString("request_id") == "unsupported" } }
            assertEquals(-32602, fixture.responses.first().getJSONObject("resp_json").getJSONObject("error").getInt("code"))
            eventually { fixture.engine.state.value.lastResponseAckAtMs != null }
            assertNotNull(fixture.engine.state.value.lastSuccessfulPollAtMs)
            assertNull(fixture.engine.state.value.lastToolsListAtMs)
        }
    }

    @Test fun gatewayDeadlineDoesNotLeakIntoDomainParametersAndCancelsCooperatively() = runBlocking {
        Fixture().use { fixture ->
            val cancelled = CompletableDeferred<Unit>()
            fixture.start { tool, args ->
                assertEquals("test.action", tool)
                assertEquals("domain-value", args.getString("domain_input"))
                assertFalse(args.has("timeout_ms"))
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            val command = toolCommand("deadline", 42)
            command.getJSONObject("jsonrpc").getJSONObject("params").getJSONObject("arguments")
                .put("timeout_ms", 1000).put("parameters", JSONObject().put("domain_input", "domain-value"))
            fixture.commands.add(command)
            withTimeout(8000L) { cancelled.await() }
            eventually { fixture.responses.any { it.optString("request_id") == "deadline" } }
            assertEquals("TIMEOUT", fixture.responses.first().getJSONObject("resp_json").getJSONObject("result")
                .getJSONObject("structuredContent").getString("reason"))
        }
    }

    @Test fun malformedArgumentsDoNotInvokeTheHost() = runBlocking {
        Fixture().use { fixture ->
            val calls = AtomicInteger()
            fixture.start { _, _ -> calls.incrementAndGet(); JSONObject() }
            val command = toolCommand("bad-input", 90)
            command.getJSONObject("jsonrpc").getJSONObject("params").getJSONObject("arguments").put("parameters", "wrong-type")
            fixture.commands.add(command)
            eventually { fixture.responses.any { it.optString("request_id") == "bad-input" } }
            assertEquals(0, calls.get())
            assertEquals(-32602, fixture.responses.first().getJSONObject("resp_json").getJSONObject("error").getInt("code"))
        }
    }

    @Test fun unknownDemoToolExplainsRefreshWithoutInvokingOrClaimingBusinessSuccess() = runBlocking {
        Fixture().use { fixture ->
            val calls = AtomicInteger()
            fixture.start { _, _ -> calls.incrementAndGet(); JSONObject() }
            val legacy = toolCommand("legacy", 101)
            legacy.getJSONObject("jsonrpc").getJSONObject("params").put("name", "server_info")
            fixture.commands.add(legacy)
            eventually { fixture.responses.any { it.optString("request_id") == "legacy" } }
            val error = fixture.responses.first().getJSONObject("resp_json").getJSONObject("error")
            assertEquals(-32602, error.getInt("code"))
            val data = error.getJSONObject("data")
            assertEquals("TOOL_NOT_ADVERTISED", data.getString("gateway_error_code"))
            assertEquals(6, data.getJSONArray("advertised_tools").length())
            assertEquals(4, data.getJSONArray("metadata_refresh_steps").length())
            assertEquals(GatewayAdmission.DOCUMENTATION_URL, data.getString("documentation_url"))
            assertFalse(error.toString().contains("server_info"))
            assertEquals(0, calls.get())
            assertEquals(1L, fixture.engine.state.value.access.protocolErrorCount)
            assertEquals(1L, fixture.engine.state.value.access.unadvertisedToolCount)
            eventually { fixture.engine.statusJson().getJSONObject("access").getString("stage") == "CATALOG_MISMATCH_SUSPECTED" }

            fixture.commands.add(rpcCommand("fresh-list", 102, "tools/list"))
            eventually { fixture.responses.any { it.optString("request_id") == "fresh-list" } }
            assertTrue(fixture.engine.state.value.access.catalogMismatchSuspected)
            fixture.commands.add(rpcCommand("fresh-status", 103, "tools/call",
                JSONObject().put("name", "ai_limbs_gateway_status").put("arguments", JSONObject())))
            eventually { fixture.responses.any { it.optString("request_id") == "fresh-status" } }
            val status = fixture.responses.first { it.optString("request_id") == "fresh-status" }
                .getJSONObject("resp_json").getJSONObject("result").getJSONObject("structuredContent")
            assertTrue(status.getBoolean("success"))
            assertEquals("ADVERTISED_TOOL_CALL_OBSERVED", status.getJSONObject("access").getString("stage"))
            assertFalse(status.getBoolean("client_catalog_refresh_verified"))
            assertEquals(0L, fixture.engine.state.value.access.capabilitySuccessCount)
            assertEquals(0, calls.get())
        }
    }

    @Test fun hostRefusalsAndUnknownOutcomesAreNotCountedAsSuccessfulInvocations() = runBlocking {
        Fixture().use { fixture ->
            fixture.start { _, args -> when (args.getString("kind")) {
                "ok" -> JSONObject().put("error", JSONObject.NULL).put("value", "read result")
                "ask" -> JSONObject().put("execution_policy", JSONObject().put("outcome", "ASK"))
                "unknown" -> JSONObject().put("execution_state", "UNKNOWN")
                else -> throw java.io.IOException("Simulated ingress interruption")
            } }
            listOf("ok", "ask", "unknown", "exception").forEachIndexed { index, kind ->
                val command = toolCommand(kind, 200 + index)
                command.getJSONObject("jsonrpc").getJSONObject("params").getJSONObject("arguments")
                    .put("parameters", JSONObject().put("kind", kind))
                fixture.commands.add(command)
            }
            eventually { fixture.responses.size == 4 }
            val observed = fixture.engine.state.value.access
            assertEquals(4L, observed.capabilityInvokeCount)
            assertEquals(1L, observed.capabilitySuccessCount)
            assertEquals(1L, observed.capabilityFailureCount)
            assertEquals(2L, observed.capabilityUncertainCount)
            assertEquals(0L, observed.protocolErrorCount)
            assertNotNull(observed.lastSuccessfulInvokeAtMs)
            val unknown = fixture.responses.first { it.optString("request_id") == "unknown" }
                .getJSONObject("resp_json").getJSONObject("result")
            assertTrue(unknown.getBoolean("isError"))
        }
    }

    @Test fun metadataContractStaysStableAndRestartRequiresNewAccessEvidence() = runBlocking {
        Fixture().use { fixture ->
            fixture.start { _, _ -> JSONObject().put("success", true) }
            fixture.commands.add(rpcCommand("init", 300, "initialize", JSONObject().put("protocolVersion", "2025-11-25")))
            fixture.commands.add(rpcCommand("catalog", 301, "tools/list"))
            fixture.commands.add(toolCommand("invoke", 302))
            eventually { fixture.responses.size == 3 }
            val init = fixture.responses.first { it.optString("request_id") == "init" }.getJSONObject("resp_json").getJSONObject("result")
            assertEquals("0.0.11", init.getJSONObject("serverInfo").getString("version"))
            assertFalse(init.getJSONObject("capabilities").getJSONObject("tools").getBoolean("listChanged"))
            assertTrue(init.getString("instructions").contains("new conversation"))
            val tools = fixture.responses.first { it.optString("request_id") == "catalog" }.getJSONObject("resp_json")
                .getJSONObject("result").getJSONArray("tools")
            assertEquals(6, tools.length())
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index)
                assertTrue(tool.getString("title").isNotBlank())
                assertEquals("object", tool.getJSONObject("inputSchema").getString("type"))
                assertFalse(tool.getJSONObject("inputSchema").getBoolean("additionalProperties"))
                assertEquals("object", tool.getJSONObject("outputSchema").getString("type"))
                val invoke = tool.getString("name") == "ai_limbs_capability_invoke"
                assertEquals(!invoke, tool.getJSONObject("annotations").getBoolean("readOnlyHint"))
                assertEquals(invoke, tool.getJSONObject("annotations").getBoolean("destructiveHint"))
                if (tool.getString("name") == "ai_limbs_capability_search")
                    assertEquals(1, tool.getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("query").getInt("minLength"))
            }
            assertEquals(1L, fixture.engine.state.value.access.initializeCount)
            assertEquals(1L, fixture.engine.state.value.access.capabilitySuccessCount)
            val digest = fixture.engine.statusJson().getString("tool_catalog_sha256")
            fixture.engine.stop()
            fixture.start { _, _ -> JSONObject() }
            eventually { fixture.engine.statusJson().getJSONObject("access").getString("stage") == "AWAITING_TOOL_DISCOVERY" }
            assertEquals(0L, fixture.engine.state.value.access.capabilityInvokeCount)
            assertEquals(0L, fixture.engine.state.value.access.initializeCount)
            assertNull(fixture.engine.state.value.lastToolsListAtMs)
            assertNull(fixture.engine.state.value.access.lastSuccessfulInvokeAtMs)
            assertEquals(digest, fixture.engine.statusJson().getString("tool_catalog_sha256"))
        }
    }

    @Test fun resultPreparationFailurePreservesTheKnownHostOutcomeAndNeverReexecutes() = runBlocking {
        Fixture().use { fixture ->
            val calls = AtomicInteger()
            fixture.start { _, _ ->
                calls.incrementAndGet()
                fixture.store.failNextWrite = true
                JSONObject().put("success", true).put("text", "x".repeat(20_000))
            }
            val command = toolCommand("known-result", 400)
            fixture.commands.add(command)
            eventually { fixture.responses.any { it.optString("request_id") == "known-result" } }
            val result = fixture.responses.first().getJSONObject("resp_json").getJSONObject("result")
            assertTrue(result.getBoolean("isError"))
            val details = result.getJSONObject("structuredContent")
            assertEquals("RESULT_RECEIVED", details.getString("execution_state"))
            assertTrue(details.getBoolean("host_result_received"))
            assertFalse(details.getBoolean("host_result_failed"))
            assertFalse(details.getBoolean("automatic_reexecution"))
            assertEquals("RESULT_ADAPTATION_FAILED", details.getString("result_delivery_error"))
            assertEquals(1L, fixture.engine.state.value.access.capabilityInvokeCount)
            assertEquals(1L, fixture.engine.state.value.access.capabilitySuccessCount)
            assertEquals(0L, fixture.engine.state.value.access.capabilityFailureCount)
            assertEquals(0L, fixture.engine.state.value.access.capabilityUncertainCount)
            assertEquals(1L, fixture.engine.state.value.access.resultPreparationFailureCount)
            fixture.commands.add(JSONObject(command.toString()).put("shard_token", "fresh-delivery-token"))
            eventually { fixture.responses.count { it.optString("request_id") == "known-result" } == 2 }
            assertEquals(1, calls.get())
            assertEquals(1L, fixture.engine.state.value.access.resultPreparationFailureCount)
        }
    }

    @Test fun oversizedHostResultsPreserveSuccessAndRefusalWithoutRepeatingEitherRequest() = runBlocking {
        Fixture().use { fixture ->
            val calls = AtomicInteger()
            fixture.start { _, args ->
                calls.incrementAndGet()
                val refused = args.getBoolean("refused")
                JSONObject().put("success", !refused).put("text", "x".repeat(4 * 1024 * 1024 + 1))
                    .put("execution_policy", JSONObject().put("outcome", if (refused) "ASK" else "ALLOW"))
            }
            val commands = listOf(false, true).mapIndexed { index, refused ->
                toolCommand("oversized-$refused", 410 + index).apply {
                    getJSONObject("jsonrpc").getJSONObject("params").getJSONObject("arguments")
                        .put("parameters", JSONObject().put("refused", refused))
                }
            }
            commands.forEach { fixture.commands.add(it) }
            eventually { fixture.responses.size == 2 }
            for (refused in listOf(false, true)) {
                val result = fixture.responses.first { it.optString("request_id") == "oversized-$refused" }
                    .getJSONObject("resp_json").getJSONObject("result")
                assertTrue(result.getBoolean("isError"))
                val details = result.getJSONObject("structuredContent")
                assertEquals("RESULT_RECEIVED", details.getString("execution_state"))
                assertTrue(details.getBoolean("host_result_received"))
                assertEquals(refused, details.getBoolean("host_result_failed"))
                assertFalse(details.getBoolean("automatic_reexecution"))
                assertEquals(if (refused) "ASK" else "ALLOW", details.getJSONObject("execution_policy").getString("outcome"))
            }
            assertEquals(2L, fixture.engine.state.value.access.capabilityInvokeCount)
            assertEquals(1L, fixture.engine.state.value.access.capabilitySuccessCount)
            assertEquals(1L, fixture.engine.state.value.access.capabilityFailureCount)
            assertEquals(0L, fixture.engine.state.value.access.capabilityUncertainCount)
            assertEquals(0L, fixture.engine.state.value.access.protocolErrorCount)
            assertEquals(2L, fixture.engine.state.value.access.resultPreparationFailureCount)
            commands.forEach { fixture.commands.add(JSONObject(it.toString()).put("shard_token", "second-delivery")) }
            eventually { fixture.responses.size == 4 }
            assertEquals(2, calls.get())
            assertEquals(2L, fixture.engine.state.value.access.resultPreparationFailureCount)
        }
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(10_000L) {
        while (!condition()) delay(20L)
    }

    private fun toolCommand(requestId: String, rpcId: Int): JSONObject = rpcCommand(requestId, rpcId, "tools/call",
        JSONObject().put("name", "ai_limbs_capability_invoke").put("arguments", JSONObject().put("capability_id", "test.action").put("parameters", JSONObject())))

    private fun rpcCommand(requestId: String, rpcId: Int?, method: String, params: JSONObject = JSONObject()): JSONObject {
        val rpc = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        if (rpcId != null) rpc.put("id", rpcId)
        return JSONObject().put("request_id", requestId).put("shard_token", "original-token").put("channel", "main")
            .put("command_type", "jsonrpc").put("jsonrpc", rpc)
    }

    private class Fixture(failFirstPost: Boolean = false) : AutoCloseable {
        val server = MockWebServer()
        val commands = ConcurrentLinkedQueue<JSONObject>()
        val responses = ConcurrentLinkedQueue<JSONObject>()
        val postAttempts = AtomicInteger()
        val store = MemoryGatewayStore()
        private val failPost = AtomicBoolean(failFirstPost)
        val baseUrl: String
        val engine: ChatGptNativeProbeEngine
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path.orEmpty().contains("/response")) {
                        postAttempts.incrementAndGet()
                        if (failPost.compareAndSet(true, false)) return MockResponse().setResponseCode(503)
                        responses.add(JSONObject(request.body.readUtf8()))
                        return MockResponse().setResponseCode(200).setBody("{}")
                    }
                    val batch = JSONArray()
                    repeat(8) { commands.poll()?.let(batch::put) }
                    return if (batch.length() == 0) MockResponse().setResponseCode(204)
                    else MockResponse().setResponseCode(200).setBody(JSONObject().put("commands", batch).toString())
                }
            }
            server.start()
            baseUrl = server.url("/").toString().trimEnd('/')
            engine = ChatGptNativeProbeEngine(object : GatewayConfiguration {
                override fun readConfig() = ChatGptProbeConfig(true, true, "tunnel-test", baseUrl)
                override fun readApiKey() = "fake-runtime-key"
            }, store)
        }
        fun start(handler: suspend (String, JSONObject) -> JSONObject) {
            engine.bindRemoteIngress(object : BridgeRemoteIngress {
                override val transportId = "test"
                override val providerId = "test"
                override fun beginSession() = Unit
                override suspend fun invoke(tool: String, args: JSONObject) = handler(tool, args)
            })
            engine.start()
        }
        override fun close() { engine.close(); server.shutdown() }
    }
}
