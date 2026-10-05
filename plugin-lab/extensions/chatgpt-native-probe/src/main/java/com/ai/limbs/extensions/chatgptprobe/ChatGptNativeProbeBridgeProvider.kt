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
            engine.state.collect { probe ->
                mutableState.value = AiLimbsBridgeState(
                    providerId = PROFILE_ID,
                    providerLabel = PROVIDER_LABEL,
                    phase = when (probe.phase) {
                        "ONLINE" -> AiLimbsBridgePhase.ONLINE
                        "STARTING" -> AiLimbsBridgePhase.STARTING
                        "RETRYING" -> AiLimbsBridgePhase.RECONNECTING
                        "AUTH_REQUIRED", "ERROR" -> AiLimbsBridgePhase.ERROR
                        else -> if (probe.running) AiLimbsBridgePhase.CONNECTING else AiLimbsBridgePhase.STOPPED
                    },
                    detail = buildString {
                        append(probe.detail)
                        append(" | polls=")
                        append(probe.pollCount)
                        append(", commands=")
                        append(probe.commandCount)
                        append(", responses=")
                        append(probe.responseCount)
                        probe.lastMethod?.let {
                            append(", last=")
                            append(it)
                        }
                        probe.lastCommandError?.let { append(" | command: "); append(it) }
                        probe.lastDeliveryError?.let { append(" | delivery: "); append(it) }
                        probe.lastError?.let {
                            append(" | ")
                            append(it)
                        }
                    },
                    lastHeartbeatAtMs = listOfNotNull(probe.lastSuccessfulPollAtMs, probe.lastResponseAckAtMs).maxOrNull()
                )
            }
        }
    }

    override val id: String get() = profile.id
    override val enabled: Boolean get() = profile.enabled
    override val requiresScreenOffCpuKeepAlive: Boolean get() = true
    override val isRunning: Boolean get() = engine.state.value.running
    override val state: StateFlow<AiLimbsBridgeState> get() = mutableState
    override val statusSummary: String get() = "${state.value.phase}: ${state.value.detail}"
    override val supportedActions: Set<BridgeAction> get() = SUPPORTED_ACTIONS

    override fun start() {
        runCatching { engine.start() }
            .onFailure { error ->
                mutableState.value = stateFor(
                    AiLimbsBridgePhase.ERROR,
                    "连接 AI Limbs-ChatGPT 失败：${error.message ?: "unknown error"}"
                )
            }
    }

    override fun stopByUser() = markStopped()
    override fun stopRuntime() = markStopped()

    override fun markStopped() {
        engine.stop()
        mutableState.value = stateFor(
            AiLimbsBridgePhase.STOPPED,
            "AI Limbs-ChatGPT 已停止"
        )
    }

    override fun reconnect() {
        engine.stop()
        start()
    }

    override fun recover() = start()
    override fun rePair() = markStopped()
    override suspend fun openAuthorizationPage(): Boolean = false
    override fun verifyLiveness() {
        runCatching { engine.verifyLiveness() }.onFailure { error ->
            mutableState.value = stateFor(AiLimbsBridgePhase.ERROR, "检查连接失败：${error.javaClass.simpleName}")
        }
    }
    override fun onHostSignal(signal: AiLimbsBridgeHostSignal) = engine.onHostSignal(signal)

    private fun initialState(): AiLimbsBridgeState {
        val config = storage.readConfig()
        return when {
            !config.secureStorageAvailable ->
                stateFor(AiLimbsBridgePhase.ERROR, "Android 安全凭据存储不可用")
            config.configured ->
                stateFor(AiLimbsBridgePhase.STOPPED, "已配置；等待连接")
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
        detail = detail
    )

    internal class Factory(
        private val engine: ChatGptNativeProbeEngine
    ) : BridgeProviderFactory {
        override val type: String = PROFILE_TYPE
        override val transportId: String = "chatgpt-dynamic-capability-gateway"
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
                "AI Limbs-ChatGPT requires a NativeBridgeProfile"
            }
            require(profile.id == PROFILE_ID && profile.type == PROFILE_TYPE) {
                "Unsupported AI Limbs-ChatGPT profile: ${profile.id} (${profile.type})"
            }
            engine.bindRemoteIngress(remoteIngress)
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
        const val PROFILE_TYPE = "chatgpt_dynamic_capability_gateway"
        const val PROVIDER_LABEL = ChatGptNativeProbePanel.TITLE

        private val SUPPORTED_ACTIONS = setOf(
            BridgeAction.CONNECT,
            BridgeAction.STOP,
            BridgeAction.RECONNECT,
            BridgeAction.RECOVER,
            BridgeAction.REFRESH
        )
    }
}
