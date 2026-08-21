package com.ai.assistance.operit.integrations.ailimbs.providers.rdc

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeProvider
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeState
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsRdcClient
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProfile
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderFactory
import com.ai.assistance.operit.integrations.ailimbs.NativeBridgeProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

internal class RdcBridgeProvider private constructor(
    context: Context,
    scope: CoroutineScope,
    private val profile: NativeBridgeProfile
) : AiLimbsBridgeProvider {
    private val client = AiLimbsRdcClient(context, scope)

    override val id: String
        get() = profile.id
    override val enabled: Boolean
        get() = profile.enabled && AiLimbsRdcClient.ENABLED
    override val isRunning: Boolean
        get() = client.isRunning
    override val state: StateFlow<AiLimbsBridgeState>
        get() = client.state
    override val statusSummary: String
        get() = "${client.state.value.phase}: ${client.state.value.detail}"
    override val supportedActions: Set<BridgeAction>
        get() = SUPPORTED_ACTIONS

    override fun start() = client.start()
    override fun stopByUser() = client.stopByUser()
    override fun stopRuntime() = client.stopRuntime()
    override fun markStopped() = client.markStopped()
    override fun reconnect() = client.reconnect()
    override fun rePair() = client.rePair()
    override fun openAuthorizationPage(): Boolean = client.openAuthorizationPage()
    override fun verifyLiveness() = client.verifyLiveness()

    internal class Factory : BridgeProviderFactory {
        override val type: String = PROFILE_TYPE
        override val profiles: List<BridgeProfile> =
            listOf(
                NativeBridgeProfile(
                    id = PROFILE_ID,
                    type = PROFILE_TYPE,
                    label = AiLimbsRdcClient.PROVIDER_LABEL,
                    enabled = AiLimbsRdcClient.ENABLED
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
                "RDC requires a NativeBridgeProfile"
            }
            require(profile.id == PROFILE_ID && profile.type == PROFILE_TYPE) {
                "Unsupported RDC profile: ${profile.id} (${profile.type})"
            }
            return RdcBridgeProvider(context, scope, profile)
        }
    }

    companion object {
        const val PROFILE_ID = AiLimbsRdcClient.PROVIDER_ID
        const val PROFILE_TYPE = "native_rdc"

        private val SUPPORTED_ACTIONS =
            setOf(
                BridgeAction.CONNECT,
                BridgeAction.STOP,
                BridgeAction.RECONNECT,
                BridgeAction.REPAIR,
                BridgeAction.OPEN_AUTH,
                BridgeAction.REFRESH
            )
    }
}
