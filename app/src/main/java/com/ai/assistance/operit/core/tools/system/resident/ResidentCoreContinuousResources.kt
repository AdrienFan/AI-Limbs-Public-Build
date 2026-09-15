package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Session-bound resources for the Resident business owner.
 *
 * These tokens express ownership only. A held WakeLock token, a live process, or an Android
 * network callback is never treated as proof that Samsung/OEM freezer policy permits real work.
 * Real continuous-work success remains a later device-level Bridge -> plugin/Ubuntu assertion.
 */
internal class ResidentCoreContinuousResources(
    context: Context,
    private val sessionId: String
) {
    private val lock = Any()
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var callbackRegistered = false
    private var activeNetwork: Network? = null
    private var internetCapability = false
    private var validatedCapability = false
    private var notMeteredCapability = false
    private var transports: Set<String> = emptySet()
    private var active = false
    private var acquiredElapsedMs: Long? = null
    private var releasedElapsedMs: Long? = null
    private var lastNetworkEventElapsedMs: Long? = null
    private var lastError: String? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) {
                if (!active) return
                activeNetwork = network
                updateCapabilitiesLocked(network)
                lastNetworkEventElapsedMs = SystemClock.elapsedRealtime()
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(lock) {
                if (!active || activeNetwork != null && activeNetwork != network) return
                activeNetwork = network
                applyCapabilitiesLocked(capabilities)
                lastNetworkEventElapsedMs = SystemClock.elapsedRealtime()
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                if (!active || activeNetwork != network) return
                activeNetwork = null
                clearCapabilitiesLocked()
                lastNetworkEventElapsedMs = SystemClock.elapsedRealtime()
            }
        }
    }

    fun acquireForBusiness(): JSONObject = synchronized(lock) {
        if (active) return@synchronized snapshotLocked()
        check(!callbackRegistered && wakeLock?.isHeld != true) {
            "Resident Core cannot acquire a new continuous-resource lease while a prior release is incomplete"
        }
        lastError = null
        releasedElapsedMs = null
        val newWake = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AI-Limbs:ResidentCore"
        ).apply { setReferenceCounted(false) }
        var acquisitionStage = "acquire_wake_lock"
        try {
            newWake.acquire()
            check(newWake.isHeld) { "Resident Core PARTIAL_WAKE_LOCK token was not held after acquire" }
            wakeLock = newWake

            acquisitionStage = "register_network_callback"
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = true
            active = true
            acquiredElapsedMs = SystemClock.elapsedRealtime()
            acquisitionStage = "read_active_network"
            activeNetwork = connectivityManager.activeNetwork
            activeNetwork?.let(::updateCapabilitiesLocked) ?: clearCapabilitiesLocked()
            lastNetworkEventElapsedMs = SystemClock.elapsedRealtime()
            snapshotLocked()
        } catch (error: Throwable) {
            val cleanupErrors = mutableListOf<String>()
            lastError = "$acquisitionStage: $error".take(1024)
            if (callbackRegistered) {
                val callbackReleased = runCatching {
                    connectivityManager.unregisterNetworkCallback(networkCallback)
                }.onFailure {
                    cleanupErrors += "network callback rollback failed: ${it.message ?: it.javaClass.simpleName}"
                }.isSuccess
                if (callbackReleased) callbackRegistered = false
            }
            if (newWake.isHeld) {
                runCatching { newWake.release() }
                    .onFailure {
                        cleanupErrors += "WakeLock rollback failed: ${it.message ?: it.javaClass.simpleName}"
                    }
            }
            wakeLock = if (newWake.isHeld) newWake else null
            active = false
            clearCapabilitiesLocked()
            if (cleanupErrors.isNotEmpty()) {
                lastError = (lastError + "; " + cleanupErrors.joinToString("; ")).take(1024)
            }
            // Preserve the cause and failing framework operation for the Core request diagnostic.
            throw IllegalStateException(
                "Resident continuous resources failed at $acquisitionStage: $error", error
            )
        }
    }

    fun release(reason: String): JSONObject = synchronized(lock) {
        val errors = mutableListOf<String>()
        if (callbackRegistered) {
            val callbackReleased = runCatching {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            }.onFailure {
                errors += "network callback release failed: ${it.message ?: it.javaClass.simpleName}"
            }.isSuccess
            if (callbackReleased) callbackRegistered = false
        }

        wakeLock?.let { token ->
            if (token.isHeld) {
                runCatching { token.release() }
                    .onFailure { errors += "WakeLock release failed: ${it.message ?: it.javaClass.simpleName}" }
            }
            if (!token.isHeld) wakeLock = null
        }
        if (wakeLock?.isHeld == true) {
            errors += "WakeLock token still reports held after release"
        }

        active = false
        activeNetwork = null
        clearCapabilitiesLocked()
        releasedElapsedMs = SystemClock.elapsedRealtime()
        val residualResources = callbackRegistered || wakeLock?.isHeld == true
        if (residualResources && errors.isEmpty()) {
            errors += "continuous resources remain owned after release"
        }
        lastError = errors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.take(1024)
        JSONObject()
            .put("release_reason", reason.take(120))
            .put("release_confirmed", errors.isEmpty() && !residualResources)
            .put("release_errors", JSONArray(errors))
            .put("state", snapshotLocked())
    }

    fun snapshot(): JSONObject = synchronized(lock) { snapshotLocked() }

    private fun updateCapabilitiesLocked(network: Network) {
        val capabilities = runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull()
        if (capabilities == null) clearCapabilitiesLocked() else applyCapabilitiesLocked(capabilities)
    }

    private fun applyCapabilitiesLocked(capabilities: NetworkCapabilities) {
        internetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        validatedCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        notMeteredCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val currentTransports = mutableSetOf<String>()
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) currentTransports += "wifi"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) currentTransports += "cellular"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) currentTransports += "ethernet"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) currentTransports += "vpn"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) currentTransports += "bluetooth"
        transports = currentTransports
    }

    private fun clearCapabilitiesLocked() {
        internetCapability = false
        validatedCapability = false
        notMeteredCapability = false
        transports = emptySet()
    }

    private fun snapshotLocked(): JSONObject {
        val residualResources = callbackRegistered || wakeLock?.isHeld == true
        val state = when {
            active -> "active"
            residualResources -> "release_failed"
            releasedElapsedMs != null -> "released"
            else -> "detached"
        }
        return JSONObject()
        .put("owner", "resident_core")
        .put("owner_session", sessionId)
        .put("owner_pid", Process.myPid())
        .put("owner_uid", Process.myUid())
        .put("state", state)
        .put("acquired_elapsed_ms", acquiredElapsedMs ?: JSONObject.NULL)
        .put("released_elapsed_ms", releasedElapsedMs ?: JSONObject.NULL)
        .put("cpu_wake_requested", active)
        .put("cpu_wake_token_held", active && wakeLock?.isHeld == true)
        .put("cpu_wake_effective", JSONObject.NULL)
        .put("cpu_wake_evidence", "token_only_unverified")
        .put("network_callback_registered", callbackRegistered)
        .put("network_available", active && activeNetwork != null)
        .put("network_internet_capability", active && internetCapability)
        .put("network_validated", active && validatedCapability)
        .put("network_not_metered", active && notMeteredCapability)
        .put("network_transports", JSONArray(transports.sorted()))
        .put("network_last_event_elapsed_ms", lastNetworkEventElapsedMs ?: JSONObject.NULL)
        .put("network_real_io_verified", false)
        .put("network_effective", JSONObject.NULL)
        .put("last_error", lastError ?: JSONObject.NULL)
    }
}
