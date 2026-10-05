package com.ai.limbs.extensions.chatgptprobe

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderControl
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanel
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelField
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelFieldKind
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelResult
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelState

internal object ChatGptNativeProbePanel : BridgeProviderPanel {
    override fun snapshot(
        context: Context,
        control: BridgeProviderControl
    ): BridgeProviderPanelState {
        val config = ChatGptNativeProbeStorage(context).readConfig()
        return BridgeProviderPanelState(
            title = "ChatGPT Control Plane Probe",
            description = "纯 Android/Kotlin Tunnel 测试：直接用 OkHttp 连接 OpenAI Control Plane，不依赖 Ubuntu，也不运行 tunnel-client ELF。",
            statusLines = buildList {
                add("状态：${control.state.phase}")
                add("方式：Android OkHttp HTTPS long-poll")
                add("Tunnel ID：${if (config.tunnelId.isBlank()) "未配置" else config.tunnelId}")
                add("Runtime Key：${if (config.configured) "已安全配置" else "未配置"}")
                add("Control Plane：${config.baseUrl}")
                control.state.detail.takeIf { it.isNotBlank() }?.let(::add)
            },
            fields = listOf(
                BridgeProviderPanelField(
                    id = FIELD_API_KEY,
                    label = "Runtime API Key",
                    kind = BridgeProviderPanelFieldKind.SECRET,
                    placeholder = if (config.configured) "已加密保存；如需更换请先清除配置" else "在本机粘贴 sk-…",
                    enabled = config.secureStorageAvailable && !config.configured
                ),
                BridgeProviderPanelField(
                    id = FIELD_TUNNEL_ID,
                    label = "Tunnel ID",
                    value = config.tunnelId,
                    placeholder = "tunnel_…",
                    enabled = !config.configured
                ),
                BridgeProviderPanelField(
                    id = FIELD_BASE_URL,
                    label = "Control Plane URL",
                    value = config.baseUrl,
                    placeholder = ChatGptNativeProbeStorage.DEFAULT_BASE_URL,
                    enabled = !config.configured
                )
            ),
            actions = buildList {
                if (!config.configured) {
                    add(
                        BridgeProviderPanelAction(
                            id = ACTION_SAVE_TEST,
                            label = "保存并测试",
                            enabled = config.secureStorageAvailable,
                            requiredFieldIds = setOf(FIELD_API_KEY, FIELD_TUNNEL_ID)
                        )
                    )
                } else {
                    add(BridgeProviderPanelAction(ACTION_CLEAR, "清除本地配置"))
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
        val storage = ChatGptNativeProbeStorage(context)
        return when (actionId) {
            ACTION_SAVE_TEST -> {
                storage.saveBinding(
                    apiKey = fieldValues[FIELD_API_KEY].orEmpty(),
                    tunnelId = fieldValues[FIELD_TUNNEL_ID].orEmpty(),
                    baseUrl = fieldValues[FIELD_BASE_URL].orEmpty()
                )
                val accepted = control.perform(BridgeAction.CONNECT)
                val config = storage.readConfig()
                BridgeProviderPanelResult(
                    message = if (accepted) "配置已安全保存，正在直连 OpenAI Control Plane" else "配置已保存；当前状态暂不接受测试",
                    fieldValues = mapOf(
                        FIELD_API_KEY to "",
                        FIELD_TUNNEL_ID to config.tunnelId,
                        FIELD_BASE_URL to config.baseUrl
                    )
                )
            }
            ACTION_CLEAR -> {
                storage.clearBinding()
                control.perform(BridgeAction.STOP)
                BridgeProviderPanelResult(
                    message = "ChatGPT Probe 本地凭据与 Tunnel ID 已清除",
                    fieldValues = mapOf(
                        FIELD_API_KEY to "",
                        FIELD_TUNNEL_ID to "",
                        FIELD_BASE_URL to ChatGptNativeProbeStorage.DEFAULT_BASE_URL
                    )
                )
            }
            else -> performBridgeAction(actionId, control)
        }
    }

    private suspend fun performBridgeAction(
        actionId: String,
        control: BridgeProviderControl
    ): BridgeProviderPanelResult {
        val action = runCatching {
            BridgeAction.valueOf(actionId.removePrefix("bridge:"))
        }.getOrElse {
            error("未知 ChatGPT Probe 动作：$actionId")
        }
        val accepted = control.perform(action)
        return BridgeProviderPanelResult(
            message = if (accepted) "已执行：${actionLabel(action)}" else "当前状态不支持：${actionLabel(action)}"
        )
    }

    private fun actionLabel(action: BridgeAction): String = when (action) {
        BridgeAction.CONNECT -> "测试连接"
        BridgeAction.STOP -> "停止"
        BridgeAction.RECONNECT -> "重新测试"
        BridgeAction.RECOVER -> "恢复并测试"
        BridgeAction.REFRESH -> "刷新 / 再测"
        BridgeAction.REPAIR -> "重新配置"
        BridgeAction.OPEN_AUTH -> "无授权页"
    }

    private const val FIELD_API_KEY = "runtime_api_key"
    private const val FIELD_TUNNEL_ID = "tunnel_id"
    private const val FIELD_BASE_URL = "base_url"
    private const val ACTION_SAVE_TEST = "chatgpt_probe.save_test"
    private const val ACTION_CLEAR = "chatgpt_probe.clear"
}
