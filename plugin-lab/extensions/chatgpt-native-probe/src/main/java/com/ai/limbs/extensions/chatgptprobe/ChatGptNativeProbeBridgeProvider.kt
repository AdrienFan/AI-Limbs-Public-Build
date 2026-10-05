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
    private val scope: CoroutineScope,
    private val profile: NativeBridgeProfile,
    private val engine: ChatGptNativeProbeEngine
) : AiLimbsBridgeProvider {
    private val running = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(
        AiLimbsBridgeState(
            providerId = PROFILE_ID,
            providerLabel = PROVIDER_LABEL,
            phase = AiLimbsBridgePhase.STOPPED,
            detail = "尚未运行 Android Host native probe"
        )
    )

    init {
        scope.launch {
            engine.lastResult.collect { result ->
                if (result != null) {
                    mutableState.value = AiLimbsBridgeState(
                        providerId = PROFILE_ID,
                        providerLabel = PROVIDER_LABEL,
                        phase = if (result.success) {
                            AiLimbsBridgePhase.ONLINE
                        } else {
                            AiLimbsBridgePhase.ERROR
                        },
                        detail = buildString {
                            append(result.phase)
                            append(": ")
                            append(result.detail)
                            if (result.output.isNotBlank()) {
                                append(" | ")
                                append(result.output.take(240))
                            }
                        },
                        lastHeartbeatAtMs = System.currentTimeMillis()
                    )
                    running.set(false)
                }
            }
        }
    }

    override val id: String
        get() = profile.id
    override val enabled: Boolean
        get() = profile.enabled
    override val isRunning: Boolean
        get() = running.get()
    override val state: StateFlow<AiLimbsBridgeState>
        get() = mutableState
    override val statusSummary: String
        get() = "${mutableState.value.phase}: ${mutableState.value.detail}"
    override val supportedActions: Set<BridgeAction>
        get() = SUPPORTED_ACTIONS

    override fun start() = launchProbe("CONNECT")
    override fun stopByUser() = markStopped()
    override fun stopRuntime() = markStopped()

    override fun markStopped() {
        running.set(false)
        mutableState.value = AiLimbsBridgeState(
            providerId = PROFILE_ID,
            providerLabel = PROVIDER_LABEL,
            phase = AiLimbsBridgePhase.STOPPED,
            detail = "Probe stopped"
        )
    }

    override fun reconnect() = launchProbe("RECONNECT")
    override fun recover() = launchProbe("RECOVER")
    override fun rePair() = launchProbe("REPAIR")
    override suspend fun openAuthorizationPage(): Boolean = false
    override fun verifyLiveness() = launchProbe("REFRESH")
    override fun onHostSignal(signal: AiLimbsBridgeHostSignal) = Unit

    private fun launchProbe(source: String) {
        if (!running.compareAndSet(false, true)) return
        mutableState.value = AiLimbsBridgeState(
            providerId = PROFILE_ID,
            providerLabel = PROVIDER_LABEL,
            phase = AiLimbsBridgePhase.STARTING,
            detail = "$source: executing bundled tunnel-client --version directly in Android Host"
        )
        scope.launch {
            engine.runProbe()
        }
    }

    internal class Factory(
        private val engine: ChatGptNativeProbeEngine
    ) : BridgeProviderFactory {
        override val type: String = PROFILE_TYPE
        override val transportId: String = "chatgpt-native-probe"
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
            profile: BridgeProfile,
            remoteIngress: BridgeRemoteIngress
        ): AiLimbsBridgeProvider {
            require(profile is NativeBridgeProfile) {
                "ChatGPT Native Probe requires a NativeBridgeProfile"
            }
            require(profile.id == PROFILE_ID && profile.type == PROFILE_TYPE) {
                "Unsupported ChatGPT Native Probe profile: ${profile.id} (${profile.type})"
            }
            return ChatGptNativeProbeBridgeProvider(scope, profile, engine)
        }
    }

    companion object {
        const val PROFILE_ID = "chatgpt_native_probe"
        const val PROFILE_TYPE = "chatgpt_native_probe"
        const val PROVIDER_LABEL = "ChatGPT Native Probe"

        private val SUPPORTED_ACTIONS = setOf(
            BridgeAction.CONNECT,
            BridgeAction.STOP,
            BridgeAction.RECONNECT,
            BridgeAction.RECOVER,
            BridgeAction.REPAIR,
            BridgeAction.REFRESH
        )
    }
}
