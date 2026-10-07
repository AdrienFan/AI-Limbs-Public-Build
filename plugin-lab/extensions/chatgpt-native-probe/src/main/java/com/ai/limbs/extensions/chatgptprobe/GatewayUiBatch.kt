package com.ai.limbs.extensions.chatgptprobe

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Bridge-owned orchestration. Native execution/authorization and feedback remain Host primitives. */
internal object GatewayUiBatch {
    private data class Step(val capability: String, val nativeName: String, val parameters: JSONObject)
    private val actions = setOf("tap", "long_press", "click_element", "swipe", "set_input_text", "press_key", "start_app", "execute_shell")

    suspend fun execute(arguments: JSONObject, resolve: suspend (String) -> String,
        invoke: suspend (String, JSONObject) -> JSONObject): JSONObject {
        val input = arguments.getJSONArray("steps")
        require(input.length() in 1..8) { "steps must contain 1..8 known sequential UI actions" }
        val steps = (0 until input.length()).map { index ->
            val item = input.getJSONObject(index)
            require(item.keys().asSequence().all { it == "capability_id" || it == "parameters" }) { "Unknown batch step field" }
            require(item.opt("capability_id") is String) { "Step capability_id must be a string" }
            val capability = item.getString("capability_id").trim()
            val nativeName = capability.removePrefix("native.")
            require(nativeName in actions && (capability == nativeName || capability == "native.$nativeName")) { "Batch accepts only native UI actions" }
            require(!item.has("parameters") || item.opt("parameters") is JSONObject) { "Step parameters must be an object" }
            val parameters = JSONObject((item.optJSONObject("parameters") ?: JSONObject()).toString())
            require(!parameters.has("screen_feedback")) { "Batch controls screen_feedback" }
            if (nativeName == "execute_shell") require(parameters.opt("screen_action") == true) { "UI shell steps require screen_action=true" }
            Step(capability, nativeName, parameters)
        }
        // Resolve every address and require final read support BEFORE starting any UI action.
        val feedbackId = resolve("ai_limbs.operation_feedback.read")
        val ids = mutableListOf<String>()
        for (step in steps) {
            val id = resolve(step.capability)
            require(id == step.nativeName) { "Batch step did not resolve to its native action" }
            ids += id
        }
        val outputs = JSONArray()
        var successful = 0
        var uncertain = false
        for ((index, step) in steps.withIndex()) {
            val result = try {
                invoke(ids[index], JSONObject(step.parameters.toString()).put("screen_feedback", false))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                JSONObject().put("success", false).put("execution_state", "UNKNOWN")
                    .put("error_code", "BATCH_STEP_RESULT_UNKNOWN").put("error_class", error.javaClass.simpleName)
                    .put("automatic_reexecution", false)
            }
            outputs.put(JSONObject().put("index", index).put("capability_id", step.capability).put("result", result))
            uncertain = result.optString("execution_state") == "UNKNOWN"
            if (GatewayResults.failed(result) || uncertain) break
            successful++
        }
        val feedback = try {
            invoke(feedbackId, JSONObject())
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            JSONObject().put("success", false).put("error_code", "BATCH_FEEDBACK_UNAVAILABLE")
                .put("error_class", error.javaClass.simpleName).put("automatic_reexecution", false)
        }
        val complete = successful == steps.size
        return JSONObject().put("success", complete).put("execution_state", if (uncertain) "UNKNOWN" else "RESULT_RECEIVED")
            .put("automatic_reexecution", false).put("batch", JSONObject().put("requested_steps", steps.size)
                .put("attempted_steps", outputs.length()).put("successful_steps", successful)
                .put("status", if (complete) "COMPLETED" else if (uncertain) "UNCERTAIN" else "STOPPED")
                .apply { if (!complete) put("failed_step", outputs.length() - 1) })
            .put("steps", outputs).put("feedback_result", feedback)
    }
}
