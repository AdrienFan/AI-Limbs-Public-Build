package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Separate concurrent server for the Resident Core policy/Dispatcher data plane. */
internal class ResidentCoreDispatcherServer(
    context: Context,
    private val coreSessionId: String
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val serverRef = AtomicReference<LocalServerSocket?>(null)
    private val acceptThreadRef = AtomicReference<Thread?>(null)
    private var workers: ExecutorService? = null
    @Volatile private var runtime: ResidentCoreDispatcherRuntime? = null
    @Volatile private var lastError: String? = null

    fun start() = synchronized(lock) {
        if (running.get()) return@synchronized
        val fence = checkNotNull(ResidentBusinessTakeoverFence.snapshot(appContext)) {
            "Resident Dispatcher requires an armed takeover fence"
        }
        check(fence.getInt("core_pid") == Process.myPid()) { "Resident Dispatcher fence PID mismatch" }
        check(fence.getString("core_session") == coreSessionId) { "Resident Dispatcher fence session mismatch" }
        check(fence.getString("state") == "armed") {
            "Resident Dispatcher must start before takeover is marked owned"
        }

        val createdRuntime = ResidentCoreDispatcherRuntime(appContext, coreSessionId)
        val server = LocalServerSocket(ResidentCoreDispatchWire.socketName())
        val pool = Executors.newFixedThreadPool(MAX_CONCURRENT_DISPATCH) { task ->
            Thread(task, "resident-dispatch-worker").apply { isDaemon = true }
        }
        runtime = createdRuntime
        workers = pool
        serverRef.set(server)
        running.set(true)
        val acceptThread = Thread({ acceptLoop(server, pool) }, "resident-dispatch-accept").apply {
            isDaemon = true
            start()
        }
        acceptThreadRef.set(acceptThread)
    }

    fun stop() {
        var pool: ExecutorService? = null
        var acceptThread: Thread? = null
        var hadResources = false
        synchronized(lock) {
            val server = serverRef.getAndSet(null)
            pool = workers
            workers = null
            acceptThread = acceptThreadRef.getAndSet(null)
            hadResources = running.getAndSet(false) || server != null || pool != null || acceptThread != null
            if (hadResources) runCatching { server?.close() }
        }
        if (!hadResources) return
        val poolToStop = pool
        val acceptThreadToJoin = acceptThread
        try { acceptThreadToJoin?.join(1_000L) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        poolToStop?.shutdown()
        try {
            if (poolToStop != null && !poolToStop.awaitTermination(DISPATCH_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                poolToStop.shutdownNow()
            }
        } catch (_: InterruptedException) {
            poolToStop?.shutdownNow()
            Thread.currentThread().interrupt()
        } finally {
            runtime = null
        }
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("running", running.get())
        .put("dispatcher_owner", if (running.get()) "resident_core" else JSONObject.NULL)
        .put("owner_pid", if (running.get()) Process.myPid() else JSONObject.NULL)
        .put("last_error", lastError ?: JSONObject.NULL)
        .put("runtime", runtime?.snapshot() ?: JSONObject.NULL)

    private fun acceptLoop(server: LocalServerSocket, pool: ExecutorService) {
        try {
            while (running.get()) {
                val socket = server.accept()
                runCatching { pool.execute { handle(socket) } }
                    .onFailure { runCatching { socket.close() } }
            }
        } catch (error: Throwable) {
            if (running.get()) {
                lastError = error.toString().take(1024)
                running.set(false)
                runCatching { server.close() }
            }
        }
    }

    private fun handle(socket: LocalSocket) {
        socket.use { client ->
            client.soTimeout = ResidentCoreDispatchWire.TIMEOUT_MS
            var requestId = ""
            try {
                val peer = client.peerCredentials
                check(peer.uid == Process.myUid()) { "Resident Dispatcher client UID mismatch" }
                val request = ResidentCoreDispatchWire.read(client)
                requestId = request.getString("request_id")
                require(requestId.length in 1..64) { "Invalid Resident Dispatcher request ID" }
                require(request.getInt("protocol") == ResidentCoreDispatchWire.VERSION) {
                    "Unsupported Resident Dispatcher protocol"
                }
                check(request.getString("session_id") == coreSessionId) { "Stale Resident Core dispatch session" }
                val dispatcher = checkNotNull(runtime) { "Resident Dispatcher runtime is stopping" }
                val result = when (val operation = request.getString("operation")) {
                    "invoke" -> dispatcher.invoke(request.optJSONObject("payload") ?: JSONObject())
                    "rearm_bootstrap" -> dispatcher.rearmBootstrap()
                    "status" -> dispatcher.snapshot()
                    else -> error("Unsupported Resident Dispatcher operation: $operation")
                }
                ResidentCoreDispatchWire.write(client, response(requestId, true).put("result", result))
            } catch (error: Throwable) {
                runCatching {
                    ResidentCoreDispatchWire.write(
                        client,
                        response(requestId.take(64), false)
                            .put("error_code", "RESIDENT_CORE_DISPATCH_ERROR")
                            .put("error", error.toString().take(1024))
                    )
                }
            }
        }
    }

    private fun response(requestId: String, success: Boolean): JSONObject = JSONObject()
        .put("protocol", ResidentCoreDispatchWire.VERSION)
        .put("request_id", requestId)
        .put("session_id", coreSessionId)
        .put("core_pid", Process.myPid())
        .put("core_uid", Process.myUid())
        .put("success", success)

    private companion object {
        const val MAX_CONCURRENT_DISPATCH = 4
        const val DISPATCH_DRAIN_TIMEOUT_MS = 5_000L
    }
}
