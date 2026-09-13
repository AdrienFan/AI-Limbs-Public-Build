package com.ai.limbs.extensions.sentinelx

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgePhase
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderControl
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderNotification
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderNotificationAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderNotificationState
import com.ai.limbs.extensions.sentinelx.runtime.SentinelXBridgeStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object SentinelXBridgeProviderNotification : BridgeProviderNotification {
    override fun snapshot(
        context: Context,
        control: BridgeProviderControl
    ): BridgeProviderNotificationState {
        val state = control.state
        val config = SentinelXBridgeStorage(context).readConfig()
        return BridgeProviderNotificationState(
            title = "SentinelX · ${phaseLabel(state.phase)}",
            summary = state.detail.ifBlank { "SentinelX Bridge" },
            statusLines = buildList {
                add("状态：${phaseLabel(state.phase)}")
                add("Host ID：${config.hostId}")
                add("授权：${if (config.configured) "已配置" else "未配置"}")
                state.lastHeartbeatAtMs?.let { add("最后心跳：${clock(it)}") }
            },
            actions = control.availableActions
                .filter { it in NOTIFICATION_ACTIONS }
                .map { action ->
                    BridgeProviderNotificationAction(
                        id = "bridge:${action.name}",
                        label = actionLabel(action),
                        priority = priority(action)
                    )
                }
        )
    }

    override suspend fun perform(
        context: Context,
        actionId: String,
        control: BridgeProviderControl
    ) {
        val action = runCatching { BridgeAction.valueOf(actionId.removePrefix("bridge:")) }
            .getOrElse { error("未知 SentinelX 通知动作：$actionId") }
        check(control.perform(action)) { "当前 SentinelX 状态不支持：${actionLabel(action)}" }
    }

    private fun actionLabel(action: BridgeAction): String = when (action) {
        BridgeAction.CONNECT -> "连接"
        BridgeAction.STOP -> "停止连接"
        BridgeAction.RECONNECT -> "重新连接"
        BridgeAction.RECOVER -> "恢复"
        BridgeAction.REPAIR -> "重新授权"
        BridgeAction.OPEN_AUTH -> "授权页"
        BridgeAction.REFRESH -> "刷新"
    }
    private fun priority(action: BridgeAction): Int = when (action) {
        BridgeAction.STOP -> 100
        BridgeAction.RECONNECT, BridgeAction.CONNECT -> 90
        else -> 0
    }

    private fun phaseLabel(phase: AiLimbsBridgePhase): String = when (phase) {
        AiLimbsBridgePhase.STOPPED -> "已停止"
        AiLimbsBridgePhase.STARTING -> "正在启动"
        AiLimbsBridgePhase.CONNECTING -> "连接中"
        AiLimbsBridgePhase.PAIRING -> "等待授权"
        AiLimbsBridgePhase.ONLINE -> "在线"
        AiLimbsBridgePhase.RECONNECTING -> "重连中"
        AiLimbsBridgePhase.RECOVERING -> "恢复中"
        AiLimbsBridgePhase.RECOVERY_FAILED -> "恢复失败"
        AiLimbsBridgePhase.ERROR -> "错误"
    }

    private fun clock(epochMs: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

    private val NOTIFICATION_ACTIONS = setOf(
        BridgeAction.CONNECT,
        BridgeAction.STOP,
        BridgeAction.RECONNECT
    )
}
