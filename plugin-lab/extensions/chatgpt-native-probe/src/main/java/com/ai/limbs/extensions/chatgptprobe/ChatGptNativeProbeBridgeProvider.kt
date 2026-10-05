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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val running = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(initialState())

    init {
        scope.launch {
            engine.lastResult.collect { result ->
                if (result != null) {
                    mutableState.value = AiLimbsBridgeState(
                        providerId = PROFILE_ID,
                        providerLabel = PROVIDER_LABEL,
                        phase = if (result.success) AiLimbsBridgePhase.ONLINE else AiLimbsBridgePhase.ERROR,
                        detail = "${result.phase}: ${result.detail}",
                        lastHeartbeatAtMs = System.currentTimeMillis()
                    )
                    running.set(false)
                }
            }
        }
    }

    override val id: String get() = profile.id
    override val enabled: Boolean get() = profile.enabled
    override val isRunning: Boolean get() = running.get()
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
        launchProbe("CONNECT")
    }

    override fun stopByUser() = markStopped()
    override fun stopRuntime() = markStopped()

    override fun markStopped() {
        running.set(false)
        mutableState.value = stateFor(AiLimbsBridgePhase.STOPPED, "Control Plane Probe 已停止")
    }

    override fun reconnect() = start()
    override fun recover() = start()
    override fun rePair() = markStopped()
    override suspend fun openAuthorizationPage(): Boolean = false
    override fun verifyLiveness() = start()
    override fun onHostSignal(signal: AiLimbsBridgeHostSignal) = Unit

    private fun launchProbe(source: String) {
        if (!running.compareAndSet(false, true)) return
        mutableState.value = stateFor(
            AiLimbsBridgePhase.CONNECTING,
            "$source: 正在通过 Android OkHttp 直连 OpenAI Tunnel Control Plane"
        )
        scope.launch {
            engine.runProbe()
        }
    }

    private fun initialState(): AiLimbsBridgeState {
        val config = storage.readConfig()
        return when {
            !config.secureStorageAvailable ->
                stateFor(AiLimbsBridgePhase.ERROR, "Android 安全凭据存储不可用")
            config.configured ->
                stateFor(AiLimbsBridgePhase.STOPPED, "已配置；尚未执行 Control Plane Probe")
            else ->
                stateFor(AiLimbsBridgePhase.PAIRING, "尚未配置 Tunnel ID / Runtime API Key")
        }
    }

    private fun stateFor(phase: AiLimbsBridgePhase, detail: String): AiLimbsBridgeState =
        AiLimbsBridgeState(
            providerId = PROFILE_ID,
            providerLabel = PROVIDER_LABEL,
            phase = phase,
            detail = detail
        )

    internal class Factory(
        private val engine: ChatGptNativeProbeEngine
    ) : BridgeProviderFactory {
        override val type: String = PROFILE_TYPE
        override val transportId: String = "chatgpt-control-plane-probe"
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
                "ChatGPT Control Plane Probe requires a NativeBridgeProfile"
            }
            require(profile.id == PROFILE_ID && profile.type == PROFILE_TYPE) {
                "Unsupported ChatGPT Control Plane Probe profile: ${profile.id} (${profile.type})"
            }
            return ChatGptNativeProbeBridgeProvider(context, scope, profile, engine)
        }
    }

    companion object {
        const val PROFILE_ID = "chatgpt_native_probe"
        const val PROFILE_TYPE = "chatgpt_control_plane_probe"
        const val PROVIDER_LABEL = "ChatGPT Control Plane Probe"

        private val SUPPORTED_ACTIONS = setOf(
            BridgeAction.CONNECT,
            BridgeAction.STOP,
            BridgeAction.RECONNECT,
            BridgeAction.RECOVER,
            BridgeAction.REFRESH
        )
    }
}
