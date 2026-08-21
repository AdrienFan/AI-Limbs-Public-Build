package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

internal interface AiLimbsBridgeProvider {
    val id: String
    val enabled: Boolean
    val isRunning: Boolean
    val state: StateFlow<AiLimbsBridgeState>
    val statusSummary: String
    val supportedActions: Set<BridgeAction>

    fun start()
    fun stopByUser()
    fun stopRuntime()
    fun markStopped()
    fun reconnect()
    fun rePair()
    fun openAuthorizationPage(): Boolean
    fun verifyLiveness()
}

internal interface BridgeProviderFactory {
    val type: String
    val profiles: List<BridgeProfile>
    val supportedActions: Set<BridgeAction>

    fun create(
        context: Context,
        scope: CoroutineScope,
        profile: BridgeProfile
    ): AiLimbsBridgeProvider
}
