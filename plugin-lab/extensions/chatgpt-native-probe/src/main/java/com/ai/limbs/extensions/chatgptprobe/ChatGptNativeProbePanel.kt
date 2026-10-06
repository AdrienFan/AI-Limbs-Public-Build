package com.ai.limbs.extensions.chatgptprobe

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgePhase
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderControl
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanel
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelField
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelFieldKind
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelResult
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Presentation stays in this child; the existing Bridge/Plugin Center owns rendering. */
internal class ChatGptNativeProbePanel(
    context: Context,
    private val engine: ChatGptNativeProbeEngine
) : BridgeProviderPanel {
    private val storage = ChatGptNativeProbeStorage(context.applicationContext)
    private val initialConfig = storage.readConfig()
    private enum class View { OVERVIEW, SETTINGS, ROTATE_KEY, DIAGNOSTICS, CATALOG_GUIDE, CONFIRM_CLEAR }
    @Volatile private var view = View.OVERVIEW
    @Volatile private var showAdvancedSetup = initialConfig.baseUrl != ChatGptNativeProbeStorage.DEFAULT_BASE_URL
    @Volatile private var setupBaseUrl = initialConfig.baseUrl

    override fun snapshot(context: Context, control: BridgeProviderControl): BridgeProviderPanelState {
        val config = storage.readConfig()
        val probe = engine.state.value
        val access = engine.accessStatus(config)
        val observed = probe.access
        val receipts = engine.uiReceiptCounts(config)
        val phase = control.state.phase
        val stateLine = phaseLabel(phase)
        val heartbeat = listOfNotNull(probe.lastSuccessfulPollAtMs, probe.lastResponseAckAtMs).maxOrNull()
        val summary = buildList {
            add(stateLine)
            if (!config.secureStorageAvailable) add("安全凭据存储不可用，暂时无法连接。")
        }
        fun action(id: String, label: String) = BridgeProviderPanelAction(id, label)
        fun bridge(action: BridgeAction) = BridgeProviderPanelAction("bridge:${action.name}", actionLabel(action))
        fun connectionActions(): List<BridgeProviderPanelAction> = buildList {
            val active = phase in setOf(AiLimbsBridgePhase.ONLINE, AiLimbsBridgePhase.STARTING,
                AiLimbsBridgePhase.CONNECTING, AiLimbsBridgePhase.RECONNECTING, AiLimbsBridgePhase.RECOVERING)
            val main = when {
                active -> BridgeAction.STOP
                phase in setOf(AiLimbsBridgePhase.ERROR, AiLimbsBridgePhase.RECOVERY_FAILED) -> BridgeAction.RECOVER
                else -> BridgeAction.CONNECT
            }
            if (main in control.availableActions) add(bridge(main))
            if (active && BridgeAction.RECONNECT in control.availableActions) add(bridge(BridgeAction.RECONNECT))
        }
        fun panel(description: String, lines: List<String> = summary,
                  fields: List<BridgeProviderPanelField> = emptyList(),
                  actions: List<BridgeProviderPanelAction> = emptyList()) = BridgeProviderPanelState(
            title = TITLE, description = description, statusLines = lines, fields = fields, actions = actions
        )

        // Setup is the only unconfigured form. A live-state refresh must not expose a secret editor.
        if (!config.configured && view !in setOf(View.DIAGNOSTICS, View.CATALOG_GUIDE)) {
            val fields = buildList {
                add(BridgeProviderPanelField(FIELD_TUNNEL_ID, "隧道 ID", value = config.tunnelId, placeholder = "tunnel_…"))
                add(BridgeProviderPanelField(FIELD_API_KEY, "Runtime Key", kind = BridgeProviderPanelFieldKind.SECRET,
                    placeholder = "在本机粘贴 sk-…", enabled = config.secureStorageAvailable))
                if (showAdvancedSetup) add(BridgeProviderPanelField(FIELD_BASE_URL, "服务地址", value = setupBaseUrl,
                    placeholder = ChatGptNativeProbeStorage.DEFAULT_BASE_URL))
            }
            return panel("首次连接 · 填入隧道 ID 与密钥即可开始。", fields = fields, actions = listOf(
                BridgeProviderPanelAction(ACTION_SAVE_START, "保存并连接", enabled = config.secureStorageAvailable,
                    requiredFieldIds = setOf(FIELD_API_KEY, FIELD_TUNNEL_ID)),
                action(ACTION_ADVANCED, if (showAdvancedSetup) "收起高级设置" else "高级设置"),
                action(ACTION_DIAGNOSTICS, "连接诊断")
            ))
        }

        return when (view) {
            View.OVERVIEW -> panel("连接 ChatGPT，让 AI Limbs 执行你的任务。", lines = buildList {
                addAll(summary)
                add(access.label)
                if (access.catalogMismatchSuspected) add("疑似仍在使用旧工具目录，请打开工具目录指引。")
                if (phase in setOf(AiLimbsBridgePhase.ERROR, AiLimbsBridgePhase.RECOVERY_FAILED)) add("打开连接诊断，查看需要处理的问题。")
                add("本次调用 ${observed.capabilityInvokeCount}  ·  成功结果 ${observed.capabilitySuccessCount}")
                if (observed.capabilityFailureCount > 0 || observed.capabilityUncertainCount > 0)
                    add("未成功 ${observed.capabilityFailureCount}  ·  结果不确定 ${observed.capabilityUncertainCount}")
                if (observed.resultPreparationFailureCount > 0) add("本次结果准备异常 ${observed.resultPreparationFailureCount}")
                add("隧道请求 ${probe.commandCount}  ·  回复送达 ${probe.responseCount}")
                if (receipts != null) {
                    val pending = receipts.optInt("READY")
                    val failed = receipts.optInt("DELIVERY_FAILED")
                    if (pending > 0 || failed > 0) add("待送结果 $pending  ·  交付异常 $failed")
                }
                if (heartbeat != null) add("最近通信 ${time(heartbeat)}")
            }, actions = connectionActions() + listOf(action(ACTION_CATALOG_GUIDE, "工具目录指引"),
                action(ACTION_SETTINGS, "连接设置"), action(ACTION_DIAGNOSTICS, "连接诊断")))

            View.CATALOG_GUIDE -> panel("工具目录 · 六个稳定入口，动态发现 Host 能力。", lines = buildList {
                addAll(summary)
                add("新增 Host 业务能力通过搜索和描述工具发现；六个入口的元数据未变时，无需刷新 ChatGPT 工具目录。")
                add("入口名称、描述或参数等元数据变化后，需要在 ChatGPT 更新这条连接。")
                add("如果还看到 echo、server_info、uppercase 三个演示工具，请按下面步骤更新旧目录。")
                addAll(GatewayAdmission.REFRESH_STEPS)
                add("当前正式入口：")
                addAll(engine.advertisedToolNames())
                add("重连隧道和检查隧道只处理本机通信，不会清除 ChatGPT 保存的工具目录。")
                add("本机不自动更新 ChatGPT 工具目录；旧会话请停止使用并新建会话。")
            }, actions = listOf(action(ACTION_HOME, "返回概览"), action(ACTION_DIAGNOSTICS, "连接诊断")))

            View.SETTINGS -> panel("连接设置 · 密钥已加密保存。", lines = summary + "当前隧道 ${shortTunnel(config.tunnelId)}",
                fields = listOf(
                    BridgeProviderPanelField(FIELD_TUNNEL_ID, "隧道 ID", value = config.tunnelId, enabled = false),
                    BridgeProviderPanelField(FIELD_BASE_URL, "服务地址", value = config.baseUrl, enabled = false)
                ), actions = listOf(action(ACTION_HOME, "返回概览"), action(ACTION_EDIT_KEY, "更换密钥"), action(ACTION_SHOW_CLEAR, "清除配置…")))

            View.ROTATE_KEY -> panel("更换密钥 · 保存后重新连接，隧道不变。", lines = summary + "当前隧道 ${shortTunnel(config.tunnelId)}",
                fields = listOf(BridgeProviderPanelField(FIELD_API_KEY, "新的 Runtime Key", kind = BridgeProviderPanelFieldKind.SECRET,
                    placeholder = "输入新的 sk-…", enabled = config.secureStorageAvailable)),
                actions = listOf(
                    BridgeProviderPanelAction(ACTION_ROTATE, "保存并重连", enabled = config.secureStorageAvailable, requiredFieldIds = setOf(FIELD_API_KEY)),
                    action(ACTION_SETTINGS, "取消")
                ))

            View.CONFIRM_CLEAR -> panel("确认清除连接配置", lines = listOf(
                "将断开连接，并删除本机的密钥与隧道 ID。",
                "这不会撤销已经执行的操作。"
            ), actions = listOf(action(ACTION_SETTINGS, "取消"), action(ACTION_CLEAR, "确认清除")))

            View.DIAGNOSTICS -> panel("连接诊断 · 查看通信与结果交付情况。", lines = buildList {
                addAll(summary)
                add("版本 ${McpGatewayState.PROBE_VERSION}")
                add("接入观察：${access.label}")
                observed.startedAtMs?.let { add("本次观察始于 ${time(it)}；重新连接会重置观察计数。") }
                add("当前工具调用 ${observed.advertisedToolCallCount}  ·  协议错误 ${observed.protocolErrorCount}")
                add("未知工具请求 ${observed.unadvertisedToolCount}  ·  初始化响应 ${observed.initializeCount}")
                add("能力调用 ${observed.capabilityInvokeCount}  ·  成功结果 ${observed.capabilitySuccessCount}")
                add("未成功 ${observed.capabilityFailureCount}  ·  结果不确定 ${observed.capabilityUncertainCount}")
                add("结果准备异常 ${observed.resultPreparationFailureCount}")
                observed.lastProtocolErrorCode?.let { add("最近 JSON-RPC 错误码 $it") }
                add("轮询 ${probe.pollCount}  ·  请求 ${probe.commandCount}  ·  已送达 ${probe.responseCount}")
                add("正在处理 ${engine.activeRequestCount} 个请求")
                if (receipts == null) add("交付记录尚未加载，连接时会读取。") else {
                    add("待送 ${receipts.optInt("READY")}  ·  交付异常 ${receipts.optInt("DELIVERY_FAILED")}")
                    add("处理中或待确认 ${receipts.optInt("EXECUTING")}")
                }
                add("最近通信 ${if (heartbeat == null) "尚无成功通信" else time(heartbeat)}")
                add("工具目录请求 ${if (probe.lastToolsListAtMs == null) "尚未收到" else time(probe.lastToolsListAtMs)}")
                add("结果交付 ${if (probe.lastResponseAckAtMs == null) "尚无成功交付" else time(probe.lastResponseAckAtMs)}")
                probe.lastStatusCode?.let { add("最近 HTTP 状态 $it") }
                probe.lastMethod?.let { add("最近请求 $it") }
                probe.lastError?.let { add("连接问题：$it") }
                probe.lastDeliveryError?.let { add("交付问题：$it") }
                probe.lastCommandError?.let { add("请求问题：$it") }
                if (phase in setOf(AiLimbsBridgePhase.ERROR, AiLimbsBridgePhase.RECOVERY_FAILED)) add(control.state.detail)
                add("收到目录请求不代表 ChatGPT 已刷新工具列表。")
                add("Refresh 是 ChatGPT 工具目录刷新，不是本页的检查隧道或重连隧道。")
                if (access.catalogMismatchSuspected || !access.advertisedToolObserved) addAll(GatewayAdmission.REFRESH_STEPS)
            }, actions = listOf(action(ACTION_HOME, "返回概览"), action(ACTION_CATALOG_GUIDE, "工具目录指引")) + control.availableActions
                .filter { it in setOf(BridgeAction.REFRESH, BridgeAction.RECONNECT, BridgeAction.RECOVER) }.map(::bridge))
        }
    }

    override suspend fun perform(context: Context, actionId: String, fieldValues: Map<String, String>,
                                 control: BridgeProviderControl): BridgeProviderPanelResult {
        if (!storage.readConfig().configured && showAdvancedSetup && fieldValues.containsKey(FIELD_BASE_URL)) {
            setupBaseUrl = fieldValues.getValue(FIELD_BASE_URL)
        }
        when (actionId) {
            ACTION_HOME -> view = View.OVERVIEW
            ACTION_SETTINGS -> view = View.SETTINGS
            ACTION_EDIT_KEY -> { require(storage.readConfig().configured) { "请先完成连接配置" }; view = View.ROTATE_KEY }
            ACTION_DIAGNOSTICS -> view = View.DIAGNOSTICS
            ACTION_CATALOG_GUIDE -> view = View.CATALOG_GUIDE
            ACTION_SHOW_CLEAR -> { require(storage.readConfig().configured) { "没有可清除的连接配置" }; view = View.CONFIRM_CLEAR }
            ACTION_ADVANCED -> {
                showAdvancedSetup = !showAdvancedSetup
                // Preserve the unfinished form while only changing which fields are visible.
                return BridgeProviderPanelResult()
            }
            ACTION_SAVE_START -> {
                storage.saveBinding(fieldValues[FIELD_API_KEY].orEmpty(), fieldValues[FIELD_TUNNEL_ID].orEmpty(), setupBaseUrl)
                val accepted = control.perform(BridgeAction.CONNECT)
                view = View.OVERVIEW
                return clearedSecret(if (accepted) "配置已保存，正在连接" else "配置已保存，请点击连接")
            }
            ACTION_ROTATE -> {
                val config = storage.readConfig()
                require(config.configured) { "请先完成连接配置" }
                val key = fieldValues[FIELD_API_KEY].orEmpty()
                storage.validateApiKey(key)
                control.perform(BridgeAction.STOP)
                storage.saveBinding(key, config.tunnelId, config.baseUrl)
                val accepted = control.perform(BridgeAction.CONNECT)
                view = View.OVERVIEW
                return clearedSecret(if (accepted) "密钥已更新，正在重新连接" else "密钥已更新，请点击连接")
            }
            ACTION_CLEAR -> {
                require(view == View.CONFIRM_CLEAR) { "请先打开清除配置确认界面" }
                control.perform(BridgeAction.STOP)
                storage.clearBinding()
                view = View.OVERVIEW
                setupBaseUrl = storage.readConfig().baseUrl
                showAdvancedSetup = setupBaseUrl != ChatGptNativeProbeStorage.DEFAULT_BASE_URL
                return BridgeProviderPanelResult("连接配置已清除", mapOf(FIELD_API_KEY to "", FIELD_TUNNEL_ID to "", FIELD_BASE_URL to setupBaseUrl))
            }
            else -> {
                require(actionId.startsWith("bridge:")) { "未知面板操作" }
                val action = BridgeAction.valueOf(actionId.removePrefix("bridge:"))
                val accepted = control.perform(action)
                return clearedSecret(if (accepted) "已执行：${actionLabel(action)}" else "当前状态不支持：${actionLabel(action)}")
            }
        }
        // Leaving any form clears the transient secret in the parent renderer's field cache.
        return clearedSecret()
    }

    private fun clearedSecret(message: String = "") = BridgeProviderPanelResult(message, mapOf(FIELD_API_KEY to ""))
    private fun time(value: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(value))
    private fun shortTunnel(value: String): String = if (value.length <= 24) value else value.take(14) + "…" + value.takeLast(6)

    private fun phaseLabel(phase: AiLimbsBridgePhase): String = when (phase) {
        AiLimbsBridgePhase.ONLINE -> "🟢 隧道已连接"
        AiLimbsBridgePhase.STOPPED -> "⚪ 已停止"
        AiLimbsBridgePhase.PAIRING -> "⚪ 等待配置"
        AiLimbsBridgePhase.STARTING -> "🟠 正在启动"
        AiLimbsBridgePhase.CONNECTING -> "🟠 正在连接"
        AiLimbsBridgePhase.RECONNECTING -> "🟠 正在重新连接"
        AiLimbsBridgePhase.RECOVERING -> "🟠 正在恢复"
        AiLimbsBridgePhase.RECOVERY_FAILED -> "🔴 恢复失败"
        AiLimbsBridgePhase.ERROR -> "🔴 连接异常"
    }

    private fun actionLabel(action: BridgeAction): String = when (action) {
        BridgeAction.CONNECT -> "连接隧道"
        BridgeAction.STOP -> "断开连接"
        BridgeAction.RECONNECT -> "重连隧道"
        BridgeAction.RECOVER -> "恢复连接"
        BridgeAction.REFRESH -> "检查隧道"
        BridgeAction.REPAIR -> "重新配置"
        BridgeAction.OPEN_AUTH -> "授权"
    }

    companion object {
        const val TITLE = "AI Limbs-ChatGPT"
        private const val FIELD_API_KEY = "runtime_api_key"
        private const val FIELD_TUNNEL_ID = "tunnel_id"
        private const val FIELD_BASE_URL = "base_url"
        private const val ACTION_SAVE_START = "chatgpt_probe.save_start"
        private const val ACTION_ROTATE = "chatgpt_probe.rotate_key"
        private const val ACTION_CLEAR = "chatgpt_probe.clear"
        private const val ACTION_HOME = "chatgpt_probe.view_home"
        private const val ACTION_SETTINGS = "chatgpt_probe.view_settings"
        private const val ACTION_EDIT_KEY = "chatgpt_probe.edit_key"
        private const val ACTION_DIAGNOSTICS = "chatgpt_probe.view_diagnostics"
        private const val ACTION_CATALOG_GUIDE = "chatgpt_probe.view_catalog_guide"
        private const val ACTION_SHOW_CLEAR = "chatgpt_probe.confirm_clear"
        private const val ACTION_ADVANCED = "chatgpt_probe.toggle_advanced"
    }
}
