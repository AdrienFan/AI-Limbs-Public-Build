package com.ai.limbs.extensions.chatgptprobe

import com.ai.limbs.plugin.runtime.ChildExtensionHost
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class McpEchoProbeState(
    val running: Boolean = false,
    val phase: String = "STOPPED",
    val detail: String = "MCP Echo Probe 已停止",
    val pollCount: Long = 0,
    val commandCount: Long = 0,
    val responseCount: Long = 0,
    val lastMethod: String? = null,
    val lastStatusCode: Int? = null,
    val lastServiceRequestId: String? = null,
    val lastError: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("running", running)
        .put("phase", phase)
        .put("detail", detail)
        .put("transport", "ANDROID_OKHTTP_MCP_TUNNEL")
        .put("poll_count", pollCount)
        .put("command_count", commandCount)
        .put("response_count", responseCount)
        .put("last_method", lastMethod ?: JSONObject.NULL)
        .put("last_status_code", lastStatusCode ?: JSONObject.NULL)
        .put("last_service_request_id", lastServiceRequestId ?: JSONObject.NULL)
        .put("last_error", lastError ?: JSONObject.NULL)
        .put("runtime_key_exposed", false)
        .put("wire_protocol_version", WIRE_PROTOCOL_VERSION)
        .put("probe_version", PROBE_VERSION)

    companion object {
        const val WIRE_PROTOCOL_VERSION = "2026-08-25"
        const val PROBE_VERSION = "0.0.5"
    }
}

internal class ChatGptNativeProbeEngine(
    private val host: ChildExtensionHost
) {
    private val storage = ChatGptNativeProbeStorage(host.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(McpEchoProbeState())
    private val lifecycleLock = Any()
    private val instanceId = newInstanceId()

    @Volatile
    private var loopJob: Job? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    val state: StateFlow<McpEchoProbeState> = mutableState

    fun start(): Boolean = synchronized(lifecycleLock) {
        if (loopJob?.isActive == true) return false

        val config = storage.readConfig()
        require(config.secureStorageAvailable) { "Android secure storage is unavailable" }
        require(config.configured) { "Tunnel ID / Runtime API Key 尚未配置" }

        mutableState.value = mutableState.value.copy(
            running = true,
            phase = "STARTING",
            detail = "正在启动 Android/Kotlin MCP Tunnel listener",
            lastError = null
        )
        loopJob = scope.launch { pollLoop() }
        true
    }

    fun stop(): Boolean = synchronized(lifecycleLock) {
        val job = loopJob
        val wasRunning = job?.isActive == true
        loopJob = null
        job?.cancel()
        httpClient.dispatcher.cancelAll()
        mutableState.value = mutableState.value.copy(
            running = false,
            phase = "STOPPED",
            detail = "MCP Echo Probe 已停止"
        )
        wasRunning
    }

    fun statusJson(): JSONObject {
        val config = storage.readConfig()
        return JSONObject()
            .put("configured", config.configured)
            .put("secure_storage_available", config.secureStorageAvailable)
            .put("tunnel_id_present", config.tunnelId.isNotBlank())
            .put("base_url", config.baseUrl)
            .put("runtime_key_exposed", false)
            .put("state", mutableState.value.toJson())
    }

    fun close() {
        stop()
        scope.coroutineContext[Job]?.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private suspend fun pollLoop() {
        var retryDelayMs = INITIAL_RETRY_MS
        while (currentCoroutineContext().isActive) {
            try {
                val config = storage.readConfig()
                val apiKey = storage.readApiKey()
                    ?: error("Runtime API Key is unavailable")
                require(config.tunnelId.isNotBlank()) { "Tunnel ID is unavailable" }

                val status = pollOnce(config, apiKey)
                retryDelayMs = INITIAL_RETRY_MS
                mutableState.value = mutableState.value.copy(
                    running = true,
                    phase = "ONLINE",
                    detail = status,
                    lastError = null
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!currentCoroutineContext().isActive) return
                val safeMessage = "${error::class.java.simpleName}: ${error.message ?: "unknown error"}".take(300)
                mutableState.value = mutableState.value.copy(
                    running = true,
                    phase = "RETRYING",
                    detail = "Tunnel listener 暂时失败，准备重试",
                    lastError = safeMessage
                )
                host.logger.w(TAG, "Tunnel listener retrying: $safeMessage")
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    private fun pollOnce(config: ChatGptProbeConfig, apiKey: String): String {
        val url = config.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1/tunnels")
            .addPathSegment(config.tunnelId)
            .addPathSegment("poll")
            .addQueryParameter("limit", POLL_LIMIT.toString())
            .addQueryParameter("timeout_ms", POLL_TIMEOUT_MS.toString())
            .build()

        val request = commonHeaders(
            Request.Builder().url(url).get(),
            apiKey
        ).build()

        httpClient.newCall(request).execute().use { response ->
            val serviceRequestId = response.header("x-request-id")
                ?: response.header("X-Request-Id")
                ?: response.header("X-Tunnel-Service-Request-Id")

            mutableState.value = mutableState.value.copy(
                pollCount = mutableState.value.pollCount + 1,
                lastStatusCode = response.code,
                lastServiceRequestId = serviceRequestId
            )

            when (response.code) {
                204 -> return "Tunnel poll 正常；暂无待处理 MCP command"
                200 -> {
                    val body = response.body?.string().orEmpty()
                    val envelope = JSONObject(body)
                    val commands = envelope.optJSONArray("commands") ?: JSONArray()
                    for (index in 0 until commands.length()) {
                        val command = commands.optJSONObject(index) ?: continue
                        processCommand(config, apiKey, command)
                    }
                    return if (commands.length() == 0) {
                        "Tunnel poll 正常；返回空 command 集合"
                    } else {
                        "Tunnel poll 正常；已处理 ${commands.length()} 个 command"
                    }
                }
                else -> {
                    val body = response.body?.string().orEmpty()
                    val safeError = runCatching {
                        val json = JSONObject(body)
                        json.optString("error", json.optString("message", ""))
                    }.getOrDefault("").take(240)
                    error(
                        buildString {
                            append("OpenAI Tunnel poll HTTP ${response.code}")
                            if (safeError.isNotBlank()) append(": $safeError")
                        }
                    )
                }
            }
        }
    }

    private fun processCommand(
        config: ChatGptProbeConfig,
        apiKey: String,
        command: JSONObject
    ) {
        val requestId = command.optString("request_id").trim()
        val shardToken = command.optString("shard_token").trim()
        val commandType = command.optString("command_type").trim()
        val channel = command.optString("channel").trim().ifBlank { DEFAULT_CHANNEL }

        require(requestId.isNotBlank()) { "Polled command request_id is missing" }
        require(shardToken.isNotBlank()) { "Polled command shard_token is missing" }

        mutableState.value = mutableState.value.copy(
            commandCount = mutableState.value.commandCount + 1
        )

        when (commandType) {
            "jsonrpc" -> processJsonRpcCommand(
                config,
                apiKey,
                requestId,
                shardToken,
                channel,
                command.optJSONObject("jsonrpc")
                    ?: error("JSON-RPC command payload is missing")
            )
            "session_termination" -> postTunnelResponse(
                config,
                apiKey,
                requestId,
                shardToken,
                channel,
                200,
                "session_termination_response",
                null
            )
            else -> error("Unsupported Tunnel command_type: $commandType")
        }
    }

    private fun processJsonRpcCommand(
        config: ChatGptProbeConfig,
        apiKey: String,
        requestId: String,
        shardToken: String,
        channel: String,
        rpc: JSONObject
    ) {
        val method = rpc.optString("method").trim()
        mutableState.value = mutableState.value.copy(lastMethod = method)

        val hasValidId = rpc.has("id") && !rpc.isNull("id")
        if (!hasValidId) {
            postTunnelResponse(
                config,
                apiKey,
                requestId,
                shardToken,
                channel,
                202,
                "notify_ack",
                null
            )
            return
        }

        val id = rpc.get("id")
        val response = when (method) {
            "initialize" -> rpcSuccess(id, initializeResult(rpc.optJSONObject("params")))
            "ping" -> rpcSuccess(id, JSONObject())
            "tools/list" -> rpcSuccess(id, JSONObject().put("tools", toolDefinitions()))
            "tools/call" -> handleToolCall(id, rpc.optJSONObject("params"))
            else -> rpcError(id, -32601, "Method not found: $method")
        }

        postTunnelResponse(
            config,
            apiKey,
            requestId,
            shardToken,
            channel,
            200,
            "jsonrpc_response",
            response
        )
    }

    private fun initializeResult(params: JSONObject?): JSONObject {
        val requestedProtocol = params?.optString("protocolVersion")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK_MCP_PROTOCOL_VERSION

        return JSONObject()
            .put("protocolVersion", requestedProtocol)
            .put(
                "capabilities",
                JSONObject().put(
                    "tools",
                    JSONObject().put("listChanged", false)
                )
            )
            .put(
                "serverInfo",
                JSONObject()
                    .put("name", "ai-limbs-chatgpt-probe")
                    .put("title", "AI Limbs ChatGPT MCP Echo Probe")
                    .put("version", McpEchoProbeState.PROBE_VERSION)
            )
            .put(
                "instructions",
                "Test MCP server running directly inside AI Limbs Android child extension."
            )
    }

    private fun toolDefinitions(): JSONArray = JSONArray()
        .put(tool("ail_ping", "Return a fixed message proving ChatGPT reached AI Limbs Android.", emptyObjectSchema()))
        .put(tool("server_info", "Return information about the AI Limbs MCP Echo Probe.", emptyObjectSchema()))
        .put(tool("echo", "Echo an input string from AI Limbs.", stringInputSchema("input")))
        .put(tool("uppercase", "Convert an input string to uppercase inside AI Limbs.", stringInputSchema("input")))

    private fun handleToolCall(id: Any, params: JSONObject?): JSONObject {
        val name = params?.optString("name")?.trim().orEmpty()
        val arguments = params?.optJSONObject("arguments") ?: JSONObject()

        return when (name) {
            "ail_ping" -> {
                val structured = JSONObject()
                    .put("message", "hello from AI Limbs")
                    .put("runtime", "android-kotlin")
                    .put("version", McpEchoProbeState.PROBE_VERSION)
                rpcSuccess(id, toolResult("hello from AI Limbs", structured))
            }
            "server_info" -> {
                val structured = JSONObject()
                    .put("name", "ai-limbs-chatgpt-probe")
                    .put("version", McpEchoProbeState.PROBE_VERSION)
                    .put(
                        "available_tools",
                        JSONArray()
                            .put("ail_ping")
                            .put("server_info")
                            .put("echo")
                            .put("uppercase")
                    )
                rpcSuccess(id, toolResult(structured.toString(), structured))
            }
            "echo" -> {
                val input = arguments.optString("input")
                rpcSuccess(
                    id,
                    toolResult(input, JSONObject().put("echoed", input))
                )
            }
            "uppercase" -> {
                val input = arguments.optString("input")
                val upper = input.uppercase()
                rpcSuccess(
                    id,
                    toolResult(upper, JSONObject().put("uppercase", upper))
                )
            }
            else -> rpcError(id, -32602, "Unknown tool: $name")
        }
    }

    private fun toolResult(text: String, structured: JSONObject): JSONObject =
        JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", text)
                )
            )
            .put("structuredContent", structured)

    private fun tool(name: String, description: String, inputSchema: JSONObject): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", inputSchema)

    private fun emptyObjectSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())
        .put("additionalProperties", false)

    private fun stringInputSchema(field: String): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(field, JSONObject().put("type", "string"))
        )
        .put("required", JSONArray().put(field))
        .put("additionalProperties", false)

    private fun rpcSuccess(id: Any, result: JSONObject): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)

    private fun rpcError(id: Any, code: Int, message: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("message", message)
            )

    private fun postTunnelResponse(
        config: ChatGptProbeConfig,
        apiKey: String,
        requestId: String,
        shardToken: String,
        channel: String,
        responseCode: Int,
        responseType: String,
        rpcResponse: JSONObject?
    ) {
        val payload = JSONObject()
            .put("request_id", requestId)
            .put("channel", channel)
            .put("resp_code", responseCode)
            .put("resp_type", responseType)

        if (rpcResponse != null) {
            payload.put("resp_json", rpcResponse)
            payload.put(
                "resp_headers",
                JSONObject().put(
                    "Content-Type",
                    JSONArray().put("application/json")
                )
            )
        }

        val url = config.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1/tunnels")
            .addPathSegment(config.tunnelId)
            .addPathSegment("response")
            .build()

        val request = commonHeaders(
            Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)),
            apiKey
        )
            .header("Content-Type", "application/json")
            .header("X-Tunnel-Shard-Token", shardToken)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val serviceRequestId = response.header("x-request-id")
                ?: response.header("X-Request-Id")
                ?: response.header("X-Tunnel-Service-Request-Id")
            if (!response.isSuccessful) {
                val safeBody = response.body?.string().orEmpty().take(240)
                error("OpenAI Tunnel response POST HTTP ${response.code}: $safeBody")
            }
            mutableState.value = mutableState.value.copy(
                responseCount = mutableState.value.responseCount + 1,
                lastStatusCode = response.code,
                lastServiceRequestId = serviceRequestId
            )
        }
    }

    private fun commonHeaders(builder: Request.Builder, apiKey: String): Request.Builder =
        builder
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("User-Agent", "AI-Limbs-ChatGPT/${McpEchoProbeState.PROBE_VERSION}")
            .header("X-Tunnel-Client-Name", "ai-limbs-chatgpt")
            .header("X-Tunnel-Client-Version", McpEchoProbeState.PROBE_VERSION)
            .header(
                "X-Tunnel-Client-Wire-Protocol-Version",
                McpEchoProbeState.WIRE_PROTOCOL_VERSION
            )
            .header("X-Tunnel-Client-Instance-Id", instanceId)

    private fun newInstanceId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ChatGptMcpEchoProbe"
        private const val DEFAULT_CHANNEL = "main"
        private const val FALLBACK_MCP_PROTOCOL_VERSION = "2025-06-18"
        private const val POLL_LIMIT = 8
        private const val POLL_TIMEOUT_MS = 20_000L
        private const val INITIAL_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 15_000L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
