package com.ai.assistance.operit.integrations.ailimbs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Post-operation sideband. It never changes the operation's outcome or executes it again. */
internal object AiLimbsOperationFeedback {
    const val EVENT = "screen_interaction"
    private val screenTools = setOf(
        "tap", "long_press", "click_element", "swipe", "set_input_text", "press_key",
        "start_app", "stop_app", "run_ui_subagent"
    )

    fun requested(tool: String, parameters: JSONObject): Boolean {
        if (tool != "execute_shell") return tool in screenTools
        if (!parameters.has("screen_action")) return false
        val flag = parameters.get("screen_action")
        require(flag is Boolean) { "screen_action must be a boolean" }
        return flag
    }

    data class Provider(val id: String, val owner: String, val invoke: suspend (String) -> String)

    suspend fun attach(result: JSONObject, request: JSONObject, providers: List<Provider>): JSONObject {
        val feedback = JSONArray()
        for (provider in providers) {
            val item = try {
                val response = withTimeout(6_000L) { JSONObject(provider.invoke(request.toString())) }
                require(response.getInt("schema") == 1) { "Unsupported feedback schema" }
                require(response.getString("operation_id") == request.getString("operation_id")) {
                    "Feedback operation identity mismatch"
                }
                when (response.getString("status")) {
                    "INACTIVE" -> null
                    "READY" -> {
                        val fresh = response.getJSONObject("freshness")
                        require(fresh.getString("method") == "new_surface") { "Feedback is not a fresh frame" }
                        require(fresh.getLong("requested_elapsed_ms") >= request.getLong("completed_elapsed_ms")) {
                            "Feedback frame request predates the action"
                        }
                        require(fresh.getLong("captured_elapsed_ms") >= fresh.getLong("requested_elapsed_ms")) {
                            "Feedback capture predates the frame request"
                        }
                        val content = response.getJSONArray("mcp_content")
                        require(content.length() == 1 && content.getJSONObject(0).getString("type") == "image") {
                            "Feedback must contain one image"
                        }
                        response
                    }
                    "FAILED" -> {
                        // Failure sidebands cannot smuggle a stale preview into image delivery.
                        JSONObject().put("schema", 1).put("status", "FAILED")
                            .put("operation_id", request.getString("operation_id"))
                            .put("error_code", response.getString("error_code"))
                            .put("error", response.getString("error"))
                    }
                    else -> error("Unknown operation feedback status")
                }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                failed(request, "OPERATION_FEEDBACK_TIMEOUT", "Post-action feedback timed out")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed(request, "OPERATION_FEEDBACK_FAILED", error.message ?: error.javaClass.simpleName)
            }
            if (item != null) feedback.put(item.put("provider_id", provider.id).put("owner_plugin_id", provider.owner))
        }
        if (feedback.length() > 0) result.put("operation_feedback", feedback)
        return result
    }

    private fun failed(request: JSONObject, code: String, message: String) = JSONObject()
        .put("schema", 1).put("status", "FAILED").put("operation_id", request.getString("operation_id"))
        .put("error_code", code).put("error", message)
}
