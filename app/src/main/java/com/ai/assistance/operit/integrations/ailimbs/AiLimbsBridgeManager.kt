package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import kotlinx.coroutines.CoroutineScope

internal interface AiLimbsBridgeProvider {
    val id: String
    val enabled: Boolean
    val isRunning: Boolean
    val statusSummary: String

    fun start()
    fun stopByUser()
    fun stopRuntime()
    fun showStopped()
    fun reconnect()
    fun rePair()
    fun openAuthorizationPage(): Boolean
    fun refreshConsole()
}

private class RdcBridgeProvider(
    context: Context,
    scope: CoroutineScope
) : AiLimbsBridgeProvider {
    private val client = AiLimbsRdcClient(context, scope)

    override val id: String = "rdc"
    override val enabled: Boolean
        get() = AiLimbsRdcClient.ENABLED
    override val isRunning: Boolean
        get() = client.isRunning
    override val statusSummary: String
        get() = "${client.state.value.phase}: ${client.state.value.detail}"

    override fun start() = client.start()
    override fun stopByUser() = client.stopByUser()
    override fun stopRuntime() = client.stopRuntime()
    override fun showStopped() = client.showStopped()
    override fun reconnect() = client.reconnect()
    override fun rePair() = client.rePair()
    override fun openAuthorizationPage(): Boolean = client.openAuthorizationPage()
    override fun refreshConsole() = client.refreshConsole()
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

    val shouldKeepAlive: Boolean
        get() = provider.enabled && desiredConnected()

    fun startIfDesired() {
        if (shouldKeepAlive) {
            provider.start()
        } else {
            provider.showStopped()
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

    fun refreshConsole() {
        if (shouldKeepAlive && !provider.isRunning) {
            provider.start()
        } else {
            provider.refreshConsole()
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
