package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd.TriggerCmdStructuredBridgeExecutor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerCmdStructuredBridgeExecutorTest {
    @Test
    fun acceptedRequestCanBePolledWithoutDuplicateExecution() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<JSONObject>()
        val calls = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor =
            TriggerCmdStructuredBridgeExecutor(scope) { _, _ ->
                calls.incrementAndGet()
                started.complete(Unit)
                finish.await()
            }
        val request = request("req-poll", "capability.search", JSONObject().put("query", "Ubuntu"))

        val accepted = JSONObject(executor.execute(request))
        assertEquals("accepted", accepted.getString("status"))
        assertTrue(accepted.getBoolean("ok"))
        withTimeout(2_000) { started.await() }

        val running = JSONObject(executor.execute(request))
        assertEquals("running", running.getString("status"))
        assertEquals(1, calls.get())

        val conflict =
            JSONObject(
                executor.execute(
                    request("req-poll", "capability.search", JSONObject().put("query", "Android"))
                )
            )
        assertEquals("REQUEST_ID_CONFLICT", conflict.getString("code"))
        assertFalse(conflict.getBoolean("ok"))
        assertEquals(1, calls.get())

        finish.complete(JSONObject().put("success", true).put("value", "done"))
        val completed = awaitCompleted(executor, request)
        assertTrue(completed.getBoolean("ok"))
        assertEquals("done", completed.getJSONObject("result").getString("value"))
        assertEquals(1, calls.get())
        scope.cancel()
    }

    @Test
    fun slowRequestDoesNotBlockAnotherRequestFromStarting() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor =
            TriggerCmdStructuredBridgeExecutor(scope) { tool, _ ->
                if (tool == "slow.first") {
                    firstStarted.complete(Unit)
                } else {
                    secondStarted.complete(Unit)
                }
                release.await()
                JSONObject().put("success", true)
            }

        val first = request("req-first", "slow.first")
        val second = request("req-second", "slow.second")
        assertEquals("accepted", JSONObject(executor.execute(first)).getString("status"))
        withTimeout(2_000) { firstStarted.await() }

        assertEquals("accepted", JSONObject(executor.execute(second)).getString("status"))
        withTimeout(2_000) { secondStarted.await() }

        release.complete(Unit)
        scope.cancel()
    }

    private suspend fun awaitCompleted(
        executor: TriggerCmdStructuredBridgeExecutor,
        request: String
    ): JSONObject =
        withTimeout(2_000) {
            while (true) {
                val response = JSONObject(executor.execute(request))
                if (response.getString("status") == "completed") {
                    return@withTimeout response
                }
                delay(10)
            }
            error("unreachable")
        }

    private fun request(
        requestId: String,
        tool: String,
        args: JSONObject = JSONObject()
    ): String =
        JSONObject()
            .put("protocol", "AIL_TRIGGER_BRIDGE_V1")
            .put("request_id", requestId)
            .put("tool", tool)
            .put("args", args)
            .toString()
}
