package com.ai.assistance.operit.core.tools.system.resident

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.R
import java.io.File

/**
 * AMS-owned process host for Resident descendants.
 *
 * Guardian/Core are deliberately forked from this Android app process instead of DEBUGGER/adbd.
 * That moves their inherited cgroup under the app UID and makes Wi-Fi/ADB teardown independent.
 */
internal class ResidentProcessHostService : Service() {
    companion object {
        private const val CHANNEL_ID = "ai_limbs_resident_host"
        private const val NOTIFICATION_ID = 0xA159
        private const val ACTION_KEEP_ALIVE = "com.ai.assistance.operit.resident.KEEP_HOST"
        private const val ACTION_START_GUARDIAN = "com.ai.assistance.operit.resident.START_GUARDIAN"
        private const val ACTION_START_CORE = "com.ai.assistance.operit.resident.START_CORE"
        private const val ACTION_STOP_HOST = "com.ai.assistance.operit.resident.STOP_HOST"
        private const val EXTRA_LAUNCH_ID = "launch_id"
        fun ensureRunning(context: Context) {
            start(context, ACTION_KEEP_ALIVE)
        }

        fun launchGuardian(context: Context) {
            start(context, ACTION_START_GUARDIAN)
        }

        fun launchCore(context: Context, launchId: String) {
            require(launchId.isNotBlank())
            start(context, ACTION_START_CORE) { putExtra(EXTRA_LAUNCH_ID, launchId) }
        }

        fun stopHost(context: Context) {
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, ResidentProcessHostService::class.java)) }
        }

        private inline fun start(
            context: Context,
            action: String,
            configure: Intent.() -> Unit = {}
        ) {
            val app = context.applicationContext
            val intent = Intent(app, ResidentProcessHostService::class.java).setAction(action).apply(configure)
            app.startForegroundService(intent)
        }
    }
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GUARDIAN -> runCatching { launchGuardian() }
                .onFailure { writeHostError("guardian", it) }
            ACTION_START_CORE -> {
                val launchId = intent.getStringExtra(EXTRA_LAUNCH_ID)
                if (launchId.isNullOrBlank()) {
                    writeHostError("core", IllegalArgumentException("Missing Core launch id"))
                } else {
                    runCatching { launchCore(launchId) }.onFailure { writeHostError("core", it) }
                }
            }
            ACTION_STOP_HOST -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private fun launchGuardian() {
        val stateDir = File(filesDir, "ai_limbs/resident")
        check(stateDir.mkdirs() || stateDir.isDirectory)
        val logFile = File(stateDir, "launcher.log")
        if (logFile.exists()) logFile.delete()

        val sourceApk = applicationInfo.sourceDir
        val command = listOf(
            "/system/bin/setsid",
            "/system/bin/app_process",
            "/system/bin",
            "--nice-name=${AiLimbsResidentRuntime.PROCESS_NAME}",
            "com.ai.assistance.operit.core.tools.system.resident.AiLimbsResidentMain",
            stateDir.absolutePath,
            packageName,
            BuildConfig.VERSION_CODE.toString(),
            sourceApk
        )
        startResidentProcess(command, logFile) { env ->
            env["CLASSPATH"] = sourceApk
        }
    }
    private fun launchCore(launchId: String) {
        val directory = File(filesDir, "ai_limbs/resident_core")
        check(directory.mkdirs() || directory.isDirectory)
        val request = File(directory, "launch.request")
        check(request.readText().trim() == launchId) { "Core launch request changed before process start" }

        val nativeLibraryDir = applicationInfo.nativeLibraryDir?.trim().orEmpty()
        check(nativeLibraryDir.isNotBlank() && File(nativeLibraryDir).isDirectory) {
            "Resident Core native library directory is unavailable: $nativeLibraryDir"
        }
        val sourceApk = applicationInfo.sourceDir
        val logFile = File(directory, "bootstrap.log")
        if (logFile.exists()) logFile.delete()
        val command = listOf(
            "/system/bin/setsid",
            "/system/bin/app_process",
            "-Djava.library.path=$nativeLibraryDir",
            "/system/bin",
            "--nice-name=ail_resident_core",
            "com.ai.assistance.operit.core.tools.system.resident.ResidentCoreMain",
            packageName,
            directory.absolutePath,
            launchId
        )
        startResidentProcess(command, logFile) { env ->
            env["CLASSPATH"] = sourceApk
            env["LD_LIBRARY_PATH"] =
                if (env["LD_LIBRARY_PATH"].isNullOrBlank()) nativeLibraryDir
                else "$nativeLibraryDir:${env["LD_LIBRARY_PATH"]}"
        }
    }

    private fun startResidentProcess(
        command: List<String>,
        logFile: File,
        configureEnvironment: (MutableMap<String, String>) -> Unit
    ) {
        logFile.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
        val builder = ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectErrorStream(true)
        configureEnvironment(builder.environment())
        builder.start()
    }

    private fun writeHostError(role: String, error: Throwable) {
        val directory = File(filesDir, "ai_limbs/resident_host")
        if (directory.mkdirs() || directory.isDirectory) {
            File(directory, "last-error.txt").writeText(
                "role=$role\nerror=${error.stackTraceToString().take(8192)}\n"
            )
        }
    }
    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI Limbs 常驻宿主",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持 AI Limbs 常驻内核独立于无线调试进程"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_limbs_notification)
            .setContentTitle("AI Limbs 常驻运行")
            .setContentText("Resident 内核已由独立 Android 进程托管")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()
    }
}