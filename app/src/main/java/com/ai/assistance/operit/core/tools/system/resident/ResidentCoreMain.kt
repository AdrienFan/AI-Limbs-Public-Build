package com.ai.assistance.operit.core.tools.system.resident

import android.net.LocalServerSocket
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.ai.assistance.operit.BuildConfig
import java.io.File
import java.util.UUID
import kotlin.system.exitProcess
import org.json.JSONObject

/**
 * Standalone Resident Core process entry point.
 *
 * The process owns its own Context bootstrap, main Looper and runtime lifecycle. Step 3 still does
 * not restore business services: Plugin Kernel, Bridge, Dispatcher and plugins remain Host-owned
 * until the later owner-handoff step.
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
        // ActivityThread can create non-daemon Binder threads even without an Application.
        exitProcess(exitCode)
    }

    private fun serve(packageName: String, directory: File, launchId: String) {
        // Framework Context bootstrap is intentionally completed before business-runtime init.
        val contextState = ResidentCoreContextBootstrap.create(packageName)
        val context = contextState.context
        check(directory.canonicalFile == File(context.filesDir, "ai_limbs/resident_core").canonicalFile) {
            "Unexpected core state directory"
        }

        val runtime = ResidentCoreBusinessRuntime()

        ResidentRuntimeLease.acquire(directory, "bootstrap").use {
            check(File(directory, "launch.request").readText() == launchId) { "Core launch cancelled" }
            // Only the process holding the Core lease may initialize the business-runtime container.
            runtime.initialize(context)
            val sessionId = UUID.randomUUID().toString()
            val backend = ResidentBackendBinding(context, launchId, sessionId)
            val startedElapsed = SystemClock.elapsedRealtime()
            val startedUptime = SystemClock.uptimeMillis()
            val server = LocalServerSocket(ResidentCoreWire.socketName())
            val shutdownHook = Thread { runCatching { server.close() } }
            Runtime.getRuntime().addShutdownHook(shutdownHook)

            fun snapshot(): JSONObject {
                val elapsed = SystemClock.elapsedRealtime() - startedElapsed
                val uptime = SystemClock.uptimeMillis() - startedUptime
                val runtimeState = runtime.snapshot()
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
                    .put("runtime_owner", "android_host")
                    .put("business_attached", false)
                    .put("plugins_migrated", false)
                    .put("continuous_work", false)
                    .put("core_runtime", runtimeState)
                    .put("backend", backend.snapshot())
            }

            var userStop = false
            val controlThread = Thread({
                var exitCode = 0
                try {
                    // RUNNING is granted only after the actual main Looper executes this runtime's
                    // startup barrier. Merely creating Context/socket is not sufficient.
                    runtime.start()

                    // Bind the permission backend only after the runtime startup barrier succeeds.
                    // The Host authenticates this process by calling our socket status endpoint, so
                    // the accept loop below must be ready alongside this worker.
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
                                check(socket.peerCredentials.uid == Process.myUid()) {
                                    "Core client UID mismatch"
                                }
                                val request = ResidentCoreWire.read(socket)
                                requestId = request.getString("request_id")
                                require(requestId.length in 1..64) { "Invalid request ID" }
                                require(request.getInt("protocol") == ResidentCoreWire.VERSION) {
                                    "Unsupported core protocol"
                                }
                                if (!request.isNull("session_id")) {
                                    check(request.getString("session_id") == sessionId) {
                                        "Stale core session"
                                    }
                                }
                                val operation = request.getString("operation")
                                require(operation == "status" || operation == "stop" || operation == "prepare_handoff") {
                                    "Unsupported core operation"
                                }
                                if (operation != "status") {
                                    check(request.getString("session_id") == sessionId) {
                                        "Core mutation requires the current session"
                                    }
                                }
                                if (operation == "prepare_handoff") backend.prepareHandoffAsync()
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
                    runtime.fail(error)
                    System.err.println("Resident Core runtime failed: $error")
                } finally {
                    try {
                        if (runtime.snapshot().getString("phase") == "running") runtime.stop()
                    } catch (error: Throwable) {
                        exitCode = 1
                        runtime.fail(error)
                        System.err.println("Resident Core stop barrier failed: $error")
                    }

                    // A frozen backend can block a Binder call indefinitely. Keep Core stop bounded;
                    // process death is also observed by the server's lifetime Binder.
                    val cleanup = java.util.concurrent.atomic.AtomicReference<JSONObject?>(null)
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
                        val outcome = cleanup.get() ?: JSONObject()
                            .put("backend_release_confirmed", false)
                            .put("backend_release_error", "Backend release did not acknowledge within 2000ms")
                        val runtimeState = runtime.snapshot()
                        outcome
                            .put("session_id", sessionId)
                            .put("pid", Process.myPid())
                            .put("phase", runtimeState.getString("phase"))
                            .put("runtime_skeleton_ready", runtimeState.getBoolean("runtime_skeleton_ready"))
                        if (!runtimeState.isNull("last_error")) {
                            outcome.put("runtime_error", runtimeState.getString("last_error"))
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

            // Resident Core owns an actual process main Looper. All future business components that
            // require a main-thread lifecycle can be attached to ResidentCoreBusinessRuntime without
            // constructing OperitApplication.
            try {
                Looper.loop()
                error("Resident Core main Looper exited unexpectedly")
            } catch (error: Throwable) {
                runtime.fail(error)
                System.err.println("Resident Core main Looper failed: $error")
                runCatching { server.close() }
                try { controlThread.join(1_000L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                throw error
            }
        }
    }
}
