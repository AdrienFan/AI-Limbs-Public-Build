package com.ai.limbs.extensions.sentinelx

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderControl
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanel
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelField
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelFieldKind
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelResult
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelState
import com.ai.limbs.extensions.sentinelx.runtime.SentinelXBridgeStorage

internal object SentinelXBridgeProviderPanel : BridgeProviderPanel {
    override fun snapshot(
        context: Context,
        control: BridgeProviderControl
    ): BridgeProviderPanelState {
        val config = SentinelXBridgeStorage(context).readConfig()
        return BridgeProviderPanelState(
            title = "SentinelX",
            description = "SentinelX Bridge 授权、身份与连接控制（原生 Android，不依赖 Ubuntu）",
            statusLines = buildList {
                add("状态：${control.state.phase}")
                add("授权：${if (config.configured) "已配置" else "未配置"}")
                add("Host ID：${config.hostId}")
                add("Hub：${config.hubUrl}")
                if (!config.secureStorageAvailable) add("安全存储不可用")
                control.state.detail.takeIf { it.isNotBlank() }?.let(::add)
            },
            fields = listOf(
                BridgeProviderPanelField(
                    id = FIELD_TOKEN,
                    label = "Enrollment Token",
                    kind = BridgeProviderPanelFieldKind.SECRET,
                    placeholder = if (config.configured) "已配置；请先清除绑定后再重新授权" else "粘贴 SentinelX Enrollment JWT",
                    enabled = config.secureStorageAvailable && !config.configured
                ),
                BridgeProviderPanelField(
                    id = FIELD_HUB_URL,
                    label = "Hub URL",
                    value = config.hubUrl,
                    placeholder = SentinelXBridgeStorage.DEFAULT_HUB_URL,
                    enabled = !config.configured
                ),
                BridgeProviderPanelField(
                    id = FIELD_DEVICE_NAME,
                    label = "Device Name",
                    value = config.deviceName,
                    placeholder = "AI Limbs 设备名称",
                    enabled = !config.configured
                )
            ),
            actions = buildList {
                add(
                    BridgeProviderPanelAction(
                        id = ACTION_SAVE_CONNECT,
                        label = "保存并连接",
                        enabled = config.secureStorageAvailable && !config.configured,
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
        val storage = SentinelXBridgeStorage(context)
        return when (actionId) {
            ACTION_SAVE_CONNECT -> {
                check(!storage.readConfig().configured) {
                    "SentinelX 已绑定；请先清除绑定后再重新配置"
                }
                storage.saveBinding(
                    token = fieldValues[FIELD_TOKEN].orEmpty(),
                    hubUrl = fieldValues[FIELD_HUB_URL].orEmpty(),
                    deviceName = fieldValues[FIELD_DEVICE_NAME].orEmpty()
                )
                val accepted = control.perform(BridgeAction.CONNECT)
                val config = storage.readConfig()
                BridgeProviderPanelResult(
                    message = if (accepted) "配置已保存，正在连接" else "配置已保存；当前状态暂不接受连接",
                    fieldValues = mapOf(
                        FIELD_TOKEN to "",
                        FIELD_HUB_URL to config.hubUrl,
                        FIELD_DEVICE_NAME to config.deviceName
                    )
                )
            }
            ACTION_CLEAR_BINDING -> {
                storage.clearBinding()
                control.perform(BridgeAction.STOP)
                val config = storage.readConfig()
                BridgeProviderPanelResult(
                    message = "SentinelX 绑定已清除；Host ID 保留，可重新授权",
                    fieldValues = mapOf(
                        FIELD_TOKEN to "",
                        FIELD_HUB_URL to config.hubUrl,
                        FIELD_DEVICE_NAME to config.deviceName
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
            .getOrElse { error("未知 SentinelX 动作：$actionId") }
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
        BridgeAction.REPAIR -> "重新授权（新 Host ID）"
        BridgeAction.OPEN_AUTH -> "打开授权页"
        BridgeAction.REFRESH -> "刷新 / Liveness"
    }

    private const val FIELD_TOKEN = "enrollment_token"
    private const val FIELD_HUB_URL = "hub_url"
    private const val FIELD_DEVICE_NAME = "device_name"
    private const val ACTION_SAVE_CONNECT = "sentinelx.save_connect"
    private const val ACTION_CLEAR_BINDING = "sentinelx.clear_binding"
}
