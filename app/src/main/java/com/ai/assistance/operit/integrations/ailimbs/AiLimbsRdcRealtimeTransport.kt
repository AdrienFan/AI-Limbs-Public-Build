package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.integrations.ailimbs.chat.LanerChatQueueChangedEvent
import com.ai.assistance.operit.util.AppLogger
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Supabase Realtime transport used by the current Remote Desktop Commander device protocol.
 *
 * RDC's hosted server treats transport_broadcast_v1 as binding. This class therefore reports
 * readiness only after both the private channel join and Presence track are acknowledged; marking
 * the capability earlier can make the hosted dispatcher stop using a device that is not reachable.
 */
internal class AiLimbsRdcRealtimeTransport(
    private val httpClient: OkHttpClient,
    private val supabaseUrl: String,
    private val anonKey: String,
    private val accessToken: String,
    private val userId: String,
    private val deviceId: String,
    private val deviceName: String,
    private val appVersion: String,
    private val onNewCall: (String) -> Unit
) {
    private val nextRef = AtomicLong(0L)
    private val ready = CompletableDeferred<Unit>()
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val topic = "realtime:user:$userId"

    @Volatile private var socket: WebSocket? = null
    @Volatile private var joinRef: String? = null
    @Volatile private var presenceRef: String? = null
    @Volatile private var readyState = false
    @Volatile private var closing = false
    @Volatile private var failureCause: Throwable? = null

    suspend fun connectAndAwaitReady(timeoutMs: Long) {
        check(socket == null) { "RDC Realtime transport is already connected" }
        val request = Request.Builder().url(webSocketUrl()).build()
        socket = httpClient.newWebSocket(request, listener)
        val joined = withTimeoutOrNull(timeoutMs) {
            ready.await()
            true
        } ?: false
        if (!joined) {
            close()
            throw IllegalStateException("RDC Realtime channel did not become ready within ${timeoutMs}ms")
        }
    }

    fun throwIfFailed() {
        failureCause?.let { error ->
            throw IllegalStateException("RDC Realtime transport failed: ${error.message}", error)
        }
        check(readyState) { "RDC Realtime transport is not ready" }
    }

    suspend fun sendHeartbeatAndAwaitAck(timeoutMs: Long): Boolean {
        if (!readyState) return false
        val heartbeatRef = ref()
        val ack = CompletableDeferred<Boolean>()
        pendingAcks[heartbeatRef] = ack
        val sent = sendFrame(
            topic = "phoenix",
            event = "heartbeat",
            payload = JSONObject(),
            ref = heartbeatRef,
            joinRef = null
        )
        if (!sent) {
            pendingAcks.remove(heartbeatRef)?.complete(false)
            return false
        }
        val acknowledged = withTimeoutOrNull(timeoutMs) { ack.await() } ?: false
        pendingAcks.remove(heartbeatRef)
        return acknowledged
    }

    suspend fun notifyResult(callId: String): Boolean {
        return broadcastAndAwaitAck(
            event = "result",
            payload = JSONObject().put("call_id", callId)
        )
    }

    suspend fun notifyLanerChatQueueChanged(event: LanerChatQueueChangedEvent): Boolean {
        return broadcastAndAwaitAck(
            event = "laner_chat_queue_changed",
            payload = JSONObject()
                .put("schema_version", 2)
                .put("event", "queue_changed")
                .put("event_id", event.eventId)
                .put("reason", event.reason)
                .put("session_id", event.sessionId ?: JSONObject.NULL)
                .put("latest_seq", event.latestSeq)
                .put("pending_count", event.pendingCount)
                .put("unresolved_count", event.unresolvedCount)
                .put("highest_priority", event.highestPriority?.name ?: JSONObject.NULL)
                .put("high_count", event.highCount)
                .put("normal_count", event.normalCount)
                .put("low_count", event.lowCount)
                .put("active_turn_id", event.activeTurnId ?: JSONObject.NULL)
                .put("scheduler_paused", event.schedulerPaused)
                .put("attention_required", event.attentionRequired)
                .put("contains_body", false)
        )
    }

    private suspend fun broadcastAndAwaitAck(event: String, payload: JSONObject): Boolean {
        if (!readyState) return false
        val pushRef = ref()
        val ack = CompletableDeferred<Boolean>()
        pendingAcks[pushRef] = ack
        val sent = sendFrame(
            topic = topic,
            event = "broadcast",
            payload = JSONObject()
                .put("type", "broadcast")
                .put("event", event)
                .put("payload", payload),
            ref = pushRef,
            joinRef = joinRef
        )
        if (!sent) {
            pendingAcks.remove(pushRef)?.complete(false)
            return false
        }
        val acknowledged = withTimeoutOrNull(RESULT_ACK_TIMEOUT_MS) { ack.await() } ?: false
        pendingAcks.remove(pushRef)
        return acknowledged
    }

    fun close(force: Boolean = false) {
        closing = true
        readyState = false
        val currentSocket = socket
        val currentJoinRef = joinRef
        if (currentSocket != null && currentJoinRef != null) {
            sendFrame(topic, "phx_leave", JSONObject(), ref(), currentJoinRef)
        }
        currentSocket?.close(1000, "AI Limbs RDC transport closing")
        if (force) {
            // Recovery must not let a half-open socket linger while the replacement channel starts.
            currentSocket?.cancel()
        }
        socket = null
        pendingAcks.values.forEach { it.complete(false) }
        pendingAcks.clear()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val newJoinRef = ref()
            joinRef = newJoinRef
            val payload = JSONObject()
                .put(
                    "config",
                    JSONObject()
                        .put("private", true)
                        .put("broadcast", JSONObject().put("ack", true).put("self", false))
                        .put(
                            "presence",
                            JSONObject().put("key", deviceId).put("enabled", true)
                        )
                        .put("postgres_changes", JSONArray())
                )
                .put("access_token", accessToken)
            if (!sendFrame(topic, "phx_join", payload, newJoinRef, newJoinRef)) {
                fail(IllegalStateException("Unable to send RDC Realtime phx_join"))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { JSONObject(text) }.getOrElse { error ->
                AppLogger.w(TAG, "Ignoring invalid RDC Realtime frame", error)
                return
            }
            val event = message.optString("event")
            val ref = message.optString("ref")
            val payload = message.optJSONObject("payload") ?: JSONObject()
            when (event) {
                "phx_reply" -> handleReply(ref, payload)
                "broadcast" -> handleBroadcast(payload)
                "phx_error" -> fail(IllegalStateException("RDC Realtime channel reported phx_error"))
                "phx_close" -> if (!closing) {
                    fail(IllegalStateException("RDC Realtime channel closed unexpectedly"))
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!closing) fail(t)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closing) {
                fail(IllegalStateException("RDC Realtime socket closed: $code $reason"))
            }
        }
    }

    private fun handleReply(ref: String, payload: JSONObject) {
        val ok = payload.optString("status") == "ok"
        when (ref) {
            joinRef -> {
                if (!ok) {
                    fail(IllegalStateException("RDC Realtime private channel join was rejected: $payload"))
                    return
                }
                sendPresenceTrack()
            }
            presenceRef -> {
                if (!ok) {
                    fail(IllegalStateException("RDC Realtime Presence track was rejected: $payload"))
                    return
                }
                readyState = true
                ready.complete(Unit)
                AppLogger.i(TAG, "RDC Realtime private channel and Presence are ready")
            }
            else -> pendingAcks.remove(ref)?.complete(ok)
        }
    }

    private fun sendPresenceTrack() {
        val trackRef = ref()
        presenceRef = trackRef
        val payload = JSONObject()
            .put("type", "presence")
            .put("event", "track")
            .put(
                "payload",
                JSONObject()
                    .put("device_id", deviceId)
                    .put("device_name", deviceName)
                    .put("app_version", appVersion)
                    .put("platform", "android")
            )
        if (!sendFrame(topic, "presence", payload, trackRef, joinRef)) {
            fail(IllegalStateException("Unable to publish RDC Realtime Presence"))
        }
    }

    private fun handleBroadcast(payload: JSONObject) {
        if (payload.optString("event") != "new_call") return
        val doorbell = payload.optJSONObject("payload") ?: return
        val callId = doorbell.optString("call_id")
        val targetDeviceId = doorbell.optString("device_id")
        if (callId.isBlank()) return
        if (targetDeviceId.isNotBlank() && targetDeviceId != deviceId) return
        onNewCall(callId)
    }

    private fun sendFrame(
        topic: String,
        event: String,
        payload: JSONObject,
        ref: String,
        joinRef: String?
    ): Boolean {
        val currentSocket = socket ?: return false
        val frame = JSONObject()
            .put("topic", topic)
            .put("event", event)
            .put("payload", payload)
            .put("ref", ref)
            .put("join_ref", joinRef ?: JSONObject.NULL)
        return currentSocket.send(frame.toString())
    }

    private fun fail(error: Throwable) {
        if (closing || failureCause != null) return
        failureCause = error
        readyState = false
        if (!ready.isCompleted) ready.completeExceptionally(error)
        pendingAcks.values.forEach { it.complete(false) }
        pendingAcks.clear()
        AppLogger.e(TAG, "RDC Realtime transport failed", error)
    }

    private fun ref(): String = nextRef.incrementAndGet().toString()

    private fun webSocketUrl(): String {
        val websocketBase = when {
            supabaseUrl.startsWith("https://") -> "wss://${supabaseUrl.removePrefix("https://")}"
            supabaseUrl.startsWith("http://") -> "ws://${supabaseUrl.removePrefix("http://")}"
            else -> throw IllegalArgumentException("Unsupported Supabase URL: $supabaseUrl")
        }
        val encodedKey = URLEncoder.encode(anonKey, StandardCharsets.UTF_8.name())
        return "$websocketBase/realtime/v1/websocket?apikey=$encodedKey&vsn=1.0.0"
    }

    companion object {
        private const val TAG = "AiLimbsRdcRealtime"
        private const val RESULT_ACK_TIMEOUT_MS = 3_000L
    }
}
