package com.ai.limbs.extensions.sentinelx.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeHostSignal
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeNetworkState
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgePhase
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeProvider
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeState
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProfile
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderFactory
import com.ai.assistance.operit.integrations.ailimbs.BridgeRemoteIngress
import com.ai.assistance.operit.integrations.ailimbs.NativeBridgeProfile
import com.ai.limbs.extensions.sentinelx.SentinelXLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class SentinelXBridgeProvider private constructor(
    context: Context,
    private val scope: CoroutineScope,
    private val profile: NativeBridgeProfile,
    remoteIngress: BridgeRemoteIngress
) : AiLimbsBridgeProvider, SentinelXTransportListener {
    private val appContext = context.applicationContext
    private val storage = SentinelXBridgeStorage(appContext)
    private val executor = SentinelXRemoteInvocationExecutor(remoteIngress)
    private val stateFlow = MutableStateFlow(initialState())
    private val client = SentinelXTransportClient(scope, storage, executor, this)

    @Volatile private var stoppedByUser = false
    @Volatile private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    override val id: String get() = profile.id
    override val enabled: Boolean get() = profile.enabled
    override val isRunning: Boolean get() = client.isRunning
    override val state: StateFlow<AiLimbsBridgeState> = stateFlow.asStateFlow()
    override val statusSummary: String get() = "${state.value.phase}: ${state.value.detail}"
    override val supportedActions: Set<BridgeAction> get() = SUPPORTED_ACTIONS
    override val requiresScreenOffCpuKeepAlive: Boolean get() = true

    override fun start() {
        stoppedByUser = false
        reconnectJob?.cancel()
        val config = storage.readConfig()
        if (!config.secureStorageAvailable) {
            update(AiLimbsBridgePhase.ERROR, "SentinelX 安全凭据存储不可用")
            return
        }
        if (!config.configured) {
            update(AiLimbsBridgePhase.PAIRING, "SentinelX 尚未授权；请打开授权页并保存 Enrollment Token")
            return
        }
        if (client.isRunning && state.value.phase == AiLimbsBridgePhase.ONLINE) return
        update(AiLimbsBridgePhase.CONNECTING, "正在连接 SentinelX Hub")
        client.connect()
    }
    override fun stopByUser() {
        stoppedByUser = true
        reconnectJob?.cancel()
        reconnectJob = null
        client.disconnect("user_stop")
        update(AiLimbsBridgePhase.STOPPED, "SentinelX Bridge 已停止")
    }

    override fun stopRuntime() {
        stoppedByUser = true
        reconnectJob?.cancel()
        reconnectJob = null
        client.disconnect("runtime_stop")
        update(AiLimbsBridgePhase.STOPPED, "SentinelX Bridge runtime 已停止")
    }

    override fun markStopped() {
        val config = storage.readConfig()
        val detail = when {
            !config.secureStorageAvailable -> "SentinelX 安全凭据存储不可用"
            config.configured -> "SentinelX Bridge 未启动"
            else -> "SentinelX 尚未授权"
        }
        update(AiLimbsBridgePhase.STOPPED, detail)
    }

    override fun reconnect() {
        stoppedByUser = false
        reconnectJob?.cancel()
        reconnectJob = null
        client.disconnect("manual_reconnect")
        update(AiLimbsBridgePhase.RECONNECTING, "正在重新连接 SentinelX")
        start()
    }

    override fun recover() = reconnect()
    override fun rePair() {
        stoppedByUser = true
        reconnectJob?.cancel()
        client.disconnect("re_pair")
        val hostId = storage.regenerateHostId()
        update(
            AiLimbsBridgePhase.PAIRING,
            "已生成新 Host ID：$hostId；请重新完成 SentinelX 授权"
        )
    }

    override fun openAuthorizationPage(): Boolean {
        val target = storage.enrollmentUrl()
        return runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrElse { error ->
            SentinelXLogger.e(TAG, "Unable to open SentinelX enrollment page", error)
            false
        }
    }

    override fun verifyLiveness() {
        val config = storage.readConfig()
        when {
            !config.configured -> update(AiLimbsBridgePhase.PAIRING, "SentinelX 尚未授权")
            !client.isRunning && !stoppedByUser -> scheduleReconnect("Liveness 检测发现连接已断开")
        }
    }

    override fun onHostSignal(signal: AiLimbsBridgeHostSignal) {
        if (signal is AiLimbsBridgeHostSignal.NetworkChanged &&
            signal.state == AiLimbsBridgeNetworkState.VALIDATED &&
            storage.readConfig().configured &&
            !client.isRunning &&
            !stoppedByUser
        ) {
            scheduleReconnect("网络已恢复")
        }
    }
    override fun onConnecting() {
        update(AiLimbsBridgePhase.CONNECTING, "正在连接 SentinelX Hub")
    }

    override fun onOnline(sessionId: String) {
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        stateFlow.value = baseState(
            AiLimbsBridgePhase.ONLINE,
            "SentinelX 已在线 · session ${shortId(sessionId)}"
        ).copy(lastHeartbeatAtMs = System.currentTimeMillis())
    }

    override fun onHeartbeat() {
        stateFlow.value = state.value.copy(lastHeartbeatAtMs = System.currentTimeMillis())
    }

    override fun onDisconnected(detail: String) {
        if (stoppedByUser) {
            update(AiLimbsBridgePhase.STOPPED, detail)
        } else {
            scheduleReconnect(detail)
        }
    }

    override fun onError(detail: String, error: Throwable?) {
        error?.let { SentinelXLogger.w(TAG, detail, it) } ?: SentinelXLogger.w(TAG, detail)
        if (stoppedByUser) {
            update(AiLimbsBridgePhase.ERROR, detail)
        } else {
            scheduleReconnect(detail)
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (stoppedByUser || reconnectJob?.isActive == true) return
        reconnectAttempt += 1
        val delayMs = reconnectDelay(reconnectAttempt)
        stateFlow.value = baseState(
            AiLimbsBridgePhase.RECONNECTING,
            "$reason；${delayMs / 1000}s 后重试"
        ).copy(reconnectAttempt = reconnectAttempt)
        reconnectJob = scope.launch {
            delay(delayMs)
            reconnectJob = null
            if (!stoppedByUser && storage.readConfig().configured) {
                start()
            }
        }
    }

    private fun reconnectDelay(attempt: Int): Long = when {
        attempt <= 1 -> 2_000L
        attempt == 2 -> 5_000L
        attempt == 3 -> 10_000L
        attempt == 4 -> 20_000L
        else -> 30_000L
    }

    private fun update(phase: AiLimbsBridgePhase, detail: String) {
        stateFlow.value = baseState(phase, detail).copy(
            lastHeartbeatAtMs = state.value.lastHeartbeatAtMs,
            reconnectAttempt = reconnectAttempt
        )
    }

    private fun initialState(): AiLimbsBridgeState {
        val config = storage.readConfig()
        return when {
            !config.secureStorageAvailable -> baseState(AiLimbsBridgePhase.ERROR, "SentinelX 安全凭据存储不可用")
            config.configured -> baseState(AiLimbsBridgePhase.STOPPED, "SentinelX Bridge 未启动")
            else -> baseState(AiLimbsBridgePhase.PAIRING, "SentinelX 尚未授权")
        }
    }

    private fun baseState(phase: AiLimbsBridgePhase, detail: String): AiLimbsBridgeState =
        AiLimbsBridgeState(
            providerId = PROFILE_ID,
            providerLabel = PROVIDER_LABEL,
            phase = phase,
            detail = detail,
            deviceId = storage.readConfig().hostId
        )

    private fun shortId(value: String): String =
        if (value.length <= 12) value else "…${value.takeLast(12)}"

    internal class Factory : BridgeProviderFactory {
        override val type: String = PROFILE_TYPE
        override val transportId: String = PROFILE_ID
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
            require(profile is NativeBridgeProfile) { "SentinelX requires a NativeBridgeProfile" }
            require(profile.id == PROFILE_ID && profile.type == PROFILE_TYPE) {
                "Unsupported SentinelX profile: ${profile.id} (${profile.type})"
            }
            return SentinelXBridgeProvider(context, scope, profile, remoteIngress)
        }
    }

    companion object {
        const val PROFILE_ID = "sentinelx"
        const val PROFILE_TYPE = "native_sentinelx"
        const val PROVIDER_LABEL = "SentinelX"
        private const val TAG = "SentinelXBridge"
        private val SUPPORTED_ACTIONS = setOf(
            BridgeAction.CONNECT,
            BridgeAction.STOP,
            BridgeAction.RECONNECT,
            BridgeAction.RECOVER,
            BridgeAction.REPAIR,
            BridgeAction.OPEN_AUTH,
            BridgeAction.REFRESH
        )
    }
}
