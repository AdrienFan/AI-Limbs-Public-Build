package com.ai.assistance.operit.core.tools.system.resident

import android.net.LocalServerSocket
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.ai.assistance.operit.BuildConfig
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import org.json.JSONObject

/**
 * Standalone Resident Core process entry point.
 *
 * The process owns its Context, main Looper and lifecycle. Plugin Kernel ownership is armed only
 * after a prepared backend handoff and is acquired only after the old Host PID actually exits.
 */
object ResidentCoreMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = try {
            if (args.size == 1 && args[0] == "status") {
                println(ResidentCoreWire.request("status"))
            } else {
                require(args.size == 3) { "Expected package name, private state directory and launch ID" }
                serve(args[0], File(args[1]), args[2])
            }
            0
        } catch (error: Throwable) {
            error.printStackTrace()
            1
        }
        exitProcess(exitCode)
    }

    private fun serve(packageName: String, directory: File, launchId: String) {
        val contextState = ResidentCoreContextBootstrap.create(packageName)
        val context = contextState.context
        check(directory.canonicalFile == File(context.filesDir, "ai_limbs/resident_core").canonicalFile) {
            "Unexpected core state directory"
        }

        val runtime = ResidentCoreBusinessRuntime()

        ResidentRuntimeLease.acquire(directory, "bootstrap").use {
            check(File(directory, "launch.request").readText() == launchId) { "Core launch cancelled" }
            runtime.initialize(context)
            val sessionId = UUID.randomUUID().toString()
            val backend = ResidentBackendBinding(context, launchId, sessionId)
            val dispatcherServer = ResidentCoreDispatcherServer(context, sessionId)
            val uiProxyServer = ResidentUiProxyServer(context, sessionId)
            val continuousResources = ResidentCoreContinuousResources(context, sessionId)
            val startedElapsed = SystemClock.elapsedRealtime()
            val startedUptime = SystemClock.uptimeMillis()
            val server = LocalServerSocket(ResidentCoreWire.socketName())
            val shutdownHook = Thread { runCatching { server.close() } }
            val activationThread = AtomicReference<Thread?>(null)
            Runtime.getRuntime().addShutdownHook(shutdownHook)

            fun snapshot(): JSONObject {
                val elapsed = SystemClock.elapsedRealtime() - startedElapsed
                val uptime = SystemClock.uptimeMillis() - startedUptime
                val runtimeState = runtime.snapshot()
                val businessPhase = runtimeState.getString("business_phase")
                val businessAttached = runtimeState.getBoolean("business_attached")
                val owner = when {
                    businessAttached -> "resident_core"
                    businessPhase == "waiting_for_host_exit" ||
                        businessPhase == "acquiring_owner" ||
                        businessPhase == "starting_kernel" ||
                        businessPhase == "claiming_backend" ||
                        businessPhase == "starting_bridge" ||
                        businessPhase == "starting_plugin_services" -> "handoff_pending"
                    businessPhase == "failed" -> "unavailable"
                    else -> "android_host"
                }
                return JSONObject()
                    .put("available", runtimeState.getString("phase") == "running")
                    .put("phase", runtimeState.getString("phase"))
                    .put("build_code", BuildConfig.VERSION_CODE)
                    .put("pid", Process.myPid())
                    .put("uid", Process.myUid())
                    .put("session_id", sessionId)
                    .put("launch_id", launchId)
                    .put("package_name", packageName)
                    .put("context_ready", true)
                    .put("resource_package", contextState.resourcePackage)
                    .put("elapsed_ms", elapsed)
                    .put("uptime_ms", uptime)
                    .put("suspend_ms", (elapsed - uptime).coerceAtLeast(0L))
                    .put("runtime_owner", owner)
                    .put("business_phase", businessPhase)
                    .put("expected_host_pid", runtimeState.opt("expected_host_pid"))
                    .put("business_attached", businessAttached)
                    .put("plugin_kernel_started", runtimeState.getBoolean("plugin_kernel_started"))
                    .put("bridge_ingress_prepared", runtimeState.getBoolean("bridge_ingress_prepared"))
                    .put("bridge_plugin_mounted", runtimeState.getBoolean("bridge_plugin_mounted"))
                    .put("plugin_services_prepared", runtimeState.getBoolean("plugin_services_prepared"))
                    .put("ubuntu_control_ready", runtimeState.getBoolean("ubuntu_control_ready"))
                    .put("plugins_migrated", runtimeState.getBoolean("plugin_services_prepared"))
                    .put("continuous_work", false)
                    .put("core_runtime", runtimeState)
                    .put("backend", backend.snapshot())
                    .put("dispatcher", dispatcherServer.snapshot())
                    .put("ui_proxy", uiProxyServer.attachmentSnapshot())
                    .put("continuous_resources", continuousResources.snapshot())
                    .put("takeover_fence", ResidentBusinessTakeoverFence.snapshot(context) ?: JSONObject.NULL)
            }

            var userStop = false
            val controlThread = Thread({
                var exitCode = 0
                try {
                    runtime.start()
                    uiProxyServer.start()
                    Thread({ backend.connect() }, "resident-backend-bind").apply {
                        isDaemon = true
                        start()
                    }
                    println("AIL_RESIDENT_CORE_RUNNING " + snapshot())

                    var stopping = false
                    while (!stopping) {
                        server.accept().use { socket ->
                            socket.soTimeout = ResidentCoreWire.TIMEOUT_MS
                            var requestId = ""
                            try {
                                val peer = socket.peerCredentials
                                check(peer.uid == Process.myUid()) { "Core client UID mismatch" }
                                val request = ResidentCoreWire.read(socket)
                                requestId = request.getString("request_id")
                                require(requestId.length in 1..64) { "Invalid request ID" }
                                require(request.getInt("protocol") == ResidentCoreWire.VERSION) {
                                    "Unsupported core protocol"
                                }
                                if (!request.isNull("session_id")) {
                                    check(request.getString("session_id") == sessionId) { "Stale core session" }
                                }
                                val operation = request.getString("operation")
                                require(operation == "status" || operation == "stop" ||
                                    operation == "prepare_handoff" || operation == "activate_business" ||
                                    operation == "cancel_business_activation" || operation == "quiesce_business") {
                                    "Unsupported core operation"
                                }
                                if (operation != "status") {
                                    check(request.getString("session_id") == sessionId) {
                                        "Core mutation requires the current session"
                                    }
                                }

                                when (operation) {
                                    "prepare_handoff" -> backend.prepareHandoffAsync()
                                    "activate_business" -> {
                                        check(backend.snapshot().getString("state") == "prepared") {
                                            "Permission backend must be prepared before business takeover"
                                        }
                                        check(activationThread.get() == null) { "Business activation is already armed" }
                                        try {
                                            continuousResources.acquireForBusiness()
                                            runtime.armBusinessTakeover(context, sessionId, peer.pid)
                                        } catch (error: Throwable) {
                                            val release =
                                                continuousResources.release("activate_business_rejected")
                                            if (!release.optBoolean("release_confirmed", false)) {
                                                runCatching { server.close() }
                                            }
                                            throw error
                                        }
                                        val worker = Thread({
                                            try {
                                                runtime.activateBusiness(
                                                    context = context,
                                                    coreSession = sessionId,
                                                    backend = backend,
                                                    hostPid = peer.pid,
                                                    onBusinessOwnerReady = dispatcherServer::start
                                                )
                                            } catch (error: Throwable) {
                                                runtime.fail(error)
                                                System.err.println("Resident Core business activation failed: $error")
                                                runCatching { server.close() }
                                            } finally {
                                                activationThread.compareAndSet(Thread.currentThread(), null)
                                            }
                                        }, "resident-business-activation").apply { isDaemon = false }
                                        try {
                                            check(activationThread.compareAndSet(null, worker)) {
                                                "Business activation worker changed unexpectedly"
                                            }
                                            worker.start()
                                        } catch (error: Throwable) {
                                            activationThread.compareAndSet(worker, null)
                                            runCatching {
                                                runtime.cancelBusinessTakeover(context, sessionId, peer.pid)
                                            }
                                            val release =
                                                continuousResources.release("activation_worker_arm_failed")
                                            if (!release.optBoolean("release_confirmed", false)) {
                                                runCatching { server.close() }
                                            }
                                            throw error
                                        }
                                    }
                                    "cancel_business_activation" -> {
                                        runtime.cancelBusinessTakeover(context, sessionId, peer.pid)
                                        val release = continuousResources.release("business_takeover_cancelled")
                                        if (!release.optBoolean("release_confirmed", false)) {
                                            runCatching { server.close() }
                                        }
                                        check(release.getBoolean("release_confirmed")) {
                                            "Resident continuous resources did not release after takeover cancellation: $release"
                                        }
                                    }
                                    "quiesce_business" -> {
                                        runtime.beginBusinessQuiesce()
                                        val drain = dispatcherServer.quiesceAndDrain()
                                        if (drain.optBoolean("drain_success", false)) {
                                            runtime.markBusinessDrained()
                                        } else {
                                            runtime.markBusinessQuiesceFailed(
                                                drain.optString("last_error", "Resident Dispatcher drain failed")
                                            )
                                        }
                                    }
                                    "stop" -> runtime.requestStop()
                                }

                                val response = JSONObject()
                                    .put("protocol", ResidentCoreWire.VERSION)
                                    .put("request_id", requestId)
                                    .put("session_id", sessionId)
                                    .put("success", true)
                                    .put("result", snapshot().put("stop_requested", operation == "stop"))
                                ResidentCoreWire.write(socket, response)
                                stopping = operation == "stop"
                                userStop = stopping
                            } catch (error: Exception) {
                                System.err.println("Resident Core rejected request: $error")
                                try {
                                    ResidentCoreWire.write(socket, JSONObject()
                                        .put("protocol", ResidentCoreWire.VERSION)
                                        .put("request_id", requestId.take(64))
                                        .put("session_id", sessionId)
                                        .put("success", false)
                                        .put("error", error.toString().take(512)))
                                } catch (writeError: Exception) {
                                    System.err.println("Resident Core response failed: $writeError")
                                }
                            }
                        }
                    }
                } catch (error: Throwable) {
                    exitCode = 1
                    if (runtime.snapshot().getString("phase") != "failed") runtime.fail(error)
                    System.err.println("Resident Core runtime failed: $error")
                } finally {
                    runtime.requestStop()
                    activationThread.get()?.let { worker ->
                        try { worker.join(3_000L) }
                        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                    uiProxyServer.close()
                    dispatcherServer.stop()
                    try {
                        runtime.stop()
                    } catch (error: Throwable) {
                        exitCode = 1
                        runtime.fail(error)
                        System.err.println("Resident Core stop barrier failed: $error")
                    }
                    val resourceRelease = continuousResources.release(
                        if (userStop) "resident_off" else "core_exit"
                    )
                    if (!resourceRelease.optBoolean("release_confirmed", false)) {
                        exitCode = 1
                        System.err.println("Resident continuous resource release was not clean: $resourceRelease")
                    }

                    val cleanup = AtomicReference<JSONObject?>(null)
                    val closing = Thread({
                        try {
                            backend.close(returnToHost = userStop)
                            cleanup.set(JSONObject().put("backend_release_confirmed", true))
                        } catch (error: Exception) {
                            cleanup.set(JSONObject().put("backend_release_confirmed", false)
                                .put("backend_release_error", error.toString().take(512)))
                        }
                    }, "resident-backend-close").apply { isDaemon = true; start() }
                    try {
                        closing.join(2_000L)
                        if (userStop) {
                            ResidentPolicyStateHandoff.clearForExplicitCoreStop(context, sessionId)
                            ResidentBusinessTakeoverFence.clearForExplicitCoreStop(context, sessionId)
                        }
                        val outcome = cleanup.get() ?: JSONObject()
                            .put("backend_release_confirmed", false)
                            .put("backend_release_error", "Backend release did not acknowledge within 2000ms")
                        val runtimeState = runtime.snapshot()
                        outcome
                            .put("session_id", sessionId)
                            .put("pid", Process.myPid())
                            .put("continuous_resource_release_confirmed",
                                resourceRelease.optBoolean("release_confirmed", false))
                            .put("continuous_resource_release", resourceRelease)
                            .put("phase", runtimeState.getString("phase"))
                            .put("business_phase", runtimeState.getString("business_phase"))
                            .put("runtime_skeleton_ready", runtimeState.getBoolean("runtime_skeleton_ready"))
                        if (!runtimeState.isNull("last_error")) {
                            outcome.put("runtime_error", runtimeState.getString("last_error"))
                        }
                        if (!runtimeState.isNull("business_error")) {
                            outcome.put("business_error", runtimeState.getString("business_error"))
                        }
                        val staged = File(directory, "shutdown.result.tmp")
                        staged.writeText(outcome.toString())
                        check(staged.renameTo(File(directory, "shutdown.result.json"))) {
                            "Cannot save Core shutdown result"
                        }
                    } catch (error: Exception) {
                        System.err.println("Core shutdown result could not be saved: $error")
                    } finally {
                        runCatching { server.close() }
                        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
                    }
                    exitProcess(exitCode)
                }
            }, "resident-core-control")
            controlThread.isDaemon = false
            controlThread.start()

            try {
                Looper.loop()
                error("Resident Core main Looper exited unexpectedly")
            } catch (error: Throwable) {
                runtime.fail(error)
                System.err.println("Resident Core main Looper failed: $error")
                runCatching { server.close() }
                try { controlThread.join(1_000L) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                throw error
            }
        }
    }
}
