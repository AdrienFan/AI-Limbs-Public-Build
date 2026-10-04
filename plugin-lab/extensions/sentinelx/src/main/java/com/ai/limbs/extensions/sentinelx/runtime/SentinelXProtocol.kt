package com.ai.limbs.extensions.sentinelx.runtime

import android.os.Build
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class SentinelXBridgeRequest(
    val requestId: String,
    val tool: String,
    val args: JSONObject
)

internal object SentinelXProtocol {
    const val PROTOCOL_VERSION = "1.10.0"
    const val AGENT_VERSION = "0.1.12"
    const val BRIDGE_PREFIX = "AIL_SENTINEL_BRIDGE_V1 "

    private val supportedOps =
        listOf(
            "ping",
            "capabilities",
            "state",
            "exec",
            "help",
            "file_export_init",
            "file_export_chunk",
            "file_export_complete"
        ) + SentinelXOfficialOps.NATIVE_OPS

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
        .put("capabilities", JSONArray(supportedOps))
        .put("preferred_profile", "compact")

    fun pong(timestamp: String?): JSONObject = JSONObject()
        .put("type", "pong")
        .put("timestamp", timestamp?.takeIf { it.isNotBlank() } ?: Instant.now().toString())

    fun success(id: String, result: JSONObject): JSONObject = JSONObject()
        .put("type", "response")
        .put("id", id)
        .put("ok", true)
        .put("result", result)

    fun failure(
        id: String,
        code: String,
        message: String,
        details: JSONObject? = null
    ): JSONObject {
        val error = JSONObject().put("code", code).put("message", message)
        if (details != null && details.length() > 0) error.put("details", details)
        return JSONObject()
            .put("type", "response")
            .put("id", id)
            .put("ok", false)
            .put("error", error)
    }

    fun capabilities(config: SentinelXBridgeConfig): JSONObject = JSONObject()
        .put("agent", "ai-limbs-sentinelx")
        .put("version", AGENT_VERSION)
        .put("host_label", config.deviceName)
        .put("supported_ops", JSONArray(supportedOps))
        .put("execution_targets", SentinelXEnvironmentResolver.describe())
        .put(
            "native_adapter",
            JSONObject()
                .put("policy_authority", "AI Limbs Dispatcher / Policy Engine")
                .put("linux_command_provider", SentinelXOfficialOps.SYSTEM_ENVIRONMENT_COMMAND)
                .put("bridge_exec", "AIL_SENTINEL_BRIDGE_V1 <JSON>")
        )
        .put("media", mediaCapabilities())
        .put(
            "bridge",
            JSONObject()
                .put("provider", "SentinelX")
                .put("transport", "ai_limbs.bridge.remote.invoke")
                .put("command_prefix", BRIDGE_PREFIX.trim())
                .put("command_format", "AIL_SENTINEL_BRIDGE_V1 <JSON>")
                .put("authorization", "AI Limbs Policy Engine / Dispatcher")
        )

    fun help(topic: String): JSONObject = JSONObject()
        .put("ok", true)
        .put("agent", "ai-limbs-sentinelx")
        .put("topic", topic)
        .put("supported_ops", JSONArray(supportedOps))
        .put("scope", "Android bridge child with AI Limbs Host/System Environment native-op adapters")
        .put("execution_targets", SentinelXEnvironmentResolver.describe())
        .put("media", mediaCapabilities())
        .put("bridge_command", "AIL_SENTINEL_BRIDGE_V1 <JSON>")
        .put("bridge_payload", JSONObject().put("tool", "capability name").put("args", JSONObject()))
        .put(
            "result_paging",
            "For a paged exec result, call exec again with tool=ai_limbs.bridge.result_page and args={cursor,offset}; concatenate output pages and verify sha256"
        )
        .put("authorization", "AI Limbs Dispatcher / Policy Engine")
        .put(
            "native_ops",
            "read/list/search/edit route to Host file capabilities; script_run routes to the active Linux System Environment"
        )
        .put(
            "unsupported_native_ops",
            "service/restart are intentionally not advertised: the current AI Limbs Linux environment has no systemd/service-manager capability to map faithfully"
        )
        .put(
            "exec_semantics",
            "exec remains the generic AI Limbs capability tunnel and requires AIL_SENTINEL_BRIDGE_V1 <JSON>"
        )
        .put("file_export_scope", "file_export is restricted to admitted result-media handles")

    private fun mediaCapabilities(): JSONObject = JSONObject()
        .put("native_tool", "sentinel_read_media")
        .put("virtual_read_prefix", SentinelXMediaStore.MEDIA_PREFIX)
        .put("source", "Images attached to previously authorized AI Limbs tool results")
        .put("formats", JSONArray().put("image/png").put("image/jpeg"))
        .put("inline_limit_bytes", 96 * 1024)
        .put("control_frame_budget_bytes", 120 * 1024)
        .put("max_encoded_media_bytes", 2 * 1024 * 1024)
        .put("max_image_edge", 8192)
        .put("max_image_pixels", 32 * 1024 * 1024)
        .put("cache_limit_bytes", 4 * 1024 * 1024)
        .put("max_attachments", 16)
        .put("max_exports", 4)
        .put("ttl_seconds", 600)
        .put("chunk_bytes", SentinelXMediaStore.MAX_CHUNK_BYTES)
        .put("binary_header", "16-byte transfer_id + 4-byte big-endian chunk_index")
        .put("filesystem_access", false)
        .put(
            "usage",
            "exec results include media_attachments; sentinel_read_media(path) reads existing image bytes without replaying the source tool"
        )

    fun state(config: SentinelXBridgeConfig): JSONObject = JSONObject()
        .put("hostname", config.deviceName)
        .put("kernel", Build.VERSION.RELEASE)
        .put("arch", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        .put("machine_type", Build.MODEL)
        .put("platform", "android")
        .put("host_id", config.hostId)
        .put("execution_targets", SentinelXEnvironmentResolver.describe())

    fun decodeBridgeCommand(command: String): SentinelXBridgeRequest {
        require(command.startsWith(BRIDGE_PREFIX)) { "command 必须使用 $BRIDGE_PREFIX 协议头" }
        val body = command.removePrefix(BRIDGE_PREFIX).trim()
        require(body.isNotBlank()) { "bridge request payload 为空" }
        val payload = if (body.startsWith("{")) {
            JSONObject(body)
        } else {
            // v0.1.0 compatibility: old clients may still send Base64URL JSON.
            val decoded = Base64.decode(body, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            JSONObject(String(decoded, StandardCharsets.UTF_8))
        }
        val tool = payload.optString("tool").trim()
        require(tool.isNotBlank()) { "bridge request 缺少 tool" }
        val args = payload.optJSONObject("args") ?: JSONObject()
        val requestId = payload.optString("request_id").trim()
        return SentinelXBridgeRequest(requestId, tool, args)
    }
}
