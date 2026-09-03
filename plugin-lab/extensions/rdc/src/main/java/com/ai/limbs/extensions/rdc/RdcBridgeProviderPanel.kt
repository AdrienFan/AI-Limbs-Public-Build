package com.ai.limbs.extensions.rdc

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderControl
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanel
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelResult
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelState

/**
 * Supplies Bridge-owned presentation state only. The parent Bridge plugin adapts this state to the
 * Plugin Center component schema, so this .ailx never owns or bypasses the Host UI renderer.
 */
internal object RdcBridgeProviderPanel : BridgeProviderPanel {
    override fun snapshot(
        context: Context,
        control: BridgeProviderControl
    ): BridgeProviderPanelState {
        val state = control.state
        return BridgeProviderPanelState(
            title = "RDC",
            description = "Remote Desktop Commander Bridge",
            statusLines = buildList {
                add("状态：${state.phase}")
                state.detail.takeIf { it.isNotBlank() }?.let { add(it) }
                state.deviceId?.takeIf { it.isNotBlank() }?.let { add("设备 ID：$it") }
                state.userCode?.takeIf { it.isNotBlank() }?.let { add("授权码：$it") }
            },
            actions = control.availableActions.map { action ->
                BridgeProviderPanelAction(
                    id = "bridge:${action.name}",
                    label = actionLabel(action)
                )
            }
        )
    }

    override suspend fun perform(
        context: Context,
        actionId: String,
        fieldValues: Map<String, String>,
        control: BridgeProviderControl
    ): BridgeProviderPanelResult {
        val actionName = actionId.removePrefix("bridge:")
        val action = runCatching { BridgeAction.valueOf(actionName) }
            .getOrElse { error("未知 RDC 动作：$actionId") }
        val accepted = control.perform(action)
        return BridgeProviderPanelResult(
            message = if (accepted) {
                "已执行：${actionLabel(action)}"
            } else {
                "当前状态不支持：${actionLabel(action)}"
            }
        )
    }

    private fun actionLabel(action: BridgeAction): String = when (action) {
        BridgeAction.CONNECT -> "连接"
        BridgeAction.STOP -> "停止"
        BridgeAction.RECONNECT -> "重连"
        BridgeAction.RECOVER -> "恢复"
        BridgeAction.REPAIR -> "重新配对"
        BridgeAction.OPEN_AUTH -> "打开授权页"
        BridgeAction.REFRESH -> "刷新 / Liveness"
    }
}
