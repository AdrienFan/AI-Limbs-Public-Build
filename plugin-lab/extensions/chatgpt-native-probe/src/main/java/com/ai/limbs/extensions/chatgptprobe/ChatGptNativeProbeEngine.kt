package com.ai.limbs.extensions.chatgptprobe

import com.ai.limbs.plugin.runtime.ChildExtensionHost
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal data class ControlPlaneProbeResult(
    val success: Boolean,
    val phase: String,
    val detail: String,
    val statusCode: Int? = null,
    val commandCount: Int = 0,
    val serviceRequestId: String? = null,
    val durationMs: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("success", success)
        .put("phase", phase)
        .put("detail", detail)
        .put("transport", "ANDROID_OKHTTP_LONG_POLL")
        .put("status_code", statusCode ?: JSONObject.NULL)
        .put("command_count", commandCount)
        .put("service_request_id", serviceRequestId ?: JSONObject.NULL)
        .put("runtime_key_present", true)
        .put("runtime_key_exposed", false)
        .put("duration_ms", durationMs)
        .put("wire_protocol_version", WIRE_PROTOCOL_VERSION)
        .put("control_plane_default", ChatGptNativeProbeStorage.DEFAULT_BASE_URL)

    companion object {
        const val WIRE_PROTOCOL_VERSION = "2026-08-25"
    }
}

internal class ChatGptNativeProbeEngine(
    private val host: ChildExtensionHost
) {
    private val storage = ChatGptNativeProbeStorage(host.applicationContext)
    private val mutex = Mutex()
    private val mutableLastResult = MutableStateFlow<ControlPlaneProbeResult?>(null)
    private val instanceId = newInstanceId()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    val lastResult: StateFlow<ControlPlaneProbeResult?> = mutableLastResult

    suspend fun runProbe(): ControlPlaneProbeResult = mutex.withLock {
        val started = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) {
            try {
                val config = storage.readConfig()
                require(config.secureStorageAvailable) { "Android secure storage is unavailable" }
                require(config.configured) { "Tunnel ID / Runtime API Key 尚未配置" }
                val apiKey = storage.readApiKey()
                    ?: error("Runtime API Key is unavailable")

                val url = config.baseUrl.toHttpUrl().newBuilder()
                    .addPathSegments("v1/tunnels")
                    .addPathSegment(config.tunnelId)
                    .addPathSegment("poll")
                    .addQueryParameter("limit", "1")
                    .addQueryParameter("timeout_ms", POLL_TIMEOUT_MS.toString())
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", "Bearer $apiKey")
                    .header("Accept", "application/json")
                    .header("User-Agent", "AI-Limbs-ChatGPT/$PROBE_VERSION")
                    .header("X-Tunnel-Client-Name", "ai-limbs-chatgpt")
                    .header("X-Tunnel-Client-Version", PROBE_VERSION)
                    .header("X-Tunnel-Client-Wire-Protocol-Version", ControlPlaneProbeResult.WIRE_PROTOCOL_VERSION)
                    .header("X-Tunnel-Client-Instance-Id", instanceId)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val serviceRequestId = response.header("x-request-id")
                        ?: response.header("X-Request-Id")
                        ?: response.header("X-Tunnel-Service-Request-Id")

                    when (response.code) {
                        204 -> ControlPlaneProbeResult(
                            success = true,
                            phase = "CONTROL_PLANE_OK",
                            detail = "OpenAI Tunnel poll authenticated successfully; no queued commands.",
                            statusCode = response.code,
                            commandCount = 0,
                            serviceRequestId = serviceRequestId,
                            durationMs = System.currentTimeMillis() - started
                        )
                        200 -> {
                            val body = response.body?.string().orEmpty()
                            val envelope = JSONObject(body)
                            val count = envelope.optJSONArray("commands")?.length() ?: 0
                            ControlPlaneProbeResult(
                                success = true,
                                phase = "CONTROL_PLANE_OK",
                                detail = "OpenAI Tunnel poll authenticated successfully; received $count queued command(s). Probe does not respond to MCP work.",
                                statusCode = response.code,
                                commandCount = count,
                                serviceRequestId = serviceRequestId,
                                durationMs = System.currentTimeMillis() - started
                            )
                        }
                        else -> {
                            val body = response.body?.string().orEmpty()
                            val safeError = runCatching {
                                val json = JSONObject(body)
                                json.optString("error", json.optString("message", ""))
                            }.getOrDefault("").take(240)
                            ControlPlaneProbeResult(
                                success = false,
                                phase = "CONTROL_PLANE_HTTP_ERROR",
                                detail = buildString {
                                    append("OpenAI Tunnel poll returned HTTP ${response.code}")
                                    if (safeError.isNotBlank()) append(": $safeError")
                                },
                                statusCode = response.code,
                                serviceRequestId = serviceRequestId,
                                durationMs = System.currentTimeMillis() - started
                            )
                        }
                    }
                }
            } catch (error: Throwable) {
                ControlPlaneProbeResult(
                    success = false,
                    phase = "CONTROL_PLANE_EXCEPTION",
                    detail = "${error::class.java.simpleName}: ${error.message ?: "unknown error"}",
                    durationMs = System.currentTimeMillis() - started
                )
            }
        }

        mutableLastResult.value = result
        host.logger.i(TAG, "Control-plane probe finished: ${result.phase}; status=${result.statusCode}")
        result
    }

    fun statusJson(): JSONObject {
        val config = storage.readConfig()
        return JSONObject()
            .put("configured", config.configured)
            .put("secure_storage_available", config.secureStorageAvailable)
            .put("tunnel_id_present", config.tunnelId.isNotBlank())
            .put("base_url", config.baseUrl)
            .put("runtime_key_exposed", false)
            .put("last_result", mutableLastResult.value?.toJson() ?: JSONObject.NULL)
    }

    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun newInstanceId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PROBE_VERSION = "0.0.4"
        private const val POLL_TIMEOUT_MS = 5_000L
        private const val TAG = "ChatGptControlPlaneProbe"
    }
}
