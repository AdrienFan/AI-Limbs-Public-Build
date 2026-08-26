package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.integrations.ailimbs.chat.LanerChatBridgeService
import com.ai.assistance.operit.integrations.ailimbs.chat.LanerChatQueueChangedEvent
import com.ai.assistance.operit.util.AppLogger
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.random.Random
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Remote Desktop Commander device transport for AI Limbs.
 * Ubuntu/PRoot is not part of this transport; it is reached only through Operit Terminal tools.
 */
class AiLimbsRdcClient(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val accessGate = AiLimbsAccessGate(appContext)
    private val dispatcher = AiLimbsOperitDispatcher(appContext, accessGate)
    private val accessContext = AiLimbsAccessContextService(appContext)
    private val lanerChat = LanerChatBridgeService.getInstance(appContext)
    private val adapter = AiLimbsRdcToolAdapter(appContext, dispatcher)
    private val httpClient =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
    private val preferences: SharedPreferences by lazy { createPreferences() }
    private val stateFlow = MutableStateFlow(
        AiLimbsBridgeState(providerId = PROVIDER_ID, providerLabel = PROVIDER_LABEL)
    )
    val state = stateFlow.asStateFlow()
    private var runJob: Job? = null
    private var lastSentAccessContext: String? = null
    private var activeAuthorization: DeviceAuth? = null
    private var reconnectAttempt: Int = 0
    private val activeCallJobs = ConcurrentHashMap<String, Job>()
    private val remoteCallSemaphore = Semaphore(MAX_CONCURRENT_REMOTE_CALLS)
    private val housekeepingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val realtimeTransportLock = Any()

    @Volatile
    private var realtimeTransport: AiLimbsRdcRealtimeTransport? = null
    private val recoveryInProgress = AtomicBoolean(false)

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start() {
        if (!ENABLED) return
        if (recoveryInProgress.get()) {
            AppLogger.d(TAG, "RDC start ignored: recovery transaction is active")
            return
        }
        if (runJob?.isActive == true) {
            AppLogger.d(TAG, "RDC start ignored: worker already active")
            return
        }
        updateState(AiLimbsBridgePhase.STARTING, "正在启动 Android 端 RDC 设备")
        AppLogger.i(TAG, "RDC worker start requested")
        launchWorker()
    }

    private fun launchWorker(recoveryDeadlineAtMs: Long? = null) {
        val worker =
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                val currentJob = currentCoroutineContext()[Job]
                isRunning = true
                AppLogger.i(
                    TAG,
                    if (recoveryDeadlineAtMs == null) "RDC worker started" else "RDC recovery worker started"
                )
                val queuePushJob = launch(Dispatchers.IO) {
                    lanerChat.queueEvents.collect { event ->
                        notifyLanerChatQueueDoorbell(event)
                    }
                }
                try {
                    runForever(recoveryDeadlineAtMs)
                } finally {
                    queuePushJob.cancel()
                    if (runJob === currentJob) {
                        runJob = null
                        isRunning = false
                    }
                    if (recoveryDeadlineAtMs != null) {
                        recoveryInProgress.set(false)
                        if (stateFlow.value.phase == AiLimbsBridgePhase.RECOVERING) {
                            updateState(
                                AiLimbsBridgePhase.RECOVERY_FAILED,
                                "RDC 修复被中断，请重试"
                            )
                        }
                    }
                    AppLogger.i(TAG, "RDC worker stopped")
                }
            }
        runJob = worker
        worker.start()
    }

    fun stopByUser() {
        AppLogger.i(TAG, "RDC connection stop requested by user")
        housekeepingScope.launch {
            reportOfflineBestEffort()
        }
        stopWorker("连接已由用户停止")
    }

    fun stopRuntime() {
        AppLogger.i(TAG, "RDC runtime stop requested")
        stopWorker("Android 端 RDC 运行时已停止")
    }

    fun markStopped() {
        stopWorker("连接已停止，等待用户启动")
    }

    fun verifyLiveness() {
        val current = stateFlow.value
        val heartbeatAge = current.lastHeartbeatAtMs?.let { System.currentTimeMillis() - it }
        if (
            current.phase == AiLimbsBridgePhase.ONLINE &&
            (heartbeatAge == null || heartbeatAge > ONLINE_STALE_AFTER_MS)
        ) {
            AppLogger.w(TAG, "RDC heartbeat is stale; restarting worker to verify the live session")
            runJob?.cancel()
            runJob = null
            closeRealtimeTransport()
            cancelActiveCalls("stale heartbeat")
            reconnectAttempt = 0
            updateState(
                AiLimbsBridgePhase.RECONNECTING,
                "最后心跳已过期，正在重新建立连接",
                lastHeartbeatAtMs = current.lastHeartbeatAtMs
            )
            start()
        }
    }

    private fun stopWorker(detail: String) {
        recoveryInProgress.set(false)
        runJob?.cancel()
        runJob = null
        closeRealtimeTransport()
        cancelActiveCalls("RDC stopped")
        activeAuthorization = null
        isRunning = false
        stateFlow.value =
            AiLimbsBridgeState(
                providerId = PROVIDER_ID,
                providerLabel = PROVIDER_LABEL,
                phase = AiLimbsBridgePhase.STOPPED,
                detail = detail
            )
    }

    fun reconnect() {
        AppLogger.i(TAG, "RDC manual reconnect requested")
        runJob?.cancel()
        runJob = null
        closeRealtimeTransport()
        cancelActiveCalls("manual reconnect")
        reconnectAttempt = 0
        updateState(AiLimbsBridgePhase.RECONNECTING, "用户请求重新连接")
        start()
    }

    fun recover() {
        if (!ENABLED) return
        if (!recoveryInProgress.compareAndSet(false, true)) {
            AppLogger.d(TAG, "RDC recovery ignored: recovery transaction is already active")
            return
        }

        // Recovery is intentionally a single lifecycle transaction. Without this guard, a second
        // connect/re-pair can race the old channel teardown and recreate the fake-online state.
        AppLogger.w(TAG, "RDC manual recovery requested")
        runJob?.cancel()
        runJob = null
        closeRealtimeTransport(force = true)
        cancelActiveCalls("manual recovery")
        activeAuthorization = null
        reconnectAttempt = 0
        updateState(
            AiLimbsBridgePhase.RECOVERING,
            "正在关闭旧通道并重建 RDC Realtime 连接"
        )
        launchWorker(System.currentTimeMillis() + RECOVERY_TIMEOUT_MS)
    }

    fun rePair() {
        AppLogger.i(TAG, "RDC manual re-pair requested")
        runJob?.cancel()
        runJob = null
        closeRealtimeTransport()
        cancelActiveCalls("manual re-pair")
        clearSession()
        activeAuthorization = null
        lastSentAccessContext = null
        reconnectAttempt = 0
        updateState(
            AiLimbsBridgePhase.STARTING,
            "正在申请新的 RDC 授权码",
            deviceId = null,
            lastHeartbeatAtMs = null
        )
        start()
    }

    fun openAuthorizationPage(): Boolean {
        val auth = activeAuthorization ?: return false
        val targetUrl = auth.verificationUriComplete.ifBlank { auth.verificationUri }
        if (targetUrl.isBlank()) return false
        return runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            AppLogger.i(TAG, "RDC authorization page opened from connection console")
            true
        }.getOrElse { error ->
            AppLogger.e(TAG, "Unable to open RDC authorization page", error)
            false
        }
    }

    private suspend fun runForever(recoveryDeadlineAtMs: Long? = null) {
        var recoveryMode = recoveryDeadlineAtMs != null
        val recoveryDeadline = recoveryDeadlineAtMs ?: Long.MAX_VALUE

        while (currentCoroutineContext().isActive) {
            if (recoveryMode && System.currentTimeMillis() >= recoveryDeadline) {
                finishRecoveryFailure("RDC 修复超时，请重试或重新配对")
                return
            }

            var attemptTransport: AiLimbsRdcRealtimeTransport? = null
            var attemptPendingProbeJob: Job? = null
            try {
                if (recoveryMode) {
                    updateState(
                        AiLimbsBridgePhase.RECOVERING,
                        if (reconnectAttempt == 0) {
                            "正在验证已有授权并重建 RDC Realtime 通道"
                        } else {
                            "RDC 修复第 ${reconnectAttempt} 次重试"
                        }
                    )
                } else {
                    updateState(
                        if (reconnectAttempt == 0) AiLimbsBridgePhase.CONNECTING else AiLimbsBridgePhase.RECONNECTING,
                        if (reconnectAttempt == 0) "正在连接 Remote Desktop Commander" else "正在执行第 ${reconnectAttempt} 次重连"
                    )
                }

                val info = fetchMcpInfo()
                val session = ensureSession(info, allowPairing = !recoveryMode)

                // A device is not healthy merely because its REST session can be used. Validate
                // authorization with the owner lookup, then advertise online/broadcast only after
                // private-channel join, Presence, and a Phoenix heartbeat round-trip all succeed.
                val userId = fetchRdcUserId(info, session)
                val transport =
                    AiLimbsRdcRealtimeTransport(
                        httpClient = httpClient,
                        supabaseUrl = info.supabaseUrl,
                        anonKey = info.anonKey,
                        accessToken = session.accessToken,
                        userId = userId,
                        deviceId = session.deviceId,
                        deviceName = deviceName(),
                        appVersion = BuildConfig.VERSION_NAME,
                        onNewCall = { callId, ingressSource ->
                            scope.launch(Dispatchers.IO) {
                                try {
                                    handleDoorbellCall(info, session, callId, ingressSource)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: UnauthorizedException) {
                                    AppLogger.w(
                                        TAG,
                                        "RDC doorbell call lost authorization: call=${shortCallId(callId)}"
                                    )
                                } catch (e: Exception) {
                                    AppLogger.e(
                                        TAG,
                                        "RDC doorbell call failed: source=$ingressSource call=${shortCallId(callId)}",
                                        e
                                    )
                                }
                            }
                        }
                    )
                attemptTransport = transport
                synchronized(realtimeTransportLock) {
                    realtimeTransport = transport
                }
                transport.connectAndAwaitReady(REALTIME_JOIN_TIMEOUT_MS)

                check(transport.sendHeartbeatAndAwaitAck(REALTIME_HEARTBEAT_ACK_TIMEOUT_MS)) {
                    "RDC Realtime heartbeat acknowledgement timed out"
                }
                accessGate.resetForNewSession()
                lastSentAccessContext = null
                heartbeat(info, session, broadcastCapable = true)
                var lastHeartbeatAt = System.currentTimeMillis()
                reconnectAttempt = 0
                if (recoveryMode) {
                    recoveryMode = false
                    recoveryInProgress.set(false)
                    AppLogger.i(TAG, "RDC recovery verified by Realtime heartbeat acknowledgement")
                }
                updateState(
                    AiLimbsBridgePhase.ONLINE,
                    "连接正常，RDC Realtime 往返校验已通过",
                    deviceId = session.deviceId,
                    lastHeartbeatAtMs = lastHeartbeatAt,
                    reconnectAttemptValue = 0
                )
                notifyLanerChatQueueDoorbell(lanerChat.queueSnapshotEvent("reconnect_sync"), transport)

                attemptPendingProbeJob = scope.launch(Dispatchers.IO) {
                    var probeIteration = 0L
                    while (isActive) {
                        try {
                            val pendingCount = probePendingCalls(info, session)
                            if (pendingCount == 0 && probeIteration % PENDING_CALL_PROBE_HEALTH_EVERY == 0L) {
                                AppLogger.d(TAG, "RDC pending REST probe healthy: pending=0")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: UnauthorizedException) {
                            AppLogger.w(TAG, "RDC pending REST probe lost authorization")
                            return@launch
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "RDC pending REST probe failed", e)
                        }
                        probeIteration += 1
                        delay(PENDING_CALL_PROBE_INTERVAL_MS)
                    }
                }

                var lastTransportTickElapsedMs = SystemClock.elapsedRealtime()
                while (currentCoroutineContext().isActive) {
                    val tickElapsedMs = SystemClock.elapsedRealtime()
                    val schedulerGapMs = tickElapsedMs - lastTransportTickElapsedMs
                    if (schedulerGapMs >= TRANSPORT_SCHEDULER_GAP_RECONNECT_MS) {
                        throw SchedulerGapException(schedulerGapMs)
                    }
                    transport.throwIfFailed()
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
                        check(transport.sendHeartbeatAndAwaitAck(REALTIME_HEARTBEAT_ACK_TIMEOUT_MS)) {
                            "RDC Realtime heartbeat acknowledgement timed out"
                        }
                        heartbeat(info, session, broadcastCapable = true)
                        lastHeartbeatAt = System.currentTimeMillis()
                        reconnectAttempt = 0
                        updateState(
                            AiLimbsBridgePhase.ONLINE,
                            "连接正常，RDC Realtime 往返校验已通过",
                            deviceId = session.deviceId,
                            lastHeartbeatAtMs = lastHeartbeatAt,
                            reconnectAttemptValue = 0
                        )
                    }
                    lastTransportTickElapsedMs = SystemClock.elapsedRealtime()
                    delay(TRANSPORT_TICK_MS)
                }
            } catch (e: CancellationException) {
                attemptTransport?.let { closeRealtimeTransport(it) }
                throw e
            } catch (e: RecoveryRequiresPairingException) {
                attemptTransport?.let { closeRealtimeTransport(it) }
                finishRecoveryFailure(e.message ?: "已有 RDC 授权无法恢复，请重新配对")
                return
            } catch (e: SchedulerGapException) {
                attemptTransport?.let { closeRealtimeTransport(it, force = true) }
                reconnectAttempt += 1
                AppLogger.w(
                    TAG,
                    "RDC scheduler gap detected: gap=${e.gapMs}ms; rebuilding Realtime transport"
                )
                if (recoveryMode) {
                    updateState(
                        AiLimbsBridgePhase.RECOVERING,
                        "检测到系统休眠/调度暂停 ${e.gapMs}ms，正在重建 RDC Realtime"
                    )
                    if (!delayWithinRecoveryWindow(SCHEDULER_GAP_RECONNECT_DELAY_MS, recoveryDeadline)) return
                } else {
                    updateState(
                        AiLimbsBridgePhase.RECONNECTING,
                        "检测到系统休眠/调度暂停 ${e.gapMs}ms，正在快速重连"
                    )
                    delay(SCHEDULER_GAP_RECONNECT_DELAY_MS)
                }
            } catch (e: UnauthorizedException) {
                attemptTransport?.let { closeRealtimeTransport(it, force = true) }
                reconnectAttempt += 1
                AppLogger.w(TAG, "RDC session expired; attempting token refresh")
                clearAccessTokenOnly()
                val retryDelay = reconnectDelayMs(reconnectAttempt)
                if (recoveryMode) {
                    updateState(
                        AiLimbsBridgePhase.RECOVERING,
                        "RDC 会话已过期，正在使用保存的凭证刷新授权"
                    )
                    if (!delayWithinRecoveryWindow(retryDelay, recoveryDeadline)) return
                } else {
                    updateState(AiLimbsBridgePhase.RECONNECTING, "RDC 会话过期，正在刷新凭证")
                    delay(retryDelay)
                }
            } catch (e: Exception) {
                attemptTransport?.let { closeRealtimeTransport(it, force = true) }
                reconnectAttempt += 1
                val retryDelay = reconnectDelayMs(reconnectAttempt)
                AppLogger.e(TAG, "AI Limbs RDC loop failed; reconnectAttempt=$reconnectAttempt", e)
                if (recoveryMode) {
                    updateState(
                        AiLimbsBridgePhase.RECOVERING,
                        "修复步骤失败：${e.message ?: e.javaClass.simpleName}；准备重试"
                    )
                    if (!delayWithinRecoveryWindow(retryDelay, recoveryDeadline)) return
                } else {
                    updateState(
                        AiLimbsBridgePhase.RECONNECTING,
                        "连接失败：${e.message ?: e.javaClass.simpleName}；稍后重试"
                    )
                    delay(retryDelay)
                }
            } finally {
                attemptPendingProbeJob?.cancel()
            }
        }
    }

    private suspend fun delayWithinRecoveryWindow(delayMs: Long, recoveryDeadline: Long): Boolean {
        val remaining = recoveryDeadline - System.currentTimeMillis()
        if (remaining <= 0L) {
            finishRecoveryFailure("RDC 修复超时，请重试或重新配对")
            return false
        }
        delay(minOf(delayMs, remaining))
        if (System.currentTimeMillis() >= recoveryDeadline) {
            finishRecoveryFailure("RDC 修复超时，请重试或重新配对")
            return false
        }
        return true
    }

    private fun finishRecoveryFailure(detail: String) {
        recoveryInProgress.set(false)
        updateState(AiLimbsBridgePhase.RECOVERY_FAILED, detail)
        AppLogger.w(TAG, "RDC recovery failed: $detail")
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 6)
        val baseDelay =
            (RECONNECT_BASE_DELAY_MS * (1L shl exponent))
                .coerceAtMost(RECONNECT_MAX_DELAY_MS)
        val jitterWindow = (baseDelay * RECONNECT_JITTER_PERCENT / 100L).coerceAtLeast(1L)
        val jitter = Random.nextLong(jitterWindow + 1L)
        return (baseDelay + jitter).coerceAtMost(RECONNECT_MAX_DELAY_MS)
    }

    private fun closeRealtimeTransport(
        expected: AiLimbsRdcRealtimeTransport? = null,
        force: Boolean = false
    ) {
        val transportToClose =
            synchronized(realtimeTransportLock) {
                val current = realtimeTransport
                if (current == null || (expected != null && current !== expected)) {
                    null
                } else {
                    realtimeTransport = null
                    current
                }
            }
        transportToClose?.close(force)
    }

    private fun updateState(
        phase: AiLimbsBridgePhase,
        detail: String = "",
        userCode: String? = null,
        verificationUri: String? = null,
        deviceId: String? = stateFlow.value.deviceId,
        lastHeartbeatAtMs: Long? = stateFlow.value.lastHeartbeatAtMs,
        reconnectAttemptValue: Int = reconnectAttempt
    ) {
        val previous = stateFlow.value
        val next = AiLimbsBridgeState(
            providerId = PROVIDER_ID,
            providerLabel = PROVIDER_LABEL,
            phase = phase,
            detail = detail,
            userCode = userCode,
            verificationUri = verificationUri,
            deviceId = deviceId,
            lastHeartbeatAtMs = lastHeartbeatAtMs,
            reconnectAttempt = reconnectAttemptValue
        )
        stateFlow.value = next
        if (previous.phase != next.phase || previous.detail != next.detail) {
            AppLogger.i(TAG, "RDC state ${previous.phase} -> ${next.phase}: ${next.detail}")
        }
    }

    private suspend fun fetchMcpInfo(): McpInfo {
        val response = executeHttp(
            Request.Builder()
                .url("$MCP_BASE_URL/api/mcp-info")
                .get()
                .build()
        )
        if (response.code !in 200..299) {
            throw IllegalStateException("RDC MCP info failed: HTTP ${response.code}")
        }
        val json = JSONObject(response.body)
        val supabaseUrl = json.optString("supabaseUrl").trimEnd('/')
        val anonKey = json.optString("supabasePublishableKey")
            .ifBlank { json.optString("supabaseAnonKey") }
        require(supabaseUrl.isNotBlank() && anonKey.isNotBlank()) {
            "RDC MCP info is incomplete"
        }
        return McpInfo(supabaseUrl, anonKey)
    }

    private suspend fun ensureSession(info: McpInfo, allowPairing: Boolean = true): Session {
        val deviceId = preferences.getString(KEY_DEVICE_ID, null).orEmpty()
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null).orEmpty()
        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null).orEmpty()
        if (deviceId.isNotBlank() && accessToken.isNotBlank()) {
            val expiresAtMs = accessTokenExpiryMs(accessToken)
            val remainingMs = expiresAtMs?.minus(System.currentTimeMillis())
            if (remainingMs != null && remainingMs <= ACCESS_TOKEN_REFRESH_MARGIN_MS && refreshToken.isNotBlank()) {
                AppLogger.i(TAG, "RDC access token near expiry (${remainingMs}ms remaining); proactively refreshing device=${shortDeviceId(deviceId)}")
                refreshSession(info, deviceId, refreshToken)?.let { return it }
                AppLogger.w(TAG, "RDC proactive token refresh failed; continuing with saved access token for retry")
            }
            AppLogger.i(TAG, "RDC saved session restored for device=${shortDeviceId(deviceId)}")
            return Session(deviceId, accessToken, refreshToken)
        }
        if (deviceId.isNotBlank() && refreshToken.isNotBlank()) {
            AppLogger.i(TAG, "RDC access token missing; refreshing saved session for device=${shortDeviceId(deviceId)}")
            refreshSession(info, deviceId, refreshToken)?.let { return it }
            AppLogger.w(TAG, "RDC saved refresh token could not restore the session; new pairing required")
            if (!allowPairing) {
                throw RecoveryRequiresPairingException("保存的 RDC 授权已失效，请重新配对")
            }
        } else {
            AppLogger.i(TAG, "RDC has no reusable session; new pairing required")
            if (!allowPairing) {
                throw RecoveryRequiresPairingException("没有可恢复的 RDC 授权，请重新配对")
            }
        }
        return pairDevice(deviceId.takeIf { it.isNotBlank() })
    }

    private suspend fun refreshSession(
        info: McpInfo,
        deviceId: String,
        refreshToken: String
    ): Session? {
        val body = JSONObject().put("refresh_token", refreshToken).toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val response = executeHttp(
            Request.Builder()
                .url("${info.supabaseUrl}/auth/v1/token?grant_type=refresh_token")
                .header("apikey", info.anonKey)
                .header("Content-Type", JSON_MEDIA_TYPE_STRING)
                .post(body)
                .build()
        )
        if (response.code !in 200..299) {
            AppLogger.w(TAG, "RDC token refresh failed: HTTP ${response.code}")
            return null
        }
        val json = JSONObject(response.body)
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token").ifBlank { refreshToken }
        if (access.isBlank()) {
            AppLogger.w(TAG, "RDC token refresh response contained no access token")
            return null
        }
        AppLogger.i(TAG, "RDC token refresh succeeded for device=${shortDeviceId(deviceId)}")
        return Session(deviceId, access, refresh).also(::saveSession)
    }

    private suspend fun pairDevice(existingDeviceId: String?): Session {
        updateState(AiLimbsBridgePhase.CONNECTING, "正在向 RDC 申请新的授权码")
        AppLogger.i(TAG, "RDC device authorization flow started")
        val verifier = randomUrlSafe(48)
        val challenge = sha256UrlSafe(verifier)
        val startBody = JSONObject()
            .put("client_id", DEVICE_CLIENT_ID)
            .put("scope", DEVICE_SCOPE)
            .put("device_name", deviceName())
            .put("device_type", "mcp")
            .put("code_challenge", challenge)
            .put("code_challenge_method", "S256")
        if (!existingDeviceId.isNullOrBlank()) {
            startBody.put("device_id", existingDeviceId)
        }

        val startResponse = postJson("$MCP_BASE_URL/device/start", startBody)
        if (startResponse.code !in 200..299) {
            throw IllegalStateException("RDC device start failed: HTTP ${startResponse.code}")
        }
        val startJson = JSONObject(startResponse.body)
        val auth = DeviceAuth(
            deviceCode = startJson.getString("device_code"),
            userCode = startJson.optString("user_code"),
            verificationUri = startJson.optString("verification_uri"),
            verificationUriComplete = startJson.optString("verification_uri_complete"),
            expiresInSeconds = startJson.optLong("expires_in", 600L),
            pollIntervalSeconds = startJson.optLong("interval", 5L).coerceAtLeast(2L),
            verifier = verifier
        )
        activeAuthorization = auth
        updateState(
            AiLimbsBridgePhase.PAIRING,
            "授权码有效期约 ${auth.expiresInSeconds / 60L} 分钟",
            userCode = auth.userCode,
            verificationUri = auth.verificationUriComplete.ifBlank { auth.verificationUri },
            deviceId = existingDeviceId,
            lastHeartbeatAtMs = null
        )
        AppLogger.i(TAG, "RDC pairing code received; waiting for browser authorization")
        return pollDeviceAuthorization(auth)
    }

    private suspend fun pollDeviceAuthorization(auth: DeviceAuth): Session {
        val deadline = System.currentTimeMillis() + auth.expiresInSeconds * 1000L
        var intervalMs = auth.pollIntervalSeconds * 1000L
        while (System.currentTimeMillis() < deadline && currentCoroutineContext().isActive) {
            delay(intervalMs)
            val response = postJson(
                "$MCP_BASE_URL/device/poll",
                JSONObject()
                    .put("device_code", auth.deviceCode)
                    .put("client_id", DEVICE_CLIENT_ID)
                    .put("code_verifier", auth.verifier)
            )
            if (response.code in 200..299) {
                val json = JSONObject(response.body)
                val deviceId = json.optString("device_id")
                val accessToken = json.optString("access_token")
                val refreshToken = json.optString("refresh_token")
                if (deviceId.isNotBlank() && accessToken.isNotBlank()) {
                    activeAuthorization = null
                    AppLogger.i(TAG, "RDC device authorization succeeded for device=${shortDeviceId(deviceId)}")
                    updateState(
                        AiLimbsBridgePhase.CONNECTING,
                        "授权成功，正在建立设备心跳",
                        deviceId = deviceId,
                        lastHeartbeatAtMs = null
                    )
                    return Session(deviceId, accessToken, refreshToken).also(::saveSession)
                }
            }

            val error = runCatching { JSONObject(response.body).optString("error") }.getOrDefault("")
            when (error) {
                "authorization_pending", "" -> Unit
                "slow_down" -> intervalMs += 2_000L
                "expired_token", "access_denied" -> break
                else -> if (response.code !in 400..499) break
            }
        }
        activeAuthorization = null
        updateState(AiLimbsBridgePhase.ERROR, "RDC 授权已过期或被拒绝")
        AppLogger.w(TAG, "RDC device authorization expired or was denied")
        throw IllegalStateException("RDC device authorization expired or was denied")
    }
    private suspend fun reportOfflineBestEffort() {
        val deviceId = preferences.getString(KEY_DEVICE_ID, null).orEmpty()
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null).orEmpty()
        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null).orEmpty()
        if (deviceId.isBlank() || accessToken.isBlank()) return

        runCatching {
            val info = fetchMcpInfo()
            val session = Session(deviceId, accessToken, refreshToken)
            val response = authorizedRequest(
                info,
                session,
                "PATCH",
                "/rest/v1/mcp_devices?id=eq.${encode(deviceId)}",
                JSONObject()
                    .put("status", "offline")
                    .put("last_seen", nowIso()),
                prefer = "return=minimal"
            )
            if (response.code !in 200..299) {
                AppLogger.w(TAG, "RDC offline status update failed: HTTP ${response.code}")
            } else {
                AppLogger.i(TAG, "RDC device marked offline after user stop")
            }
        }.onFailure { error ->
            AppLogger.w(TAG, "Unable to mark RDC device offline during stop: ${error.message}", error)
        }
    }

    private suspend fun heartbeat(
        info: McpInfo,
        session: Session,
        broadcastCapable: Boolean
    ) {
        val capabilities = JSONObject()
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("ai_limbs", true)
        if (broadcastCapable) {
            capabilities
                .put("transport_broadcast_v1", true)
                .put("laner_chat_queue_push_v1", true)
                .put("laner_chat_turn_protocol_v5", true)
        }
        val body = JSONObject()
            .put("status", "online")
            .put("last_seen", nowIso())
            .put("device_name", deviceName())
            .put("capabilities", capabilities)
        val response = authorizedRequest(
            info,
            session,
            "PATCH",
            "/rest/v1/mcp_devices?id=eq.${encode(session.deviceId)}",
            body,
            prefer = "return=minimal"
        )
        ensureAuthorized(response)
        if (response.code !in 200..299) {
            throw IllegalStateException("RDC heartbeat failed: HTTP ${response.code}")
        }
    }

    private suspend fun fetchRdcUserId(info: McpInfo, session: Session): String {
        val response = authorizedRequest(
            info,
            session,
            "GET",
            "/rest/v1/mcp_devices?id=eq.${encode(session.deviceId)}&select=user_id&limit=1"
        )
        ensureAuthorized(response)
        if (response.code !in 200..299) {
            throw IllegalStateException("RDC device owner lookup failed: HTTP ${response.code}")
        }
        val row = JSONArray(response.body).optJSONObject(0)
            ?: throw IllegalStateException("RDC device owner lookup returned no device")
        return row.optString("user_id").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("RDC device owner lookup returned no user_id")
    }

    private suspend fun probePendingCalls(info: McpInfo, session: Session): Int {
        val response = authorizedRequest(
            info,
            session,
            "GET",
            "/rest/v1/mcp_remote_calls?device_id=eq.${encode(session.deviceId)}" +
                "&status=eq.pending" +
                "&select=id,device_id,tool_name,status" +
                "&limit=$PENDING_CALL_PROBE_LIMIT"
        )
        ensureAuthorized(response)
        if (response.code !in 200..299) {
            throw IllegalStateException("RDC pending probe failed: HTTP ${response.code}")
        }
        val rows = JSONArray(response.body)
        if (rows.length() == 0) return 0

        AppLogger.w(TAG, "RDC pending REST probe found ${rows.length()} pending call(s)")
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val callId = row.optString("id")
            if (callId.isBlank()) continue
            AppLogger.w(
                TAG,
                "RDC ingress observed source=rest_probe call=${shortCallId(callId)} " +
                    "tool=${row.optString("tool_name").ifBlank { "unknown" }} status=pending"
            )
            handleDoorbellCall(info, session, callId, "rest_probe")
        }
        return rows.length()
    }

    private suspend fun handleDoorbellCall(
        info: McpInfo,
        session: Session,
        callId: String,
        ingressSource: String
    ) {
        AppLogger.i(TAG, "RDC ingress dispatch source=$ingressSource call=${shortCallId(callId)}")
        val response = authorizedRequest(
            info,
            session,
            "GET",
            "/rest/v1/mcp_remote_calls?id=eq.${encode(callId)}&device_id=eq.${encode(session.deviceId)}&select=*&limit=1"
        )
        ensureAuthorized(response)
        if (response.code !in 200..299) {
            throw IllegalStateException("RDC doorbell call fetch failed: HTTP ${response.code}")
        }
        val call = JSONArray(response.body).optJSONObject(0)
        if (call == null) {
            AppLogger.w(TAG, "RDC ingress fetch empty source=$ingressSource call=${shortCallId(callId)}")
            return
        }
        val status = call.optString("status")
        val toolName = call.optString("tool_name").ifBlank { "unknown" }
        if (status != "pending") {
            AppLogger.d(
                TAG,
                "RDC ingress ignored source=$ingressSource call=${shortCallId(callId)} " +
                    "tool=$toolName status=${status.ifBlank { "unknown" }}"
            )
            return
        }
        AppLogger.i(
            TAG,
            "RDC claim attempt source=$ingressSource call=${shortCallId(callId)} tool=$toolName " +
                "createdAt=${call.optString("created_at").ifBlank { "unknown" }}"
        )
        if (!claimCall(info, session, callId)) {
            AppLogger.d(TAG, "RDC claim lost source=$ingressSource call=${shortCallId(callId)} tool=$toolName")
            return
        }
        AppLogger.i(TAG, "RDC claim won source=$ingressSource call=${shortCallId(callId)} tool=$toolName")
        launchClaimedCall(info, session, call)
    }

    private fun launchClaimedCall(info: McpInfo, session: Session, call: JSONObject) {
        val callId = call.optString("id")
        val toolName = call.optString("tool_name")
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            AppLogger.i(TAG, "RDC tool worker started: tool=$toolName call=${shortCallId(callId)}")
            try {
                remoteCallSemaphore.withPermit { handleClaimedCall(info, session, call) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                AppLogger.w(TAG, "RDC tool worker lost authorization: tool=$toolName call=${shortCallId(callId)}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "RDC tool worker crashed: tool=$toolName call=${shortCallId(callId)}", e)
            }
        }
        activeCallJobs[callId] = job
        job.invokeOnCompletion {
            activeCallJobs.remove(callId, job)
            AppLogger.i(TAG, "RDC tool worker finished: tool=$toolName call=${shortCallId(callId)} active=${activeCallJobs.size}")
        }
        job.start()
    }

    private fun cancelActiveCalls(reason: String) {
        val jobs = activeCallJobs.values.toList()
        if (jobs.isNotEmpty()) {
            AppLogger.i(TAG, "Cancelling ${jobs.size} RDC tool worker(s): $reason")
            jobs.forEach { it.cancel() }
        }
        activeCallJobs.clear()
    }

    private suspend fun claimCall(
        info: McpInfo,
        session: Session,
        callId: String
    ): Boolean {
        val response = authorizedRequest(
            info,
            session,
            "PATCH",
            "/rest/v1/mcp_remote_calls?id=eq.${encode(callId)}&status=eq.pending",
            JSONObject().put("status", "executing"),
            prefer = "return=representation"
        )
        ensureAuthorized(response)
        if (response.code !in 200..299) {
            AppLogger.w(TAG, "RDC claim HTTP failure: call=${shortCallId(callId)} code=${response.code}")
            return false
        }
        val claimed = runCatching { JSONArray(response.body).length() > 0 }.getOrDefault(false)
        if (!claimed) {
            AppLogger.d(TAG, "RDC claim returned no row: call=${shortCallId(callId)}")
        }
        return claimed
    }

    private suspend fun handleClaimedCall(
        info: McpInfo,
        session: Session,
        call: JSONObject
    ) {
        val callId = call.optString("id")
        val toolName = call.optString("tool_name")
        val rawArgs = call.opt("tool_args")
        val args = when (rawArgs) {
            is JSONObject -> rawArgs
            is String -> runCatching { JSONObject(rawArgs) }.getOrDefault(JSONObject())
            else -> JSONObject()
        }
        try {
            val rawResult =
                when (toolName) {
                    "ping" -> mcpText("pong ${nowIso()}")
                    "shutdown" -> mcpText(
                        "AI Limbs RDC is managed by the Android foreground service; " +
                            "close AI Limbs to stop the device runtime."
                    )
                    else -> adapter.execute(toolName, args)
                }
            if (toolName == "ping") {
                lastSentAccessContext = null
            }
            val result = attachAiLimbsContext(rawResult)
            logOutboundResultMetadata(callId, toolName, result)
            updateCall(
                info,
                session,
                callId,
                JSONObject()
                    .put("status", "completed")
                    .put("completed_at", nowIso())
                    .put("result", result)
            )
            notifyResultDoorbell(callId)
        } catch (e: UnauthorizedException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "RDC tool call failed: $toolName", e)
            updateCall(
                info,
                session,
                callId,
                JSONObject()
                    .put("status", "failed")
                    .put("completed_at", nowIso())
                    .put("error_message", e.message ?: "AI Limbs tool call failed")
            )
            notifyResultDoorbell(callId)
        }
    }

    private suspend fun notifyLanerChatQueueDoorbell(
        event: LanerChatQueueChangedEvent,
        transportOverride: AiLimbsRdcRealtimeTransport? = null
    ) {
        val transport = transportOverride ?: realtimeTransport ?: return
        val notified = try {
            transport.notifyLanerChatQueueChanged(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(
                TAG,
                "Laner Chat queue doorbell send failed: reason=${event.reason}, event=${event.eventId}",
                e
            )
            false
        }
        if (notified) {
            AppLogger.d(
                TAG,
                "Laner Chat queue doorbell acknowledged: reason=${event.reason}, latestSeq=${event.latestSeq}"
            )
        } else {
            AppLogger.w(
                TAG,
                "Laner Chat queue doorbell was not acknowledged: reason=${event.reason}, latestSeq=${event.latestSeq}"
            )
        }
    }

    private suspend fun notifyResultDoorbell(callId: String) {
        val notified = try {
            realtimeTransport?.notifyResult(callId) == true
        } catch (e: CancellationException) {
            AppLogger.d(TAG, "RDC result doorbell cancelled after terminal write: call=${shortCallId(callId)}")
            false
        } catch (e: Exception) {
            AppLogger.w(
                TAG,
                "RDC result doorbell send failed after terminal result write: call=${shortCallId(callId)}",
                e
            )
            false
        }
        if (notified) {
            AppLogger.d(TAG, "RDC result doorbell acknowledged: call=${shortCallId(callId)}")
        } else {
            // The database row is already terminal here. Notification transport failure must not
            // rewrite a completed tool result as failed; the result bytes remain unchanged in RDC.
            AppLogger.w(TAG, "RDC result doorbell was not acknowledged: call=${shortCallId(callId)}")
        }
    }

    private fun logOutboundResultMetadata(callId: String, toolName: String, result: JSONObject) {
        val content = result.optJSONArray("content") ?: JSONArray()
        val summary = StringBuilder("contentCount=${content.length()}")
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            val type = item.optString("type").ifBlank { "unknown" }
            summary.append(" content[").append(index).append("]=").append(type)
            if (type == "image") {
                summary
                    .append("(")
                    .append(item.optString("mimeType").ifBlank { "unknown-mime" })
                    .append(",dataLength=")
                    .append(item.optString("data").length)
                    .append(")")
            }
        }
        // Intentionally log metadata only. The Base64 payload itself must never enter logs.
        AppLogger.i(
            TAG,
            "RDC outbound result call=${shortCallId(callId)} tool=$toolName $summary"
        )
    }

    private suspend fun attachAiLimbsContext(result: JSONObject): JSONObject {
        val withWorkNotification = attachLanerChatWorkNotification(result)
        return attachAccessContext(withWorkNotification)
    }

    private suspend fun attachAccessContext(result: JSONObject): JSONObject {
        val context = try {
            accessContext.readAccessContext()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Unable to build AI Limbs access context", e)
            return result
        }
        if (context == lastSentAccessContext) return result
        lastSentAccessContext = context
        return prependTextContent(result, context)
    }

    private fun attachLanerChatWorkNotification(result: JSONObject): JSONObject {
        val notification = try {
            lanerChat.workNotificationSnapshot()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Unable to build Laner Chat work notification", e)
            return result
        }
        if (notification.unreadCount <= 0) return result

        val metadata = JSONObject()
            .put("event", notification.event)
            .put("source", "laner_chat")
            .put("pending_count", notification.unreadCount)
            .put("highest_priority", notification.highestPriority?.name ?: "NONE")
            .put("latest_seq", notification.latestSeq)
            .put(
                "priority_counts",
                JSONObject()
                    .put("HIGH", notification.highCount)
                    .put("NORMAL", notification.normalCount)
                    .put("LOW", notification.lowCount)
            )
            .put("attention_required", true)
            .put("body_included", false)
        val text = buildString {
            append("[AI Limbs Work Notification]\n")
            append(metadata.toString())
            append("\nUnread Laner Chat message bodies are not included. ")
            append("Follow the System Access Prompt priority timing and fetch bodies only at an appropriate safe work-switching point.")
        }
        AppLogger.d(
            TAG,
            "Laner Chat work notification attached: pending=${notification.unreadCount} " +
                "priority=${notification.highestPriority?.name ?: "NONE"} latestSeq=${notification.latestSeq}"
        )
        return prependTextContent(result, text)
    }

    private fun prependTextContent(result: JSONObject, text: String): JSONObject {
        val oldContent = result.optJSONArray("content") ?: JSONArray()
        val newContent = JSONArray().put(
            JSONObject().put("type", "text").put("text", text)
        )
        for (index in 0 until oldContent.length()) {
            newContent.put(oldContent.opt(index))
        }
        result.put("content", newContent)
        return result
    }

    private suspend fun updateCall(
        info: McpInfo,
        session: Session,
        callId: String,
        body: JSONObject
    ) {
        val response = authorizedRequest(
            info,
            session,
            "PATCH",
            "/rest/v1/mcp_remote_calls?id=eq.${encode(callId)}",
            body,
            prefer = "return=minimal"
        )
        ensureAuthorized(response)
        if (response.code !in 200..299) {
            throw IllegalStateException("RDC result update failed: HTTP ${response.code}")
        }
    }

    private suspend fun authorizedRequest(
        info: McpInfo,
        session: Session,
        method: String,
        path: String,
        body: JSONObject? = null,
        prefer: String? = null
    ): HttpResponse {
        val builder = Request.Builder()
            .url("${info.supabaseUrl}$path")
            .header("apikey", info.anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Accept", "application/json")
        if (!prefer.isNullOrBlank()) builder.header("Prefer", prefer)

        val requestBody = body?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
        when (method) {
            "GET" -> builder.get()
            "PATCH" -> builder.patch(requestBody ?: EMPTY_JSON_BODY)
            "POST" -> builder.post(requestBody ?: EMPTY_JSON_BODY)
            else -> error("Unsupported HTTP method: $method")
        }
        return executeHttp(builder.build())
    }

    private fun ensureAuthorized(response: HttpResponse) {
        if (response.code == 401 || response.code == 403) {
            throw UnauthorizedException()
        }
    }

    private suspend fun postJson(url: String, body: JSONObject): HttpResponse =
        executeHttp(
            Request.Builder()
                .url(url)
                .header("Content-Type", JSON_MEDIA_TYPE_STRING)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )

    private suspend fun executeHttp(request: Request): HttpResponse =
        withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                HttpResponse(
                    code = response.code,
                    body = response.body?.string().orEmpty()
                )
            }
        }

    private fun saveSession(session: Session) {
        preferences.edit()
            .putString(KEY_DEVICE_ID, session.deviceId)
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .apply()
    }

    private fun clearAccessTokenOnly() {
        preferences.edit().remove(KEY_ACCESS_TOKEN).apply()
    }

    private fun clearSession() {
        preferences.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
        AppLogger.i(TAG, "RDC saved session cleared")
    }

    private fun createPreferences(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREF_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Encrypted RDC preferences unavailable; using app-private storage", e)
            appContext.getSharedPreferences("${PREF_FILE}_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun mcpText(text: String): JSONObject =
        JSONObject().put(
            "content",
            JSONArray().put(
                JSONObject()
                    .put("type", "text")
                    .put("text", text)
            )
        )

    private fun accessTokenExpiryMs(accessToken: String): Long? {
        return runCatching {
            val payload = accessToken.split('.').getOrNull(1) ?: return@runCatching null
            val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
            val expSeconds = JSONObject(String(decoded, StandardCharsets.UTF_8)).optLong("exp", 0L)
            expSeconds.takeIf { it > 0L }?.times(1000L)
        }.getOrNull()
    }

    private fun deviceName(): String =
        "AI Limbs ${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun shortDeviceId(deviceId: String): String =
        if (deviceId.length <= 12) deviceId else "…${deviceId.takeLast(12)}"

    private fun shortCallId(callId: String): String =
        if (callId.length <= 10) callId else "…${callId.takeLast(10)}"

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun sha256UrlSafe(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.US_ASCII))
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun nowIso(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    private data class McpInfo(
        val supabaseUrl: String,
        val anonKey: String
    )

    private data class Session(
        val deviceId: String,
        val accessToken: String,
        val refreshToken: String
    )

    private data class DeviceAuth(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresInSeconds: Long,
        val pollIntervalSeconds: Long,
        val verifier: String
    )

    private data class HttpResponse(
        val code: Int,
        val body: String
    )

    private class RecoveryRequiresPairingException(message: String) : IllegalStateException(message)

    private class SchedulerGapException(val gapMs: Long) :
        IllegalStateException("RDC scheduler gap: ${gapMs}ms")

    private class UnauthorizedException : IllegalStateException()

    companion object {
        const val ENABLED = true
        const val PROVIDER_ID = "rdc"
        const val PROVIDER_LABEL = "RDC"

        private const val TAG = "AiLimbsRdcClient"
        private const val MCP_BASE_URL = "https://mcp.desktopcommander.app"
        private const val DEVICE_CLIENT_ID = "mcp-device"
        private const val DEVICE_SCOPE = "mcp:tools"
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val ONLINE_STALE_AFTER_MS = HEARTBEAT_INTERVAL_MS * 3
        private const val TRANSPORT_TICK_MS = 500L
        private const val PENDING_CALL_PROBE_INTERVAL_MS = 3_000L
        private const val PENDING_CALL_PROBE_HEALTH_EVERY = 10L
        private const val PENDING_CALL_PROBE_LIMIT = 20
        private const val TRANSPORT_SCHEDULER_GAP_RECONNECT_MS = 30_000L
        private const val SCHEDULER_GAP_RECONNECT_DELAY_MS = 250L
        private const val REALTIME_JOIN_TIMEOUT_MS = 15_000L
        private const val REALTIME_HEARTBEAT_ACK_TIMEOUT_MS = 5_000L
        private const val RECOVERY_TIMEOUT_MS = 120_000L
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 120_000L
        private const val RECONNECT_JITTER_PERCENT = 15L
        private const val ACCESS_TOKEN_REFRESH_MARGIN_MS = 60_000L
        private const val MAX_CONCURRENT_REMOTE_CALLS = 4

        private const val PREF_FILE = "ai_limbs_rdc"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"

        private const val JSON_MEDIA_TYPE_STRING = "application/json; charset=utf-8"
        private val JSON_MEDIA_TYPE = JSON_MEDIA_TYPE_STRING.toMediaType()
        private val EMPTY_JSON_BODY = "{}".toRequestBody(JSON_MEDIA_TYPE)
    }
}
