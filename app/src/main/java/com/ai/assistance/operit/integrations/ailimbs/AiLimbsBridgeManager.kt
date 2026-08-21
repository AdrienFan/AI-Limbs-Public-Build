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

    fun start()
    fun stopByUser()
    fun stopRuntime()
    fun markStopped()
    fun reconnect()
    fun rePair()
    fun openAuthorizationPage(): Boolean
    fun verifyLiveness()
}

private class RdcBridgeProvider(
    context: Context,
    scope: CoroutineScope
) : AiLimbsBridgeProvider {
    private val client = AiLimbsRdcClient(context, scope)

    override val id: String = AiLimbsRdcClient.PROVIDER_ID
    override val enabled: Boolean
        get() = AiLimbsRdcClient.ENABLED
    override val isRunning: Boolean
        get() = client.isRunning
    override val state: StateFlow<AiLimbsBridgeState>
        get() = client.state
    override val statusSummary: String
        get() = "${client.state.value.phase}: ${client.state.value.detail}"

    override fun start() = client.start()
    override fun stopByUser() = client.stopByUser()
    override fun stopRuntime() = client.stopRuntime()
    override fun markStopped() = client.markStopped()
    override fun reconnect() = client.reconnect()
    override fun rePair() = client.rePair()
    override fun openAuthorizationPage(): Boolean = client.openAuthorizationPage()
    override fun verifyLiveness() = client.verifyLiveness()
}

/**
 * Stable bridge boundary for AI Limbs.
 *
 * AIForegroundService talks only to this manager. RDC is the first provider,
 * not a permanent dependency of the service layer. Future bridge providers can
 * be swapped in without changing Android / Ubuntu / Operit tool dispatch.
 */
class AiLimbsBridgeManager(
    context: Context,
    scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val provider: AiLimbsBridgeProvider = RdcBridgeProvider(appContext, scope)
    private val preferences =
        appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    val state: StateFlow<AiLimbsBridgeState>
        get() = provider.state

    val shouldKeepAlive: Boolean
        get() = provider.enabled && desiredConnected()

    fun startIfDesired() {
        if (shouldKeepAlive) {
            provider.start()
        } else {
            provider.markStopped()
        }
    }

    fun connect() {
        setDesiredConnected(true)
        provider.start()
    }

    fun stopByUser() {
        setDesiredConnected(false)
        provider.stopByUser()
    }

    fun stopRuntime() {
        provider.stopRuntime()
    }

    fun reconnect() {
        setDesiredConnected(true)
        provider.reconnect()
    }

    fun rePair() {
        setDesiredConnected(true)
        provider.rePair()
    }

    fun openAuthorizationPage(): Boolean = provider.openAuthorizationPage()

    fun verifyLiveness() {
        if (shouldKeepAlive && !provider.isRunning) {
            provider.start()
        } else {
            provider.verifyLiveness()
        }
    }

    fun statusSummary(): String = provider.statusSummary

    private fun desiredConnected(): Boolean =
        preferences.getBoolean(KEY_DESIRED_CONNECTED, true)

    private fun setDesiredConnected(value: Boolean) {
        preferences.edit().putBoolean(KEY_DESIRED_CONNECTED, value).apply()
    }

    companion object {
        const val ENABLED = true
        private const val PREF_FILE = "ai_limbs_bridge_manager"
        private const val KEY_DESIRED_CONNECTED = "desired_connected"

        fun shouldKeepAlive(context: Context): Boolean {
            if (!ENABLED) return false
            return context.applicationContext
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_DESIRED_CONNECTED, true)
        }
    }
}
