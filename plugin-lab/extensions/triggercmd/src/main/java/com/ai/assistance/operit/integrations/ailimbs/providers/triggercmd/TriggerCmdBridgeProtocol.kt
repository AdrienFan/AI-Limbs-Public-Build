// Source: AI Limbs V0.6.4.7.8 @ 70438d99bb40c147cadc0a4a085deb90d15b347c; host execution is adapted separately.
package com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd

import com.ai.assistance.operit.integrations.ailimbs.AiLimbsTriggerCmdContract
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject

internal data class TriggerCmdBridgeRequest(
    val requestId: String,
    val tool: String,
    val args: JSONObject
) {
    val signature: String
        get() = tool + "\n" + args.toString()
}

internal sealed class TriggerCmdBridgeDecodeResult {
    data class Success(val request: TriggerCmdBridgeRequest) : TriggerCmdBridgeDecodeResult()

    data class Failure(
        val requestId: String?,
        val code: String,
        val message: String
    ) : TriggerCmdBridgeDecodeResult()
}

internal object TriggerCmdBridgeProtocol {
    const val PROTOCOL = AiLimbsTriggerCmdContract.PROTOCOL
    const val BASE64_PREFIX = "b64:"

    private const val MAX_PARAMS_CHARS = 32_768
    private const val MAX_REQUEST_ID_CHARS = 128
    private const val MAX_TOOL_CHARS = 192
    private const val RETRY_AFTER_MS = 60_000
    private val requestIdPattern = Regex("[A-Za-z0-9._:-]+")
    private val toolPattern = Regex("[A-Za-z0-9_.:-]+")

    fun decode(rawParams: String): TriggerCmdBridgeDecodeResult {
        val trimmed = rawParams.trim()
        if (trimmed.isBlank()) {
            return failure(null, "INVALID_REQUEST", "Bridge parameters are empty")
        }
        if (trimmed.length > MAX_PARAMS_CHARS) {
            return failure(null, "REQUEST_TOO_LARGE", "Bridge parameters exceed $MAX_PARAMS_CHARS characters")
        }

        val jsonText = when {
            trimmed.startsWith("{") -> trimmed
            trimmed.startsWith(BASE64_PREFIX) -> decodeBase64(trimmed.removePrefix(BASE64_PREFIX))
                ?: return failure(null, "INVALID_ENCODING", "Invalid Base64URL bridge envelope")
            else -> return failure(
                null,
                "INVALID_ENCODING",
                "Expected JSON or b64:Base64URL(JSON) bridge envelope"
            )
        }

        val envelope = try {
            JSONObject(jsonText)
        } catch (error: Exception) {
            return failure(null, "INVALID_JSON", error.message ?: "Invalid JSON bridge envelope")
        }
        val requestId = envelope.optString("request_id").trim()
        if (requestId.isBlank() || requestId.length > MAX_REQUEST_ID_CHARS || !requestIdPattern.matches(requestId)) {
            return failure(
                requestId.ifBlank { null },
                "INVALID_REQUEST_ID",
                "request_id must be 1-$MAX_REQUEST_ID_CHARS URL-safe characters"
            )
        }
        if (envelope.optString("protocol") != PROTOCOL) {
            return failure(requestId, "UNSUPPORTED_PROTOCOL", "protocol must equal $PROTOCOL")
        }

        val tool = envelope.optString("tool").trim()
        if (tool.isBlank() || tool.length > MAX_TOOL_CHARS || !toolPattern.matches(tool)) {
            return failure(requestId, "INVALID_TOOL", "tool is missing or contains unsupported characters")
        }
        val rawArgs = envelope.opt("args")
        if (rawArgs != null && rawArgs != JSONObject.NULL && rawArgs !is JSONObject) {
            return failure(requestId, "INVALID_ARGS", "args must be a JSON object")
        }
        return TriggerCmdBridgeDecodeResult.Success(
            TriggerCmdBridgeRequest(requestId, tool, rawArgs as? JSONObject ?: JSONObject())
        )
    }

    fun accepted(request: TriggerCmdBridgeRequest): String =
        progressResponse(request, "accepted")

    fun running(request: TriggerCmdBridgeRequest): String =
        progressResponse(request, "running")

    fun completed(request: TriggerCmdBridgeRequest, result: JSONObject): String {
        val ok = if (result.has("success")) result.optBoolean("success", false) else true
        val response = JSONObject()
            .put("protocol", PROTOCOL)
            .put("request_id", request.requestId)
            .put("status", "completed")
            .put("ok", ok)
            .put("tool", request.tool)
            .put("result", result)
        if (!ok) {
            response.put(
                "code",
                result.optString("error_code").ifBlank { "EXECUTION_FAILED" }
            )
        }
        return response.toString()
    }

    fun executionError(request: TriggerCmdBridgeRequest, message: String): String =
        errorResponse(request.requestId, "EXECUTION_ERROR", message, "failed")

    fun requestIdConflict(requestId: String): String =
        errorResponse(
            requestId,
            "REQUEST_ID_CONFLICT",
            "request_id was already used for a different bridge request",
            "rejected"
        )

    fun bridgeBusy(requestId: String): String =
        errorResponse(
            requestId,
            "BRIDGE_BUSY",
            "Too many structured TRIGGERcmd requests are currently running",
            "rejected"
        )

    fun decodeFailure(failure: TriggerCmdBridgeDecodeResult.Failure): String =
        errorResponse(failure.requestId, failure.code, failure.message, "rejected")

    private fun progressResponse(request: TriggerCmdBridgeRequest, status: String): String =
        JSONObject()
            .put("protocol", PROTOCOL)
            .put("request_id", request.requestId)
            .put("status", status)
            .put("ok", true)
            .put("tool", request.tool)
            .put("retry_after_ms", RETRY_AFTER_MS)
            .toString()

    private fun failure(
        requestId: String?,
        code: String,
        message: String
    ): TriggerCmdBridgeDecodeResult.Failure =
        TriggerCmdBridgeDecodeResult.Failure(requestId, code, message)

    private fun errorResponse(
        requestId: String?,
        code: String,
        message: String,
        status: String
    ): String =
        JSONObject()
            .put("protocol", PROTOCOL)
            .put("request_id", requestId ?: JSONObject.NULL)
            .put("status", status)
            .put("ok", false)
            .put("code", code)
            .put("message", message.take(500))
            .toString()

    private fun decodeBase64(encoded: String): String? =
        runCatching {
            String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
        }.getOrNull()
}
