package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.os.Binder
import android.os.IBinder
import com.ai.assistance.operit.core.tools.system.privilege.PrivilegeRuntime
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Core-only connection. Merely connecting does not transfer runtime ownership. */
internal class ResidentBackendBinding(
    private val context: Context,
    private val launchId: String,
    private val sessionId: String
) {
    private val lock = Any()
    private val lifetime = Binder()
    private val diagnostic = AtomicReference(
        JSONObject().put("state", "connecting").put("connected", false).toString()
    )
    private var server: IBinder? = null
    private var serverToken: String? = null
    private var serverInstance: String? = null
    private var recipient: IBinder.DeathRecipient? = null
    private var ownsRuntime = false
    private var prepared = false
    private var preparationAttempted = false
    private var closed = false
    private val rebindInFlight = AtomicBoolean(false)

    fun snapshot(): JSONObject = JSONObject(diagnostic.get())

    /** Runs beside the socket listener, because the receiver authenticates us through that socket. */
    fun connect() {
        try {
            val response = ResidentCoreBootstrap.requestBackend(context, launchId, sessionId)
            check(response.getString("session_id") == sessionId && response.getString("launch_id") == launchId) {
                "Core backend binding session mismatch"
            }
            val incoming = checkNotNull(response.getBinder("backend"))
            val token = checkNotNull(response.getString("backend_token"))
            val uid = response.getInt("backend_uid", -1)
            synchronized(lock) {
                if (closed) return
                PrivilegeRuntime.adoptResidentConnection(context, uid, incoming)
                val description = ResidentPermissionWire.describe(incoming)
                check(description.getInt("uid") == uid) { "Permission server identity mismatch" }
                val instance = description.getString("instance_id")
                val death = IBinder.DeathRecipient {
                    synchronized(lock) {
                        if (server == incoming) {
                            server = null
                            serverToken = null
                            serverInstance = null
                            recipient = null
                            ownsRuntime = false
                            prepared = false
                            preparationAttempted = false
                            diagnostic.set(
                                JSONObject()
                                    .put("state", "degraded")
                                    .put("connected", false)
                                    .put(
                                        "runtime_owner",
                                        when {
                                            ownsRuntime -> "resident_core"
                                            prepared -> "handoff_prepared"
                                            else -> "unavailable"
                                        }
                                    )
                                    .put("permission_backend_available", false)
                                    .put("degraded_reason", "permission_backend_died")
                                    .toString()
                            )
                            // Backend loss degrades privileged capabilities only. Resident business
                            // ownership must survive; no alternate backend is selected implicitly.
                        }
                    }
                }
                incoming.linkToDeath(death, 0)
                check(incoming.isBinderAlive) { "Permission backend died during Core registration" }
                server = incoming
                serverToken = token
                serverInstance = instance
                recipient = death
                ownsRuntime = false
                prepared = false
                preparationAttempted = false
                diagnostic.set(description.put("state", "ready").put("connected", true).toString())
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (!closed) diagnostic.set(JSONObject().put("state", "connection_failed")
                    .put("connected", false).put("error", error.toString().take(512)).toString())
            }
            System.err.println("Resident backend connection failed: $error")
        }
    }

    fun prepareHandoffAsync() {
        while (true) {
            val before = diagnostic.get()
            val state = JSONObject(before).getString("state")
            if (state == "prepared" || state == "preparing") return
            check(state == "ready") { "Core backend cannot prepare from $state" }
            if (!diagnostic.compareAndSet(before, JSONObject(before).put("state", "preparing").toString())) continue
            Thread({
                try { prepareHandoff() }
                catch (error: Exception) { System.err.println("Resident handoff preparation failed: $error") }
            }, "resident-backend-prepare").apply { isDaemon = true; start() }
            return
        }
    }

    private fun prepareHandoff() = synchronized(lock) {
        check(!closed && !ownsRuntime) { "Core backend is closed or already active" }
        val current = checkNotNull(server) { "Core backend is not ready" }
        preparationAttempted = true
        try {
            val result = ResidentPermissionWire.prepare(current, checkNotNull(serverToken), sessionId, lifetime)
            check(result.getString("instance_id") == serverInstance &&
                result.getString("core_session") == sessionId &&
                result.getInt("core_pid") == android.os.Process.myPid()) { "Prepared backend identity mismatch" }
            prepared = true
            check(current.isBinderAlive) { "Permission backend died during handoff preparation" }
            diagnostic.set(result.put("state", "prepared").put("connected", true).toString())
        } catch (error: Exception) {
            diagnostic.set(JSONObject().put("state", "prepare_failed").put("connected", current.isBinderAlive)
                .put("error", error.toString().take(512)).toString())
            throw error
        }
    }

    /** Called by the business bootstrap only after this process has acquired the kernel lease. */
    fun claimRuntimeOwnership() = synchronized(lock) {
        check(!closed && prepared && PluginPlatformKernel.isInitialized && PluginPlatformKernel.isStarted) {
            "Core must own a running plugin kernel before claiming its backend lifetime"
        }
        val current = checkNotNull(server) { "Core backend is not connected" }
        val result = ResidentPermissionWire.claim(current, checkNotNull(serverToken), sessionId, lifetime)
        check(result.getString("instance_id") == serverInstance &&
            result.getString("core_session") == sessionId &&
            result.getInt("core_pid") == android.os.Process.myPid()) { "Permission ownership acknowledgement mismatch" }
        ownsRuntime = true
        check(current.isBinderAlive) { "Permission backend died during ownership activation" }
        diagnostic.set(result.put("state", "owned").put("connected", true).toString())
    }

    fun claimRuntimeOwnershipIfPrepared(): Boolean {
        val state = snapshot().optString("state")
        if (state != "prepared") {
            publishDegraded("permission_backend_unavailable_at_business_start", null)
            return false
        }
        return try {
            claimRuntimeOwnership()
            true
        } catch (error: Exception) {
            publishDegraded("permission_backend_claim_failed", error)
            false
        }
    }

    fun rebindActiveBusinessAsync() {
        if (!rebindInFlight.compareAndSet(false, true)) return
        Thread({
            try {
                val alreadyOwned = synchronized(lock) {
                    check(!closed) { "Core backend is closed" }
                    ownsRuntime && server?.isBinderAlive == true
                }
                if (!alreadyOwned) {
                    connect()
                    check(snapshot().optString("state") == "ready") {
                        "Permission backend did not become ready during rebind: ${snapshot()}"
                    }
                    prepareHandoff()
                    claimRuntimeOwnership()
                }
            } catch (error: Exception) {
                publishDegraded("permission_backend_rebind_failed", error)
                System.err.println("Resident backend rebind failed: $error")
            } finally {
                rebindInFlight.set(false)
            }
        }, "resident-backend-rebind").apply { isDaemon = true; start() }
    }

    private fun publishDegraded(reason: String, error: Throwable?) = synchronized(lock) {
        if (closed) return@synchronized
        val value = JSONObject()
            .put("state", "degraded")
            .put("connected", server?.isBinderAlive == true)
            .put("runtime_owner", "resident_core")
            .put("permission_backend_available", server?.isBinderAlive == true)
            .put("degraded_reason", reason)
        if (error != null) value.put("error", error.toString().take(512))
        diagnostic.set(value.toString())
    }

    /** returnToHost is reserved for an explicit user stop after the business kernel has retired. */
    fun close(returnToHost: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        val current = server
        try {
            if (preparationAttempted && current != null && current.isBinderAlive) {
                check(!ownsRuntime || !PluginPlatformKernel.isStarted) { "Retire the Core kernel before releasing the backend" }
                ResidentPermissionWire.release(current, checkNotNull(serverToken), sessionId, lifetime, returnToHost)
            }
        } finally {
            recipient?.let { death ->
                if (current != null) {
                    try { current.unlinkToDeath(death, 0) }
                    catch (error: Exception) { System.err.println("Core backend death link cleanup failed: $error") }
                }
            }
            server = null
            serverToken = null
            recipient = null
            ownsRuntime = false
            prepared = false
            preparationAttempted = false
            diagnostic.set(JSONObject().put("state", "closed").put("connected", false).toString())
        }
    }
}
