package com.ai.limbs.extensions.chatgptprobe

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderControl
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanel
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelResult
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelState

internal object ChatGptNativeProbePanel : BridgeProviderPanel {
    override fun snapshot(
        context: Context,
        control: BridgeProviderControl
    ): BridgeProviderPanelState {
        val state = control.state
        return BridgeProviderPanelState(
            title = "ChatGPT Native Probe",
            description = "一次性兼容性测试：不使用 Ubuntu，不连接真实 Tunnel，只验证 Android Host 能否直接 exec 官方 tunnel-client v0.0.15 ARM64。",
            statusLines = listOf(
                "状态：${state.phase}",
                state.detail,
                "成功标准：tunnel-client --version exit=0",
                "此测试不读取 Runtime API Key，也不会建立 OpenAI 连接。"
            ),
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
        val action = runCatching {
            BridgeAction.valueOf(actionId.removePrefix("bridge:"))
        }.getOrElse {
            error("未知 ChatGPT Native Probe 动作：$actionId")
        }
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
        BridgeAction.CONNECT -> "运行测试"
        BridgeAction.STOP -> "停止"
        BridgeAction.RECONNECT -> "重新测试"
        BridgeAction.RECOVER -> "恢复并测试"
        BridgeAction.REPAIR -> "重新测试"
        BridgeAction.REFRESH -> "刷新 / 再测"
        BridgeAction.OPEN_AUTH -> "无授权页"
    }
}
