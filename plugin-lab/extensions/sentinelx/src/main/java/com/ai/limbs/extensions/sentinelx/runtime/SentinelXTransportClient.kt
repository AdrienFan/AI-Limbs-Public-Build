package com.ai.limbs.extensions.sentinelx.runtime

import com.ai.limbs.extensions.sentinelx.SentinelXLogger
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
                "welcome" -> listener.onOnline(message.optString("session_id"))
                "ping" -> {
                    webSocket.send(SentinelXProtocol.pong(message.optString("timestamp")).toString())
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
            listener.onDisconnected("SentinelX 已断开：$code ${reason.take(120)}")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket !== webSocket) return
            socket = null
            isRunning = false
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
        val response = try {
            when (op) {
                "ping" -> SentinelXProtocol.success(
                    id,
                    JSONObject()
                        .put("pong", true)
                        .put("agent_version", SentinelXProtocol.AGENT_VERSION)
                )
                "capabilities" -> SentinelXProtocol.success(id, SentinelXProtocol.capabilities(config))
                "state" -> SentinelXProtocol.success(id, SentinelXProtocol.state(config))
                "exec" -> handleExec(id, payload)
                else -> SentinelXProtocol.failure(
                    id,
                    "unsupported_op",
                    "AI Limbs SentinelX transport 不实现 SentinelX op: $op"
                )
            }
        } catch (error: Exception) {
            SentinelXLogger.e(TAG, "SentinelX request failed: $op", error)
            SentinelXProtocol.failure(
                id,
                "ai_limbs_bridge_error",
                error.message ?: error::class.java.simpleName
            )
        }
        webSocket.send(response.toString())
        listener.onHeartbeat()
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
        val result = executor.execute(bridgeRequest.tool, bridgeRequest.args)
        val duration = (System.nanoTime() - startedAt) / 1_000_000_000.0
        return SentinelXProtocol.success(
            id,
            JSONObject()
                .put("output", result.toString())
                .put("duration", duration)
                .put("returncode", 0)
                .put("request_id", bridgeRequest.requestId)
                .put("bridge_result", result)
        )
    }

    companion object {
        private const val TAG = "SentinelXTransport"
    }
}
