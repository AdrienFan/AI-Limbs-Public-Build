package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.content.ContextWrapper
import android.net.LocalServerSocket
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.R
import java.io.File
import java.util.UUID
import kotlin.system.exitProcess
import org.json.JSONObject

/**
 * Bootstrap only: an independent Context and authenticated diagnostic IPC.
 * No plugin is restored here until the View/runtime boundary has been migrated.
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
        val context = createPackageContext(packageName)
        check(context.applicationInfo.uid == Process.myUid()) { "Core must run as the app UID" }
        check(directory.canonicalFile == File(context.filesDir, "ai_limbs/resident_core").canonicalFile) {
            "Unexpected core state directory"
        }
        val resourcePackage = context.resources.getResourcePackageName(R.string.app_name)
        ResidentRuntimeLease.acquire(directory, "bootstrap").use {
            check(File(directory, "launch.request").readText() == launchId) { "Core launch cancelled" }
            val sessionId = UUID.randomUUID().toString()
            val backend = ResidentBackendBinding(context, launchId, sessionId)
            val startedElapsed = SystemClock.elapsedRealtime()
            val startedUptime = SystemClock.uptimeMillis()
            val server = LocalServerSocket(ResidentCoreWire.socketName())
            val shutdownHook = Thread { server.close() }
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            fun snapshot(): JSONObject {
                val elapsed = SystemClock.elapsedRealtime() - startedElapsed
                val uptime = SystemClock.uptimeMillis() - startedUptime
                return JSONObject()
                    .put("available", true)
                    .put("phase", "bootstrap_ready")
                    .put("build_code", BuildConfig.VERSION_CODE)
                    .put("pid", Process.myPid())
                    .put("uid", Process.myUid())
                    .put("session_id", sessionId)
                    .put("launch_id", launchId)
                    .put("package_name", packageName)
                    .put("context_ready", true)
                    .put("resource_package", resourcePackage)
                    .put("elapsed_ms", elapsed)
                    .put("uptime_ms", uptime)
                    .put("suspend_ms", (elapsed - uptime).coerceAtLeast(0L))
                    .put("runtime_owner", "android_host")
                    .put("plugins_migrated", false)
                    .put("continuous_work", false)
                    .put("backend", backend.snapshot())
            }
            var userStop = false
            try {
                Thread({ backend.connect() }, "resident-backend-bind").apply { isDaemon = true; start() }
                println("AIL_RESIDENT_CORE_READY " + snapshot())
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
                            System.err.println("Resident Core rejected request: " + error)
                            // Protocol failures remain explicit; a malformed request never executes work.
                            try {
                                ResidentCoreWire.write(socket, JSONObject()
                                    .put("protocol", ResidentCoreWire.VERSION)
                                    .put("request_id", requestId.take(64))
                                    .put("session_id", sessionId)
                                    .put("success", false)
                                    .put("error", error.toString().take(512)))
                            } catch (writeError: Exception) {
                                System.err.println("Resident Core response failed: " + writeError)
                            }
                        }
                    }
                }
            } finally {
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
                    outcome.put("session_id", sessionId).put("pid", Process.myPid())
                    val staged = File(directory, "shutdown.result.tmp")
                    staged.writeText(outcome.toString())
                    check(staged.renameTo(File(directory, "shutdown.result.json"))) { "Cannot save Core shutdown result" }
                } catch (error: Exception) {
                    System.err.println("Core shutdown result could not be saved: $error")
                }
                finally {
                    server.close()
                    Runtime.getRuntime().removeShutdownHook(shutdownHook)
                }
            }
        }
    }

    private fun createPackageContext(packageName: String): Context {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getDeclaredMethod("systemMain").invoke(null)
        val systemContext = activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as Context
        val packageContext = systemContext.createPackageContext(packageName, 0)
        // Do not construct OperitApplication: that would restore a second set of plugins.
        return object : ContextWrapper(packageContext) {
            override fun getApplicationContext(): Context = this
        }
    }
}
