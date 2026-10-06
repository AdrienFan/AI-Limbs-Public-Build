package com.ai.assistance.operit.integrations.ailimbs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Neutral per-turn context protocol. Providers own source selection and acquisition. */
internal object AiLimbsMessageContext {
    const val TIMEOUT_MS = 12_000
    data class Provider(val id: String, val owner: String, val invoke: suspend (String) -> String)

    suspend fun read(request: JSONObject, providers: List<Provider>, elapsed: () -> Long): JSONObject {
        val contexts = JSONArray()
        for (provider in providers) {
            val response = try {
                val remaining = request.getLong("deadline_elapsed_ms") - elapsed()
                check(remaining > 0L) { "Message context deadline expired" }
                val wire = withTimeoutOrNull(remaining) { provider.invoke(request.toString()) }
                check(wire != null) { "Message context capture timed out" }
                val value = JSONObject(wire)
                require(value.getInt("schema") == 1 && value.getString("context_id") == request.getString("context_id")) {
                    "Message context response identity mismatch"
                }
                when (value.getString("status")) {
                    "INACTIVE", "FAILED" -> require(!value.has("mcp_content")) { "Inactive or failed context contains an image" }
                    "READY" -> {
                        val proof = value.getJSONObject("freshness")
                        require(proof.getString("method").isNotBlank() &&
                            proof.getLong("requested_elapsed_ms") >= request.getLong("requested_elapsed_ms") &&
                            proof.getLong("captured_elapsed_ms") >= proof.getLong("requested_elapsed_ms") &&
                            proof.getLong("captured_elapsed_ms") < request.getLong("deadline_elapsed_ms") &&
                            elapsed() < request.getLong("deadline_elapsed_ms")) { "Message context is stale" }
                        val media = value.getJSONArray("mcp_content")
                        require(media.length() == 1) { "Expected one context image" }
                        val image = media.getJSONObject(0)
                        require(image.getString("type") == "image" && image.getString("mimeType") == "image/jpeg" &&
                            image.getString("data").length in 1..699_052) { "Context image is invalid or oversized" }
                        require(!value.getJSONObject("image").has("data")) { "Duplicate image payload" }
                    }
                    else -> error("Unsupported message context status")
                }
                value
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                JSONObject().put("schema", 1).put("context_id", request.getString("context_id"))
                    .put("status", "FAILED").put("error_code", "MESSAGE_CONTEXT_FAILED")
                    .put("error", error.message ?: error.javaClass.simpleName)
            }
            contexts.put(response.put("provider_id", provider.id).put("owner_plugin_id", provider.owner))
        }
        val values = (0 until contexts.length()).map { contexts.getJSONObject(it) }
        return JSONObject().put("success", values.none { it.getString("status") == "FAILED" })
            .put("context_id", request.getString("context_id"))
            .put("active", values.any { it.getString("status") == "READY" }).put("contexts", contexts)
    }
}
