package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dedicated Resident UI proxy protocol.
 *
 * This wire transports versioned JSON descriptors/state/commands only. Android View, Compose
 * objects, Context, IBinder/window tokens and arbitrary Java objects are forbidden by construction.
 * The small diagnostic ResidentCoreWire remains separate from this larger presentation data plane.
 */
internal object ResidentUiProxyWire {
    const val VERSION = 1
    const val TIMEOUT_MS = 5_000
    const val MAX_FRAME_BYTES = 4 * 1024 * 1024

    fun socketName(): String = "ai_limbs_ui_proxy_" + Process.myUid()

    fun read(socket: LocalSocket): JSONObject {
        val input = DataInputStream(socket.inputStream)
        val length = input.readInt()
        require(length in 1..MAX_FRAME_BYTES) { "Invalid UI proxy frame size: $length" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    fun write(socket: LocalSocket, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME_BYTES) { "UI proxy frame exceeds ${MAX_FRAME_BYTES} bytes" }
        DataOutputStream(socket.outputStream).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    fun request(
        operation: String,
        sessionId: String,
        payload: JSONObject = JSONObject()
    ): JSONObject {
        val requestId = UUID.randomUUID().toString()
        LocalSocket().use { socket ->
            // Android 16 real-device invariant: connect before assigning soTimeout.
            socket.connect(LocalSocketAddress(socketName(), LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = TIMEOUT_MS
            val peer = socket.peerCredentials
            check(peer.uid == Process.myUid()) { "UI proxy peer UID mismatch" }
            write(
                socket,
                JSONObject()
                    .put("protocol", VERSION)
                    .put("request_id", requestId)
                    .put("session_id", sessionId)
                    .put("operation", operation)
                    .put("payload", JSONObject(payload.toString()))
            )
            val response = read(socket)
            check(response.getInt("protocol") == VERSION) { "UI proxy protocol mismatch" }
            check(response.getString("request_id") == requestId) { "UI proxy request mismatch" }
            check(response.getString("session_id") == sessionId) { "UI proxy Core session changed" }
            check(response.getInt("core_uid") == peer.uid) { "UI proxy Core UID mismatch" }
            check(response.getInt("core_pid") == peer.pid) { "UI proxy Core PID mismatch" }
            check(response.getBoolean("success")) { response.optString("error", "UI proxy request failed") }
            return response.optJSONObject("result") ?: JSONObject()
        }
    }
}

internal data class ResidentComponentProxyRequest(
    val requestId: String,
    val kind: String,
    val payload: JSONObject,
    val createdElapsedMs: Long,
    val deadlineElapsedMs: Long
)

/**
 * Core-side rendezvous for Android framework work that must execute in the Host process.
 *
 * Requests contain neutral IDs/JSON only. A raw Activity, Context, ActivityResultLauncher, Binder or
 * window token is never stored here. Window access is represented by an opaque Host-local lease ID.
 */
internal class ResidentComponentProxyBroker {
    private data class Pending(
        val request: ResidentComponentProxyRequest,
        val claimed: AtomicBoolean = AtomicBoolean(false),
        val result: AtomicReference<JSONObject?> = AtomicReference(null),
        val latch: CountDownLatch = CountDownLatch(1)
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    fun request(kind: String, payload: JSONObject, timeoutMs: Long = 0L): JSONObject {
        require(kind in SUPPORTED_KINDS) { "Unsupported Host component proxy kind: $kind" }
        val effectiveTimeoutMs = when {
            timeoutMs > 0L -> timeoutMs
            kind == KIND_ACTIVITY_RESULT -> ACTIVITY_RESULT_TIMEOUT_MS
            else -> DEFAULT_TIMEOUT_MS
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val id = UUID.randomUUID().toString()
        val entry = Pending(
            ResidentComponentProxyRequest(
                requestId = id,
                kind = kind,
                payload = JSONObject(payload.toString()),
                createdElapsedMs = now,
                deadlineElapsedMs = now + effectiveTimeoutMs
            )
        )
        check(pending.putIfAbsent(id, entry) == null)
        try {
            check(entry.latch.await(effectiveTimeoutMs, TimeUnit.MILLISECONDS)) {
                "Host component proxy timed out: $kind/$id"
            }
            return checkNotNull(entry.result.get()) { "Host component proxy completed without a result" }
        } finally {
            pending.remove(id, entry)
        }
    }

    fun poll(maxItems: Int = 8): JSONArray {
        val now = android.os.SystemClock.elapsedRealtime()
        val result = JSONArray()
        pending.values
            .asSequence()
            .filter { it.request.deadlineElapsedMs > now && it.claimed.compareAndSet(false, true) }
            .take(maxItems.coerceIn(1, 32))
            .forEach { entry ->
                val request = entry.request
                result.put(
                    JSONObject()
                        .put("request_id", request.requestId)
                        .put("kind", request.kind)
                        .put("payload", JSONObject(request.payload.toString()))
                        .put("deadline_elapsed_ms", request.deadlineElapsedMs)
                )
            }
        return result
    }

    fun complete(requestId: String, result: JSONObject) {
        val entry = pending[requestId] ?: return
        if (entry.result.compareAndSet(null, JSONObject(result.toString()))) {
            entry.latch.countDown()
        }
    }

    companion object {
        const val KIND_ACTIVITY_RESULT = "activity_result"
        const val KIND_START_ACTIVITY = "start_activity"
        const val KIND_WINDOW_FLAGS = "window_flags"
        const val KIND_WINDOW_LEASE = "window_lease"
        const val KIND_ACTIVITY_PRESENCE = "activity_presence"
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val ACTIVITY_RESULT_TIMEOUT_MS = 120_000L
        val SUPPORTED_KINDS = setOf(
            KIND_ACTIVITY_RESULT, KIND_START_ACTIVITY, KIND_WINDOW_FLAGS,
            KIND_WINDOW_LEASE, KIND_ACTIVITY_PRESENCE
        )
    }
}

/**
 * BUSINESS-Core entry point for Android framework work owned by the Host process.
 *
 * This object never stores framework objects. It only forwards neutral JSON requests to the broker
 * owned by the current Resident UI server. No broker means no live Host component proxy.
 */
internal object ResidentHostComponentProxy {
    private val activeBroker = AtomicReference<ResidentComponentProxyBroker?>(null)

    internal fun bind(broker: ResidentComponentProxyBroker) {
        check(activeBroker.compareAndSet(null, broker)) { "Resident Host component proxy is already bound" }
    }

    internal fun unbind(broker: ResidentComponentProxyBroker) {
        activeBroker.compareAndSet(broker, null)
    }

    fun request(kind: String, payload: JSONObject = JSONObject(), timeoutMs: Long = 0L): JSONObject =
        checkNotNull(activeBroker.get()) { "Resident Host component proxy is unavailable" }
            .request(kind, JSONObject(payload.toString()), timeoutMs)
}

/** Core-owned server for UI snapshot/event/command transport and Host component rendezvous. */
internal class ResidentUiProxyServer(
    private val context: Context,
    private val sessionId: String,
    val componentBroker: ResidentComponentProxyBroker = ResidentComponentProxyBroker()
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val revision = AtomicLong(0L)
    private val lastSnapshotText = AtomicReference<String?>(null)
    private var server: LocalServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = LocalServerSocket(ResidentUiProxyWire.socketName())
        ResidentHostComponentProxy.bind(componentBroker)
        server = socket
        thread = Thread({ serve(socket) }, "resident-ui-proxy").apply {
            isDaemon = true
            start()
        }
    }

    private fun serve(socket: LocalServerSocket) {
        while (running.get()) {
            val client = try { socket.accept() } catch (_: Throwable) { break }
            client.use { local ->
                var requestId = ""
                try {
                    local.soTimeout = ResidentUiProxyWire.TIMEOUT_MS
                    val peer = local.peerCredentials
                    check(peer.uid == Process.myUid()) { "UI proxy client UID mismatch" }
                    val request = ResidentUiProxyWire.read(local)
                    requestId = request.getString("request_id")
                    require(requestId.length in 1..64) { "Invalid UI proxy request ID" }
                    require(request.getInt("protocol") == ResidentUiProxyWire.VERSION)
                    check(request.getString("session_id") == sessionId) { "Stale UI proxy Core session" }
                    val operation = request.getString("operation")
                    val payload = request.optJSONObject("payload") ?: JSONObject()
                    val result = when (operation) {
                        "snapshot" -> snapshotResult(force = true, sinceRevision = -1L)
                        "events" -> snapshotResult(
                            force = false,
                            sinceRevision = payload.optLong("since_revision", -1L)
                        )
                        "command" -> runBlocking(Dispatchers.IO) {
                            PluginPlatformKernel.dispatchResidentUiProxyCommand(JSONObject(payload.toString()))
                        }
                        "component_poll" -> JSONObject().put("requests", componentBroker.poll(payload.optInt("max_items", 8)))
                        "component_result" -> {
                            val componentRequestId = payload.getString("request_id")
                            componentBroker.complete(
                                componentRequestId,
                                payload.optJSONObject("result") ?: JSONObject().put("ok", false)
                            )
                            JSONObject().put("accepted", true)
                        }
                        else -> error("Unsupported UI proxy operation: $operation")
                    }
                    ResidentUiProxyWire.write(local, response(requestId, true, result, null))
                } catch (error: Throwable) {
                    runCatching {
                        ResidentUiProxyWire.write(
                            local,
                            response(requestId.take(64), false, null, error.toString().take(1024))
                        )
                    }
                }
            }
        }
    }

    private fun snapshotResult(force: Boolean, sinceRevision: Long): JSONObject {
        val snapshot = PluginPlatformKernel.residentUiProxySnapshot()
        val text = snapshot.toString()
        val previous = lastSnapshotText.getAndSet(text)
        if (previous != text) revision.incrementAndGet()
        val currentRevision = revision.get()
        val changed = force || currentRevision != sinceRevision
        return JSONObject()
            .put("revision", currentRevision)
            .put("changed", changed)
            .put("snapshot", if (changed) snapshot else JSONObject.NULL)
    }

    private fun response(requestId: String, success: Boolean, result: JSONObject?, error: String?): JSONObject =
        JSONObject()
            .put("protocol", ResidentUiProxyWire.VERSION)
            .put("request_id", requestId)
            .put("session_id", sessionId)
            .put("core_pid", Process.myPid())
            .put("core_uid", Process.myUid())
            .put("success", success)
            .put("result", result ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        ResidentHostComponentProxy.unbind(componentBroker)
        runCatching { server?.close() }
        runCatching { thread?.join(1_000L) }
        server = null
        thread = null
    }
}
