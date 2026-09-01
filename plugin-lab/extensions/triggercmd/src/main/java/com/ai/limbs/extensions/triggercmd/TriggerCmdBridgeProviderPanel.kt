package com.ai.limbs.extensions.triggercmd

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderControl
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanel
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelField
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelFieldKind
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelResult
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelState
import com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd.TriggerCmdBridgeStorage

internal object TriggerCmdBridgeProviderPanel : BridgeProviderPanel {
    override fun snapshot(
        context: Context,
        control: BridgeProviderControl
    ): BridgeProviderPanelState {
        val config = TriggerCmdBridgeStorage(context).readConfig()
        return BridgeProviderPanelState(
            title = "TRIGGERcmd",
            description = "TRIGGERcmd Bridge 配置与连接控制",
            statusLines = buildList {
                add("状态：${control.state.phase}")
                add("Agent Token：${if (config.configured) "已配置" else "未配置"}")
                add("Computer ID：${config.computerId ?: "尚未注册"}")
                if (!config.secureStorageAvailable) add("安全存储不可用")
                control.state.detail.takeIf { it.isNotBlank() }?.let(::add)
            },
            fields = listOf(
                BridgeProviderPanelField(
                    id = FIELD_TOKEN,
                    label = "Agent Token",
                    kind = BridgeProviderPanelFieldKind.SECRET,
                    placeholder = if (config.configured) "已配置；输入新 Token 可替换" else "请输入 Agent Token",
                    enabled = config.secureStorageAvailable
                ),
                BridgeProviderPanelField(
                    id = FIELD_COMPUTER_NAME,
                    label = "Computer Name",
                    value = config.computerName,
                    placeholder = "AI Limbs 设备名称"
                )
            ),
            actions = buildList {
                add(
                    BridgeProviderPanelAction(
                        id = ACTION_SAVE_CONNECT,
                        label = "保存并连接",
                        enabled = config.secureStorageAvailable,
                        requiredFieldIds = setOf(FIELD_TOKEN)
                    )
                )
                if (config.configured) {
                    add(BridgeProviderPanelAction(ACTION_CLEAR_BINDING, "清除绑定"))
                }
                control.availableActions.forEach { action ->
                    add(
                        BridgeProviderPanelAction(
                            id = "bridge:${action.name}",
                            label = actionLabel(action)
                        )
                    )
                }
            }
        )
    }

    override suspend fun perform(
        context: Context,
        actionId: String,
        fieldValues: Map<String, String>,
        control: BridgeProviderControl
    ): BridgeProviderPanelResult {
        val storage = TriggerCmdBridgeStorage(context)
        return when (actionId) {
            ACTION_SAVE_CONNECT -> {
                storage.saveBinding(
                    fieldValues[FIELD_TOKEN].orEmpty(),
                    fieldValues[FIELD_COMPUTER_NAME].orEmpty()
                )
                val accepted = control.perform(BridgeAction.CONNECT)
                val config = storage.readConfig()
                BridgeProviderPanelResult(
                    message = if (accepted) "配置已保存，正在连接" else "配置已保存；当前状态暂不接受连接",
                    fieldValues = mapOf(
                        FIELD_TOKEN to "",
                        FIELD_COMPUTER_NAME to config.computerName
                    )
                )
            }
            ACTION_CLEAR_BINDING -> {
                storage.clearBinding()
                control.perform(BridgeAction.STOP)
                val config = storage.readConfig()
                BridgeProviderPanelResult(
                    message = "TRIGGERcmd 绑定已清除",
                    fieldValues = mapOf(
                        FIELD_TOKEN to "",
                        FIELD_COMPUTER_NAME to config.computerName
                    )
                )
            }
            else -> performBridgeAction(actionId, control)
        }
    }
    private fun performBridgeAction(
        actionId: String,
        control: BridgeProviderControl
    ): BridgeProviderPanelResult {
        val actionName = actionId.removePrefix("bridge:")
        val action = runCatching { BridgeAction.valueOf(actionName) }
            .getOrElse { error("未知 TRIGGERcmd 动作：$actionId") }
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

    private const val FIELD_TOKEN = "agent_token"
    private const val FIELD_COMPUTER_NAME = "computer_name"
    private const val ACTION_SAVE_CONNECT = "triggercmd.save_connect"
    private const val ACTION_CLEAR_BINDING = "triggercmd.clear_binding"
}
