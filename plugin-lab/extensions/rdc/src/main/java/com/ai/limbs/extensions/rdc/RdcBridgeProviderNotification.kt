package com.ai.limbs.extensions.rdc

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgePhase
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderControl
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderNotification
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderNotificationAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderNotificationState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object RdcBridgeProviderNotification : BridgeProviderNotification {
    override fun snapshot(context: Context, control: BridgeProviderControl): BridgeProviderNotificationState {
        val state = control.state
        return BridgeProviderNotificationState(
            title = "RDC · ${phaseLabel(state.phase)}",
            summary = state.detail.ifBlank { "Remote Desktop Commander Bridge" },
            statusLines = buildList {
                add("状态：${phaseLabel(state.phase)}")
                state.deviceId?.takeIf { it.isNotBlank() }?.let { add("设备 ID：${shortId(it)}") }
                state.userCode?.takeIf { it.isNotBlank() }?.let { add("授权码：$it") }
                state.lastHeartbeatAtMs?.let { add("最后心跳：${clock(it)}") }
                if (state.reconnectAttempt > 0) add("重连次数：${state.reconnectAttempt}")
                state.detail.takeIf { it.isNotBlank() }?.let(::add)
            },
            actions = control.availableActions
                .filter { it in NOTIFICATION_ACTIONS }
                .map { action ->
                    BridgeProviderNotificationAction(
                        id = "bridge:${action.name}",
                        label = actionLabel(action, state.phase),
                        priority = priority(action, state.phase)
                    )
                }
        )
    }

    override suspend fun perform(context: Context, actionId: String, control: BridgeProviderControl) {
        val action = parseBridgeAction(actionId)
        check(control.perform(action)) { "当前 RDC 状态不支持：${actionLabel(action, control.state.phase)}" }
    }

    private fun parseBridgeAction(actionId: String): BridgeAction =
        runCatching { BridgeAction.valueOf(actionId.removePrefix("bridge:")) }
            .getOrElse { error("未知 RDC 通知动作：$actionId") }

    private fun actionLabel(action: BridgeAction, phase: AiLimbsBridgePhase): String = when (action) {
        BridgeAction.CONNECT -> "连接"
        BridgeAction.STOP -> "停止连接"
        BridgeAction.RECONNECT -> if (phase == AiLimbsBridgePhase.PAIRING) "刷新验证码" else "重新连接"
        BridgeAction.REPAIR -> "重新配对"
        BridgeAction.OPEN_AUTH -> "打开授权页"
        BridgeAction.RECOVER -> "恢复"
        BridgeAction.REFRESH -> "刷新"
    }

    private fun priority(action: BridgeAction, phase: AiLimbsBridgePhase): Int = when {
        phase == AiLimbsBridgePhase.PAIRING && action == BridgeAction.OPEN_AUTH -> 120
        phase == AiLimbsBridgePhase.PAIRING && action == BridgeAction.RECONNECT -> 110
        action == BridgeAction.STOP -> 100
        action == BridgeAction.RECONNECT -> 90
        action == BridgeAction.CONNECT -> 90
        action == BridgeAction.REPAIR -> 80
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

    private fun shortId(value: String): String = if (value.length <= 16) value else value.take(8) + "…" + value.takeLast(6)
    private fun clock(epochMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

    private val NOTIFICATION_ACTIONS = setOf(
        BridgeAction.CONNECT, BridgeAction.STOP, BridgeAction.RECONNECT, BridgeAction.REPAIR, BridgeAction.OPEN_AUTH
    )
}
