package com.ai.assistance.operit.api.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.RemoteViews
import android.graphics.PixelFormat
import com.ai.assistance.operit.util.AppLogger
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.drawable.IconCompat
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.speech.PersonalWakeListener
import com.ai.assistance.operit.api.speech.SpeechPrerollStore
import com.ai.assistance.operit.api.speech.SpeechService
import com.ai.assistance.operit.api.speech.SpeechServiceFactory
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.core.chat.AIMessageManager
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.core.application.ForegroundServiceCompat
import com.ai.assistance.operit.data.preferences.ExternalHttpApiConfig
import com.ai.assistance.operit.data.preferences.ExternalHttpApiPreferences
import com.ai.assistance.operit.integrations.http.ExternalChatHttpServer
import com.ai.assistance.operit.integrations.http.ExternalChatHttpState
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeHostSignal
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeManager
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeNetworkState
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeNetworkTransport
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgePhase
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeState
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.services.FloatingChatService
import com.ai.assistance.operit.services.UIDebuggerService
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.WakeWordPreferences
import com.ai.assistance.operit.data.repository.WorkflowRepository
import com.ai.assistance.operit.ui.main.MainActivity
import com.ai.assistance.operit.util.WaifuMessageProcessor
import com.ai.assistance.operit.widget.ToolPkgDesktopWidgetHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.exitProcess
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private fun AudioRecordingConfiguration.tryGetClientUid(): Int? {
    return try {
        val method =
            javaClass.methods.firstOrNull { it.name == "getClientUid" && it.parameterTypes.isEmpty() }
        val value = method?.invoke(this)
        value as? Int
    } catch (_: Exception) {
        null
    }
}

private fun AudioRecordingConfiguration.tryGetClientPackageName(): String? {
    return try {
        val method =
            javaClass.methods.firstOrNull { it.name == "getClientPackageName" && it.parameterTypes.isEmpty() }
        val value = method?.invoke(this)
        value as? String
    } catch (_: Exception) {
        null
    }
}

/** 前台服务，用于在AI进行长时间处理时保持应用活跃，防止被系统杀死。 该服务不执行实际工作，仅通过显示一个持久通知来提升应用的进程优先级。 */
class AIForegroundService : Service() {

    companion object {
        private const val TAG = "AIForegroundService"
        private const val NOTIFICATION_ID = 1
        private const val LEGACY_RDC_NOTIFICATION_ID = 7320
        private const val REPLY_NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "AI_SERVICE_CHANNEL"
        private const val REPLY_CHANNEL_ID_PREFIX = "AI_REPLY_COMPLETE_CHANNEL"
        private val REPLY_VIBRATION_PATTERN = longArrayOf(0L, 250L, 150L, 250L)

        private const val ACTION_CANCEL_CURRENT_OPERATION = "com.ai.assistance.operit.action.CANCEL_CURRENT_OPERATION"
        private const val REQUEST_CODE_CANCEL_CURRENT_OPERATION = 9002

        private const val ACTION_EXIT_APP = "com.ai.assistance.operit.action.EXIT_APP"
        private const val REQUEST_CODE_EXIT_APP = 9003
        private const val REQUEST_CODE_BRIDGE_CONNECT = 9010
        private const val REQUEST_CODE_BRIDGE_STOP = 9011
        private const val REQUEST_CODE_BRIDGE_RECONNECT = 9012
        private const val REQUEST_CODE_BRIDGE_RECOVER = 9018
        private const val REQUEST_CODE_BRIDGE_REPAIR = 9013
        private const val REQUEST_CODE_BRIDGE_OPEN_AUTH = 9014
        private const val REQUEST_CODE_FOREGROUND_NOTIFICATION_DISMISSED = 9015
        private const val REQUEST_CODE_BRIDGE_REFRESH = 9016
        private const val REQUEST_CODE_BRIDGE_CENTER = 9017
        private const val REQUEST_CODE_VOICE_FLOATING = 9005
        private const val NOTIFICATION_WATCHDOG_INTERVAL_MS = 15_000L

        private const val ACTION_TOGGLE_WAKE_LISTENING = "com.ai.assistance.operit.action.TOGGLE_WAKE_LISTENING"
        private const val REQUEST_CODE_TOGGLE_WAKE_LISTENING = 9006
        private const val REPLY_NOTIFICATION_TAG_PREFIX = "ai_reply:"

        private const val ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_IME =
            "com.ai.assistance.operit.action.SET_WAKE_LISTENING_SUSPENDED_FOR_IME"
        private const val EXTRA_IME_VISIBLE = "extra_ime_visible"

        private const val ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_FLOATING_FULLSCREEN =
            "com.ai.assistance.operit.action.SET_WAKE_LISTENING_SUSPENDED_FOR_FLOATING_FULLSCREEN"
        private const val EXTRA_FLOATING_FULLSCREEN_ACTIVE = "extra_floating_fullscreen_active"

        const val ACTION_PREPARE_WAKE_HANDOFF =
            "com.ai.assistance.operit.action.PREPARE_WAKE_HANDOFF"

        private const val ACTION_ENSURE_MICROPHONE_FOREGROUND =
            "com.ai.assistance.operit.action.ENSURE_MICROPHONE_FOREGROUND"
        private const val ACTION_START_OR_REFRESH_EXTERNAL_HTTP =
            "com.ai.assistance.operit.action.START_OR_REFRESH_EXTERNAL_HTTP"
        private const val ACTION_STOP_EXTERNAL_HTTP =
            "com.ai.assistance.operit.action.STOP_EXTERNAL_HTTP"

        const val ACTION_BRIDGE_CONNECT =
            "com.ai.assistance.operit.action.AI_LIMBS_BRIDGE_CONNECT"
        const val ACTION_BRIDGE_STOP =
            "com.ai.assistance.operit.action.AI_LIMBS_BRIDGE_STOP"
        const val ACTION_BRIDGE_RECONNECT =
            "com.ai.assistance.operit.action.AI_LIMBS_BRIDGE_RECONNECT"
        const val ACTION_BRIDGE_RECOVER =
            "com.ai.assistance.operit.action.AI_LIMBS_BRIDGE_RECOVER"
        const val ACTION_BRIDGE_REPAIR =
            "com.ai.assistance.operit.action.AI_LIMBS_BRIDGE_REPAIR"
        const val ACTION_BRIDGE_OPEN_AUTH =
            "com.ai.assistance.operit.action.AI_LIMBS_BRIDGE_OPEN_AUTH"
        const val ACTION_BRIDGE_REFRESH =
            "com.ai.assistance.operit.action.AI_LIMBS_BRIDGE_REFRESH"
        private const val ACTION_BRIDGE_SELECT_PROVIDER =
            "com.ai.assistance.operit.action.AI_LIMBS_BRIDGE_SELECT_PROVIDER"
        private const val EXTRA_BRIDGE_PROVIDER_ID = "extra_bridge_provider_id"
        private const val ACTION_FOREGROUND_NOTIFICATION_DISMISSED =
            "com.ai.assistance.operit.action.AI_LIMBS_FOREGROUND_NOTIFICATION_DISMISSED"

        @Volatile
        private var lastRequestedImeVisible: Boolean = false

        // 静态标志，用于从外部检查服务是否正在运行
        val isRunning = java.util.concurrent.atomic.AtomicBoolean(false)
        private val activeReplyNotificationTags = ConcurrentHashMap.newKeySet<String>()
        private val externalHttpStateFlow = MutableStateFlow(ExternalChatHttpState())
        val externalHttpState = externalHttpStateFlow.asStateFlow()
        
        // Intent extras keys
        const val EXTRA_CHARACTER_NAME = "extra_character_name"
        const val EXTRA_AVATAR_URI = "extra_avatar_uri"
        const val EXTRA_STATE = "extra_state"
        const val STATE_RUNNING = "running"
        const val STATE_IDLE = "idle"

        private fun buildReplyNotificationTag(chatId: String?): String {
            val suffix = chatId?.ifBlank { "default" } ?: "default"
            return "$REPLY_NOTIFICATION_TAG_PREFIX$suffix"
        }

        private fun createMainActivityPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )
        }

        private fun buildReplyNotificationChannelId(
            enableSound: Boolean,
            enableVibration: Boolean
        ): String =
            when {
                enableSound && enableVibration -> "${REPLY_CHANNEL_ID_PREFIX}_sound_vibration"
                enableSound -> "${REPLY_CHANNEL_ID_PREFIX}_sound"
                enableVibration -> "${REPLY_CHANNEL_ID_PREFIX}_vibration"
                else -> "${REPLY_CHANNEL_ID_PREFIX}_silent"
            }

        private fun getReplyNotificationChannelNameRes(
            enableSound: Boolean,
            enableVibration: Boolean
        ): Int =
            when {
                enableSound && enableVibration -> R.string.service_chat_complete_reminder_sound_vibration
                enableSound -> R.string.service_chat_complete_reminder_sound
                enableVibration -> R.string.service_chat_complete_reminder_vibration
                else -> R.string.service_chat_complete_reminder
            }

        private fun ensureReplyNotificationChannel(
            context: Context,
            enableSound: Boolean,
            enableVibration: Boolean
        ): String {
            val channelId = buildReplyNotificationChannelId(enableSound, enableVibration)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return channelId
            }
            val replyChannel =
                NotificationChannel(
                    channelId,
                    context.getString(
                        getReplyNotificationChannelNameRes(
                            enableSound = enableSound,
                            enableVibration = enableVibration
                        )
                    ),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.service_notify_when_complete)
                    setSound(
                        if (enableSound) Settings.System.DEFAULT_NOTIFICATION_URI else null,
                        if (enableSound) {
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        } else {
                            null
                        }
                    )
                    enableVibration(enableVibration)
                    vibrationPattern =
                        if (enableVibration) {
                            REPLY_VIBRATION_PATTERN
                        } else {
                            longArrayOf(0L)
                        }
                }
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(replyChannel)
            return channelId
        }

        private fun loadBitmapFromUri(context: Context, uriString: String): Bitmap? {
            return try {
                val uri = Uri.parse(uriString)
                val inputStream: InputStream? =
                    when (uri.scheme) {
                        "file" -> {
                            val path = uri.path
                            if (path != null && path.startsWith("/android_asset/")) {
                                context.assets.open(path.removePrefix("/android_asset/"))
                            } else if (!path.isNullOrEmpty()) {
                                FileInputStream(path)
                            } else {
                                null
                            }
                        }
                        null -> {
                            if (uriString.startsWith("/android_asset/")) {
                                context.assets.open(uriString.removePrefix("/android_asset/"))
                            } else {
                                try {
                                    FileInputStream(uriString)
                                } catch (_: Exception) {
                                    context.contentResolver.openInputStream(uri)
                                }
                            }
                        }
                        else -> context.contentResolver.openInputStream(uri)
                    }
                inputStream?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "从URI加载Bitmap失败: ${e.message}", e)
                null
            }
        }

        fun notifyReplyCompleted(
            context: Context,
            chatId: String?,
            characterName: String?,
            rawReplyContent: String?,
            avatarUri: String?,
            notifyReplyOverride: Boolean? = null
        ) {
            try {
                AppLogger.d(TAG, "检查是否需要发送会话完成通知: chatId=$chatId")

                if (ActivityLifecycleManager.getCurrentActivity() != null) {
                    AppLogger.d(TAG, "应用在前台，无需发送会话完成通知")
                    return
                }

                val appContext = context.applicationContext
                val displayPreferences = DisplayPreferencesManager.getInstance(appContext)
                val globalEnableReplyNotification = runBlocking {
                    displayPreferences.enableReplyNotification.first()
                }
                val shouldNotify = notifyReplyOverride ?: globalEnableReplyNotification
                if (!shouldNotify) {
                    AppLogger.d(TAG, "回复通知已禁用，跳过发送")
                    return
                }

                if (rawReplyContent.isNullOrBlank()) {
                    AppLogger.d(TAG, "回复内容为空，跳过发送回复通知: chatId=$chatId")
                    return
                }

                val enableReplyNotificationSound = runBlocking {
                    displayPreferences.enableReplyNotificationSound.first()
                }
                val enableReplyNotificationVibration = runBlocking {
                    displayPreferences.enableReplyNotificationVibration.first()
                }
                val replyChannelId =
                    ensureReplyNotificationChannel(
                        context = appContext,
                        enableSound = enableReplyNotificationSound,
                        enableVibration = enableReplyNotificationVibration
                    )

                val cleanedReplyContent = WaifuMessageProcessor.cleanContentForWaifu(rawReplyContent)
                var notificationDefaults = NotificationCompat.DEFAULT_LIGHTS
                if (enableReplyNotificationSound) {
                    notificationDefaults = notificationDefaults or NotificationCompat.DEFAULT_SOUND
                }
                if (enableReplyNotificationVibration) {
                    notificationDefaults = notificationDefaults or NotificationCompat.DEFAULT_VIBRATE
                }
                val notificationBuilder =
                    NotificationCompat.Builder(appContext, replyChannelId)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(
                            characterName
                                ?: appContext.getString(R.string.notification_ai_reply_title)
                        )
                        .setContentText(
                            cleanedReplyContent
                                .take(100)
                                .ifEmpty {
                                    appContext.getString(R.string.notification_ai_reply_content)
                                }
                        )
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setDefaults(notificationDefaults)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setContentIntent(createMainActivityPendingIntent(appContext))
                        .setAutoCancel(true)

                if (cleanedReplyContent.isNotEmpty()) {
                    notificationBuilder.setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText(cleanedReplyContent)
                            .setBigContentTitle(
                                characterName
                                    ?: appContext.getString(R.string.notification_ai_reply_title)
                            )
                    )
                }

                if (!avatarUri.isNullOrEmpty()) {
                    val bitmap = loadBitmapFromUri(appContext, avatarUri)
                    if (bitmap != null) {
                        notificationBuilder.setLargeIcon(bitmap)
                    }
                }

                val manager =
                    appContext.getSystemService(Context.NOTIFICATION_SERVICE)
                        as NotificationManager
                val tag = buildReplyNotificationTag(chatId)
                activeReplyNotificationTags.add(tag)
                manager.notify(tag, REPLY_NOTIFICATION_ID, notificationBuilder.build())
                AppLogger.d(TAG, "AI回复通知已发送: chatId=$chatId, tag=$tag")
            } catch (e: Exception) {
                AppLogger.e(TAG, "发送AI回复通知失败: ${e.message}", e)
            }
        }

        fun setWakeListeningSuspendedForIme(context: Context, imeVisible: Boolean) {
            lastRequestedImeVisible = imeVisible
            if (!isRunning.get()) return
            val intent = Intent(context, AIForegroundService::class.java).apply {
                action = ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_IME
                putExtra(EXTRA_IME_VISIBLE, imeVisible)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to request IME wake listening suspend: ${e.message}", e)
            }
        }

        fun setWakeListeningSuspendedForFloatingFullscreen(context: Context, active: Boolean) {
            if (!isRunning.get()) return
            val intent = Intent(context, AIForegroundService::class.java).apply {
                action = ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_FLOATING_FULLSCREEN
                putExtra(EXTRA_FLOATING_FULLSCREEN_ACTIVE, active)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "Failed to request floating fullscreen wake listening suspend: ${e.message}",
                    e
                )
            }
        }

        fun ensureMicrophoneForeground(context: Context, forceStart: Boolean = false) {
            val appContext = context.applicationContext
            if (!forceStart && !isRunning.get() && !hasPersistentForegroundResponsibilityConfigured(appContext)) {
                return
            }

            val intent = Intent(appContext, AIForegroundService::class.java).apply {
                action = ACTION_ENSURE_MICROPHONE_FOREGROUND
            }
            try {
                if (isRunning.get()) {
                    appContext.startService(intent)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appContext.startForegroundService(intent)
                    } else {
                        appContext.startService(intent)
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to request microphone foreground", e)
            }
        }

        fun ensureRunningForExternalHttp(context: Context) {
            startServiceForAction(context, ACTION_START_OR_REFRESH_EXTERNAL_HTTP)
        }

        fun refreshBackgroundKeepAlive(context: Context) {
            val appContext = context.applicationContext
            val keepAliveEnabled = runCatching {
                runBlocking {
                    DisplayPreferencesManager.getInstance(appContext).enableBackgroundKeepAlive.first()
                }
            }.getOrDefault(false)
            if (!keepAliveEnabled && !isRunning.get()) {
                return
            }
            val intent = Intent(appContext, AIForegroundService::class.java).apply {
                putExtra(EXTRA_STATE, STATE_IDLE)
            }
            try {
                if (isRunning.get()) {
                    appContext.startService(intent)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to refresh background keep alive: ${e.message}", e)
            }
        }

        fun stopExternalHttp(context: Context) {
            externalHttpStateFlow.value =
                externalHttpStateFlow.value.copy(isRunning = false, lastError = null)
            if (!isRunning.get()) {
                return
            }
            startServiceForAction(context, ACTION_STOP_EXTERNAL_HTTP)
        }

        fun requestBridgeAction(context: Context, action: BridgeAction) {
            startServiceForAction(context, bridgeIntentAction(action))
        }

        fun requestBridgeProviderSelection(context: Context, providerId: String) {
            require(providerId.isNotBlank()) {
                "Bridge provider id must not be blank"
            }
            val appContext = context.applicationContext
            val intent = Intent(appContext, AIForegroundService::class.java).apply {
                action = ACTION_BRIDGE_SELECT_PROVIDER
                putExtra(EXTRA_BRIDGE_PROVIDER_ID, providerId)
            }
            startServiceIntent(context, intent)
        }

        private fun bridgeIntentAction(action: BridgeAction): String =
            when (action) {
                BridgeAction.CONNECT -> ACTION_BRIDGE_CONNECT
                BridgeAction.STOP -> ACTION_BRIDGE_STOP
                BridgeAction.RECONNECT -> ACTION_BRIDGE_RECONNECT
                BridgeAction.RECOVER -> ACTION_BRIDGE_RECOVER
                BridgeAction.REPAIR -> ACTION_BRIDGE_REPAIR
                BridgeAction.OPEN_AUTH -> ACTION_BRIDGE_OPEN_AUTH
                BridgeAction.REFRESH -> ACTION_BRIDGE_REFRESH
            }

        private fun bridgeActionFromIntent(action: String?): BridgeAction? =
            when (action) {
                ACTION_BRIDGE_CONNECT -> BridgeAction.CONNECT
                ACTION_BRIDGE_STOP -> BridgeAction.STOP
                ACTION_BRIDGE_RECONNECT -> BridgeAction.RECONNECT
                ACTION_BRIDGE_RECOVER -> BridgeAction.RECOVER
                ACTION_BRIDGE_REPAIR -> BridgeAction.REPAIR
                ACTION_BRIDGE_OPEN_AUTH -> BridgeAction.OPEN_AUTH
                ACTION_BRIDGE_REFRESH -> BridgeAction.REFRESH
                else -> null
            }

        private fun bridgeActionRequestCode(action: BridgeAction): Int =
            when (action) {
                BridgeAction.CONNECT -> REQUEST_CODE_BRIDGE_CONNECT
                BridgeAction.STOP -> REQUEST_CODE_BRIDGE_STOP
                BridgeAction.RECONNECT -> REQUEST_CODE_BRIDGE_RECONNECT
                BridgeAction.RECOVER -> REQUEST_CODE_BRIDGE_RECOVER
                BridgeAction.REPAIR -> REQUEST_CODE_BRIDGE_REPAIR
                BridgeAction.OPEN_AUTH -> REQUEST_CODE_BRIDGE_OPEN_AUTH
                BridgeAction.REFRESH -> REQUEST_CODE_BRIDGE_REFRESH
            }

        private fun startServiceForAction(context: Context, action: String) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, AIForegroundService::class.java).apply {
                this.action = action
            }
            startServiceIntent(context, intent)
        }

        private fun startServiceIntent(context: Context, intent: Intent) {
            val appContext = context.applicationContext
            try {
                if (isRunning.get()) {
                    appContext.startService(intent)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "Failed to start action ${intent.action}: ${e.message}",
                    e
                )
            }
        }

        private fun hasPersistentForegroundResponsibilityConfigured(context: Context): Boolean {
            val appContext = context.applicationContext
            val alwaysListeningEnabled = runCatching {
                runBlocking {
                    WakeWordPreferences(appContext).alwaysListeningEnabledFlow.first()
                }
            }.getOrDefault(false)
            val backgroundKeepAliveEnabled = runCatching {
                runBlocking {
                    DisplayPreferencesManager.getInstance(appContext).enableBackgroundKeepAlive.first()
                }
            }.getOrDefault(false)
            val externalHttpEnabled = runCatching {
                ExternalHttpApiPreferences.getInstance(appContext).getConfigSync().let { config ->
                    config.enabled && ExternalHttpApiPreferences.isValidPort(config.port)
                }
            }.getOrDefault(false)
            return alwaysListeningEnabled || backgroundKeepAliveEnabled || externalHttpEnabled ||
                AiLimbsBridgeManager.shouldKeepAlive(appContext)
        }
    }

    private fun updateWakeListeningSuspendedForIme(imeVisible: Boolean) {
        if (wakeListeningSuspendedForIme == imeVisible) return
        wakeListeningSuspendedForIme = imeVisible
        AppLogger.d(TAG, "Wake listening suspended by IME: $wakeListeningSuspendedForIme")
        applyWakeListeningState()
    }

    private fun updateWakeListeningSuspendedForExternalRecording(externalRecording: Boolean) {
        if (wakeListeningSuspendedForExternalRecording == externalRecording) return
        wakeListeningSuspendedForExternalRecording = externalRecording
        AppLogger.d(TAG, "Wake listening suspended by external recording: $wakeListeningSuspendedForExternalRecording")
        applyWakeListeningState()
    }

    private fun updateWakeListeningSuspendedForFloatingFullscreen(active: Boolean) {
        if (wakeListeningSuspendedForFloatingFullscreen == active) return
        wakeListeningSuspendedForFloatingFullscreen = active
        AppLogger.d(TAG, "Wake listening suspended by floating fullscreen: $wakeListeningSuspendedForFloatingFullscreen")
        applyWakeListeningState()
    }
    
    private fun applyWakeListeningState() {
        wakeStateApplyJob?.cancel()
        wakeStateApplyJob =
            serviceScope.launch {
                wakeStateMutex.withLock {
                    applyWakeListeningStateLocked()
                }
            }
    }

    private suspend fun applyWakeListeningStateLocked() {
        val shouldListen =
            wakeListeningEnabled &&
                !wakeListeningSuspendedForIme &&
                !wakeListeningSuspendedForExternalRecording &&
                !wakeListeningSuspendedForFloatingFullscreen

        if (shouldListen) {
            startWakeListeningLocked()
        } else {
            val shouldRelease = !wakeListeningEnabled || wakeListeningSuspendedForFloatingFullscreen
            stopWakeListeningLocked(releaseProvider = shouldRelease)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun startRecordingStateMonitoring() {
        if (!wakeListeningEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (audioRecordingCallback != null) return

        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am

        val callback =
            object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    val isWakeListeningRunning =
                        wakeListeningMicActiveForRecordingDetection ||
                            wakeListeningJob?.isActive == true ||
                            personalWakeJob?.isActive == true
                    val myUid = Process.myUid()
                    val myPackageName = packageName

                    fun isExternalConfig(cfg: AudioRecordingConfiguration): Boolean {
                        val uid = cfg.tryGetClientUid()
                        if (uid != null && uid > 0) {
                            if (uid == myUid) return false
                            if (uid < Process.FIRST_APPLICATION_UID) {
                                return false
                            }

                            val pkg = cfg.tryGetClientPackageName()?.takeIf { it.isNotBlank() }
                            if (pkg != null) {
                                return pkg != myPackageName
                            }

                            return true
                        }

                        val pkg = cfg.tryGetClientPackageName()?.takeIf { it.isNotBlank() }
                        if (uid == null || uid <= 0) {
                            if (isWakeListeningRunning) return false
                            if (pkg != null) return pkg != myPackageName
                            return true
                        }

                        return false
                    }

                    val hasExternal = configs.any(::isExternalConfig)

                    if (hasExternal != wakeListeningSuspendedForExternalRecording) {
                        val summary =
                            configs.mapIndexed { idx, cfg ->
                                val uid = cfg.tryGetClientUid()
                                val pkg = cfg.tryGetClientPackageName()
                                val hasUidMethod = cfg.javaClass.methods.any { it.name == "getClientUid" && it.parameterTypes.isEmpty() }
                                val hasPkgMethod = cfg.javaClass.methods.any { it.name == "getClientPackageName" && it.parameterTypes.isEmpty() }
                                val raw = try {
                                    cfg.toString().replace('\n', ' ').replace('\r', ' ')
                                } catch (_: Exception) {
                                    ""
                                }
                                val rawShort = if (raw.length > 220) raw.substring(0, 220) else raw
                                "#$idx uid=${uid ?: "?"},pkg=${pkg ?: "?"},mUid=$hasUidMethod,mPkg=$hasPkgMethod cfg=$rawShort"
                            }.joinToString(" | ")
                        AppLogger.d(
                            TAG,
                            "Recording configs changed: wakeRunning=$isWakeListeningRunning micFlag=$wakeListeningMicActiveForRecordingDetection external=$hasExternal count=${configs.size} configs=[$summary]"
                        )
                    }

                    updateWakeListeningSuspendedForExternalRecording(hasExternal)
                }
            }
        audioRecordingCallback = callback

        try {
            am.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register recording callback: ${e.message}", e)
            audioRecordingCallback = null
            audioManager = null
            return
        }

        try {
            val configs = am.activeRecordingConfigurations
            val isWakeListeningRunning =
                wakeListeningMicActiveForRecordingDetection ||
                    wakeListeningJob?.isActive == true ||
                    personalWakeJob?.isActive == true
            val myUid = Process.myUid()
            val myPackageName = packageName

            fun isExternalConfig(cfg: AudioRecordingConfiguration): Boolean {
                val uid = cfg.tryGetClientUid()
                if (uid != null && uid > 0) {
                    if (uid == myUid) return false
                    if (uid < Process.FIRST_APPLICATION_UID) {
                        return false
                    }

                    val pkg = cfg.tryGetClientPackageName()?.takeIf { it.isNotBlank() }
                    if (pkg != null) {
                        return pkg != myPackageName
                    }

                    return true
                }

                val pkg = cfg.tryGetClientPackageName()?.takeIf { it.isNotBlank() }
                if (uid == null || uid <= 0) {
                    if (isWakeListeningRunning) return false
                    if (pkg != null) return pkg != myPackageName
                    return true
                }

                return false
            }

            val hasExternal = configs.any(::isExternalConfig)
            updateWakeListeningSuspendedForExternalRecording(hasExternal)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read active recording configs: ${e.message}", e)
        }
    }

    private fun stopRecordingStateMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val am = audioManager
        val callback = audioRecordingCallback

        if (am != null && callback != null) {
            try {
                am.unregisterAudioRecordingCallback(callback)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to unregister recording callback: ${e.message}", e)
            }
        }

        audioRecordingCallback = null
        audioManager = null
        wakeListeningSuspendedForExternalRecording = false
    }
    
    // 存储通知信息
    private var characterName: String? = null
    private var avatarUri: String? = null
    private var isAiBusy: Boolean = false
    @Volatile
    private var hideRuntimeTaskViewEnabled: Boolean = false
    @Volatile
    private var backgroundKeepAliveEnabled: Boolean = false
    @Volatile
    private var lastAppliedRuntimeTaskViewHidden: Boolean? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val chatRuntimeHolder by lazy { ChatRuntimeHolder.getInstance(applicationContext) }
    private val wakePrefs by lazy { WakeWordPreferences(applicationContext) }
    @Volatile
    private var wakeSpeechProvider: SpeechService? = null
    private val workflowRepository by lazy { WorkflowRepository(applicationContext) }
    private val externalHttpPreferences by lazy { ExternalHttpApiPreferences.getInstance(applicationContext) }
    private val aiLimbsBridgeManager by lazy { AiLimbsBridgeManager(applicationContext, serviceScope) }
    private var bridgeScreenReceiverRegistered = false
    private var bridgeNetworkCallbackRegistered = false
    @Volatile
    private var bridgeNetworkState = AiLimbsBridgeNetworkState.UNKNOWN
    @Volatile
    private var bridgeNetworkTransport = AiLimbsBridgeNetworkTransport.NONE
    private var bridgeScreenOffWakeLock: PowerManager.WakeLock? = null
    private val bridgeNetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                publishBridgeNetworkState("network_available")
            }

            override fun onLost(network: Network) {
                publishBridgeNetworkState("network_lost")
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                publishBridgeNetworkState("network_capabilities_changed")
            }
        }
    private val bridgeScreenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!isRunning.get()) return
                val action = intent?.action ?: return
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                when (action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        updateBridgeScreenOffWakeLock("screen_broadcast:$action")
                        logBridgeHostHealth("screen_off")
                        aiLimbsBridgeManager.onHostSignal(AiLimbsBridgeHostSignal.ScreenOff)
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        updateBridgeScreenOffWakeLock("screen_broadcast:$action")
                        logBridgeHostHealth("screen_on")
                        aiLimbsBridgeManager.onHostSignal(AiLimbsBridgeHostSignal.ScreenOn)
                    }
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        logBridgeHostHealth("device_idle_changed")
                        aiLimbsBridgeManager.onHostSignal(
                            AiLimbsBridgeHostSignal.DeviceIdleChanged(powerManager.isDeviceIdleMode)
                        )
                    }
                }
            }
        }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var keepAliveOverlayView: View? = null
    private var keepAliveOverlayPermissionLogged = false

    private var wakeMonitorJob: Job? = null
    private var externalHttpMonitorJob: Job? = null
    private var notificationWatchdogJob: Job? = null
    private var wakeListeningJob: Job? = null
    private var wakeResumeJob: Job? = null

    private val wakeStateMutex = Mutex()
    private var wakeStateApplyJob: Job? = null
    private var wakeStateRetryJob: Job? = null

    private var personalWakeJob: Job? = null
    private var personalWakeListener: PersonalWakeListener? = null

    @Volatile
    private var currentWakePhrase: String = WakeWordPreferences.DEFAULT_WAKE_PHRASE

    @Volatile
    private var wakePhraseRegexEnabled: Boolean = WakeWordPreferences.DEFAULT_WAKE_PHRASE_REGEX_ENABLED

    @Volatile
    private var wakeRecognitionMode: WakeWordPreferences.WakeRecognitionMode = WakeWordPreferences.WakeRecognitionMode.STT

    @Volatile
    private var personalWakeTemplates: List<FloatArray> = emptyList()

    @Volatile
    private var wakeListeningEnabled: Boolean = false

    @Volatile
    private var wakeListeningMicActiveForRecordingDetection: Boolean = false

    @Volatile
    private var wakeListeningSuspendedForIme: Boolean = false

    @Volatile
    private var wakeListeningSuspendedForExternalRecording: Boolean = false

    @Volatile
    private var wakeListeningSuspendedForFloatingFullscreen: Boolean = false

    private var audioManager: AudioManager? = null
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null
    private var externalHttpServer: ExternalChatHttpServer? = null
    private var externalHttpCurrentPort: Int? = null

    private var lastWakeTriggerAtMs: Long = 0L

    @Volatile
    private var pendingWakeTriggeredAtMs: Long = 0L

    @Volatile
    private var wakeHandoffPending: Boolean = false

    @Volatile
    private var wakeStopInProgress: Boolean = false

    private var lastSpeechWorkflowCheckAtMs: Long = 0L

    private fun ensureWakeSpeechProvider(): SpeechService {
        val existing = wakeSpeechProvider
        if (existing != null) return existing
        return SpeechServiceFactory.createWakeSpeechService(applicationContext).also {
            wakeSpeechProvider = it
        }
    }

    private fun releaseWakeSpeechProvider() {
        val provider = wakeSpeechProvider ?: return
        wakeSpeechProvider = null
        try {
            provider.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun startOrRefreshExternalHttpServer(config: ExternalHttpApiConfig = externalHttpPreferences.getConfigSync()) {
        if (!config.enabled) {
            AppLogger.i(TAG, "External HTTP API disabled, stopping runtime")
            stopExternalHttpServer(portOverride = config.port, lastError = null)
            stopSelfIfIdle(ignoreAppForeground = true)
            return
        }

        if (!ExternalHttpApiPreferences.isValidPort(config.port)) {
            val message = "Invalid port: ${config.port}"
            AppLogger.w(TAG, message)
            stopExternalHttpServer(portOverride = config.port, lastError = message)
            stopSelfIfIdle(ignoreAppForeground = true)
            return
        }

        if (externalHttpServer != null && externalHttpCurrentPort == config.port) {
            updateExternalHttpState(
                ExternalChatHttpState(
                    isRunning = true,
                    port = config.port,
                    lastError = null
                )
            )
            return
        }

        stopExternalHttpServer()

        try {
            val newServer = ExternalChatHttpServer(applicationContext, externalHttpPreferences, serviceScope)
            newServer.startServer()
            externalHttpServer = newServer
            externalHttpCurrentPort = config.port
            updateExternalHttpState(
                ExternalChatHttpState(
                    isRunning = true,
                    port = config.port,
                    lastError = null
                )
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start external HTTP server", e)
            stopExternalHttpServer(
                portOverride = config.port,
                lastError = e.message ?: "Failed to start server"
            )
            stopSelfIfIdle(ignoreAppForeground = true)
        }
    }

    private fun stopExternalHttpServer(portOverride: Int? = null, lastError: String? = null) {
        runCatching {
            externalHttpServer?.stopServer()
        }.onFailure { error ->
            AppLogger.e(TAG, "Failed to stop external HTTP server", error)
        }

        val stoppedPort = portOverride ?: externalHttpCurrentPort ?: externalHttpStateFlow.value.port
        externalHttpServer = null
        externalHttpCurrentPort = null
        updateExternalHttpState(
            ExternalChatHttpState(
                isRunning = false,
                port = stoppedPort,
                lastError = lastError
            )
        )
    }

    private fun updateExternalHttpState(
        newState: ExternalChatHttpState,
        refreshNotification: Boolean = true
    ) {
        externalHttpStateFlow.value = newState
        if (refreshNotification) {
            refreshServiceNotification()
        }
    }

    private fun refreshServiceNotification() {
        if (!isRunning.get()) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }


    private fun startNotificationWatchdog() {
        if (notificationWatchdogJob?.isActive == true) return
        notificationWatchdogJob = serviceScope.launch {
            while (isActive) {
                delay(NOTIFICATION_WATCHDOG_INTERVAL_MS)
                if (!isRunning.get() || !hasPersistentForegroundResponsibility()) continue
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!manager.areNotificationsEnabled()) continue
                val present = runCatching {
                    manager.activeNotifications.any { notification ->
                        notification.id == NOTIFICATION_ID
                    }
                }.getOrDefault(true)
                if (!present) {
                    AppLogger.w(TAG, "Foreground notification missing while service is active; restoring")
                    manager.notify(NOTIFICATION_ID, createNotification())
                }
            }
        }
    }

    private fun stopNotificationWatchdog() {
        notificationWatchdogJob?.cancel()
        notificationWatchdogJob = null
    }

    private fun isExternalHttpEnabledNow(): Boolean {
        return runCatching {
            externalHttpPreferences.getConfigSync().let { config ->
                config.enabled && ExternalHttpApiPreferences.isValidPort(config.port)
            }
        }.getOrDefault(false)
    }

    private fun registerBridgeScreenStateReceiver() {
        if (bridgeScreenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bridgeScreenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(bridgeScreenStateReceiver, filter)
            }
            bridgeScreenReceiverRegistered = true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register AI Limbs bridge screen receiver", e)
        }
    }

    private fun unregisterBridgeScreenStateReceiver() {
        if (!bridgeScreenReceiverRegistered) return
        runCatching { unregisterReceiver(bridgeScreenStateReceiver) }
            .onFailure { AppLogger.w(TAG, "Failed to unregister AI Limbs bridge screen receiver", it) }
        bridgeScreenReceiverRegistered = false
    }

    private fun registerBridgeNetworkCallback() {
        if (bridgeNetworkCallbackRegistered) return
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            connectivityManager.registerDefaultNetworkCallback(bridgeNetworkCallback)
            bridgeNetworkCallbackRegistered = true
            publishBridgeNetworkState("network_callback_registered")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register AI Limbs bridge network callback", e)
        }
    }

    private fun unregisterBridgeNetworkCallback() {
        if (!bridgeNetworkCallbackRegistered) return
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { connectivityManager.unregisterNetworkCallback(bridgeNetworkCallback) }
            .onFailure { AppLogger.w(TAG, "Failed to unregister AI Limbs bridge network callback", it) }
        bridgeNetworkCallbackRegistered = false
    }

    private fun publishBridgeNetworkState(reason: String) {
        if (!isRunning.get()) return
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val nextState = when {
            activeNetwork == null -> AiLimbsBridgeNetworkState.LOST
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true ->
                AiLimbsBridgeNetworkState.VALIDATED
            else -> AiLimbsBridgeNetworkState.AVAILABLE_UNVALIDATED
        }
        val nextTransport = when {
            capabilities == null -> AiLimbsBridgeNetworkTransport.NONE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> AiLimbsBridgeNetworkTransport.VPN
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> AiLimbsBridgeNetworkTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> AiLimbsBridgeNetworkTransport.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> AiLimbsBridgeNetworkTransport.ETHERNET
            else -> AiLimbsBridgeNetworkTransport.OTHER
        }
        val changed = nextState != bridgeNetworkState || nextTransport != bridgeNetworkTransport
        if (!changed && reason != "network_callback_registered") return
        bridgeNetworkState = nextState
        bridgeNetworkTransport = nextTransport
        logBridgeHostHealth(reason)
        aiLimbsBridgeManager.onHostSignal(
            AiLimbsBridgeHostSignal.NetworkChanged(nextState, nextTransport)
        )
    }

    private fun logBridgeHostHealth(reason: String) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOptimizationIgnored =
            runCatching { powerManager.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
        AppLogger.i(
            TAG,
            "AI Limbs bridge host health: reason=$reason, interactive=${powerManager.isInteractive}, " +
                "deviceIdle=${powerManager.isDeviceIdleMode}, wakeLockHeld=${bridgeScreenOffWakeLock?.isHeld == true}, " +
                "batteryOptimizationIgnored=$batteryOptimizationIgnored, " +
                "network=$bridgeNetworkState/$bridgeNetworkTransport, " +
                "bridge=${aiLimbsBridgeManager.state.value.phase}"
        )
    }

    private fun updateBridgeScreenOffWakeLock(reason: String) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val shouldHold =
            !powerManager.isInteractive &&
                aiLimbsBridgeManager.shouldKeepAlive &&
                aiLimbsBridgeManager.requiresScreenOffCpuKeepAlive
        if (shouldHold) {
            if (bridgeScreenOffWakeLock == null) {
                bridgeScreenOffWakeLock =
                    powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "$packageName:AiLimbsBridgeScreenOff"
                    ).apply { setReferenceCounted(false) }
            }
            if (bridgeScreenOffWakeLock?.isHeld != true) {
                bridgeScreenOffWakeLock?.acquire()
                AppLogger.i(
                    TAG,
                    "AI Limbs bridge screen-off WakeLock acquired: reason=$reason, " +
                        "provider=${aiLimbsBridgeManager.activeProfile.id}, deviceIdle=${powerManager.isDeviceIdleMode}"
                )
            }
        } else {
            releaseBridgeScreenOffWakeLock(reason)
        }
    }

    private fun releaseBridgeScreenOffWakeLock(reason: String) {
        val wakeLock = bridgeScreenOffWakeLock ?: return
        if (wakeLock.isHeld) {
            runCatching { wakeLock.release() }
                .onSuccess { AppLogger.i(TAG, "AI Limbs bridge screen-off WakeLock released: reason=$reason") }
                .onFailure { AppLogger.w(TAG, "Failed to release AI Limbs bridge screen-off WakeLock", it) }
        }
    }

    private fun hasPersistentForegroundResponsibility(): Boolean {
        val alwaysListeningEnabled = wakeListeningEnabled || isAlwaysListeningEnabledNow()
        val externalHttpEnabled = externalHttpStateFlow.value.isRunning || isExternalHttpEnabledNow()
        return isAiBusy ||
            alwaysListeningEnabled ||
            backgroundKeepAliveEnabled ||
            externalHttpEnabled ||
            aiLimbsBridgeManager.shouldKeepAlive
    }

    private fun persistentStartMode(): Int =
        if (
            aiLimbsBridgeManager.shouldKeepAlive ||
            externalHttpStateFlow.value.isRunning ||
            isExternalHttpEnabledNow()
        ) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }

    private fun stopSelfIfIdle(ignoreAppForeground: Boolean = false) {
        if (hasPersistentForegroundResponsibility()) {
            return
        }
        if (!ignoreAppForeground && ActivityLifecycleManager.getCurrentActivity() != null) {
            return
        }

        AppLogger.d(TAG, "No active foreground responsibilities, stopping AIForegroundService")
        stopNotificationWatchdog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        (application as OperitApplication).initializeMainApplication()
        wakeListeningSuspendedForIme = lastRequestedImeVisible
        AppLogger.d(TAG, "AI 前台服务创建。")
        chatRuntimeHolder
        createNotificationChannel()
        val notification = createNotification()
        ForegroundServiceCompat.startForeground(
            service = this,
            notificationId = NOTIFICATION_ID,
            notification = notification,
            types = ForegroundServiceCompat.buildTypes(
                dataSync = true,
                specialUse = aiLimbsBridgeManager.shouldKeepAlive ||
                    runCatching { externalHttpPreferences.getEnabled() }.getOrDefault(false)
            )
        )
        cancelLegacyRdcNotification()
        startNotificationWatchdog()
        observeBridgeState()
        observeRuntimeTaskViewPreference()
        observeBackgroundKeepAlivePreference()
        observeChatRuntimeStats()
        startWakeMonitoring()
        startExternalHttpMonitoring()
        registerBridgeScreenStateReceiver()
        aiLimbsBridgeManager.startIfDesired()
        registerBridgeNetworkCallback()
        updateBridgeScreenOffWakeLock("service_create")
        logBridgeHostHealth("service_create")
        AppLogger.i(TAG, "AI Limbs bridge manager initialized")
        AppLogger.d(TAG, "AI 前台服务已启动。")
    }

    private fun observeRuntimeTaskViewPreference() {
        serviceScope.launch {
            try {
                DisplayPreferencesManager
                    .getInstance(applicationContext)
                    .hideRuntimeTaskView
                    .collectLatest { enabled ->
                        hideRuntimeTaskViewEnabled = enabled
                        updateRuntimeTaskViewVisibility()
                    }
            } catch (e: Exception) {
                AppLogger.e(TAG, "监听运行时任务视图隐藏设置失败: ${e.message}", e)
            }
        }
    }

    private fun observeBackgroundKeepAlivePreference() {
        serviceScope.launch {
            try {
                DisplayPreferencesManager
                    .getInstance(applicationContext)
                    .enableBackgroundKeepAlive
                    .collectLatest { enabled ->
                        backgroundKeepAliveEnabled = enabled
                        updateKeepAliveOverlayVisibility()
                        if (enabled) {
                            refreshServiceNotification()
                        } else {
                            stopSelfIfIdle(ignoreAppForeground = true)
                        }
                    }
            } catch (e: Exception) {
                AppLogger.e(TAG, "监听后台保活设置失败: ${e.message}", e)
            }
        }
    }

    private fun updateAiBusyState(isBusy: Boolean) {
        isAiBusy = isBusy
        updateRuntimeTaskViewVisibility()
    }

    private fun updateRuntimeTaskViewVisibility() {
        val shouldHide = hideRuntimeTaskViewEnabled && isAiBusy
        if (lastAppliedRuntimeTaskViewHidden == shouldHide) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return

        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val appTasks = activityManager?.appTasks.orEmpty()
            if (appTasks.isEmpty()) {
                AppLogger.d(TAG, "更新运行时任务视图隐藏状态时未找到任务: hidden=$shouldHide")
                return
            }
            appTasks.forEach { task ->
                try {
                    task.setExcludeFromRecents(shouldHide)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "设置任务最近任务可见性失败: hidden=$shouldHide", e)
                }
            }
            lastAppliedRuntimeTaskViewHidden = shouldHide
            AppLogger.d(TAG, "运行时任务视图隐藏状态已更新: hidden=$shouldHide, taskCount=${appTasks.size}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "更新运行时任务视图隐藏状态失败: hidden=$shouldHide", e)
        }
    }

    private fun observeChatRuntimeStats() {
        serviceScope.launch {
            combine(
                chatRuntimeHolder.activeConversationCount,
                chatRuntimeHolder.currentSessionToolCount
            ) { _, _ ->
                Unit
            }.collect {
                if (!isRunning.get()) return@collect
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, createNotification())
            }
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun isAlwaysListeningEnabledNow(): Boolean {
        return try {
            runBlocking { wakePrefs.alwaysListeningEnabledFlow.first() }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun tryPromoteToMicrophoneForeground(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!hasRecordAudioPermission()) return false

        var waitedMs = 0L
        while (waitedMs < 3500L && ActivityLifecycleManager.getCurrentActivity() == null) {
            delay(150)
            waitedMs += 150
        }

        if (ActivityLifecycleManager.getCurrentActivity() == null) {
            AppLogger.w(TAG, "promote microphone foreground skipped: app not in foreground")
            return false
        }

        val types = ForegroundServiceCompat.buildTypes(
            dataSync = true,
            microphone = true,
            specialUse = aiLimbsBridgeManager.shouldKeepAlive || isExternalHttpEnabledNow()
        )
        return try {
            ForegroundServiceCompat.startForeground(
                service = this,
                notificationId = NOTIFICATION_ID,
                notification = createNotification(),
                types = types
            )
            true
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "promote microphone foreground failed: ${e.message}", e)
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.d(
            TAG,
            "onStartCommand action=${intent?.action ?: "<null>"}, startId=$startId, persistentMode=${persistentStartMode()}"
        )
        if (intent?.action == ACTION_EXIT_APP) {
            isRunning.set(false)
            stopNotificationWatchdog()
            updateAiBusyState(false)

            try {
                AIMessageManager.cancelCurrentOperation()
            } catch (e: Exception) {
                AppLogger.e(TAG, "退出时取消当前AI任务失败: ${e.message}", e)
            }

            try {
                stopService(Intent(this, FloatingChatService::class.java))
            } catch (e: Exception) {
                AppLogger.e(TAG, "退出时停止 FloatingChatService 失败: ${e.message}", e)
            }

            try {
                stopService(Intent(this, UIDebuggerService::class.java))
            } catch (e: Exception) {
                AppLogger.e(TAG, "退出时停止 UIDebuggerService 失败: ${e.message}", e)
            }

            stopExternalHttpServer(lastError = null)
            aiLimbsBridgeManager.stopByUser()
            AppLogger.i(TAG, "AI Limbs bridge stopped by explicit app exit")

            try {
                val activity = ActivityLifecycleManager.getCurrentActivity()
                activity?.runOnUiThread {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        activity.finishAndRemoveTask()
                    } else {
                        activity.finish()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "退出时关闭前台界面失败: ${e.message}", e)
            }

            try {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIFICATION_ID)
                manager.cancel(LEGACY_RDC_NOTIFICATION_ID)
                activeReplyNotificationTags.forEach { tag ->
                    manager.cancel(tag, REPLY_NOTIFICATION_ID)
                }
                activeReplyNotificationTags.clear()
                manager.cancel(REPLY_NOTIFICATION_ID)
            } catch (e: Exception) {
                AppLogger.e(TAG, "退出时取消通知失败: ${e.message}", e)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }

            stopSelf()
            Process.killProcess(Process.myPid())
            exitProcess(0)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_FOREGROUND_NOTIFICATION_DISMISSED) {
            if (isRunning.get() && hasPersistentForegroundResponsibility()) {
                AppLogger.w(TAG, "Foreground notification dismissed while service is active; restoring")
                refreshServiceNotification()
            }
            return persistentStartMode()
        }

        if (intent?.action == ACTION_BRIDGE_SELECT_PROVIDER) {
            val providerId = intent.getStringExtra(EXTRA_BRIDGE_PROVIDER_ID)
            if (providerId.isNullOrBlank()) {
                AppLogger.e(TAG, "Bridge provider selection is missing a provider id")
            } else {
                try {
                    aiLimbsBridgeManager.selectProvider(providerId)
                    AppLogger.i(TAG, "AI Limbs active bridge provider changed to $providerId")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Unable to select bridge provider $providerId", e)
                }
            }
            refreshServiceNotification()
            return persistentStartMode()
        }

        val requestedBridgeAction = bridgeActionFromIntent(intent?.action)
        if (requestedBridgeAction != null) {
            val handled =
                try {
                    aiLimbsBridgeManager.perform(requestedBridgeAction)
                } catch (e: Exception) {
                    AppLogger.e(
                        TAG,
                        "AI Limbs bridge action failed: $requestedBridgeAction",
                        e
                    )
                    false
                }
            if (!handled) {
                AppLogger.w(
                    TAG,
                    "AI Limbs bridge action was not available: $requestedBridgeAction"
                )
            }
            if (handled && requestedBridgeAction == BridgeAction.STOP) {
                stopSelfIfIdle(ignoreAppForeground = true)
            }
            refreshServiceNotification()
            return persistentStartMode()
        }

        if (intent?.action == ACTION_ENSURE_MICROPHONE_FOREGROUND) {
            serviceScope.launch {
                try {
                    tryPromoteToMicrophoneForeground()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "ensure microphone foreground failed", e)
                }
            }
            stopSelfIfIdle(ignoreAppForeground = true)
            return persistentStartMode()
        }

        if (intent?.action == ACTION_START_OR_REFRESH_EXTERNAL_HTTP || intent == null) {
            startOrRefreshExternalHttpServer()
            return persistentStartMode()
        }

        if (intent?.action == ACTION_STOP_EXTERNAL_HTTP) {
            val configuredPort = runCatching { externalHttpPreferences.getPort() }.getOrNull()
            stopExternalHttpServer(portOverride = configuredPort, lastError = null)
            stopSelfIfIdle(ignoreAppForeground = true)
            return persistentStartMode()
        }

        if (intent?.action == ACTION_TOGGLE_WAKE_LISTENING) {
            AppLogger.d(TAG, "收到 ACTION_TOGGLE_WAKE_LISTENING")
            serviceScope.launch {
                try {
                    val current = wakePrefs.alwaysListeningEnabledFlow.first()
                    AppLogger.d(TAG, "切换唤醒监听: $current -> ${!current}")
                    wakePrefs.saveAlwaysListeningEnabled(!current)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "切换唤醒监听失败: ${e.message}", e)
                }

                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, createNotification())
            }
            return persistentStartMode()
        }

        if (intent?.action == ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_IME) {
            val imeVisible = intent.getBooleanExtra(EXTRA_IME_VISIBLE, false)
            updateWakeListeningSuspendedForIme(imeVisible)
            return persistentStartMode()
        }

        if (intent?.action == ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_FLOATING_FULLSCREEN) {
            val active = intent.getBooleanExtra(EXTRA_FLOATING_FULLSCREEN_ACTIVE, false)
            updateWakeListeningSuspendedForFloatingFullscreen(active)
            return persistentStartMode()
        }

        if (intent?.action == ACTION_PREPARE_WAKE_HANDOFF) {
            val now = System.currentTimeMillis()
            val triggeredAt = pendingWakeTriggeredAtMs
            if (triggeredAt > 0L && wakeHandoffPending) {
                val elapsedMs = (now - triggeredAt).coerceAtLeast(0L)
                val dynamicMarginMs = (elapsedMs / 4L).coerceIn(150L, 650L)
                val windowMs = (elapsedMs + dynamicMarginMs).coerceIn(200L, 2500L)
                AppLogger.d(
                    TAG,
                    "Wake handoff prepare: elapsedMs=$elapsedMs, marginMs=$dynamicMarginMs, captureWindowMs=$windowMs"
                )
                SpeechPrerollStore.capturePending(windowMs = windowMs.toInt())
                SpeechPrerollStore.armPending()

                if (!wakeStopInProgress) {
                    wakeStopInProgress = true
                    serviceScope.launch {
                        try {
                            stopWakeListening(releaseProvider = true)
                        } finally {
                            wakeStopInProgress = false
                            wakeHandoffPending = false
                            pendingWakeTriggeredAtMs = 0L
                            SpeechPrerollStore.clearPendingWakePhrase()
                        }
                    }
                }
            } else {
                AppLogger.d(TAG, "Wake handoff prepare ignored: pending=$wakeHandoffPending, triggeredAt=$triggeredAt")
            }
            return persistentStartMode()
        }

        if (intent?.action == ACTION_CANCEL_CURRENT_OPERATION) {
            try {
                AIMessageManager.cancelCurrentOperation()
                // 立即刷新通知状态（真正的状态重置由 EnhancedAIService.cancelConversation/stopAiService 完成）
                updateAiBusyState(false)
            } catch (e: Exception) {
                AppLogger.e(TAG, "取消当前AI任务失败: ${e.message}", e)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, createNotification())
            return persistentStartMode()
        }

        // 从Intent中提取通知信息
        intent?.let {
            characterName = it.getStringExtra(EXTRA_CHARACTER_NAME)
            avatarUri = it.getStringExtra(EXTRA_AVATAR_URI)
            AppLogger.d(TAG, "收到通知数据 - 角色: $characterName, 头像: $avatarUri")

            val state = it.getStringExtra(EXTRA_STATE)
            if (state != null) {
                updateAiBusyState(state == STATE_RUNNING)
                if (!hasPersistentForegroundResponsibility()) {
                    AppLogger.d(TAG, "服务进入空闲且无持久后台职责，准备停止前台服务")
                    stopSelfIfIdle(ignoreAppForeground = true)
                    return persistentStartMode()
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, createNotification())
            }
        }
        
        // AI Limbs Bridge 或 External HTTP 启用时，保持 START_STICKY，避免 UI 状态变化
        // 将长期连接误降级为一次性前台服务。
        return persistentStartMode()
    }

    override fun onDestroy() {
        val stoppedPort = externalHttpCurrentPort ?: externalHttpStateFlow.value.port
        runCatching {
            externalHttpServer?.stopServer()
        }.onFailure { error ->
            AppLogger.e(TAG, "Failed to stop external HTTP server", error)
        }
        externalHttpServer = null
        externalHttpCurrentPort = null
        updateExternalHttpState(
            ExternalChatHttpState(
                isRunning = false,
                port = stoppedPort,
                lastError = null
            ),
            refreshNotification = false
        )
        isRunning.set(false)
        stopNotificationWatchdog()
        updateAiBusyState(false)
        hideKeepAliveOverlay()
        AppLogger.w(
            TAG,
            "AIForegroundService onDestroy: bridge=${aiLimbsBridgeManager.statusSummary()}"
        )
        unregisterBridgeNetworkCallback()
        unregisterBridgeScreenStateReceiver()
        aiLimbsBridgeManager.stopRuntime()
        releaseBridgeScreenOffWakeLock("service_destroy")
        stopWakeMonitoring()
        cancelLegacyRdcNotification()
        AppLogger.d(TAG, "AI 前台服务已销毁。")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // 该服务是启动服务，不提供绑定功能。
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = getString(R.string.service_ai_limbs_running)
            val serviceChannel =
                    NotificationChannel(
                            CHANNEL_ID,
                            channelName,
                            NotificationManager.IMPORTANCE_LOW // 低重要性，避免打扰用户
                    )
                    .apply {
                        description = getString(R.string.service_keep_background)
                    }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun startExternalHttpMonitoring() {
        if (externalHttpMonitorJob?.isActive == true) return

        if (isExternalHttpEnabledNow()) {
            startOrRefreshExternalHttpServer()
        } else {
            updateExternalHttpState(
                externalHttpStateFlow.value.copy(
                    isRunning = false,
                    port = runCatching { externalHttpPreferences.getPort() }.getOrNull(),
                    lastError = null
                )
            )
        }

        externalHttpMonitorJob =
            serviceScope.launch {
                combine(
                    externalHttpPreferences.enabledFlow,
                    externalHttpPreferences.portFlow
                ) { enabled, port ->
                    enabled to port
                }.collectLatest { (enabled, port) ->
                    AppLogger.d(TAG, "External HTTP config updated: enabled=$enabled, port=$port")
                    if (enabled) {
                        startOrRefreshExternalHttpServer(
                            config = externalHttpPreferences.getConfigSync()
                        )
                    } else {
                        stopExternalHttpServer(portOverride = port, lastError = null)
                        stopSelfIfIdle(ignoreAppForeground = true)
                    }
                }
            }
    }

    private fun startWakeMonitoring() {
        if (wakeMonitorJob?.isActive == true) return
        AppLogger.d(TAG, "startWakeMonitoring")
        wakeMonitorJob =
            serviceScope.launch {
                launch {
                    wakePrefs.wakePhraseFlow.collectLatest { phrase ->
                        currentWakePhrase = phrase.ifBlank { WakeWordPreferences.DEFAULT_WAKE_PHRASE }
                        AppLogger.d(TAG, "唤醒词更新: '$currentWakePhrase'")
                    }
                }

                launch {
                    wakePrefs.wakePhraseRegexEnabledFlow.collectLatest { enabled ->
                        wakePhraseRegexEnabled = enabled
                        AppLogger.d(TAG, "唤醒词正则开关更新: enabled=$enabled")
                    }
                }

                launch {
                    wakePrefs.wakeRecognitionModeFlow.collectLatest { mode ->
                        wakeRecognitionMode = mode
                        AppLogger.d(TAG, "唤醒识别模式更新: $mode")
                        applyWakeListeningState()
                    }
                }

                launch {
                    wakePrefs.personalWakeTemplatesFlow.collectLatest { templates ->
                        personalWakeTemplates = templates.mapNotNull { t ->
                            val feats = t.features
                            if (feats.isEmpty()) null else feats.toFloatArray()
                        }
                        AppLogger.d(TAG, "个人化唤醒模板更新: count=${personalWakeTemplates.size}")
                        applyWakeListeningState()
                    }
                }

                wakePrefs.alwaysListeningEnabledFlow.collectLatest { enabled ->
                    wakeListeningEnabled = enabled
                    AppLogger.d(TAG, "唤醒监听开关更新: enabled=$enabled")

                    updateKeepAliveOverlayVisibility()

                    if (enabled) {
                        startRecordingStateMonitoring()
                    } else {
                        stopRecordingStateMonitoring()
                    }

                    applyWakeListeningState()

                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, createNotification())
                }
            }
    }

    private fun updateKeepAliveOverlayVisibility() {
        if (wakeListeningEnabled || backgroundKeepAliveEnabled) {
            showKeepAliveOverlayIfPossible()
        } else {
            hideKeepAliveOverlay()
        }
    }

    private fun stopWakeMonitoring() {
        externalHttpMonitorJob?.cancel()
        externalHttpMonitorJob = null
        wakeMonitorJob?.cancel()
        wakeMonitorJob = null
        wakeResumeJob?.cancel()
        wakeResumeJob = null
        wakeListeningJob?.cancel()
        wakeListeningJob = null
        personalWakeListener?.stop()
        personalWakeListener = null
        personalWakeJob?.cancel()
        personalWakeJob = null
        stopRecordingStateMonitoring()
        hideKeepAliveOverlay()
        try {
            serviceScope.cancel()
        } catch (_: Exception) {
        }
        releaseWakeSpeechProvider()
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post {
                try {
                    action()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error on main thread", e)
                }
            }
        }
    }

    private fun showKeepAliveOverlayIfPossible() {
        if (keepAliveOverlayView != null) return
        if (!Settings.canDrawOverlays(this)) {
            if (!keepAliveOverlayPermissionLogged) {
                keepAliveOverlayPermissionLogged = true
                AppLogger.w(TAG, "Keep-alive overlay skipped: missing overlay permission")
            }
            return
        }

        runOnMainThread {
            if (keepAliveOverlayView != null) return@runOnMainThread
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val view = View(this)
                ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
                    val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                    updateWakeListeningSuspendedForIme(imeVisible)
                    insets
                }
                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    1,
                    1,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                    alpha = 0f
                }

                wm.addView(view, params)
                keepAliveOverlayView = view
                ViewCompat.requestApplyInsets(view)
                AppLogger.d(TAG, "Keep-alive overlay shown")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to show keep-alive overlay: ${e.message}", e)
                keepAliveOverlayView = null
            }
        }
    }

    private fun hideKeepAliveOverlay() {
        val view = keepAliveOverlayView ?: return
        runOnMainThread {
            val current = keepAliveOverlayView ?: return@runOnMainThread
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(current)
                AppLogger.d(TAG, "Keep-alive overlay hidden")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to hide keep-alive overlay: ${e.message}", e)
            } finally {
                if (keepAliveOverlayView === view) {
                    keepAliveOverlayView = null
                }
            }
        }
    }

    private suspend fun startWakeListening() {
        wakeStateMutex.withLock {
            startWakeListeningLocked()
        }
    }

    private suspend fun startWakeListeningLocked() {
        if (!wakeListeningEnabled) return
        if (wakeRecognitionMode == WakeWordPreferences.WakeRecognitionMode.STT) {
            if (personalWakeJob?.isActive == true) {
                AppLogger.d(TAG, "Switching wake listener: stopping personal wake before starting STT")
                stopWakeListeningLocked(releaseProvider = true)
            }
            if (wakeListeningJob?.isActive == true) return
        } else {
            if (wakeListeningJob?.isActive == true) {
                AppLogger.d(TAG, "Switching wake listener: stopping STT wake before starting personal")
                stopWakeListeningLocked(releaseProvider = true)
            }
            if (personalWakeJob?.isActive == true) return
        }

        if (wakeRecognitionMode == WakeWordPreferences.WakeRecognitionMode.PERSONAL_TEMPLATE) {
            startPersonalWakeListening()
            return
        }

        AppLogger.d(TAG, "startWakeListening: phrase='$currentWakePhrase'")

        if (wakeHandoffPending && !wakeStopInProgress && FloatingChatService.getInstance() == null) {
            AppLogger.d(TAG, "Clearing stale wake handoff pending state before starting wake listening")
            wakeHandoffPending = false
            wakeStopInProgress = false
            pendingWakeTriggeredAtMs = 0L
            SpeechPrerollStore.clearPendingWakePhrase()
        }

        val micGranted =
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            AppLogger.e(TAG, "启动唤醒监听失败: 未授予 RECORD_AUDIO（请在系统设置中允许麦克风权限）")
            wakeListeningEnabled = false
            try {
                wakePrefs.saveAlwaysListeningEnabled(false)
            } catch (_: Exception) {
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, createNotification())
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            tryPromoteToMicrophoneForeground()
        }

        wakeResumeJob?.cancel()
        wakeResumeJob = null

        try {
            val provider = ensureWakeSpeechProvider()
            val initOk = provider.initialize()
            AppLogger.d(TAG, "唤醒识别器 initialize: ok=$initOk")
            wakeListeningMicActiveForRecordingDetection = true
            val startOk = provider.startRecognition(
                languageCode = "zh-CN",
                continuousMode = true,
                partialResults = true,
                audioSource = MediaRecorder.AudioSource.MIC,
            )
            AppLogger.d(TAG, "唤醒识别器 startRecognition: ok=$startOk")
            if (!startOk) {
                val alreadyRunning =
                    provider.isRecognizing ||
                        provider.currentState == SpeechService.RecognitionState.PREPARING ||
                        provider.currentState == SpeechService.RecognitionState.PROCESSING ||
                        provider.currentState == SpeechService.RecognitionState.RECOGNIZING
                if (!alreadyRunning) {
                    AppLogger.w(TAG, "唤醒识别器 startRecognition failed (will retry)")
                    wakeListeningMicActiveForRecordingDetection = false
                    wakeStateRetryJob?.cancel()
                    wakeStateRetryJob =
                        serviceScope.launch {
                            delay(650)
                            wakeStateMutex.withLock {
                                applyWakeListeningStateLocked()
                            }
                        }
                    return
                }
            }
        } catch (e: Exception) {
            wakeListeningMicActiveForRecordingDetection = false
            AppLogger.e(TAG, "启动唤醒监听失败: ${e.message}", e)
            return
        }

        if (wakeListeningJob?.isActive == true) return

        wakeListeningJob =
            serviceScope.launch {
                var lastText = ""
                var lastIsFinal = false
                val provider = ensureWakeSpeechProvider()
                provider.recognitionResultFlow.collectLatest { result ->
                    val text = result.text
                    if (text.isBlank()) return@collectLatest
                    if (text == lastText && result.isFinal == lastIsFinal) return@collectLatest
                    lastText = text
                    lastIsFinal = result.isFinal

                    AppLogger.d(
                        TAG,
                        "唤醒识别输出(${if (result.isFinal) "final" else "partial"}): '$text'"
                    )

                    if (wakeHandoffPending) {
                        val floatingAlive = FloatingChatService.getInstance() != null
                        if (!wakeStopInProgress && !floatingAlive) {
                            AppLogger.d(TAG, "Clearing stale wake handoff pending state (no floating instance)")
                            wakeHandoffPending = false
                            wakeStopInProgress = false
                            pendingWakeTriggeredAtMs = 0L
                            SpeechPrerollStore.clearPendingWakePhrase()
                        } else {
                            return@collectLatest
                        }
                    }

                    try {
                        val now = System.currentTimeMillis()
                        val shouldCheckWorkflows = result.isFinal || now - lastSpeechWorkflowCheckAtMs >= 350L
                        if (shouldCheckWorkflows) {
                            lastSpeechWorkflowCheckAtMs = now
                            workflowRepository.triggerWorkflowsBySpeechEvent(text = text, isFinal = result.isFinal)
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Speech trigger processing failed: ${e.message}", e)
                    }

                    if (matchWakePhrase(text, currentWakePhrase, wakePhraseRegexEnabled)) {
                        val now = System.currentTimeMillis()
                        if (now - lastWakeTriggerAtMs < 3000L) return@collectLatest
                        lastWakeTriggerAtMs = now
                        pendingWakeTriggeredAtMs = now
                        wakeHandoffPending = true
                        wakeStopInProgress = false

                        AppLogger.d(TAG, "命中唤醒词: '$currentWakePhrase' in '$text'")
                        SpeechPrerollStore.setPendingWakePhrase(
                            phrase = currentWakePhrase,
                            regexEnabled = wakePhraseRegexEnabled,
                        )
                        triggerWakeLaunch()
                        scheduleWakeResume()
                    }
                }
            }
    }

    private suspend fun startPersonalWakeListening() {
        if (!wakeListeningEnabled) return
        if (personalWakeJob?.isActive == true) return

        AppLogger.d(TAG, "startPersonalWakeListening: templates=${personalWakeTemplates.size}")
        if (personalWakeTemplates.isEmpty()) {
            AppLogger.w(TAG, "Personal wake listening skipped: no templates")
            return
        }

        wakeListeningMicActiveForRecordingDetection = true

        val listener =
            PersonalWakeListener(
                context = applicationContext,
                templatesProvider = { personalWakeTemplates },
                onTriggered = onTriggered@{ similarity ->
                    val now = System.currentTimeMillis()
                    if (now - lastWakeTriggerAtMs < 3000L) return@onTriggered
                    lastWakeTriggerAtMs = now
                    pendingWakeTriggeredAtMs = now
                    wakeHandoffPending = true
                    wakeStopInProgress = false

                    AppLogger.d(TAG, "命中个人化唤醒: similarity=$similarity")
                    SpeechPrerollStore.setPendingWakePhrase(
                        phrase = currentWakePhrase,
                        regexEnabled = wakePhraseRegexEnabled,
                    )
                    triggerWakeLaunch()
                    scheduleWakeResume()
                }
            )
        personalWakeListener = listener

        personalWakeJob =
            serviceScope.launch {
                try {
                    listener.runLoop()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Personal wake loop failed: ${e.message}", e)
                } finally {
                    wakeListeningMicActiveForRecordingDetection = false
                }
            }
    }

    private suspend fun stopWakeListening(releaseProvider: Boolean = false) {
        wakeStateMutex.withLock {
            stopWakeListeningLocked(releaseProvider = releaseProvider)
        }
    }

    private suspend fun stopWakeListeningLocked(releaseProvider: Boolean = false) {
        AppLogger.d(TAG, "stopWakeListening")
        wakeListeningMicActiveForRecordingDetection = false
        wakeResumeJob?.cancel()
        wakeResumeJob = null

        wakeStateRetryJob?.cancel()
        wakeStateRetryJob = null

        wakeListeningJob?.cancel()
        wakeListeningJob = null

        personalWakeListener?.stop()
        personalWakeListener = null
        personalWakeJob?.cancel()
        personalWakeJob = null

        try {
            wakeSpeechProvider?.cancelRecognition()
        } catch (_: Exception) {
        }

        if (releaseProvider) {
            AppLogger.d(TAG, "Releasing wake speech provider")
            releaseWakeSpeechProvider()
        }
    }

    private fun scheduleWakeResume() {
        AppLogger.d(TAG, "scheduleWakeResume")
        wakeResumeJob?.cancel()
        wakeResumeJob =
            serviceScope.launch {
                var waitedMs = 0L
                while (isActive && waitedMs < 5000L) {
                    if (!wakeListeningEnabled) return@launch
                    if (FloatingChatService.getInstance() != null) break
                    delay(250)
                    waitedMs += 250
                }

                AppLogger.d(TAG, "等待悬浮窗启动: waitedMs=$waitedMs, instance=${FloatingChatService.getInstance() != null}")

                while (isActive) {
                    if (!wakeListeningEnabled) return@launch
                    if (FloatingChatService.getInstance() == null) break
                    delay(500)
                }

                AppLogger.d(TAG, "检测到悬浮窗已关闭，准备恢复唤醒监听")

                if (wakeHandoffPending) {
                    AppLogger.d(TAG, "Wake handoff aborted, clearing pending state")
                    wakeHandoffPending = false
                    wakeStopInProgress = false
                    pendingWakeTriggeredAtMs = 0L
                    SpeechPrerollStore.clearPendingWakePhrase()
                }

                wakeStateMutex.withLock {
                    applyWakeListeningStateLocked()
                }
            }
    }

    private fun triggerWakeLaunch() {
        AppLogger.d(TAG, "triggerWakeLaunch: 打开全屏悬浮窗并进入语音")
        try {
            val floatingIntent = Intent(this, FloatingChatService::class.java).apply {
                putExtra("INITIAL_MODE", com.ai.assistance.operit.ui.floating.FloatingMode.FULLSCREEN.name)
                putExtra(FloatingChatService.EXTRA_AUTO_ENTER_VOICE_CHAT, true)
                putExtra(FloatingChatService.EXTRA_WAKE_LAUNCHED, true)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(floatingIntent)
            } else {
                startService(floatingIntent)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "唤醒打开悬浮窗失败: ${e.message}", e)
        }
    }

    private fun matchWakePhrase(recognized: String, phrase: String, regexEnabled: Boolean): Boolean {
        if (regexEnabled) {
            if (phrase.isBlank()) return false
            return try {
                Regex(phrase).containsMatchIn(recognized)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Invalid wake phrase regex: '$phrase' (${e.message})")
                false
            }
        }

        val target = normalizeWakeText(phrase)
        if (target.isBlank()) return false
        val text = normalizeWakeText(recognized)
        return text.contains(target)
    }

    private fun normalizeWakeText(text: String): String {
        val cleaned =
            text
                .lowercase()
                .replace(
                    Regex("[\\s\\p{Punct}，。！？；：、“”‘’【】（）()\\[\\]{}<>《》]+"),
                    ""
                )
        return cleaned
    }

    private fun bridgeStatusLabel(phase: AiLimbsBridgePhase): String =
        when (phase) {
            AiLimbsBridgePhase.STOPPED ->
                getString(R.string.ai_limbs_bridge_phase_stopped)
            AiLimbsBridgePhase.STARTING ->
                getString(R.string.ai_limbs_bridge_phase_starting)
            AiLimbsBridgePhase.CONNECTING ->
                getString(R.string.ai_limbs_bridge_phase_connecting)
            AiLimbsBridgePhase.PAIRING -> getString(R.string.ai_limbs_bridge_phase_pairing)
            AiLimbsBridgePhase.ONLINE -> getString(R.string.ai_limbs_bridge_phase_online)
            AiLimbsBridgePhase.RECONNECTING ->
                getString(R.string.ai_limbs_bridge_phase_reconnecting)
            AiLimbsBridgePhase.RECOVERING ->
                getString(R.string.ai_limbs_bridge_phase_recovering)
            AiLimbsBridgePhase.RECOVERY_FAILED ->
                getString(R.string.ai_limbs_bridge_phase_recovery_failed)
            AiLimbsBridgePhase.ERROR -> getString(R.string.ai_limbs_bridge_phase_error)
        }

    private fun shortBridgeId(deviceId: String): String =
        if (deviceId.length <= 12) deviceId else "…${deviceId.takeLast(12)}"

    private fun formatBridgeClock(timestampMs: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

    private fun bridgeNotificationSignature(state: AiLimbsBridgeState): String {
        val heartbeatMinute = state.lastHeartbeatAtMs?.div(60_000L)
        return listOf(
            state.providerId,
            state.phase.name,
            state.detail,
            state.userCode.orEmpty(),
            state.verificationUri.orEmpty(),
            state.deviceId.orEmpty(),
            heartbeatMinute?.toString().orEmpty(),
            state.reconnectAttempt.toString()
        ).joinToString("|")
    }

    private fun observeBridgeState() {
        serviceScope.launch {
            var lastSignature: String? = null
            aiLimbsBridgeManager.state.collect { state ->
                updateBridgeScreenOffWakeLock("bridge_state:${state.phase}")
                val signature = bridgeNotificationSignature(state)
                if (signature != lastSignature) {
                    lastSignature = signature
                    refreshServiceNotification()
                }
            }
        }
    }

    private fun cancelLegacyRdcNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(LEGACY_RDC_NOTIFICATION_ID)
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AIForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun bridgeActionPendingIntent(action: BridgeAction): PendingIntent {
        val intent = Intent(this, AIForegroundService::class.java).apply {
            this.action = bridgeIntentAction(action)
        }
        return PendingIntent.getService(
            this,
            bridgeActionRequestCode(action),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun bridgeActionLabel(action: BridgeAction): String =
        when (action) {
            BridgeAction.CONNECT -> getString(R.string.ai_limbs_bridge_action_connect)
            BridgeAction.STOP -> getString(R.string.ai_limbs_bridge_action_stop)
            BridgeAction.RECONNECT -> getString(R.string.ai_limbs_bridge_action_reconnect)
            BridgeAction.RECOVER -> getString(R.string.ai_limbs_bridge_action_recover)
            BridgeAction.REPAIR -> getString(R.string.ai_limbs_bridge_action_repair)
            BridgeAction.OPEN_AUTH -> getString(R.string.ai_limbs_bridge_action_open_auth)
            BridgeAction.REFRESH -> getString(R.string.ai_limbs_bridge_action_refresh)
        }

    private data class NotificationPanelAction(
        val label: String,
        val pendingIntent: PendingIntent
    )

    private fun mainContentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun bridgeCenterPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(
                ToolPkgDesktopWidgetHost.EXTRA_OPEN_ROUTE_ID,
                "native.ai_limbs_bridge_center"
            )
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_BRIDGE_CENTER,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun voiceFloatingPendingIntent(): PendingIntent {
        val intent = Intent(this, FloatingChatService::class.java).apply {
            putExtra(
                "INITIAL_MODE",
                com.ai.assistance.operit.ui.floating.FloatingMode.FULLSCREEN.name
            )
            putExtra(FloatingChatService.EXTRA_AUTO_ENTER_VOICE_CHAT, true)
        }
        return PendingIntent.getForegroundService(
            this,
            REQUEST_CODE_VOICE_FLOATING,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun notificationPanelActions(
        state: AiLimbsBridgeState
    ): List<NotificationPanelAction> = buildList {
        if (isAiBusy) {
            add(
                NotificationPanelAction(
                    label = getString(R.string.service_stop),
                    pendingIntent =
                        serviceActionPendingIntent(
                            ACTION_CANCEL_CURRENT_OPERATION,
                            REQUEST_CODE_CANCEL_CURRENT_OPERATION
                        )
                )
            )
        }

        val bridgeActionLimit = if (isAiBusy) 1 else 2
        aiLimbsBridgeManager.availableActions(state)
            .asSequence()
            .filter { it != BridgeAction.REFRESH && it != BridgeAction.RECOVER }
            .take(bridgeActionLimit)
            .forEach { action ->
                add(
                    NotificationPanelAction(
                        label = if (state.phase == AiLimbsBridgePhase.PAIRING && action == BridgeAction.RECONNECT) getString(R.string.ai_limbs_bridge_action_refresh_auth_code) else bridgeActionLabel(action),
                        pendingIntent = bridgeActionPendingIntent(action)
                    )
                )
            }

        add(
            NotificationPanelAction(
                label = getString(R.string.service_exit),
                pendingIntent =
                    serviceActionPendingIntent(
                        ACTION_EXIT_APP,
                        REQUEST_CODE_EXIT_APP
                    )
            )
        )
    }

    private fun bindNotificationPanelAction(
        views: RemoteViews,
        viewId: Int,
        action: NotificationPanelAction?
    ) {
        if (action == null) {
            views.setViewVisibility(viewId, View.GONE)
            return
        }
        views.setViewVisibility(viewId, View.VISIBLE)
        views.setTextViewText(viewId, action.label)
        views.setOnClickPendingIntent(viewId, action.pendingIntent)
        views.setContentDescription(viewId, action.label)
    }

    private fun buildBridgeDetails(
        runtimeText: String,
        state: AiLimbsBridgeState
    ): String = buildString {
        if (runtimeText.isNotBlank()) {
            append(runtimeText)
            append('\n')
        }
        append(
            getString(
                R.string.ai_limbs_bridge_current_status,
                bridgeStatusLabel(state.phase)
            )
        )
        state.userCode?.takeIf { it.isNotBlank() }?.let {
            append('\n')
            append(getString(R.string.ai_limbs_bridge_authorization_code, it))
        }
        state.deviceId?.takeIf { it.isNotBlank() }?.let {
            append('\n')
            append(getString(R.string.ai_limbs_bridge_device_id, shortBridgeId(it)))
        }
        state.lastHeartbeatAtMs?.let {
            append("\n最后心跳：${formatBridgeClock(it)}")
        }
        if (state.reconnectAttempt > 0) {
            append("\n重连次数：${state.reconnectAttempt}")
        }
        if (state.detail.isNotBlank()) {
            append("\n${state.detail}")
        }
    }

    private fun createExpandedNotificationView(
        runtimeText: String,
        state: AiLimbsBridgeState,
        wakeListeningEnabledSnapshot: Boolean
    ): RemoteViews {
        val views = RemoteViews(packageName, R.layout.notification_ai_limbs_expanded)
        val isNightMode =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val primaryTextColor = if (isNightMode) Color.WHITE else Color.rgb(32, 33, 36)
        val secondaryTextColor =
            if (isNightMode) Color.rgb(210, 214, 220) else Color.rgb(82, 86, 94)
        val actionTextColor =
            if (isNightMode) Color.rgb(138, 180, 248) else Color.rgb(25, 103, 210)

        val providerLabel =
            getString(R.string.ai_limbs_notification_current_bridge, state.providerLabel) + "  ▼"
        views.setTextViewText(R.id.notification_bridge_selector, providerLabel)
        views.setTextColor(R.id.notification_bridge_selector, primaryTextColor)
        views.setContentDescription(R.id.notification_bridge_selector, providerLabel)
        views.setOnClickPendingIntent(
            R.id.notification_bridge_selector,
            bridgeCenterPendingIntent()
        )

        views.setTextViewText(
            R.id.notification_bridge_details,
            buildBridgeDetails(runtimeText, state)
        )
        views.setTextColor(R.id.notification_bridge_details, secondaryTextColor)

        val panelActions = notificationPanelActions(state)
        bindNotificationPanelAction(
            views,
            R.id.notification_bridge_action_primary,
            panelActions.getOrNull(0)
        )
        bindNotificationPanelAction(
            views,
            R.id.notification_bridge_action_secondary,
            panelActions.getOrNull(1)
        )
        bindNotificationPanelAction(
            views,
            R.id.notification_exit_action,
            panelActions.getOrNull(2)
        )

        val wakeLabel =
            getString(
                if (wakeListeningEnabledSnapshot) {
                    R.string.service_turn_off_wake
                } else {
                    R.string.service_turn_on_wake
                }
            )
        val voiceFloatingLabel = getString(R.string.service_voice_floating_window)
        views.setTextViewText(R.id.notification_voice_floating_action, voiceFloatingLabel)
        views.setContentDescription(
            R.id.notification_voice_floating_action,
            voiceFloatingLabel
        )
        views.setOnClickPendingIntent(
            R.id.notification_voice_floating_action,
            voiceFloatingPendingIntent()
        )
        views.setTextViewText(R.id.notification_wake_action, wakeLabel)
        views.setContentDescription(R.id.notification_wake_action, wakeLabel)
        views.setOnClickPendingIntent(
            R.id.notification_wake_action,
            serviceActionPendingIntent(
                ACTION_TOGGLE_WAKE_LISTENING,
                REQUEST_CODE_TOGGLE_WAKE_LISTENING
            )
        )

        listOf(
            R.id.notification_bridge_action_primary,
            R.id.notification_bridge_action_secondary,
            R.id.notification_exit_action,
            R.id.notification_voice_floating_action,
            R.id.notification_wake_action
        ).forEach { viewId ->
            views.setTextColor(viewId, actionTextColor)
        }
        return views
    }

    private fun createNotification(): Notification {
        val wakeListeningEnabledSnapshot = wakeListeningEnabled
        val externalHttpSnapshot = externalHttpStateFlow.value
        val bridgeState = aiLimbsBridgeManager.state.value

        val activeConversationCount = chatRuntimeHolder.activeConversationCount.value
        val currentSessionToolCount = chatRuntimeHolder.currentSessionToolCount.value
        val runtimeText = when {
            isAiBusy && activeConversationCount > 0 -> {
                val statsText =
                    getString(
                        R.string.service_running_stats,
                        activeConversationCount,
                        currentSessionToolCount
                    )
                if (externalHttpSnapshot.isRunning && externalHttpSnapshot.port != null) {
                    getString(
                        R.string.service_running_with_http,
                        statsText,
                        externalHttpSnapshot.port
                    )
                } else {
                    statsText
                }
            }
            externalHttpSnapshot.isRunning && externalHttpSnapshot.port != null -> {
                getString(
                    R.string.service_running_http_listening,
                    externalHttpSnapshot.port
                )
            }
            else -> ""
        }

        val bridgeSummary = "${bridgeState.providerLabel}：${bridgeStatusLabel(bridgeState.phase)}"
        val contentText =
            runtimeText.ifBlank {
                bridgeState.detail.ifBlank { bridgeStatusLabel(bridgeState.phase) }
            }
        val expandedView =
            createExpandedNotificationView(
                runtimeText = runtimeText,
                state = bridgeState,
                wakeListeningEnabledSnapshot = wakeListeningEnabledSnapshot
            )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(bridgeSummary)
            .setContentText(contentText)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomBigContentView(expandedView)
            .setSmallIcon(R.drawable.ic_ai_limbs_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setDeleteIntent(
                serviceActionPendingIntent(
                    ACTION_FOREGROUND_NOTIFICATION_DISMISSED,
                    REQUEST_CODE_FOREGROUND_NOTIFICATION_DISMISSED
                )
            )

        builder.setContentIntent(mainContentPendingIntent())

        return builder.build()
    }

}
