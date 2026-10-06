package com.ai.assistance.operit.core.tools.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Host-only camera foreground ownership. No plugin UI or additional consent is defined here. */
internal object HostCameraForeground {
    const val REQUEST_ID = "host_camera_foreground_request"
    const val STOP = "host.camera.foreground.stop"
    private val startLock = Mutex()
    private val ownershipLock = Any()
    private var stopping: CompletableDeferred<Unit>? = null
    private val leases = ConcurrentHashMap.newKeySet<String>()
    private val requests = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    @Volatile var ready = false
        private set

    suspend fun acquire(context: Context, sessionId: String) = startLock.withLock {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Camera visual sessions require Android 10 or newer" }
        synchronized(ownershipLock) { stopping }?.await()
        check(leases.add(sessionId)) { "Camera foreground lease already exists" }
        try {
            if (!ready) {
                val id = UUID.randomUUID().toString()
                val result = CompletableDeferred<Unit>()
                requests[id] = result
                try {
                    // Android requires visible Activity context when creating a while-in-use camera FGS.
                    // This is automatic foreground acquisition after policy/permission checks, not another ASK.
                    withContext(Dispatchers.Main) {
                        context.startActivity(Intent(context, HostCameraForegroundActivity::class.java)
                            .putExtra(REQUEST_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    withTimeout(15_000L) { result.await() }
                } finally { requests.remove(id) }
            }
        } catch (error: Throwable) {
            leases.remove(sessionId)
            if (leases.isEmpty()) context.stopService(Intent(context, HostCameraForegroundService::class.java))
            throw error
        }
    }

    fun release(context: Context, sessionId: String) {
        val stop = synchronized(ownershipLock) {
            leases.remove(sessionId)
            if (leases.isEmpty() && ready) {
                ready = false
                stopping = CompletableDeferred()
                true
            } else false
        }
        if (stop) context.stopService(Intent(context, HostCameraForegroundService::class.java))
    }

    suspend fun awaitStopped() { synchronized(ownershipLock) { stopping }?.await() }

    fun request(id: String): CompletableDeferred<Unit>? = requests[id]
    fun activated(id: String) {
        ready = true
        requests[id]?.complete(Unit)
    }
    fun failed(id: String, error: Throwable) { requests[id]?.completeExceptionally(error) }
    fun destroyed() {
        val completed = synchronized(ownershipLock) {
            ready = false
            leases.clear()
            stopping ?: CompletableDeferred<Unit>().also { stopping = it }
        }
        try {
            requests.values.forEach { it.completeExceptionally(IllegalStateException("Camera foreground owner stopped")) }
            VisualHostRuntime.cameraForegroundStopped()
        } finally {
            completed.complete(Unit)
            synchronized(ownershipLock) { if (stopping === completed) stopping = null }
        }
    }

}

/** A short visible acquisition surface establishes legal foreground camera ownership without a confirmation button. */
class HostCameraForegroundActivity : ComponentActivity() {
    private var requestId = ""
    private var started = false
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(HostCameraForeground.REQUEST_ID).orEmpty()
        started = savedInstanceState?.getBoolean("started") == true
        val result = HostCameraForeground.request(requestId)
        if (result == null) { delivered = true; finish(); return }
        setContentView(TextView(this).apply {
            text = "正在开启相机视觉"
            gravity = Gravity.CENTER
            textSize = 18f
        })
        lifecycleScope.launch {
            try {
                result.await()
                delivered = true
                finish()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { delivered = true; finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (started || delivered) return
        started = true
        try {
            startForegroundService(Intent(this, HostCameraForegroundService::class.java)
                .putExtra(HostCameraForeground.REQUEST_ID, requestId))
        } catch (error: Exception) {
            HostCameraForeground.failed(requestId, error)
            delivered = true
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("started", started)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing && !delivered) {
            HostCameraForeground.failed(requestId, IllegalStateException("Camera foreground acquisition cancelled"))
        }
        super.onDestroy()
    }
}

class HostCameraForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == HostCameraForeground.STOP) { stopSelf(); return START_NOT_STICKY }
        val id = intent?.getStringExtra(HostCameraForeground.REQUEST_ID).orEmpty()
        if (HostCameraForeground.request(id) == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("host-camera-visual", "摄像头视觉", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 2002,
                Intent(this, HostCameraForegroundService::class.java).setAction(HostCameraForeground.STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this, "host-camera-visual")
                .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("摄像头视觉会话")
                .setContentText("AI Limbs 正在使用相机，可随时停止").setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止摄像头", stop).build()
            startForeground(2002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            HostCameraForeground.activated(id)
        } catch (error: Exception) {
            HostCameraForeground.failed(id, error)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        HostCameraForeground.destroyed()
        super.onDestroy()
    }
}
