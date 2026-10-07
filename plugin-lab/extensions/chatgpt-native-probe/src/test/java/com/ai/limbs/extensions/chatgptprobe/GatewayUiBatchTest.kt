package com.ai.limbs.extensions.chatgptprobe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GatewayUiBatchTest {
    private fun args(vararg actions: String) = JSONObject().put("steps", JSONArray().apply {
        actions.forEach { put(JSONObject().put("capability_id", it).put("parameters", JSONObject())) }
    })
    private fun resolve(id: String) = id.removePrefix("native.")

    @Test fun preflightResolvesAllStepsAndOnlyFinalCallReadsFeedback() = runBlocking {
        val events = mutableListOf<String>()
        val input = args("native.tap", "native.press_key")
        val result = GatewayUiBatch.execute(input, resolve = { events += "resolve:$it"; resolve(it) },
            invoke = { id, p ->
                events += "invoke:$id"
                if (id == "ai_limbs.operation_feedback.read") assertEquals(0, p.length())
                else assertEquals(false, p.getBoolean("screen_feedback"))
                JSONObject().put("success", true)
            })
        assertEquals(listOf("resolve:ai_limbs.operation_feedback.read", "resolve:native.tap",
            "resolve:native.press_key", "invoke:tap", "invoke:press_key",
            "invoke:ai_limbs.operation_feedback.read"), events)
        assertTrue(result.getBoolean("success"))
        assertEquals("COMPLETED", result.getJSONObject("batch").getString("status"))
        assertEquals(2, result.getJSONObject("batch").getInt("successful_steps"))
        assertFalse(input.getJSONArray("steps").getJSONObject(0).getJSONObject("parameters").has("screen_feedback"))
    }

    @Test fun malformedOrUnresolvedBatchHasNoActionEffects() {
        val bad = listOf(args(), args(*Array(9) { "tap" }), args("native.list_files"),
            args("native.execute_shell"),
            args("tap").apply { getJSONArray("steps").getJSONObject(0).getJSONObject("parameters").put("screen_feedback", true) },
            args("tap").apply { getJSONArray("steps").getJSONObject(0).put("unknown", true) })
        for (input in bad) {
            var calls = 0
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { GatewayUiBatch.execute(input, ::resolve) { _, _ -> calls++; JSONObject() } }
            }
            assertEquals(0, calls)
        }
        var calls = 0
        assertThrows(java.io.IOException::class.java) {
            runBlocking { GatewayUiBatch.execute(args("tap", "swipe"), resolve = {
                if (it == "swipe") throw java.io.IOException("unavailable")
                resolve(it)
            }, invoke = { _, _ -> calls++; JSONObject() }) }
        }
        assertEquals(0, calls)
    }

    @Test fun failureStopsLaterActionsAndStillReadsOneFinalFeedback() = runBlocking {
        val calls = mutableListOf<String>()
        val result = GatewayUiBatch.execute(args("tap", "swipe", "press_key"), ::resolve) { id, _ ->
            calls += id
            JSONObject().put("success", id != "swipe")
        }
        assertEquals(listOf("tap", "swipe", "ai_limbs.operation_feedback.read"), calls)
        assertFalse(result.getBoolean("success"))
        assertEquals("STOPPED", result.getJSONObject("batch").getString("status"))
        assertEquals(1, result.getJSONObject("batch").getInt("failed_step"))
        assertEquals(1, result.getJSONObject("batch").getInt("successful_steps"))
    }

    @Test fun lostStepResultIsUnknownAndNeverRetried() = runBlocking {
        val calls = mutableListOf<String>()
        val result = GatewayUiBatch.execute(args("tap", "swipe"), ::resolve) { id, _ ->
            calls += id
            if (id == "tap") throw java.io.IOException("lost reply")
            JSONObject().put("success", true)
        }
        assertEquals(listOf("tap", "ai_limbs.operation_feedback.read"), calls)
        assertEquals("UNKNOWN", result.getString("execution_state"))
        assertEquals("UNCERTAIN", result.getJSONObject("batch").getString("status"))
        assertFalse(result.getBoolean("automatic_reexecution"))
    }

    @Test fun cancellationPropagatesWithoutAnyFurtherActionOrCapture() {
        val calls = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            runBlocking { GatewayUiBatch.execute(args("tap", "swipe"), ::resolve) { id, _ ->
                calls += id
                throw CancellationException("deadline")
            } }
        }
        assertEquals(listOf("tap"), calls)
    }

    @Test fun feedbackFailurePreservesCompletedActionOutcome() = runBlocking {
        val result = GatewayUiBatch.execute(args("tap"), ::resolve) { id, _ ->
            if (id == "ai_limbs.operation_feedback.read") throw java.io.IOException("capture unavailable")
            JSONObject().put("success", true)
        }
        assertTrue(result.getBoolean("success"))
        assertEquals("COMPLETED", result.getJSONObject("batch").getString("status"))
        assertFalse(result.getJSONObject("feedback_result").getBoolean("success"))
        assertFalse(result.getBoolean("automatic_reexecution"))
    }
}
