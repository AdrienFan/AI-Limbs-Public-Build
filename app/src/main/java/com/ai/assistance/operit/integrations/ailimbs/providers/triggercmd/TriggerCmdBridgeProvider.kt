package com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeHostSignal
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeNetworkState
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgePhase
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeProvider
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeState
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProfile
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderFactory
import com.ai.assistance.operit.integrations.ailimbs.NativeBridgeProfile
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class TriggerCmdBridgeProvider private constructor(
    context: Context,
    private val scope: CoroutineScope,
    private val profile: NativeBridgeProfile
) : AiLimbsBridgeProvider, TriggerCmdTransportClient.Listener {
    private val appContext = context.applicationContext
    private val storage = TriggerCmdBridgeStorage(appContext)
    private val client = TriggerCmdTransportClient(storage.transportPreferences(), this)
    private val structuredExecutor = TriggerCmdStructuredBridgeExecutor(appContext, scope)
    private val stateFlow = MutableStateFlow(initialState())

    override val id: String
        get() = profile.id
    override val enabled: Boolean
        get() = profile.enabled
    override val isRunning: Boolean
        get() = client.isRunning
    override val state: StateFlow<AiLimbsBridgeState> = stateFlow.asStateFlow()
    override val statusSummary: String
        get() = "${state.value.phase}: ${state.value.detail}"
    override val supportedActions: Set<BridgeAction>
        get() = SUPPORTED_ACTIONS
    override val requiresScreenOffCpuKeepAlive: Boolean
        get() = true

    override fun start() {
        val config = storage.readConfig()
        if (!config.secureStorageAvailable) {
            stateFlow.value = baseState(
                phase = AiLimbsBridgePhase.ERROR,
                detail = "TRIGGERcmd 安全凭据存储不可用；未读取或保存 Token"
            )
            return
        }
        val token = storage.readAgentToken()
        if (token.isNullOrBlank()) {
            stateFlow.value = baseState(
                phase = AiLimbsBridgePhase.STOPPED,
                detail = "TRIGGERcmd 未配置 Agent Token"
            )
            return
        }
        if (client.isRunning && state.value.phase == AiLimbsBridgePhase.ONLINE) return
        stateFlow.value = baseState(
            phase = AiLimbsBridgePhase.STARTING,
            detail = "正在启动 TRIGGERcmd Bridge"
        )
        client.bindAndConnect(token, config.computerName)
    }

    override fun stopByUser() = stop("TRIGGERcmd Bridge 已停止")

    override fun stopRuntime() = stop("TRIGGERcmd Bridge runtime 已停止")

    override fun markStopped() {
        val config = storage.readConfig()
        val detail = when {
            !config.secureStorageAvailable -> "TRIGGERcmd 安全凭据存储不可用"
            config.configured -> "TRIGGERcmd Bridge 未启动"
            else -> "TRIGGERcmd 未配置 Agent Token"
        }
        stateFlow.value = baseState(AiLimbsBridgePhase.STOPPED, detail)
    }

    override fun reconnect() {
        client.disconnect()
        stateFlow.value = baseState(AiLimbsBridgePhase.RECONNECTING, "正在重新连接 TRIGGERcmd")
        start()
    }

    override fun recover() = reconnect()

    override fun rePair() = Unit

    override fun openAuthorizationPage(): Boolean = false

    override fun verifyLiveness() {
        if (!storage.readConfig().configured) {
            markStopped()
        } else if (!client.isRunning) {
            reconnect()
        }
    }

    override fun onHostSignal(signal: AiLimbsBridgeHostSignal) {
        if (signal is AiLimbsBridgeHostSignal.NetworkChanged &&
            signal.state == AiLimbsBridgeNetworkState.VALIDATED &&
            storage.readConfig().configured &&
            !client.isRunning
        ) {
            reconnect()
        }
    }

    override fun onStage(stage: String, detail: String) {
        val phase = when (stage) {
            "TOKEN", "COMPUTER", "COMMAND" -> AiLimbsBridgePhase.STARTING
            "SOCKET", "ROOM" -> AiLimbsBridgePhase.CONNECTING
            "READY" -> AiLimbsBridgePhase.ONLINE
            "STOPPED" -> AiLimbsBridgePhase.STOPPED
            "ERROR" -> AiLimbsBridgePhase.ERROR
            else -> state.value.phase
        }
        stateFlow.value = state.value.copy(
            providerId = PROFILE_ID,
            providerLabel = PROVIDER_LABEL,
            phase = phase,
            detail = detail,
            lastHeartbeatAtMs = if (phase == AiLimbsBridgePhase.ONLINE) {
                System.currentTimeMillis()
            } else {
                state.value.lastHeartbeatAtMs
            }
        )
    }

    override fun onComputerId(computerId: String) {
        AppLogger.i(TAG, "TRIGGERcmd Computer ready: ${shortId(computerId)}")
    }

    override fun onSocketState(state: String) {
        val phase = when (state) {
            "ONLINE" -> AiLimbsBridgePhase.ONLINE
            "CONNECTING", "CONNECTED", "SUBSCRIBING" -> AiLimbsBridgePhase.CONNECTING
            "RECONNECTED" -> AiLimbsBridgePhase.RECONNECTING
            "DISCONNECTED", "CONNECT_ERROR", "CONNECT_TIMEOUT" -> AiLimbsBridgePhase.RECONNECTING
            "SUBSCRIBE_ERROR" -> AiLimbsBridgePhase.ERROR
            "STOPPED" -> AiLimbsBridgePhase.STOPPED
            else -> this.state.value.phase
        }
        stateFlow.value = this.state.value.copy(
            providerId = PROFILE_ID,
            providerLabel = PROVIDER_LABEL,
            phase = phase
        )
    }

    override fun onCommand(params: String, respond: (String) -> Unit) {
        stateFlow.value = state.value.copy(lastHeartbeatAtMs = System.currentTimeMillis())
        AppLogger.i(TAG, "TRIGGERcmd bridge request received (${params.length} chars)")
        if (params.trim().equals("ping", ignoreCase = true)) {
            respond("Pong")
            return
        }
        scope.launch(Dispatchers.IO) {
            respond(structuredExecutor.execute(params))
        }
    }

    override fun onResult(result: String) {
        stateFlow.value = state.value.copy(lastHeartbeatAtMs = System.currentTimeMillis())
        AppLogger.i(TAG, "TRIGGERcmd bridge result delivered (${result.length} chars)")
    }

    override fun onLog(message: String) {
        AppLogger.d(TAG, message)
    }

    private fun stop(detail: String) {
        client.disconnect()
        stateFlow.value = baseState(AiLimbsBridgePhase.STOPPED, detail)
    }

    private fun initialState(): AiLimbsBridgeState {
        val config = storage.readConfig()
        return when {
            !config.secureStorageAvailable ->
                baseState(AiLimbsBridgePhase.ERROR, "TRIGGERcmd 安全凭据存储不可用")
            config.configured ->
                baseState(AiLimbsBridgePhase.STOPPED, "TRIGGERcmd Bridge 未启动")
            else ->
                baseState(AiLimbsBridgePhase.STOPPED, "TRIGGERcmd 未配置 Agent Token")
        }
    }

    private fun baseState(phase: AiLimbsBridgePhase, detail: String): AiLimbsBridgeState =
        AiLimbsBridgeState(
            providerId = PROFILE_ID,
            providerLabel = PROVIDER_LABEL,
            phase = phase,
            detail = detail
        )

    private fun shortId(value: String): String =
        if (value.length <= 12) value else "…${value.takeLast(12)}"

    internal class Factory : BridgeProviderFactory {
        override val type: String = PROFILE_TYPE
        override val profiles: List<BridgeProfile> = listOf(
            NativeBridgeProfile(
                id = PROFILE_ID,
                type = PROFILE_TYPE,
                label = PROVIDER_LABEL,
                enabled = true,
                isDefault = false
            )
        )
        override val supportedActions: Set<BridgeAction>
            get() = SUPPORTED_ACTIONS

        override fun create(
            context: Context,
            scope: CoroutineScope,
            profile: BridgeProfile
        ): AiLimbsBridgeProvider {
            require(profile is NativeBridgeProfile) {
                "TRIGGERcmd requires a NativeBridgeProfile"
            }
            require(profile.id == PROFILE_ID && profile.type == PROFILE_TYPE) {
                "Unsupported TRIGGERcmd profile: ${profile.id} (${profile.type})"
            }
            return TriggerCmdBridgeProvider(context, scope, profile)
        }
    }

    companion object {
        const val PROFILE_ID = "triggercmd"
        const val PROFILE_TYPE = "native_triggercmd"
        const val PROVIDER_LABEL = "TRIGGERcmd"
        private const val TAG = "TriggerCmdBridge"
        private val SUPPORTED_ACTIONS = setOf(
            BridgeAction.CONNECT,
            BridgeAction.STOP,
            BridgeAction.RECONNECT,
            BridgeAction.REFRESH
        )
    }
}
