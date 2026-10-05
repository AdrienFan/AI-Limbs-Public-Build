package com.ai.limbs.extensions.chatgptprobe

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeHostSignal
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgePhase
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeProvider
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeState
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProfile
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderFactory
import com.ai.assistance.operit.integrations.ailimbs.BridgeRemoteIngress
import com.ai.assistance.operit.integrations.ailimbs.NativeBridgeProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class ChatGptNativeProbeBridgeProvider private constructor(
    context: Context,
    private val scope: CoroutineScope,
    private val profile: NativeBridgeProfile,
    private val engine: ChatGptNativeProbeEngine
) : AiLimbsBridgeProvider {
    private val storage = ChatGptNativeProbeStorage(context)
    private val mutableState = MutableStateFlow(initialState())

    init {
        scope.launch {
            engine.lastResult.collect { result ->
                if (result != null) {
                    val phase = when {
                        result.phase == "MCP_LISTENER_STOPPED" -> AiLimbsBridgePhase.STOPPED
                        !result.success -> AiLimbsBridgePhase.ERROR
                        result.running && result.phase == "MCP_LISTENER_ONLINE" -> AiLimbsBridgePhase.ONLINE
                        result.running -> AiLimbsBridgePhase.CONNECTING
                        else -> AiLimbsBridgePhase.STOPPED
                    }
                    mutableState.value = stateFor(
                        phase,
                        "${result.phase}: ${result.detail} | polled=${result.polledCommandCount} handled=${result.handledCommandCount} posted=${result.postedResponseCount} last=${result.lastMethod ?: "-"}"
                    )
                }
            }
        }
    }

    override val id: String get() = profile.id
    override val enabled: Boolean get() = profile.enabled
    override val isRunning: Boolean get() = engine.isRunning()
    override val state: StateFlow<AiLimbsBridgeState> get() = mutableState
    override val statusSummary: String get() = "${state.value.phase}: ${state.value.detail}"
    override val supportedActions: Set<BridgeAction> get() = SUPPORTED_ACTIONS

    override fun start() {
        val config = storage.readConfig()
        if (!config.secureStorageAvailable) {
            mutableState.value = stateFor(AiLimbsBridgePhase.ERROR, "Android 安全凭据存储不可用")
            return
        }
        if (!config.configured) {
            mutableState.value = stateFor(AiLimbsBridgePhase.PAIRING, "请先配置 Tunnel ID 与 Runtime API Key")
            return
        }
        if (engine.startLoop()) {
            mutableState.value = stateFor(
                AiLimbsBridgePhase.CONNECTING,
                "正在启动 Android/Kotlin MCP long-poll listener"
            )
        }
    }

    override fun stopByUser() = markStopped()
    override fun stopRuntime() = markStopped()

    override fun markStopped() {
        engine.stopLoop()
        mutableState.value = stateFor(
            AiLimbsBridgePhase.STOPPED,
            "MCP Echo Probe listener 已停止"
        )
    }

    override fun reconnect() {
        engine.stopLoop()
        start()
    }

    override fun recover() = reconnect()

    override fun rePair() {
        engine.stopLoop()
        mutableState.value = stateFor(
            AiLimbsBridgePhase.PAIRING,
            "请清除并重新保存 Tunnel 配置"
        )
    }

    override suspend fun openAuthorizationPage(): Boolean = false
    override fun verifyLiveness() = start()
    override fun onHostSignal(signal: AiLimbsBridgeHostSignal) = Unit

    private fun initialState(): AiLimbsBridgeState {
        val config = storage.readConfig()
        return when {
            !config.secureStorageAvailable ->
                stateFor(AiLimbsBridgePhase.ERROR, "Android 安全凭据存储不可用")
            config.configured ->
                stateFor(AiLimbsBridgePhase.STOPPED, "已配置；MCP Echo listener 尚未启动")
            else ->
                stateFor(AiLimbsBridgePhase.PAIRING, "尚未配置 Tunnel ID / Runtime API Key")
        }
    }

    private fun stateFor(
        phase: AiLimbsBridgePhase,
        detail: String
    ): AiLimbsBridgeState = AiLimbsBridgeState(
        providerId = PROFILE_ID,
        providerLabel = PROVIDER_LABEL,
        phase = phase,
        detail = detail,
        lastHeartbeatAtMs = if (phase == AiLimbsBridgePhase.ONLINE) {
            System.currentTimeMillis()
        } else {
            null
        }
    )

    internal class Factory(
        private val engine: ChatGptNativeProbeEngine
    ) : BridgeProviderFactory {
        override val type: String = PROFILE_TYPE
        override val transportId: String = "chatgpt-mcp-echo-probe"
        override val profiles: List<BridgeProfile> = listOf(
            NativeBridgeProfile(
                id = PROFILE_ID,
                type = PROFILE_TYPE,
                label = PROVIDER_LABEL,
                enabled = true,
                isDefault = false
            )
        )
        override val supportedActions: Set<BridgeAction> get() = SUPPORTED_ACTIONS

        override fun create(
            context: Context,
            scope: CoroutineScope,
            profile: BridgeProfile,
            remoteIngress: BridgeRemoteIngress
        ): AiLimbsBridgeProvider {
            require(profile is NativeBridgeProfile) {
                "ChatGPT MCP Echo Probe requires a NativeBridgeProfile"
            }
            require(profile.id == PROFILE_ID && profile.type == PROFILE_TYPE) {
                "Unsupported ChatGPT MCP Echo Probe profile: ${profile.id} (${profile.type})"
            }
            return ChatGptNativeProbeBridgeProvider(
                context = context,
                scope = scope,
                profile = profile,
                engine = engine
            )
        }
    }

    companion object {
        const val PROFILE_ID = "chatgpt_native_probe"
        const val PROFILE_TYPE = "chatgpt_mcp_echo_probe"
        const val PROVIDER_LABEL = "ChatGPT MCP Echo Probe"

        private val SUPPORTED_ACTIONS = setOf(
            BridgeAction.CONNECT,
            BridgeAction.STOP,
            BridgeAction.RECONNECT,
            BridgeAction.RECOVER,
            BridgeAction.REFRESH
        )
    }
}
