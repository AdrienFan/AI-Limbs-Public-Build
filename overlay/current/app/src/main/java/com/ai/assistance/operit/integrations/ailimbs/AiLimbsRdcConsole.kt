package com.ai.assistance.operit.integrations.ailimbs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AiLimbsRdcPhase {
    STOPPED,
    STARTING,
    CONNECTING,
    PAIRING,
    ONLINE,
    RECONNECTING,
    ERROR
}

data class AiLimbsRdcState(
    val phase: AiLimbsRdcPhase = AiLimbsRdcPhase.STOPPED,
    val detail: String = "",
    val userCode: String? = null,
    val verificationUri: String? = null,
    val deviceId: String? = null,
    val lastHeartbeatAtMs: Long? = null,
    val reconnectAttempt: Int = 0
)
internal class AiLimbsRdcConsole(context: Context) {
    private val appContext = context.applicationContext

    fun show(state: AiLimbsRdcState) {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AI Limbs · RDC")
            .setContentText(statusLabel(state.phase))
            .setStyle(NotificationCompat.BigTextStyle().bigText(buildDetails(state)))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(mainPendingIntent())

        if (state.phase == AiLimbsRdcPhase.PAIRING && !state.verificationUri.isNullOrBlank()) {
            builder.addAction(
                android.R.drawable.ic_menu_view,
                "打开授权页",
                servicePendingIntent(AIForegroundService.ACTION_RDC_OPEN_AUTH, REQUEST_OPEN_AUTH)
            )
        }
        builder.addAction(
            android.R.drawable.ic_popup_sync,
            "重新连接",
            servicePendingIntent(AIForegroundService.ACTION_RDC_RECONNECT, REQUEST_RECONNECT)
        )
        builder.addAction(
            android.R.drawable.ic_menu_revert,
            "重新配对",
            servicePendingIntent(AIForegroundService.ACTION_RDC_REPAIR, REQUEST_REPAIR)
        )

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancel() {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    private fun buildDetails(state: AiLimbsRdcState): String = buildString {
        append("状态：${statusLabel(state.phase)}")
        append("\n设备：${Build.MANUFACTURER} ${Build.MODEL}")
        state.userCode?.takeIf { it.isNotBlank() }?.let {
            append("\n授权码：$it")
        }
        state.deviceId?.takeIf { it.isNotBlank() }?.let {
            append("\nRDC ID：${shortDeviceId(it)}")
        }
        state.lastHeartbeatAtMs?.let {
            append("\n最后心跳：${formatClock(it)}")
        }
        if (state.reconnectAttempt > 0) {
            append("\n重连次数：${state.reconnectAttempt}")
        }
        if (state.detail.isNotBlank()) {
            append("\n${state.detail}")
        }
    }

    private fun statusLabel(phase: AiLimbsRdcPhase): String = when (phase) {
        AiLimbsRdcPhase.STOPPED -> "已停止"
        AiLimbsRdcPhase.STARTING -> "正在启动"
        AiLimbsRdcPhase.CONNECTING -> "正在连接"
        AiLimbsRdcPhase.PAIRING -> "等待授权"
        AiLimbsRdcPhase.ONLINE -> "已连接"
        AiLimbsRdcPhase.RECONNECTING -> "正在重连"
        AiLimbsRdcPhase.ERROR -> "连接异常"
    }

    private fun mainPendingIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(appContext, AIForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "AI Limbs RDC connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AI Limbs Remote Desktop Commander connection status and controls"
            }
        )
    }

    private fun shortDeviceId(deviceId: String): String =
        if (deviceId.length <= 12) deviceId else "…${deviceId.takeLast(12)}"

    private fun formatClock(timestampMs: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

    companion object {
        private const val CHANNEL_ID = "AI_LIMBS_RDC_CONSOLE"
        private const val NOTIFICATION_ID = 7320
        private const val REQUEST_OPEN_APP = 7321
        private const val REQUEST_OPEN_AUTH = 7322
        private const val REQUEST_RECONNECT = 7323
        private const val REQUEST_REPAIR = 7324
    }
}
