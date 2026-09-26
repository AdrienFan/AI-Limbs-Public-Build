package com.ai.assistance.operit.plugins.center.isolation

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.core.tools.system.resident.ResidentBusinessTakeoverFence
import com.ai.assistance.operit.core.tools.system.resident.ResidentCoreContextBootstrap
import com.ai.assistance.operit.core.tools.system.resident.ResidentCoreSecurityBootstrap
import com.ai.assistance.operit.core.tools.system.resident.ResidentLocalServerSocket
import com.ai.assistance.operit.core.tools.system.resident.ResidentProcessLiveness
import com.ai.assistance.operit.core.tools.system.resident.ResidentRuntimeLease
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.util.UUID
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Standalone crash boundary for trusted dynamic plugin business code. */
object PluginRuntimeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = try {
            require(args.size == 5) { "Expected package, state dir, launch ID, Core PID and Core session" }
            serve(args[0], File(args[1]), args[2], args[3].toInt(), args[4])
            0
        } catch (error: Throwable) {
            error.printStackTrace()
            1
        }
        exitProcess(exitCode)
    }

    private fun serve(
        packageName: String,
        directory: File,
        launchId: String,
        ownerCorePid: Int,
        ownerCoreSession: String
    ) {
        val contextState = ResidentCoreContextBootstrap.create(packageName)
        val context = contextState.context
        val security = ResidentCoreSecurityBootstrap.initialize(context)
        // app_process skips Application.onCreate(); without a file Context, Worker logger calls
        // reach logcat but never enter the shared log file read by Log Center.
        AppLogger.bindContext(context)
        require(ownerCorePid > 0 && ownerCorePid != Process.myPid()) { "Invalid owner Core PID" }
        require(ownerCoreSession.length in 1..64) { "Invalid owner Core session" }
        check(directory.canonicalFile == File(context.filesDir, "ai_limbs/plugin_runtime").canonicalFile) {
            "Unexpected plugin runtime state directory"
        }

        ResidentRuntimeLease.acquire(directory, "bootstrap").use {
            check(File(directory, "launch.request").readText() == launchId) {
                "Plugin runtime launch cancelled"
            }
            val sessionId = UUID.randomUUID().toString()
            val identityFile = File(directory, "runtime.identity")
            identityFile.writeText(
                JSONObject()
                    .put("pid", Process.myPid())
                    .put("uid", Process.myUid())
                    .put("session_id", sessionId)
                    .put("launch_id", launchId)
                    .put("owner_core_pid", ownerCorePid)
                    .put("owner_core_session", ownerCoreSession)
                    .toString()
            )

            val worker = PluginWorkerRuntime(context, ownerCorePid, ownerCoreSession)
            runBlocking { worker.start() }

            val startedElapsed = SystemClock.elapsedRealtime()
            val startedUptime = SystemClock.uptimeMillis()
            val server = ResidentLocalServerSocket.bind(PluginRuntimeWire.socketName(sessionId))
            val shutdownHook = Thread { runCatching { server.close() } }
            Runtime.getRuntime().addShutdownHook(shutdownHook)

            fun snapshot(): JSONObject {
                val elapsed = SystemClock.elapsedRealtime() - startedElapsed
                val uptime = SystemClock.uptimeMillis() - startedUptime
                return JSONObject()
                    .put("available", true)
                    .put("phase", "running")
                    .put("build_code", BuildConfig.VERSION_CODE)
                    .put("source_apk", context.applicationInfo.sourceDir)
                    .put("pid", Process.myPid())
                    .put("uid", Process.myUid())
                    .put("session_id", sessionId)
                    .put("launch_id", launchId)
                    .put("owner_core_pid", ownerCorePid)
                    .put("owner_core_session", ownerCoreSession)
                    .put("package_name", packageName)
                    .put("resource_package", contextState.resourcePackage)
                    .put("security", security)
                    .put("elapsed_ms", elapsed)
                    .put("uptime_ms", uptime)
                    .put("suspend_ms", (elapsed - uptime).coerceAtLeast(0L))
                    .put("runtime_owner", "plugin_runtime")
                    .put("kernel_attached", false)
                    .put("dynamic_code_attached", true)
                    .put("child_runtime_attached", true)
                    .put("isolation_phase", "dynamic_runtime_ready")
            }

            Thread({
                while (true) {
                    Thread.sleep(1_000L)
                    val fence = runCatching { ResidentBusinessTakeoverFence.snapshot(context) }.getOrNull()
                    val ownerValid = ResidentProcessLiveness.exists(ownerCorePid) &&
                        fence?.optInt("core_pid", -1) == ownerCorePid &&
                        fence.optString("core_session") == ownerCoreSession &&
                        fence.optString("state") in setOf("armed", "owned")
                    if (!ownerValid) {
                        System.err.println("Plugin runtime owner Core disappeared or changed; exiting fail-closed")
                        Process.killProcess(Process.myPid())
                        return@Thread
                    }
                }
            }, "plugin-runtime-core-watchdog").apply { isDaemon = true; start() }

            println("AIL_PLUGIN_RUNTIME_RUNNING " + snapshot())
            var stopping = false
            while (!stopping) {
                server.accept().use { socket ->
                    socket.soTimeout = PluginRuntimeWire.TIMEOUT_MS
                    var requestId = ""
                    var operationName = "unread"
                    val requestStarted = SystemClock.elapsedRealtime()
                    try {
                        val peer = socket.peerCredentials
                        check(peer.uid == Process.myUid()) { "Plugin runtime client UID mismatch" }
                        val request = PluginRuntimeWire.read(socket)
                        operationName = request.getString("operation")
                        requestId = request.getString("request_id")
                        // Record request boundaries, never payloads: a slow request on this serial socket
                        // can delay every later plugin mount and runtime health check.
                        if (operationName !in setOf("status", "ping", "snapshot_plugin", "child_snapshot")) {
                            Log.i("AILPluginMount", "worker request begin operation=$operationName")
                        }
                        require(requestId.length in 1..64) { "Invalid request ID" }
                        require(request.getInt("protocol") == PluginRuntimeWire.VERSION) {
                            "Unsupported plugin runtime protocol"
                        }
                        if (!request.isNull("session_id")) {
                            check(request.getString("session_id") == sessionId) {
                                "Stale plugin runtime session"
                            }
                        }
                        val payload = request.optJSONObject("payload") ?: JSONObject()
                        val operationResult = when (request.getString("operation")) {
                            "status", "ping" -> JSONObject()
                            "mount" -> runBlocking {
                                worker.mount(payload.getString("plugin_id"), payload.getString("version"))
                            }
                            "stop_plugin" -> runBlocking {
                                worker.stopPlugin(
                                    payload.getString("plugin_id"),
                                    ownerShutdown = payload.optBoolean("owner_shutdown", false)
                                )
                            }
                            "snapshot_plugin" -> worker.snapshot(payload.getString("plugin_id"))
                            "invoke_capability" -> runBlocking {
                                worker.invokeCapability(
                                    payload.getString("plugin_id"),
                                    payload.getString("capability_id"),
                                    payload.optJSONObject("parameters") ?: JSONObject()
                                )
                            }
                            "service_invoke" -> runBlocking {
                                worker.invokeService(
                                    pluginId = payload.getString("plugin_id"),
                                    serviceId = payload.getString("service_id"),
                                    callerPluginId = payload.getString("caller_plugin_id"),
                                    callerRoles = payload.optJSONArray("caller_roles").stringSet(),
                                    callerScopes = payload.optJSONArray("caller_scopes").stringSet(),
                                    operation = payload.getString("service_operation"),
                                    parameters = payload.optJSONObject("parameters") ?: JSONObject()
                                )
                            }
                            "provider_executor" -> JSONObject().put(
                                "result_json",
                                runBlocking {
                                    worker.invokeProviderExecutor(
                                        payload.getString("plugin_id"),
                                        payload.getString("provider_id"),
                                        payload.optString("parameters_json", "{}")
                                    )
                                }
                            )
                            "provider_ui_event" -> JSONObject().put(
                                "result_json",
                                runBlocking {
                                    worker.performUiProvider(
                                        payload.getString("plugin_id"),
                                        payload.getString("provider_id"),
                                        payload.getString("event_id"),
                                        payload.optString("payload_json", "{}")
                                    )
                                }
                            )
                            "notification_action" -> JSONObject().put(
                                "accepted",
                                runBlocking {
                                    worker.performNotification(
                                        payload.getString("plugin_id"),
                                        payload.getString("action_id")
                                    )
                                }
                            )
                            "start_children" -> runBlocking { worker.startChildren() }
                            "await_enabled_point_ready" -> runBlocking {
                                worker.awaitEnabledPointReady(
                                    payload.getString("point"),
                                    payload.getLong("timeout_ms")
                                )
                            }
                            "await_business_children_ready" -> runBlocking {
                                worker.awaitBusinessChildrenReady(payload.getLong("timeout_ms"))
                            }
                            "stop_children" -> runBlocking { worker.stopChildren() }
                            "child_snapshot" -> worker.childSnapshot()
                            "child_control" -> runBlocking {
                                worker.childControl(payload.getString("child_operation"), payload)
                            }
                            "child_install" -> runBlocking {
                                worker.installChild(
                                    payload.getString("owner_plugin_id"),
                                    payload.getString("package_path"),
                                    payload.optString("expected_parent_plugin_id").trim().ifBlank { null },
                                    payload.optString("expected_point").trim().ifBlank { null }
                                )
                            }
                            "child_ui_event" -> JSONObject().put(
                                "result_json",
                                runBlocking {
                                    worker.performChildUi(
                                        payload.getString("extension_id"),
                                        payload.getString("contribution_id"),
                                        payload.getString("event_id"),
                                        payload.optString("payload_json", "{}")
                                    )
                                }
                            )
                            "child_presentation_command" -> runBlocking {
                                worker.invokeChildPresentation(
                                    payload.getString("extension_id"),
                                    payload.getString("command"),
                                    payload.optJSONObject("parameters") ?: JSONObject()
                                )
                            }
                            "child_export_backups" -> runBlocking {
                                val ids = buildList {
                                    val array = payload.optJSONArray("extension_ids") ?: org.json.JSONArray()
                                    for (index in 0 until array.length()) add(array.getString(index))
                                }
                                JSONObject().put("exported", org.json.JSONArray(
                                    worker.exportChildBackups(ids, payload.getString("tree_uri"))
                                ))
                            }
                            "stop" -> {
                                check(request.getString("session_id") == sessionId) {
                                    "Plugin runtime stop requires current session"
                                }
                                runBlocking { worker.stop() }
                                stopping = true
                                JSONObject().put("stopped", true)
                            }
                            else -> error("Unsupported plugin runtime operation")
                        }
                        PluginRuntimeWire.write(
                            socket,
                            JSONObject()
                                .put("protocol", PluginRuntimeWire.VERSION)
                                .put("request_id", requestId)
                                .put("session_id", sessionId)
                                .put("success", true)
                                .put("result", snapshot().put("operation_result", operationResult))
                        )
                    } catch (error: Throwable) {
                        Log.e("AILPluginMount", "worker request failed operation=$operationName elapsed_ms=${SystemClock.elapsedRealtime() - requestStarted}", error)
                        runCatching {
                            PluginRuntimeWire.write(
                                socket,
                                JSONObject()
                                    .put("protocol", PluginRuntimeWire.VERSION)
                                    .put("request_id", requestId.ifBlank { "unknown" })
                                    .put("session_id", sessionId)
                                    .put("success", false)
                                    .put("error", error.toString().take(2048))
                                    .put("result", snapshot())
                            )
                        }
                    } finally {
                        val elapsed = SystemClock.elapsedRealtime() - requestStarted
                        if (operationName == "mount" || elapsed > 2_000L) {
                            Log.i("AILPluginMount", "worker request end operation=$operationName elapsed_ms=$elapsed")
                        }
                    }
                }
            }
            runCatching { server.close() }
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            runCatching {
                val current = JSONObject(identityFile.readText())
                if (current.optString("session_id") == sessionId) identityFile.delete()
            }
        }
    }
}

private fun JSONArray?.stringSet(): Set<String> = buildSet {
    val source = this@stringSet ?: return@buildSet
    for (index in 0 until source.length()) add(source.getString(index))
}
