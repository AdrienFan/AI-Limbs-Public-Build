package com.ailimbs.freecessprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var client: RdcProbeClient
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiverRegistered = false
    private var networkCallbackRegistered = false
    private lateinit var connectivity: ConnectivityManager

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val pm = getSystemService(PowerManager::class.java)
            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    ProbeLog.i("SCREEN", "OFF interactive=${pm.isInteractive} idle=${pm.isDeviceIdleMode}")
                    if (BuildConfig.REAPPLY_FGS_ON_SCREEN_OFF) applyForeground("screen_off", force = true)
                    updateWakeLock("screen_off")
                    client.onHostSignal("screen_off")
                }
                Intent.ACTION_SCREEN_ON -> {
                    ProbeLog.i("SCREEN", "ON interactive=${pm.isInteractive} idle=${pm.isDeviceIdleMode}")
                    updateWakeLock("screen_on")
                    client.onHostSignal("screen_on")
                }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    ProbeLog.i("HOST", "DEVICE_IDLE_MODE_CHANGED idle=${pm.isDeviceIdleMode}")
                    client.onHostSignal("device_idle")
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            ProbeLog.i("NETWORK", "available=$network")
            client.onHostSignal("network_available")
        }
        override fun onLost(network: Network) {
            ProbeLog.i("NETWORK", "lost=$network")
            client.onHostSignal("network_lost")
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            ProbeLog.d("NETWORK", "caps validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} vpn=${caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}")
            client.onHostSignal("network_capabilities")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        connectivity = getSystemService(ConnectivityManager::class.java)
        client = RdcProbeClient(this, scope) {
            refreshNotification()
            updateWakeLock("rdc_state")
        }
        applyForeground("service_create", force = true)
        registerScreenReceiver()
        if (BuildConfig.USE_HOST_SIGNALS) registerNetworkCallback()
        ProbeLog.i("SERVICE", "created variant=${BuildConfig.PROBE_LABEL}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> client.start()
            ACTION_REPAIR -> client.rePair()
            ACTION_STOP -> {
                client.stop()
                releaseWakeLock("user_stop")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        refreshNotification()
        updateWakeLock("on_start_command")
        return START_STICKY
    }

    override fun onDestroy() {
        if (screenReceiverRegistered) runCatching { unregisterReceiver(screenReceiver) }
        if (networkCallbackRegistered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        releaseWakeLock("service_destroy")
        client.stop()
        scope.cancel()
        ProbeLog.i("SERVICE", "destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            if (BuildConfig.USE_HOST_SIGNALS) addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(screenReceiver, filter)
        screenReceiverRegistered = true
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
            .onSuccess { networkCallbackRegistered = true }
            .onFailure { ProbeLog.e("NETWORK", "registerDefaultNetworkCallback failed", it) }
    }

    private fun updateWakeLock(reason: String) {
        val pm = getSystemService(PowerManager::class.java)
        val running = ProbeRuntime.state.value.phase != "STOPPED"
        val shouldHold = !pm.isInteractive && running
        if (shouldHold) {
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:FreecessProbeScreenOff").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
                ProbeLog.i("WAKELOCK", "acquired reason=$reason idle=${pm.isDeviceIdleMode}")
            }
        } else {
            releaseWakeLock(reason)
        }
        ProbeRuntime.update { it.copy(wakeLockHeld = wakeLock?.isHeld == true) }
    }

    private fun releaseWakeLock(reason: String) {
        val wl = wakeLock ?: return
        if (wl.isHeld) runCatching { wl.release() }.onSuccess { ProbeLog.i("WAKELOCK", "released reason=$reason") }
        ProbeRuntime.update { it.copy(wakeLockHeld = false) }
    }

    private fun applyForeground(reason: String, force: Boolean) {
        val notification = buildNotification()
        var types = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        if (Build.VERSION.SDK_INT >= 34) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val fallbackTypes = types
        val pm = getSystemService(PowerManager::class.java)
        val allowlisted = runCatching { pm.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
        val trySystemExempted = BuildConfig.USE_SYSTEM_EXEMPTED && Build.VERSION.SDK_INT >= 34 && allowlisted
        if (trySystemExempted && Build.VERSION.SDK_INT >= 34) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        }
        try {
            startForegroundCompat(notification, types)
            ProbeRuntime.update { it.copy(activeFgsTypesHex = "0x${types.toString(16)}") }
            ProbeLog.i("FGS", "applied reason=$reason force=$force types=0x${types.toString(16)} allowlisted=$allowlisted")
        } catch (e: RuntimeException) {
            if (!trySystemExempted) throw e
            ProbeLog.w("FGS", "systemExempted rejected; fallback ${e.javaClass.simpleName}: ${e.message}", e)
            startForegroundCompat(notification, fallbackTypes)
            ProbeRuntime.update { it.copy(activeFgsTypesHex = "0x${fallbackTypes.toString(16)}") }
        }
    }

    private fun startForegroundCompat(notification: Notification, types: Int) {
        if (Build.VERSION.SDK_INT >= 29 && types != 0) startForeground(NOTIFICATION_ID, notification, types)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun refreshNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = ProbeRuntime.state.value
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = buildString {
            append(state.phase).append(" · ").append(state.detail)
            state.userCode?.takeIf { it.isNotBlank() }?.let { append(" · Code ").append(it) }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Freecess Probe · ${BuildConfig.PROBE_LABEL}")
            .setContentText(text.take(120))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Freecess Probe", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val ACTION_START = "com.ailimbs.freecessprobe.START"
        const val ACTION_STOP = "com.ailimbs.freecessprobe.STOP"
        const val ACTION_REPAIR = "com.ailimbs.freecessprobe.REPAIR"
        private const val CHANNEL_ID = "freecess_probe"
        private const val NOTIFICATION_ID = 643
    }
}
