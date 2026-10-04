package com.ai.limbs.extensions.sentinelx.runtime

import com.ai.limbs.extensions.sentinelx.SentinelXLogger
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import okio.ByteString.Companion.toByteString

internal interface SentinelXTransportListener {
    fun onConnecting()
    fun onOnline(sessionId: String)
    fun onHeartbeat()
    fun onDisconnected(detail: String)
    fun onError(detail: String, error: Throwable? = null)
}

internal class SentinelXTransportClient(
    private val scope: CoroutineScope,
    private val storage: SentinelXBridgeStorage,
    private val executor: SentinelXRemoteInvocationExecutor,
    private val listener: SentinelXTransportListener
) {
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    private val resultPager = SentinelXResultPager()
    private val mediaStore = SentinelXMediaStore()
    private val officialOps = SentinelXOfficialOps(executor)
    private val pendingJobEvents = ConcurrentHashMap<String, JSONObject>()
    @Volatile var isRunning: Boolean = false
        private set

    fun connect() {
        disconnect("reconnect")
        val config = storage.readConfig()
        val token = storage.readToken()
        if (token.isNullOrBlank()) {
            listener.onError("SentinelX 尚未配置 enrollment token")
            return
        }
        val request = Request.Builder()
            .url(SentinelXProtocol.webSocketUrl(config.hubUrl))
            .header("Authorization", "Bearer $token")
            .build()
        listener.onConnecting()
        isRunning = true
        socket = httpClient.newWebSocket(request, SocketListener(config))
    }

    fun disconnect(reason: String) {
        mediaStore.clear()
        socket?.close(1000, reason.take(120))
        socket = null
        isRunning = false
    }

    private inner class SocketListener(
        private val config: SentinelXBridgeConfig
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            SentinelXLogger.i(TAG, "SentinelX WebSocket opened")
            webSocket.send(SentinelXProtocol.hello(config).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { JSONObject(text) }.getOrElse { error ->
                SentinelXLogger.w(TAG, "Invalid SentinelX JSON frame", error)
                return
            }
            when (message.optString("type")) {
                "welcome" -> {
                    listener.onOnline(message.optString("session_id"))
                    flushPendingJobEvents(webSocket)
                }
                "ping" -> {
                    webSocket.send(SentinelXProtocol.pong(message.optString("timestamp")).toString())
                    flushPendingJobEvents(webSocket)
                    listener.onHeartbeat()
                }
                "pong" -> listener.onHeartbeat()
                "request" -> scope.launch(Dispatchers.IO) { handleRequest(webSocket, message, config) }
                "error" -> listener.onError(
                    "${message.optString("code")}: ${message.optString("message")}".trim()
                )
                else -> SentinelXLogger.w(TAG, "Unsupported SentinelX frame type: ${message.optString("type")}")
            }
        }


        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            SentinelXLogger.i(TAG, "SentinelX WebSocket closing: $code ${reason.take(120)}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket !== webSocket) return
            socket = null
            isRunning = false
            mediaStore.clear()
            listener.onDisconnected("SentinelX 已断开：$code ${reason.take(120)}")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket !== webSocket) return
            socket = null
            isRunning = false
            mediaStore.clear()
            val http = response?.code?.let { "HTTP $it" }
            val detail = listOfNotNull(http, t.message).joinToString(" · ").ifBlank { "连接失败" }
            listener.onError("SentinelX $detail", t)
        }
    }

    private suspend fun handleRequest(
        webSocket: WebSocket,
        message: JSONObject,
        config: SentinelXBridgeConfig
    ) {
        val id = message.optString("id").trim()
        if (id.isBlank()) {
            SentinelXLogger.w(TAG, "Ignoring SentinelX request without id")
            return
        }
        val op = message.optString("op").trim()
        val payload = message.optJSONObject("payload") ?: JSONObject()

        if (payload.optBoolean("background", false) && op in BACKGROUND_OPS) {
            startBackgroundJob(webSocket, id, op, payload, config)
            listener.onHeartbeat()
            return
        }

        val response = executeRequest(webSocket, id, op, payload, config)
        webSocket.send(response.toString())
        listener.onHeartbeat()
    }

    private suspend fun executeRequest(
        webSocket: WebSocket,
        id: String,
        op: String,
        payload: JSONObject,
        config: SentinelXBridgeConfig
    ): JSONObject = try {
        when (op) {
            "ping" -> SentinelXProtocol.success(
                id,
                JSONObject()
                    .put("pong", true)
                    .put("agent_version", SentinelXProtocol.AGENT_VERSION)
            )
            "capabilities" -> SentinelXProtocol.success(id, SentinelXProtocol.capabilities(config))
            "state" -> SentinelXProtocol.success(id, SentinelXProtocol.state(config))
            "help" -> SentinelXProtocol.success(id, SentinelXProtocol.help(payload.optString("topic", "index")))
            "exec" -> handleExec(id, payload)
            in SentinelXOfficialOps.NATIVE_OPS ->
                SentinelXProtocol.success(id, officialOps.execute(op, payload))
            "file_export_init" -> SentinelXProtocol.success(id, mediaStore.initialize(payload))
            "file_export_chunk" -> {
                val chunk = mediaStore.chunk(payload)
                if (!webSocket.send(chunk.frame.toByteString())) {
                    throw SentinelXMediaException(
                        "binary_emit_error",
                        "WebSocket did not accept the media frame"
                    )
                }
                SentinelXProtocol.success(id, chunk.metadata)
            }
            "file_export_complete" -> SentinelXProtocol.success(id, mediaStore.complete(payload))
            else -> SentinelXProtocol.failure(
                id,
                "unsupported_op",
                "AI Limbs SentinelX transport 不实现 SentinelX op: $op"
            )
        }
    } catch (error: SentinelXMediaException) {
        SentinelXLogger.e(TAG, "SentinelX media request failed: $op", error)
        SentinelXProtocol.failure(id, error.code, error.message ?: error.code)
    } catch (error: SentinelXOpException) {
        SentinelXLogger.w(TAG, "SentinelX native op failed: $op (${error.code})", error)
        SentinelXProtocol.failure(
            id,
            error.code,
            error.message ?: error.code,
            error.details
        )
    } catch (error: Exception) {
        SentinelXLogger.e(TAG, "SentinelX request failed: $op", error)
        SentinelXProtocol.failure(
            id,
            "ai_limbs_bridge_error",
            error.message ?: error::class.java.simpleName
        )
    }

    private fun startBackgroundJob(
        webSocket: WebSocket,
        id: String,
        op: String,
        payload: JSONObject,
        config: SentinelXBridgeConfig
    ) {
        val jobId = payload.optString("job_id").trim()
            .ifBlank { "job_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        val startedAt = Instant.now()
        val ack = SentinelXProtocol.success(
            id,
            JSONObject()
                .put("status", "running")
                .put("job_id", jobId)
                .put("tool", op)
                .put("host", config.hostId)
        )
        webSocket.send(ack.toString())

        scope.launch(Dispatchers.IO) {
            val response = executeRequest(webSocket, id, op, payload, config)
            val finishedAt = Instant.now()
            val event = buildSentinelXJobCompletedEvent(
                jobId = jobId,
                op = op,
                hostId = config.hostId,
                response = response,
                startedAt = startedAt,
                finishedAt = finishedAt
            )
            pendingJobEvents[jobId] = event
            flushPendingJobEvents(socket ?: webSocket)
            listener.onHeartbeat()
        }
    }

    private fun flushPendingJobEvents(webSocket: WebSocket) {
        pendingJobEvents.entries.toList().forEach { (jobId, event) ->
            if (webSocket.send(event.toString())) {
                pendingJobEvents.remove(jobId, event)
            }
        }
    }

    private suspend fun handleExec(id: String, payload: JSONObject): JSONObject {
        val command = payload.optString("command").trim()
        if (command.isBlank()) {
            return SentinelXProtocol.failure(id, "invalid_payload", "missing non-empty command")
        }
        val startedAt = System.nanoTime()
        val bridgeRequest = try {
            SentinelXProtocol.decodeBridgeCommand(command)
        } catch (error: Exception) {
            return SentinelXProtocol.failure(
                id,
                "invalid_bridge_request",
                error.message ?: "无法解析 AI Limbs Bridge 请求"
            )
        }
        if (bridgeRequest.tool == "ai_limbs.bridge.result_page") {
            val cursor = bridgeRequest.args.optString("cursor").trim()
            val offset = bridgeRequest.args.optInt("offset", -1)
            val page = resultPager.page(cursor, offset)
                ?: return SentinelXProtocol.failure(id, "invalid_result_cursor", "Cursor expired or offset is invalid")
            return SentinelXProtocol.success(id, page
                .put("duration", (System.nanoTime() - startedAt) / 1_000_000_000.0)
                .put("returncode", 0)
                .put("request_id", bridgeRequest.requestId))
        }
        val result = executor.execute(bridgeRequest.tool, bridgeRequest.args)
        // The Host events repeat result.value; shipping them as both output and
        // bridge_result multiplies payload size before the Hub's own response cap.
        val extracted = SentinelXResultMedia.split(result)
        val compact = extracted.payload
        val images = extracted.images
        val serialized = compact.toString()
        if (!resultPager.canStore(serialized)) {
            return SentinelXProtocol.failure(id, "bridge_result_too_large",
                "Result exceeds 4 MiB; request a smaller range at the source")
        }
        val response = if (resultPager.inline(serialized)) {
            JSONObject().put("output", serialized).put("bridge_result", compact)
        } else {
            resultPager.store(serialized)
        }
        val businessSucceeded = !compact.has("error") && !compact.optBoolean("isError") &&
            (!compact.has("success") || compact.getBoolean("success"))
        if (businessSucceeded && (images.length() > 0 || extracted.errors.length() > 0)) {
            val deliveryErrors = extracted.errors
            if (images.length() > 0) {
                // Both channels carry the same immutable images from the original tool result.
                try {
                    val attachments = mediaStore.store(images)
                    val candidate = JSONObject(response.toString())
                        .put("media_attachments", attachments).put("mcp_content", images)
                        .put("duration", (System.nanoTime() - startedAt) / 1_000_000_000.0)
                        .put("returncode", 0).put("request_id", bridgeRequest.requestId)
                        .put("media_delivery", JSONObject().put("status", "available")
                            .put("operation_completed", true).put("inline", true)
                            .put("native_read_tool", "sentinel_read_media").put("errors", deliveryErrors))
                    // Bound the entire control envelope, including duplicated inline text/metadata.
                    // Carrier selection happens before publishing; it never retries a failed delivery.
                    val inline = images.toString().toByteArray(Charsets.UTF_8).size <= 96 * 1024 &&
                        SentinelXProtocol.success(id, candidate).toString().toByteArray(Charsets.UTF_8).size <= 120 * 1024
                    for (index in 0 until attachments.length())
                        attachments.getJSONObject(index).put("delivery", if (inline) "inline" else "binary")
                    response.put("media_attachments", attachments)
                    if (inline) response.put("mcp_content", images)
                } catch (error: Exception) {
                    SentinelXLogger.e(TAG, "Media attachment delivery failed", error)
                    deliveryErrors.put(JSONObject()
                        .put("error_code", if (error is SentinelXMediaException) error.code else "media_delivery_failed")
                        .put("error", (error.message ?: error.javaClass.simpleName).take(256)))
                }
            }
            val published = response.has("media_attachments")
            val status = if (deliveryErrors.length() == 0) "available" else if (published) "partial" else "error"
            response.put("media_delivery", JSONObject().put("status", status)
                .put("operation_completed", true).put("inline", response.has("mcp_content"))
                .put("native_read_tool", "sentinel_read_media").put("errors", deliveryErrors))
        }
        return SentinelXProtocol.success(
            id,
            response
                .put("duration", (System.nanoTime() - startedAt) / 1_000_000_000.0)
                .put("returncode", 0)
                .put("request_id", bridgeRequest.requestId)
        )
    }

    companion object {
        private val BACKGROUND_OPS = setOf("exec", "script_run")
        private const val TAG = "SentinelXTransport"
    }
}

internal fun buildSentinelXJobCompletedEvent(
    jobId: String,
    op: String,
    hostId: String,
    response: JSONObject,
    startedAt: Instant,
    finishedAt: Instant
): JSONObject {
    val ok = response.optBoolean("ok", false)
    val result = response.optJSONObject("result") ?: JSONObject()
    val returnCode =
        if (result.has("returncode") && !result.isNull("returncode")) result.optInt("returncode") else null
    val timedOut = result.optBoolean("timed_out", false)
    val status = when {
        !ok -> "failed"
        timedOut -> "timeout"
        returnCode == 0 -> "succeeded"
        else -> "failed"
    }
    val rawOutput = if (ok) result.optString("output") else ""
    val output = clipUtf8(rawOutput, 256 * 1024)
    val errorMessage =
        if (ok) null
        else jsonTextOrNull(response.optJSONObject("error"), "message") ?: "operation failed"

    val data = JSONObject()
        .put("job_id", jobId)
        .put("tool", op)
        .put("host", hostId)
        .put("status", status)
        .put("exit_code", returnCode ?: JSONObject.NULL)
        .put("started_at", startedAt.toString())
        .put("finished_at", finishedAt.toString())
        .put("duration_s", Duration.between(startedAt, finishedAt).toMillis() / 1000.0)
        .put("output", output)
        .put(
            "output_truncated",
            rawOutput.toByteArray(Charsets.UTF_8).size > 256 * 1024
        )
        .put("error", errorMessage ?: JSONObject.NULL)

    return JSONObject()
        .put("type", "event")
        .put("kind", "job_completed")
        .put("data", data)
        .put("timestamp", finishedAt.toString())
}
