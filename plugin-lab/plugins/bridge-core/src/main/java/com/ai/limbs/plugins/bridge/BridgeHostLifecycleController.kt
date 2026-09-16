package com.ai.limbs.plugins.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeHostSignal
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeNetworkState
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeNetworkTransport
import com.ai.assistance.operit.integrations.ailimbs.PluginBridgeManager

internal class BridgeHostLifecycleController(
    context: Context,
    private val managerProvider: () -> PluginBridgeManager?
) : AutoCloseable {
    private val appContext = context.applicationContext
    private var screenReceiverRegistered = false
    private var networkCallbackRegistered = false
    @Volatile
    private var networkState = AiLimbsBridgeNetworkState.UNKNOWN
    @Volatile
    private var networkTransport = AiLimbsBridgeNetworkTransport.NONE
    private var screenOffWakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var stopped = false

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                publishNetworkState("network_available")
            }

            override fun onLost(network: Network) {
                publishNetworkState("network_lost")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                publishNetworkState("network_capabilities_changed")
            }
        }

    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (stopped) return
                val action = intent?.action ?: return
                val powerManager =
                    appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                when (action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        managerProvider()?.onHostSignal(AiLimbsBridgeHostSignal.ScreenOff)
                        updateScreenOffWakeLock("screen_broadcast:$action")
                        logHostHealth("screen_off")
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        managerProvider()?.onHostSignal(AiLimbsBridgeHostSignal.ScreenOn)
                        updateScreenOffWakeLock("screen_broadcast:$action")
                        logHostHealth("screen_on")
                    }
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        managerProvider()?.onHostSignal(
                            AiLimbsBridgeHostSignal.DeviceIdleChanged(
                                powerManager.isDeviceIdleMode
                            )
                        )
                        logHostHealth("device_idle_changed")
                    }
                }
            }
        }

    fun start() {
        check(!stopped) { "Bridge host lifecycle controller is stopped" }
        registerScreenStateReceiver()
        registerNetworkCallback()
        publishNetworkState("lifecycle_started", force = true)
        publishPowerState("lifecycle_started")
    }
    fun onManagerChanged(reason: String) {
        if (stopped) return
        publishNetworkState(reason, force = true)
        publishPowerState(reason)
        updateScreenOffWakeLock(reason)
        logHostHealth(reason)
    }

    fun onManagerStateChanged(reason: String) {
        if (stopped) return
        updateScreenOffWakeLock(reason)
        logHostHealth(reason)
    }

    private fun registerScreenStateReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                    screenStateReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(screenStateReceiver, filter)
            }
            screenReceiverRegistered = true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to register Bridge screen receiver", error)
        }
    }
    private fun unregisterScreenStateReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { appContext.unregisterReceiver(screenStateReceiver) }
            .onFailure { Log.w(TAG, "Failed to unregister Bridge screen receiver", it) }
        screenReceiverRegistered = false
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
            publishNetworkState("network_callback_registered", force = true)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to register Bridge network callback", error)
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "Failed to unregister Bridge network callback", it) }
        networkCallbackRegistered = false
    }
    private fun publishNetworkState(reason: String, force: Boolean = false) {
        if (stopped) return
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities =
            activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val nextState =
            when {
                activeNetwork == null -> AiLimbsBridgeNetworkState.LOST
                capabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                ) == true -> AiLimbsBridgeNetworkState.VALIDATED
                else -> AiLimbsBridgeNetworkState.AVAILABLE_UNVALIDATED
            }
        val nextTransport =
            when {
                capabilities == null -> AiLimbsBridgeNetworkTransport.NONE
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
                    AiLimbsBridgeNetworkTransport.VPN
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                    AiLimbsBridgeNetworkTransport.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    AiLimbsBridgeNetworkTransport.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                    AiLimbsBridgeNetworkTransport.ETHERNET
                else -> AiLimbsBridgeNetworkTransport.OTHER
            }
        val changed = nextState != networkState || nextTransport != networkTransport
        if (!changed && !force) return
        networkState = nextState
        networkTransport = nextTransport
        managerProvider()?.onHostSignal(
            AiLimbsBridgeHostSignal.NetworkChanged(nextState, nextTransport)
        )
        logHostHealth(reason)
    }

    private fun publishPowerState(reason: String) {
        if (stopped) return
        val currentManager = managerProvider() ?: return
        val powerManager =
            appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        currentManager.onHostSignal(
            if (powerManager.isInteractive) {
                AiLimbsBridgeHostSignal.ScreenOn
            } else {
                AiLimbsBridgeHostSignal.ScreenOff
            }
        )
        currentManager.onHostSignal(
            AiLimbsBridgeHostSignal.DeviceIdleChanged(powerManager.isDeviceIdleMode)
        )
        logHostHealth(reason)
    }
    private fun updateScreenOffWakeLock(reason: String) {
        val powerManager =
            appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val currentManager = managerProvider()
        val shouldHold =
            !stopped &&
                !powerManager.isInteractive &&
                currentManager != null &&
                (currentManager.shouldKeepAlive ||
                    currentManager.hasActivePairingTransaction) &&
                currentManager.requiresScreenOffCpuKeepAlive
        if (!shouldHold) {
            releaseScreenOffWakeLock(reason)
            return
        }
        if (screenOffWakeLock == null) {
            screenOffWakeLock =
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "${appContext.packageName}:PluginBridgeScreenOff"
                ).apply { setReferenceCounted(false) }
        }
        if (screenOffWakeLock?.isHeld != true) {
            screenOffWakeLock?.acquire()
            Log.i(TAG, "Bridge screen-off WakeLock acquired: reason=$reason")
        }
    }

    private fun releaseScreenOffWakeLock(reason: String) {
        val wakeLock = screenOffWakeLock ?: return
        if (wakeLock.isHeld) {
            runCatching { wakeLock.release() }
                .onSuccess {
                    Log.i(TAG, "Bridge screen-off WakeLock released: reason=$reason")
                }
                .onFailure {
                    Log.w(TAG, "Failed to release Bridge screen-off WakeLock", it)
                }
        }
        screenOffWakeLock = null
    }

    private fun logHostHealth(reason: String) {
        val powerManager =
            appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val currentManager = managerProvider()
        Log.i(
            TAG,
            "Bridge host health: reason=$reason, " +
                "interactive=${powerManager.isInteractive}, " +
                "deviceIdle=${powerManager.isDeviceIdleMode}, " +
                "wakeLockHeld=${screenOffWakeLock?.isHeld == true}, " +
                "network=$networkState/$networkTransport, " +
                "bridge=${currentManager?.state?.value?.phase}"
        )
    }
    override fun close() {
        if (stopped) return
        stopped = true
        unregisterNetworkCallback()
        unregisterScreenStateReceiver()
        releaseScreenOffWakeLock("lifecycle_stopped")
    }

    companion object {
        private const val TAG = "BridgeHostLifecycle"
    }
}
