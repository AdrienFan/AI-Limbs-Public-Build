package com.ai.limbs.extensions.chatgptprobe

import com.ai.limbs.plugin.runtime.ChildExtensionHost
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class McpEchoProbeStatus(
    val success: Boolean,
    val phase: String,
    val detail: String,
    val running: Boolean,
    val statusCode: Int? = null,
    val polledCommandCount: Int = 0,
    val handledCommandCount: Int = 0,
    val postedResponseCount: Int = 0,
    val lastMethod: String? = null,
    val serviceRequestId: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("success", success)
        .put("phase", phase)
        .put("detail", detail)
        .put("running", running)
        .put("transport", "ANDROID_OKHTTP_MCP_LOOP")
        .put("status_code", statusCode ?: JSONObject.NULL)
        .put("polled_command_count", polledCommandCount)
        .put("handled_command_count", handledCommandCount)
        .put("posted_response_count", postedResponseCount)
        .put("last_method", lastMethod ?: JSONObject.NULL)
        .put("service_request_id", serviceRequestId ?: JSONObject.NULL)
        .put("runtime_key_exposed", false)
        .put("wire_protocol_version", WIRE_PROTOCOL_VERSION)

    companion object {
        const val WIRE_PROTOCOL_VERSION = "2026-08-25"
    }
}

internal class ChatGptNativeProbeEngine(
    private val host: ChildExtensionHost
) {
    private val storage = ChatGptNativeProbeStorage(host.applicationContext)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableLastResult = MutableStateFlow<McpEchoProbeStatus?>(null)
    private val instanceId = newInstanceId()
    private val lifecycleLock = Any()

    @Volatile
    private var loopJob: Job? = null

    private var polledCommandCount = 0
    private var handledCommandCount = 0
    private var postedResponseCount = 0
    private var lastMethod: String? = null
    private var lastServiceRequestId: String? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    val lastResult: StateFlow<McpEchoProbeStatus?> = mutableLastResult

    fun isRunning(): Boolean = loopJob?.isActive == true

    fun startResponder(): Boolean = startLoop()

    fun stopResponder(reason: String) {
        stopLoop()
        publish(
            success = true,
            phase = "MCP_LISTENER_STOPPED",
            detail = reason,
            running = false
        )
    }

    fun startLoop(): Boolean = synchronized(lifecycleLock) {
        if (loopJob?.isActive == true) return@synchronized false
        loopJob = engineScope.launch { runLoop() }
        true
    }

    fun stopLoop() {
        synchronized(lifecycleLock) {
            loopJob?.cancel()
            loopJob = null
        }
        httpClient.dispatcher.cancelAll()
        publish(
            success = true,
            phase = "MCP_LISTENER_STOPPED",
            detail = "MCP Echo Probe listener stopped.",
            running = false
        )
    }

    suspend fun startAndAwaitReady(): McpEchoProbeStatus {
        val started = startLoop()
        if (!started) {
            mutableLastResult.value?.let { return it }
        }
        return withTimeoutOrNull(START_WAIT_MS) {
            lastResult.filterNotNull().first {
                it.phase == "MCP_LISTENER_ONLINE" || it.phase == "MCP_LISTENER_ERROR"
            }
        } ?: McpEchoProbeStatus(
            success = isRunning(),
            phase = if (isRunning()) "MCP_LISTENER_STARTING" else "MCP_LISTENER_ERROR",
            detail = if (isRunning()) {
                "Listener started; waiting for first OpenAI long-poll result."
            } else {
                "Listener did not start."
            },
            running = isRunning(),
            polledCommandCount = polledCommandCount,
            handledCommandCount = handledCommandCount,
            postedResponseCount = postedResponseCount,
            lastMethod = lastMethod,
            serviceRequestId = lastServiceRequestId
        )
    }

    fun statusJson(): JSONObject {
        val config = storage.readConfig()
        return JSONObject()
            .put("configured", config.configured)
            .put("secure_storage_available", config.secureStorageAvailable)
            .put("tunnel_id_present", config.tunnelId.isNotBlank())
            .put("base_url", config.baseUrl)
            .put("running", isRunning())
            .put("runtime_key_exposed", false)
            .put("last_result", mutableLastResult.value?.toJson() ?: JSONObject.NULL)
    }

    fun close() {
        stopLoop()
        engineScope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private suspend fun runLoop() {
        publish(
            success = true,
            phase = "MCP_LISTENER_STARTING",
            detail = "Starting Android/Kotlin MCP long-poll listener.",
            running = true
        )

        var retryDelayMs = 1_000L

        while (currentCoroutineContext().isActive) {
            try {
                val config = storage.readConfig()
                require(config.secureStorageAvailable) { "Android secure storage is unavailable" }
                require(config.configured) { "Tunnel ID / Runtime API Key 尚未配置" }
                val apiKey = storage.readApiKey()
                    ?: error("Runtime API Key is unavailable")

                val poll = pollOnce(config, apiKey)
                lastServiceRequestId = poll.serviceRequestId

                if (poll.statusCode == 204) {
                    retryDelayMs = 1_000L
                    publish(
                        success = true,
                        phase = "MCP_LISTENER_ONLINE",
                        detail = "OpenAI Tunnel listener online; no queued MCP commands.",
                        running = true,
                        statusCode = 204
                    )
                    continue
                }

                if (poll.statusCode != 200) {
                    val fatal = poll.statusCode in setOf(400, 401, 403, 404)
                    publish(
                        success = false,
                        phase = "MCP_LISTENER_ERROR",
                        detail = "OpenAI Tunnel poll returned HTTP ${poll.statusCode}${poll.safeErrorSuffix()}",
                        running = !fatal,
                        statusCode = poll.statusCode
                    )
                    if (fatal) break
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(10_000L)
                    continue
                }

                retryDelayMs = 1_000L
                polledCommandCount += poll.commands.length()

                for (index in 0 until poll.commands.length()) {
                    val command = poll.commands.optJSONObject(index) ?: continue
                    processCommand(config, apiKey, command)
                }

                publish(
                    success = true,
                    phase = "MCP_LISTENER_ONLINE",
                    detail = "Handled ${poll.commands.length()} queued command(s); listener remains online.",
                    running = true,
                    statusCode = 200
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                publish(
                    success = false,
                    phase = "MCP_LISTENER_ERROR",
                    detail = "${error::class.java.simpleName}: ${error.message ?: "unknown error"}",
                    running = true
                )
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(10_000L)
            }
        }
    }

    private data class PollResult(
        val statusCode: Int,
        val commands: JSONArray,
        val serviceRequestId: String?,
        val safeError: String
    ) {
        fun safeErrorSuffix(): String = if (safeError.isBlank()) "" else ": $safeError"
    }

    private fun pollOnce(config: ChatGptProbeConfig, apiKey: String): PollResult {
        val url = config.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1/tunnels")
            .addPathSegment(config.tunnelId)
            .addPathSegment("poll")
            .addQueryParameter("limit", POLL_LIMIT.toString())
            .addQueryParameter("timeout_ms", POLL_TIMEOUT_MS.toString())
            .build()

        val request = commonRequestBuilder(url.toString(), apiKey)
            .get()
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val serviceRequestId = response.header("x-request-id")
                ?: response.header("X-Request-Id")
                ?: response.header("X-Tunnel-Service-Request-Id")

            if (response.code == 204) {
                return PollResult(204, JSONArray(), serviceRequestId, "")
            }

            val body = response.body?.string().orEmpty()
            if (response.code != 200) {
                val safeError = runCatching {
                    val json = JSONObject(body)
                    json.optString("error", json.optString("message", ""))
                }.getOrDefault("").take(240)
                return PollResult(response.code, JSONArray(), serviceRequestId, safeError)
            }

            val envelope = JSONObject(body)
            return PollResult(
                statusCode = 200,
                commands = envelope.optJSONArray("commands") ?: JSONArray(),
                serviceRequestId = serviceRequestId,
                safeError = ""
            )
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
        val channel = command.optString("channel", DEFAULT_CHANNEL).trim()
            .ifBlank { DEFAULT_CHANNEL }

        require(requestId.isNotBlank()) { "Tunnel command request_id is missing" }
        require(shardToken.isNotBlank()) { "Tunnel command shard_token is missing" }

        when (commandType) {
            "jsonrpc" -> {
                val rpc = command.optJSONObject("jsonrpc")
                    ?: error("Tunnel jsonrpc command payload is missing")
                val method = rpc.optString("method").trim()
                lastMethod = method.ifBlank { null }

                val hasRpcId = rpc.has("id") && !rpc.isNull("id")
                if (!hasRpcId) {
                    handleNotification(method)
                    postTunnelResponse(
                        config = config,
                        apiKey = apiKey,
                        requestId = requestId,
                        shardToken = shardToken,
                        channel = channel,
                        responseType = "notify_ack",
                        responseCode = 200,
                        jsonResponse = null
                    )
                } else {
                    val rpcResponse = handleJsonRpcRequest(rpc)
                    postTunnelResponse(
                        config = config,
                        apiKey = apiKey,
                        requestId = requestId,
                        shardToken = shardToken,
                        channel = channel,
                        responseType = "jsonrpc_response",
                        responseCode = 200,
                        jsonResponse = rpcResponse
                    )
                }
                handledCommandCount += 1
            }

            "session_termination" -> {
                postTunnelResponse(
                    config = config,
                    apiKey = apiKey,
                    requestId = requestId,
                    shardToken = shardToken,
                    channel = channel,
                    responseType = "session_termination_response",
                    responseCode = 200,
                    jsonResponse = null
                )
                handledCommandCount += 1
            }

            "oauth_discovery" -> {
                val error = JSONObject()
                    .put("error", JSONObject()
                        .put("message", "AI Limbs MCP Echo Probe uses no MCP OAuth.")
                        .put("type", "not_found_error")
                        .put("code", "oauth_not_configured"))
                postTunnelResponse(
                    config = config,
                    apiKey = apiKey,
                    requestId = requestId,
                    shardToken = shardToken,
                    channel = channel,
                    responseType = "oauth_discovery_response",
                    responseCode = 404,
                    jsonResponse = error
                )
                handledCommandCount += 1
            }

            else -> error("Unsupported tunnel command_type: $commandType")
        }
    }

    private fun handleNotification(method: String) {
        when (method) {
            "notifications/initialized",
            "notifications/cancelled",
            "notifications/progress" -> Unit
            else -> Unit
        }
    }

    private fun handleJsonRpcRequest(request: JSONObject): JSONObject {
        val id = request.opt("id")
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()

        val result = when (method) {
            "initialize" -> initializeResult(params)
            "ping" -> JSONObject()
            "tools/list" -> listToolsResult()
            "tools/call" -> callToolResult(params)
            else -> return jsonRpcError(
                id = id,
                code = -32601,
                message = "Method not found: $method"
            )
        }

        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
    }

    private fun initializeResult(params: JSONObject): JSONObject {
        val requestedProtocol = params.optString("protocolVersion").trim()
        val negotiatedProtocol = if (
            requestedProtocol in SUPPORTED_MCP_PROTOCOL_VERSIONS &&
            requestedProtocol < MCP_LATEST_PROTOCOL_VERSION
        ) {
            requestedProtocol
        } else {
            DEFAULT_MCP_PROTOCOL_VERSION
        }

        return JSONObject()
            .put("protocolVersion", negotiatedProtocol)
            .put("capabilities", JSONObject()
                .put("tools", JSONObject()))
            .put("serverInfo", JSONObject()
                .put("name", "ai-limbs-chatgpt-mcp-probe")
                .put("title", "AI Limbs ChatGPT MCP Echo Probe")
                .put("version", PROBE_VERSION))
            .put(
                "instructions",
                "Test MCP server hosted directly inside the AI Limbs Android child extension."
            )
    }

    private fun listToolsResult(): JSONObject = JSONObject()
        .put("tools", JSONArray()
            .put(toolDefinition(
                name = "ail_ping",
                title = "AI Limbs Ping",
                description = "Verifies the direct ChatGPT ↔ OpenAI Tunnel ↔ AI Limbs Android MCP path.",
                inputSchema = emptyInputSchema()
            ))
            .put(toolDefinition(
                name = "server_info",
                title = "Server Info",
                description = "Returns information about the AI Limbs MCP Echo Probe.",
                inputSchema = emptyInputSchema()
            ))
            .put(toolDefinition(
                name = "echo",
                title = "Echo",
                description = "Echoes the supplied input string.",
                inputSchema = stringInputSchema()
            ))
            .put(toolDefinition(
                name = "uppercase",
                title = "Uppercase",
                description = "Converts the supplied input string to uppercase.",
                inputSchema = stringInputSchema()
            )))

    private fun callToolResult(params: JSONObject): JSONObject {
        val name = params.optString("name").trim()
        val arguments = params.optJSONObject("arguments") ?: JSONObject()

        val structured = when (name) {
            "ail_ping" -> JSONObject()
                .put("message", "hello from AI Limbs")
                .put("transport", "ANDROID_OKHTTP_MCP_LOOP")
                .put("ubuntu_required", false)
                .put("version", PROBE_VERSION)

            "server_info" -> JSONObject()
                .put("name", "ai-limbs-chatgpt-mcp-probe")
                .put("version", PROBE_VERSION)
                .put("transport", "ANDROID_OKHTTP_MCP_LOOP")
                .put("ubuntu_required", false)
                .put("available_tools", JSONArray()
                    .put("ail_ping")
                    .put("server_info")
                    .put("echo")
                    .put("uppercase"))

            "echo" -> {
                val input = readInput(arguments)
                JSONObject().put("echoed", input)
            }

            "uppercase" -> {
                val input = readInput(arguments)
                JSONObject().put("uppercase", input.uppercase())
            }

            else -> return JSONObject()
                .put("content", JSONArray()
                    .put(textContent("Unknown tool: $name")))
                .put("isError", true)
        }

        return JSONObject()
            .put("content", JSONArray()
                .put(textContent(structured.toString())))
            .put("structuredContent", structured)
    }

    private fun readInput(arguments: JSONObject): String =
        arguments.optString("input").takeIf { it.isNotBlank() }
            ?: arguments.optString("message")

    private fun toolDefinition(
        name: String,
        title: String,
        description: String,
        inputSchema: JSONObject
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("title", title)
        .put("description", description)
        .put("inputSchema", inputSchema)

    private fun emptyInputSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())
        .put("additionalProperties", false)

    private fun stringInputSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("input", JSONObject()
                .put("type", "string")
                .put("description", "Input text")))
        .put("required", JSONArray().put("input"))
        .put("additionalProperties", false)

    private fun textContent(text: String): JSONObject = JSONObject()
        .put("type", "text")
        .put("text", text)

    private fun jsonRpcError(id: Any?, code: Int, message: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject()
                .put("code", code)
                .put("message", message))

    private fun postTunnelResponse(
        config: ChatGptProbeConfig,
        apiKey: String,
        requestId: String,
        shardToken: String,
        channel: String,
        responseType: String,
        responseCode: Int,
        jsonResponse: JSONObject?
    ) {
        val url = config.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1/tunnels")
            .addPathSegment(config.tunnelId)
            .addPathSegment("response")
            .build()

        val payload = JSONObject()
            .put("request_id", requestId)
            .put("channel", channel)
            .put("resp_headers", if (jsonResponse != null) {
                JSONObject().put(
                    "Content-Type",
                    JSONArray().put("application/json")
                )
            } else {
                JSONObject()
            })
            .put("resp_code", responseCode)
            .put("resp_type", responseType)

        if (jsonResponse != null) {
            payload.put("resp_json", jsonResponse)
        }

        val request = commonRequestBuilder(url.toString(), apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("X-Tunnel-Shard-Token", shardToken)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val serviceRequestId = response.header("x-request-id")
                ?: response.header("X-Request-Id")
                ?: response.header("X-Tunnel-Service-Request-Id")
            if (!serviceRequestId.isNullOrBlank()) {
                lastServiceRequestId = serviceRequestId
            }

            if (response.code != 200 && response.code != 404) {
                val safeBody = response.body?.string().orEmpty().take(240)
                error("POST /response failed HTTP ${response.code}: $safeBody")
            }
        }

        postedResponseCount += 1
    }

    private fun commonRequestBuilder(url: String, apiKey: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", "AI-Limbs-ChatGPT/$PROBE_VERSION")
            .header("X-Tunnel-Client-Name", "ai-limbs-chatgpt")
            .header("X-Tunnel-Client-Version", PROBE_VERSION)
            .header(
                "X-Tunnel-Client-Wire-Protocol-Version",
                McpEchoProbeStatus.WIRE_PROTOCOL_VERSION
            )
            .header("X-Tunnel-Client-Instance-Id", instanceId)

    private fun publish(
        success: Boolean,
        phase: String,
        detail: String,
        running: Boolean,
        statusCode: Int? = null
    ) {
        val status = McpEchoProbeStatus(
            success = success,
            phase = phase,
            detail = detail,
            running = running,
            statusCode = statusCode,
            polledCommandCount = polledCommandCount,
            handledCommandCount = handledCommandCount,
            postedResponseCount = postedResponseCount,
            lastMethod = lastMethod,
            serviceRequestId = lastServiceRequestId
        )
        mutableLastResult.value = status
        host.logger.i(
            TAG,
            "MCP probe status: $phase running=$running polled=$polledCommandCount handled=$handledCommandCount posted=$postedResponseCount method=${lastMethod ?: "-"}"
        )
    }

    private fun newInstanceId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PROBE_VERSION = "0.0.5"
        private const val POLL_LIMIT = 4
        private const val POLL_TIMEOUT_MS = 20_000L
        private const val START_WAIT_MS = 12_000L
        private const val DEFAULT_CHANNEL = "main"
        private const val DEFAULT_MCP_PROTOCOL_VERSION = "2025-11-25"
        private const val MCP_LATEST_PROTOCOL_VERSION = "2026-07-28"
        private const val TAG = "ChatGptMcpEchoProbe"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val SUPPORTED_MCP_PROTOCOL_VERSIONS = setOf(
            "2026-07-28",
            "2025-11-25",
            "2025-06-18",
            "2025-03-26",
            "2024-11-05"
        )
    }
}
