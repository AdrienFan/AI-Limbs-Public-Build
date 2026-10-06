package com.ai.limbs.extensions.chatgptprobe

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

/** Bridge owns notification lifetime and dispatch; Host owns Android rendering and PendingIntent. */
internal class ChatGptNativeProbeNotification(
    context: Context,
    private val engine: ChatGptNativeProbeEngine
) : BridgeProviderNotification {
    private val storage = ChatGptNativeProbeStorage(context.applicationContext)

    override fun snapshot(context: Context, control: BridgeProviderControl): BridgeProviderNotificationState {
        val config = storage.readConfig()
        val probe = engine.state.value
        val access = engine.accessStatus(config)
        val observed = probe.access
        val phase = control.state.phase
        val heartbeat = control.state.lastHeartbeatAtMs
        val activeCount = engine.activeRequestCount
        val attention = when {
            !config.secureStorageAvailable -> "安全凭据存储不可用，请打开连接诊断"
            !config.configured -> "请在插件设置中填写隧道 ID 与 Runtime Key"
            access.catalogMismatchSuspected -> "疑似旧工具目录，请在 ChatGPT 刷新此连接并新建会话；重连隧道不更新目录"
            probe.phase == "AUTH_REQUIRED" -> "连接授权需要更新，请打开插件设置"
            phase == AiLimbsBridgePhase.ERROR || phase == AiLimbsBridgePhase.RECOVERY_FAILED ->
                "连接需要处理，请打开连接诊断"
            else -> null
        }
        return BridgeProviderNotificationState(
            title = "${ChatGptNativeProbePanel.TITLE} · ${phaseLabel(phase)}",
            summary = attention ?: when (phase) {
                AiLimbsBridgePhase.ONLINE -> access.label
                AiLimbsBridgePhase.STOPPED -> "连接已停止"
                AiLimbsBridgePhase.PAIRING -> "等待连接配置"
                else -> "正在处理连接，请稍候"
            },
            statusLines = buildList {
                add("本次调用 ${observed.capabilityInvokeCount} · 成功结果 ${observed.capabilitySuccessCount}")
                if (observed.capabilityFailureCount > 0 || observed.capabilityUncertainCount > 0 || observed.resultPreparationFailureCount > 0)
                    add("未成功 ${observed.capabilityFailureCount} · 不确定 ${observed.capabilityUncertainCount} · 准备异常 ${observed.resultPreparationFailureCount}")
                add("回复送达 ${probe.responseCount} · 协议错误 ${observed.protocolErrorCount}")
                if (activeCount > 0) add("正在处理 $activeCount 个请求")
                add("最近通信：${if (heartbeat == null) "尚无成功通信" else clock(heartbeat)}")
                // Raw provider detail/errors may contain request information; keep them in diagnostics.
                if (probe.lastDeliveryError != null) add("结果交付需要关注，请查看连接诊断")
            },
            actions = notificationActions(phase)
                .filter { it in control.availableActions && allowedByConfig(it, config) }
                .mapIndexed { index, action ->
                    BridgeProviderNotificationAction(
                        id = "bridge:${action.name}", label = actionLabel(action), priority = 100 - index * 10
                    )
                }
        )
    }

    override suspend fun perform(context: Context, actionId: String, control: BridgeProviderControl) {
        // Reject unknown or stale controls before dispatch, including manually constructed action IDs.
        val action = NOTIFICATION_ACTIONS.singleOrNull { actionId == "bridge:${it.name}" }
            ?: error("未知 AI Limbs-ChatGPT 通知动作")
        check(action in notificationActions(control.state.phase) && action in control.availableActions) {
            "通知操作已过期，请使用当前连接状态下的操作"
        }
        check(allowedByConfig(action, storage.readConfig())) { "请先在插件设置中完成连接配置" }
        check(control.perform(action)) { "当前连接状态不支持：${actionLabel(action)}" }
    }

    private fun allowedByConfig(action: BridgeAction, config: ChatGptProbeConfig): Boolean =
        action !in setOf(BridgeAction.CONNECT, BridgeAction.RECONNECT, BridgeAction.RECOVER) ||
            (config.configured && config.secureStorageAvailable)

    private fun notificationActions(phase: AiLimbsBridgePhase): List<BridgeAction> = when (phase) {
        AiLimbsBridgePhase.ONLINE -> listOf(BridgeAction.STOP, BridgeAction.RECONNECT)
        AiLimbsBridgePhase.STOPPED -> listOf(BridgeAction.CONNECT, BridgeAction.REFRESH)
        AiLimbsBridgePhase.STARTING, AiLimbsBridgePhase.CONNECTING, AiLimbsBridgePhase.PAIRING ->
            listOf(BridgeAction.STOP, BridgeAction.REFRESH)
        AiLimbsBridgePhase.RECONNECTING, AiLimbsBridgePhase.ERROR, AiLimbsBridgePhase.RECOVERY_FAILED ->
            listOf(BridgeAction.RECOVER, BridgeAction.STOP)
        AiLimbsBridgePhase.RECOVERING -> emptyList()
    }

    private fun phaseLabel(phase: AiLimbsBridgePhase): String = when (phase) {
        AiLimbsBridgePhase.STOPPED -> "已停止"
        AiLimbsBridgePhase.STARTING -> "正在启动"
        AiLimbsBridgePhase.CONNECTING -> "连接中"
        AiLimbsBridgePhase.PAIRING -> "等待配置"
        AiLimbsBridgePhase.ONLINE -> "隧道已连接"
        AiLimbsBridgePhase.RECONNECTING -> "重连中"
        AiLimbsBridgePhase.RECOVERING -> "恢复中"
        AiLimbsBridgePhase.RECOVERY_FAILED -> "恢复失败"
        AiLimbsBridgePhase.ERROR -> "连接异常"
    }

    private fun actionLabel(action: BridgeAction): String = when (action) {
        BridgeAction.CONNECT -> "连接隧道"
        BridgeAction.STOP -> "断开连接"
        BridgeAction.RECONNECT -> "重连隧道"
        BridgeAction.RECOVER -> "恢复连接"
        BridgeAction.REFRESH -> "检查隧道"
        BridgeAction.REPAIR, BridgeAction.OPEN_AUTH -> error("不支持的通知动作")
    }

    private fun clock(epochMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

    companion object {
        private val NOTIFICATION_ACTIONS = setOf(
            BridgeAction.CONNECT, BridgeAction.STOP, BridgeAction.RECONNECT, BridgeAction.RECOVER, BridgeAction.REFRESH
        )
    }
}
