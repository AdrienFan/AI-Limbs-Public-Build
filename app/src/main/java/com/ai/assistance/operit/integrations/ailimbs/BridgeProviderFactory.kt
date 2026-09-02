package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

enum class AiLimbsBridgeNetworkState {
    UNKNOWN,
    AVAILABLE_UNVALIDATED,
    VALIDATED,
    LOST
}

enum class AiLimbsBridgeNetworkTransport {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER
}

sealed interface AiLimbsBridgeHostSignal {
    data object ScreenOff : AiLimbsBridgeHostSignal
    data object ScreenOn : AiLimbsBridgeHostSignal
    data class DeviceIdleChanged(val isIdle: Boolean) : AiLimbsBridgeHostSignal
    data class NetworkChanged(
        val state: AiLimbsBridgeNetworkState,
        val transport: AiLimbsBridgeNetworkTransport
    ) : AiLimbsBridgeHostSignal
}

interface AiLimbsBridgeProvider {
    val id: String
    val enabled: Boolean
    val isRunning: Boolean
    val state: StateFlow<AiLimbsBridgeState>
    val statusSummary: String
    val supportedActions: Set<BridgeAction>
    val requiresScreenOffCpuKeepAlive: Boolean
        get() = false

    fun start()
    fun stopByUser()
    fun stopRuntime()
    fun markStopped()
    fun reconnect()
    fun recover()
    fun rePair()
    fun openAuthorizationPage(): Boolean
    fun verifyLiveness()
    fun onHostSignal(signal: AiLimbsBridgeHostSignal) = Unit
}

interface BridgeProviderFactory {
    val type: String
    val profiles: List<BridgeProfile>
    val supportedActions: Set<BridgeAction>

    fun create(
        context: Context,
        scope: CoroutineScope,
        profile: BridgeProfile
    ): AiLimbsBridgeProvider
}
