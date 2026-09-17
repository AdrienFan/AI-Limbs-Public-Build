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
    private val accepting = AtomicBoolean(false)
    private val drained = AtomicBoolean(false)
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
        val server = ResidentLocalServerSocket.bind(ResidentCoreDispatchWire.socketName(coreSessionId))
        val pool = Executors.newFixedThreadPool(MAX_CONCURRENT_DISPATCH) { task ->
            Thread(task, "resident-dispatch-worker").apply { isDaemon = true }
        }
        runtime = createdRuntime
        workers = pool
        serverRef.set(server)
        drained.set(false)
        accepting.set(true)
        running.set(true)
        val acceptThread = Thread({ acceptLoop(server, pool) }, "resident-dispatch-accept").apply {
            isDaemon = true
            start()
        }
        acceptThreadRef.set(acceptThread)
    }

    /**
     * Close admission first, then wait for already accepted Dispatcher work to finish.
     * The runtime object remains available for diagnostics until final Core stop.
     */
    fun quiesceAndDrain(): JSONObject {
        var pool: ExecutorService? = null
        var acceptThread: Thread? = null
        var server: LocalServerSocket? = null
        synchronized(lock) {
            if (drained.get()) return snapshot().put("drain_success", true)
            accepting.set(false)
            running.set(false)
            server = serverRef.getAndSet(null)
            pool = workers
            workers = null
            acceptThread = acceptThreadRef.getAndSet(null)
            runCatching { server?.close() }
        }

        var success = true
        try {
            acceptThread?.join(1_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            success = false
        }

        pool?.shutdown()
        try {
            if (pool != null && !pool!!.awaitTermination(DISPATCH_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                pool!!.shutdownNow()
                success = pool!!.awaitTermination(DISPATCH_FORCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            pool?.shutdownNow()
            Thread.currentThread().interrupt()
            success = false
        }

        drained.set(success)
        if (!success) {
            lastError = "Resident Dispatcher drain timed out; remaining work was force-cancelled"
        }
        return snapshot()
            .put("drain_success", success)
            .put("drain_timeout_ms", DISPATCH_DRAIN_TIMEOUT_MS)
    }

    fun stop() {
        runCatching { quiesceAndDrain() }
            .onFailure { lastError = it.toString().take(1024) }
        runtime = null
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("running", running.get())
        .put("accepting", accepting.get())
        .put("drained", drained.get())
        .put("dispatcher_owner", if (runtime != null) "resident_core" else JSONObject.NULL)
        .put("owner_pid", if (runtime != null) Process.myPid() else JSONObject.NULL)
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
                    "plugin_delegate" -> {
                        requirePluginWorkerPeer(peer.pid)
                        dispatcher.invokePluginDelegated(request.optJSONObject("payload") ?: JSONObject())
                    }
                    "plugin_service_describe" -> {
                        requirePluginWorkerPeer(peer.pid)
                        dispatcher.describePluginService(request.optJSONObject("payload") ?: JSONObject())
                    }
                    "plugin_service_invoke" -> {
                        requirePluginWorkerPeer(peer.pid)
                        dispatcher.invokePluginService(request.optJSONObject("payload") ?: JSONObject())
                    }
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

    private fun requirePluginWorkerPeer(pid: Int) {
        check(com.ai.assistance.operit.plugins.center.isolation.PluginRuntimeController
            .isAttestedWorkerPeer(appContext, pid, coreSessionId)) {
            "Resident Dispatcher rejected an unattested plugin worker peer"
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
        const val DISPATCH_FORCE_STOP_TIMEOUT_MS = 1_000L
    }
}
