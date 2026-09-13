package com.ai.limbs.extensions.sentinelx.runtime

import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant

data class SentinelXBridgeRequest(
    val requestId: String,
    val tool: String,
    val args: JSONObject
)

internal object SentinelXProtocol {
    const val PROTOCOL_VERSION = "1.10.0"
    const val AGENT_VERSION = "0.1.0"
    const val BRIDGE_PREFIX = "AIL_SENTINEL_BRIDGE_V1 "

    fun webSocketUrl(hubUrl: String): String {
        val base = hubUrl.trim().trimEnd('/')
        val socketBase = when {
            base.startsWith("https://") -> "wss://${base.removePrefix("https://")}"
            base.startsWith("http://") -> "ws://${base.removePrefix("http://")}"
            else -> error("Unsupported SentinelX Hub URL: $hubUrl")
        }
        return "$socketBase/agent/connect"
    }

    fun hello(config: SentinelXBridgeConfig): JSONObject = JSONObject()
        .put("type", "hello")
        .put("protocol_version", PROTOCOL_VERSION)
        .put("agent_version", AGENT_VERSION)
        .put("agent_name", "ai-limbs-sentinelx")
        .put(
            "host",
            JSONObject()
                .put("id", config.hostId)
                .put("hostname", config.deviceName)
                .put("os", "android")
                .put("kernel", Build.VERSION.RELEASE)
                .put("arch", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                .put("machine_type", Build.MODEL)
        )
        .put("capabilities", JSONArray(listOf("ping", "capabilities", "state", "exec")))
        .put("preferred_profile", "compact")

    fun pong(timestamp: String?): JSONObject = JSONObject()
        .put("type", "pong")
        .put("timestamp", timestamp?.takeIf { it.isNotBlank() } ?: Instant.now().toString())

    fun success(id: String, result: JSONObject): JSONObject = JSONObject()
        .put("type", "response")
        .put("id", id)
        .put("ok", true)
        .put("result", result)

    fun failure(id: String, code: String, message: String): JSONObject = JSONObject()
        .put("type", "response")
        .put("id", id)
        .put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", message))

    fun capabilities(config: SentinelXBridgeConfig): JSONObject = JSONObject()
        .put("agent", "ai-limbs-sentinelx")
        .put("version", AGENT_VERSION)
        .put("host_label", config.deviceName)
        .put("supported_ops", JSONArray(listOf("ping", "capabilities", "state", "exec")))
        .put(
            "bridge",
            JSONObject()
                .put("provider", "SentinelX")
                .put("transport", "ai_limbs.bridge.remote.invoke")
                .put("command_prefix", BRIDGE_PREFIX.trim())
                .put("authorization", "AI Limbs Policy Engine / Dispatcher")
        )

    fun state(config: SentinelXBridgeConfig): JSONObject = JSONObject()
        .put("hostname", config.deviceName)
        .put("kernel", Build.VERSION.RELEASE)
        .put("arch", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        .put("machine_type", Build.MODEL)
        .put("platform", "android")
        .put("host_id", config.hostId)

    fun decodeBridgeCommand(command: String): SentinelXBridgeRequest {
        require(command.startsWith(BRIDGE_PREFIX)) { "command 必须使用 $BRIDGE_PREFIX 协议头" }
        val encoded = command.removePrefix(BRIDGE_PREFIX).trim()
        require(encoded.isNotBlank()) { "bridge request payload 为空" }
        val decoded = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val payload = JSONObject(String(decoded, StandardCharsets.UTF_8))
        val tool = payload.optString("tool").trim()
        require(tool.isNotBlank()) { "bridge request 缺少 tool" }
        val args = payload.optJSONObject("args") ?: JSONObject()
        val requestId = payload.optString("request_id").trim()
        return SentinelXBridgeRequest(requestId, tool, args)
    }
}
