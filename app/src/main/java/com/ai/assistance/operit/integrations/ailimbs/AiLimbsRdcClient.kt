package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ai.assistance.operit.BuildConfig
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
import kotlinx.coroutines.withContext
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
    private val dispatcher = AiLimbsOperitDispatcher(appContext)
    private val documents = AiLimbsDocumentProvider(appContext)
    private val adapter = AiLimbsRdcToolAdapter(dispatcher)
    private val httpClient =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
    private val preferences: SharedPreferences by lazy { createPreferences() }
    private val console = AiLimbsRdcConsole(appContext)
    private val stateFlow = MutableStateFlow(AiLimbsRdcState())
    val state = stateFlow.asStateFlow()
    private var runJob: Job? = null
    private var lastSentAccessPrompt: String? = null
    private var activeAuthorization: DeviceAuth? = null
    private var reconnectAttempt: Int = 0
    private val activeCallJobs = ConcurrentHashMap<String, Job>()
    private val housekeepingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start() {
        if (!ENABLED) return
        if (runJob?.isActive == true) {
            AppLogger.d(TAG, "RDC start ignored: worker already active")
            console.show(stateFlow.value)
            return
        }
        updateState(AiLimbsRdcPhase.STARTING, "正在启动 Android 端 RDC 设备")
        AppLogger.i(TAG, "RDC worker start requested")
        runJob = scope.launch(Dispatchers.IO) {
            isRunning = true
            AppLogger.i(TAG, "RDC worker started")
            try {
                runForever()
            } finally {
                isRunning = false
                AppLogger.i(TAG, "RDC worker stopped")
            }
        }
    }

    fun stopByUser() {
        AppLogger.i(TAG, "RDC connection stop requested by user")
        housekeepingScope.launch {
            reportOfflineBestEffort()
        }
        stopWorker("连接已由用户停止", showConsole = true)
    }

    fun stopRuntime() {
        AppLogger.i(TAG, "RDC runtime stop requested")
        stopWorker("Android 端 RDC 运行时已停止", showConsole = false)
    }

    fun showStopped() {
        stopWorker("连接已停止，等待用户启动", showConsole = true)
    }

    private fun stopWorker(detail: String, showConsole: Boolean) {
        runJob?.cancel()
        runJob = null
        cancelActiveCalls("RDC stopped")
        activeAuthorization = null
        isRunning = false
        val stopped = AiLimbsRdcState(AiLimbsRdcPhase.STOPPED, detail)
        stateFlow.value = stopped
        if (showConsole) {
            console.show(stopped)
        } else {
            console.cancel()
        }
    }

    fun refreshConsole() {
        val current = stateFlow.value
        AppLogger.i(TAG, "RDC console refresh requested for phase=${current.phase}")
        val heartbeatAge = current.lastHeartbeatAtMs?.let { System.currentTimeMillis() - it }
        if (
            current.phase == AiLimbsRdcPhase.ONLINE &&
            (heartbeatAge == null || heartbeatAge > ONLINE_STALE_AFTER_MS)
        ) {
            AppLogger.w(TAG, "RDC heartbeat is stale; restarting worker to verify the live session")
            runJob?.cancel()
            runJob = null
            cancelActiveCalls("stale heartbeat")
            reconnectAttempt = 0
            updateState(
                AiLimbsRdcPhase.RECONNECTING,
                "最后心跳已过期，正在重新建立连接",
                lastHeartbeatAtMs = current.lastHeartbeatAtMs
            )
            start()
        } else {
            console.show(current)
        }
    }

    fun reconnect() {
        AppLogger.i(TAG, "RDC manual reconnect requested")
        runJob?.cancel()
        runJob = null
        cancelActiveCalls("manual reconnect")
        reconnectAttempt = 0
        updateState(AiLimbsRdcPhase.RECONNECTING, "用户请求重新连接")
        start()
    }

    fun rePair() {
        AppLogger.i(TAG, "RDC manual re-pair requested")
        runJob?.cancel()
        runJob = null
        cancelActiveCalls("manual re-pair")
        clearSession()
        activeAuthorization = null
        lastSentAccessPrompt = null
        reconnectAttempt = 0
        updateState(
            AiLimbsRdcPhase.STARTING,
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

    private suspend fun runForever() {
        while (currentCoroutineContext().isActive) {
            try {
                updateState(
                    if (reconnectAttempt == 0) AiLimbsRdcPhase.CONNECTING else AiLimbsRdcPhase.RECONNECTING,
                    if (reconnectAttempt == 0) "正在连接 Remote Desktop Commander" else "正在执行第 ${reconnectAttempt} 次重连"
                )
                val info = fetchMcpInfo()
                val session = ensureSession(info)
                var lastHeartbeatAt = 0L

                while (currentCoroutineContext().isActive) {
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
                        heartbeat(info, session)
                        lastHeartbeatAt = now
                        reconnectAttempt = 0
                        updateState(
                            AiLimbsRdcPhase.ONLINE,
                            "连接正常，可接收 RDC 工具调用",
                            deviceId = session.deviceId,
                            lastHeartbeatAtMs = now,
                            reconnectAttemptValue = 0
                        )
                    }
                    pollAndHandleCalls(info, session)
                    delay(POLL_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                reconnectAttempt += 1
                AppLogger.w(TAG, "RDC session expired; attempting token refresh")
                updateState(AiLimbsRdcPhase.RECONNECTING, "RDC 会话过期，正在刷新凭证")
                clearAccessTokenOnly()
                delay(500L)
            } catch (e: Exception) {
                reconnectAttempt += 1
                AppLogger.e(TAG, "AI Limbs RDC loop failed; reconnectAttempt=$reconnectAttempt", e)
                updateState(
                    AiLimbsRdcPhase.RECONNECTING,
                    "连接失败：${e.message ?: e.javaClass.simpleName}"
                )
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private fun updateState(
        phase: AiLimbsRdcPhase,
        detail: String = "",
        userCode: String? = null,
        verificationUri: String? = null,
        deviceId: String? = stateFlow.value.deviceId,
        lastHeartbeatAtMs: Long? = stateFlow.value.lastHeartbeatAtMs,
        reconnectAttemptValue: Int = reconnectAttempt
    ) {
        val previous = stateFlow.value
        val next = AiLimbsRdcState(
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
        console.show(next)
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

    private suspend fun ensureSession(info: McpInfo): Session {
        val deviceId = preferences.getString(KEY_DEVICE_ID, null).orEmpty()
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null).orEmpty()
        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null).orEmpty()
        if (deviceId.isNotBlank() && accessToken.isNotBlank()) {
            AppLogger.i(TAG, "RDC saved session restored for device=${shortDeviceId(deviceId)}")
            return Session(deviceId, accessToken, refreshToken)
        }
        if (deviceId.isNotBlank() && refreshToken.isNotBlank()) {
            AppLogger.i(TAG, "RDC access token missing; refreshing saved session for device=${shortDeviceId(deviceId)}")
            refreshSession(info, deviceId, refreshToken)?.let { return it }
            AppLogger.w(TAG, "RDC saved refresh token could not restore the session; new pairing required")
        } else {
            AppLogger.i(TAG, "RDC has no reusable session; new pairing required")
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
        updateState(AiLimbsRdcPhase.CONNECTING, "正在向 RDC 申请新的授权码")
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
            AiLimbsRdcPhase.PAIRING,
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
                        AiLimbsRdcPhase.CONNECTING,
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
        updateState(AiLimbsRdcPhase.ERROR, "RDC 授权已过期或被拒绝")
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

    private suspend fun heartbeat(info: McpInfo, session: Session) {
        val body = JSONObject()
            .put("status", "online")
            .put("last_seen", nowIso())
            .put("device_name", deviceName())
            .put(
                "capabilities",
                JSONObject()
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("ai_limbs", true)
            )
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

    private suspend fun pollAndHandleCalls(info: McpInfo, session: Session) {
        val availableSlots = (MAX_CONCURRENT_REMOTE_CALLS - activeCallJobs.size).coerceAtLeast(0)
        if (availableSlots == 0) return

        val response = authorizedRequest(
            info,
            session,
            "GET",
            "/rest/v1/mcp_remote_calls?device_id=eq.${encode(session.deviceId)}&status=eq.pending&select=*&order=created_at.asc&limit=$availableSlots"
        )
        ensureAuthorized(response)
        if (response.code !in 200..299) return
        val calls = JSONArray(response.body)
        for (index in 0 until calls.length()) {
            if (activeCallJobs.size >= MAX_CONCURRENT_REMOTE_CALLS) break
            val call = calls.optJSONObject(index) ?: continue
            val callId = call.optString("id")
            if (callId.isBlank() || !claimCall(info, session, callId)) continue
            launchClaimedCall(info, session, call)
        }
    }

    private fun launchClaimedCall(info: McpInfo, session: Session, call: JSONObject) {
        val callId = call.optString("id")
        val toolName = call.optString("tool_name")
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            AppLogger.i(TAG, "RDC tool worker started: tool=$toolName call=${shortCallId(callId)}")
            try {
                handleClaimedCall(info, session, call)
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
        if (response.code !in 200..299) return false
        return runCatching { JSONArray(response.body).length() > 0 }.getOrDefault(false)
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
                lastSentAccessPrompt = null
            }
            val result = attachDynamicAccessPrompt(rawResult)
            updateCall(
                info,
                session,
                callId,
                JSONObject()
                    .put("status", "completed")
                    .put("completed_at", nowIso())
                    .put("result", result)
            )
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
        }
    }

    private suspend fun attachDynamicAccessPrompt(result: JSONObject): JSONObject {
        val prompt = try {
            documents.readAccessPrompt()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Unable to read dynamic AI Limbs access prompt", e)
            return result
        }
        if (prompt == lastSentAccessPrompt) return result
        lastSentAccessPrompt = prompt
        val handshake = buildString {
            appendLine("[AI Limbs dynamic access prompt]")
            appendLine("Path: ${AiLimbsDocumentProvider.ACCESS_PROMPT_PATH}")
            appendLine(prompt.ifBlank { "(empty)" })
            appendLine()
            appendLine("[AI Limbs RDC bridge]")
            appendLine("- Normal start_process runs in Operit Terminal/PRoot Linux.")
            appendLine("- shell=android routes command through Operit execute_shell.")
            appendLine(
                "- shell=operit expects command JSON: " +
                    "{\"name\":\"tool_name\",\"parameters\":{...}}."
            )
            append("- All Operit calls keep ALLOW / ASK / FORBID permission semantics.")
        }
        val oldContent = result.optJSONArray("content") ?: JSONArray()
        val newContent = JSONArray().put(
            JSONObject().put("type", "text").put("text", handshake)
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

    private class UnauthorizedException : IllegalStateException()

    companion object {
        const val ENABLED = true

        private const val TAG = "AiLimbsRdcClient"
        private const val MCP_BASE_URL = "https://mcp.desktopcommander.app"
        private const val DEVICE_CLIENT_ID = "mcp-device"
        private const val DEVICE_SCOPE = "mcp:tools"
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val ONLINE_STALE_AFTER_MS = HEARTBEAT_INTERVAL_MS * 3
        private const val POLL_INTERVAL_MS = 1_500L
        private const val RECONNECT_DELAY_MS = 5_000L
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
