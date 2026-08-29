package com.ailimbs.freecessprobe

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class RdcProbeClient(
    context: Context,
    private val scope: CoroutineScope,
    private val onStateChanged: () -> Unit
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("freecess_probe_rdc", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
    private var worker: Job? = null
    @Volatile private var transport: RdcRealtimeTransport? = null
    private var reconnectAttempt = 0

    fun start() {
        if (worker?.isActive == true) return
        setState("STARTING", "正在启动 Probe RDC")
        worker = scope.launch(Dispatchers.IO) { runForever() }
    }

    fun stop() {
        worker?.cancel()
        worker = null
        transport?.close()
        transport = null
        reconnectAttempt = 0
        setState("STOPPED", "已停止", socketSinceMs = null, lastHeartbeatAtMs = null)
        ProbeLog.i(TAG, "runtime stopped")
    }

    fun rePair() {
        ProbeLog.i(TAG, "manual re-pair requested")
        worker?.cancel()
        worker = null
        transport?.close()
        transport = null
        prefs.edit().clear().apply()
        reconnectAttempt = 0
        setState("STARTING", "已清除旧授权，正在申请新授权码", deviceId = null, userCode = null, verificationUri = null)
        start()
    }

    fun onHostSignal(signal: String) {
        ProbeLog.i("HOST", "signal=$signal phase=${ProbeRuntime.state.value.phase}")
        if (!BuildConfig.USE_HOST_SIGNALS) return
        if (signal == "screen_on") {
            val age = ProbeRuntime.state.value.lastHeartbeatAtMs?.let { System.currentTimeMillis() - it }
            if (age != null && age > ONLINE_STALE_AFTER_MS) {
                fastRestart("screen_on_stale_${age}ms")
            }
        } else if (signal == "network_available" && ProbeRuntime.state.value.phase != "ONLINE") {
            fastRestart("network_available")
        }
    }

    private fun fastRestart(reason: String) {
        ProbeLog.w(TAG, "fast restart requested: $reason")
        worker?.cancel()
        worker = null
        transport?.close(force = true)
        transport = null
        reconnectAttempt = 0
        setState("RECONNECTING", "快速重建：$reason", socketSinceMs = null)
        start()
    }

    private suspend fun runForever() {
        while (currentCoroutineContext().isActive) {
            var currentTransport: RdcRealtimeTransport? = null
            try {
                setState(
                    if (reconnectAttempt == 0) "CONNECTING" else "RECONNECTING",
                    if (reconnectAttempt == 0) "正在连接 RDC" else "第 $reconnectAttempt 次重连"
                )
                val info = fetchMcpInfo()
                val session = ensureSession(info)
                val userId = fetchRdcUserId(info, session)
                val t = RdcRealtimeTransport(
                    httpClient = http,
                    supabaseUrl = info.supabaseUrl,
                    anonKey = info.anonKey,
                    accessToken = session.accessToken,
                    userId = userId,
                    deviceId = session.deviceId,
                    deviceName = deviceName(),
                    appVersion = BuildConfig.VERSION_NAME,
                    onNewCall = { callId, source -> ProbeLog.i("INGRESS", "$source call=…${callId.takeLast(10)}") }
                )
                currentTransport = t
                transport = t
                t.connectAndAwaitReady(REALTIME_JOIN_TIMEOUT_MS)
                check(t.sendHeartbeatAndAwaitAck(REALTIME_HEARTBEAT_ACK_TIMEOUT_MS)) {
                    "Realtime heartbeat acknowledgement timed out"
                }
                heartbeat(info, session)
                var lastHeartbeatAt = System.currentTimeMillis()
                var lastTickElapsed = SystemClock.elapsedRealtime()
                var lastSuspendOffset = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
                reconnectAttempt = 0
                setState(
                    "ONLINE",
                    "RDC Realtime ONLINE",
                    deviceId = session.deviceId,
                    userCode = null,
                    verificationUri = null,
                    socketSinceMs = System.currentTimeMillis(),
                    lastHeartbeatAtMs = lastHeartbeatAt
                )
                ProbeLog.i(TAG, "ONLINE device=…${session.deviceId.takeLast(12)} variant=${BuildConfig.PROBE_LABEL}")

                while (currentCoroutineContext().isActive) {
                    val tickElapsed = SystemClock.elapsedRealtime()
                    val schedulerGap = tickElapsed - lastTickElapsed
                    if (schedulerGap >= SCHEDULER_GAP_RECONNECT_MS) {
                        throw SchedulerGapException(schedulerGap)
                    }
                    if (BuildConfig.USE_SUSPEND_DETECTOR) {
                        val offset = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
                        val suspendDelta = offset - lastSuspendOffset
                        if (suspendDelta >= SUSPEND_DETECT_THRESHOLD_MS) {
                            ProbeLog.w("SUSPEND", "detected delta=${suspendDelta}ms force=${BuildConfig.FORCE_REBUILD_ON_SUSPEND}")
                            ProbeRuntime.update { it.copy(lastSuspendDeltaMs = suspendDelta) }
                            onStateChanged()
                            if (BuildConfig.FORCE_REBUILD_ON_SUSPEND) throw SuspendDetectedException(suspendDelta)
                        }
                        lastSuspendOffset = offset
                    }
                    t.throwIfFailed()
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
                        check(t.sendHeartbeatAndAwaitAck(REALTIME_HEARTBEAT_ACK_TIMEOUT_MS)) {
                            "Realtime heartbeat acknowledgement timed out"
                        }
                        heartbeat(info, session)
                        lastHeartbeatAt = System.currentTimeMillis()
                        setState("ONLINE", "RDC Realtime ONLINE", deviceId = session.deviceId, lastHeartbeatAtMs = lastHeartbeatAt)
                    }
                    lastTickElapsed = SystemClock.elapsedRealtime()
                    delay(TRANSPORT_TICK_MS)
                }
            } catch (e: CancellationException) {
                currentTransport?.close()
                throw e
            } catch (e: SchedulerGapException) {
                currentTransport?.close(force = true)
                transport = null
                reconnectAttempt += 1
                ProbeLog.w("SCHEDULER", "gap=${e.gapMs}ms; 0643-style rebuild")
                setState("RECONNECTING", "调度暂停 ${e.gapMs}ms，0643-style 快速重连", socketSinceMs = null)
                delay(FAST_RECONNECT_DELAY_MS)
            } catch (e: SuspendDetectedException) {
                currentTransport?.close(force = true)
                transport = null
                reconnectAttempt += 1
                ProbeLog.w("SUSPEND", "force-close and rebuild delta=${e.deltaMs}ms")
                setState("RECONNECTING", "suspend ${e.deltaMs}ms → force rebuild", socketSinceMs = null)
                delay(FAST_RECONNECT_DELAY_MS)
            } catch (e: UnauthorizedException) {
                currentTransport?.close(force = true)
                transport = null
                prefs.edit().remove(KEY_ACCESS_TOKEN).apply()
                reconnectAttempt += 1
                setState("RECONNECTING", "授权过期，准备刷新", socketSinceMs = null)
                delay(reconnectDelayMs(reconnectAttempt))
            } catch (e: Exception) {
                currentTransport?.close(force = true)
                transport = null
                reconnectAttempt += 1
                ProbeLog.e(TAG, "loop failed attempt=$reconnectAttempt", e)
                setState("RECONNECTING", "连接失败：${e.message ?: e.javaClass.simpleName}", socketSinceMs = null)
                delay(reconnectDelayMs(reconnectAttempt))
            }
        }
    }

    private suspend fun fetchMcpInfo(): McpInfo {
        val response = executeHttp(Request.Builder().url("$MCP_BASE_URL/api/mcp-info").get().build())
        check(response.code in 200..299) { "MCP info HTTP ${response.code}" }
        val json = JSONObject(response.body)
        val url = json.optString("supabaseUrl").trimEnd('/')
        val key = json.optString("supabasePublishableKey").ifBlank { json.optString("supabaseAnonKey") }
        require(url.isNotBlank() && key.isNotBlank()) { "MCP info incomplete" }
        return McpInfo(url, key)
    }

    private suspend fun ensureSession(info: McpInfo): Session {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null).orEmpty()
        val access = prefs.getString(KEY_ACCESS_TOKEN, null).orEmpty()
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null).orEmpty()
        if (deviceId.isNotBlank() && access.isNotBlank()) return Session(deviceId, access, refresh)
        if (deviceId.isNotBlank() && refresh.isNotBlank()) {
            refreshSession(info, deviceId, refresh)?.let { return it }
        }
        return pairDevice(deviceId.takeIf { it.isNotBlank() })
    }

    private suspend fun refreshSession(info: McpInfo, deviceId: String, refreshToken: String): Session? {
        val body = JSONObject().put("refresh_token", refreshToken).toString().toRequestBody(JSON_MEDIA_TYPE)
        val response = executeHttp(
            Request.Builder()
                .url("${info.supabaseUrl}/auth/v1/token?grant_type=refresh_token")
                .header("Content-Type", JSON_MEDIA_TYPE_STRING)
                .header("apikey", info.anonKey)
                .post(body)
                .build()
        )
        if (response.code !in 200..299) return null
        val json = JSONObject(response.body)
        val access = json.optString("access_token")
        if (access.isBlank()) return null
        return Session(deviceId, access, json.optString("refresh_token").ifBlank { refreshToken }).also(::saveSession)
    }

    private suspend fun pairDevice(existingDeviceId: String?): Session {
        setState("PAIRING", "正在向 RDC 申请授权码", deviceId = existingDeviceId, userCode = null, verificationUri = null)
        val verifier = randomUrlSafe(48)
        val body = JSONObject()
            .put("client_id", DEVICE_CLIENT_ID)
            .put("scope", DEVICE_SCOPE)
            .put("device_name", deviceName())
            .put("device_type", "mcp")
            .put("code_challenge", sha256UrlSafe(verifier))
            .put("code_challenge_method", "S256")
        if (!existingDeviceId.isNullOrBlank()) body.put("device_id", existingDeviceId)
        val response = postJson("$MCP_BASE_URL/device/start", body)
        check(response.code in 200..299) { "device/start HTTP ${response.code}" }
        val json = JSONObject(response.body)
        val auth = DeviceAuth(
            json.getString("device_code"),
            json.optString("user_code"),
            json.optString("verification_uri"),
            json.optString("verification_uri_complete"),
            json.optLong("expires_in", 600L),
            json.optLong("interval", 5L).coerceAtLeast(2L),
            verifier
        )
        val uri = auth.verificationUriComplete.ifBlank { auth.verificationUri }
        setState("PAIRING", "等待浏览器授权", deviceId = existingDeviceId, userCode = auth.userCode, verificationUri = uri)
        ProbeLog.i(TAG, "pairing code=${auth.userCode} uri=$uri")
        return pollAuthorization(auth)
    }

    private suspend fun pollAuthorization(auth: DeviceAuth): Session {
        val deadline = System.currentTimeMillis() + auth.expiresInSeconds * 1000L
        var interval = auth.pollIntervalSeconds * 1000L
        while (System.currentTimeMillis() < deadline && currentCoroutineContext().isActive) {
            delay(interval)
            val response = postJson(
                "$MCP_BASE_URL/device/poll",
                JSONObject().put("device_code", auth.deviceCode).put("client_id", DEVICE_CLIENT_ID).put("code_verifier", auth.verifier)
            )
            if (response.code in 200..299) {
                val json = JSONObject(response.body)
                val deviceId = json.optString("device_id")
                val access = json.optString("access_token")
                if (deviceId.isNotBlank() && access.isNotBlank()) {
                    ProbeLog.i(TAG, "authorization succeeded device=…${deviceId.takeLast(12)}")
                    return Session(deviceId, access, json.optString("refresh_token")).also(::saveSession)
                }
            }
            when (runCatching { JSONObject(response.body).optString("error") }.getOrDefault("")) {
                "authorization_pending", "" -> Unit
                "slow_down" -> interval += 2_000L
                "expired_token", "access_denied" -> break
                else -> if (response.code !in 400..499) break
            }
        }
        throw IllegalStateException("RDC authorization expired or denied")
    }

    private suspend fun fetchRdcUserId(info: McpInfo, session: Session): String {
        val response = authorizedRequest(info, session, "GET", "/rest/v1/mcp_devices?id=eq.${encode(session.deviceId)}&select=user_id&limit=1")
        ensureAuthorized(response)
        check(response.code in 200..299) { "owner lookup HTTP ${response.code}" }
        val rows = JSONArray(response.body)
        val userId = rows.optJSONObject(0)?.optString("user_id").orEmpty()
        require(userId.isNotBlank()) { "RDC owner lookup returned no user_id" }
        return userId
    }

    private suspend fun heartbeat(info: McpInfo, session: Session) {
        val capabilities = JSONObject()
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("ai_limbs", true)
            .put("transport_broadcast_v1", true)
        val body = JSONObject()
            .put("status", "online")
            .put("last_seen", nowIso())
            .put("device_name", deviceName())
            .put("capabilities", capabilities)
        val response = authorizedRequest(
            info, session, "PATCH",
            "/rest/v1/mcp_devices?id=eq.${encode(session.deviceId)}",
            body, "return=minimal"
        )
        ensureAuthorized(response)
        check(response.code in 200..299) { "heartbeat HTTP ${response.code}" }
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
            else -> error("unsupported method $method")
        }
        return executeHttp(builder.build())
    }

    private fun ensureAuthorized(response: HttpResponse) {
        if (response.code == 401 || response.code == 403) throw UnauthorizedException()
    }

    private suspend fun postJson(url: String, body: JSONObject): HttpResponse = executeHttp(
        Request.Builder().url(url).header("Content-Type", JSON_MEDIA_TYPE_STRING)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
    )

    private suspend fun executeHttp(request: Request): HttpResponse = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { HttpResponse(it.code, it.body?.string().orEmpty()) }
    }

    private fun saveSession(session: Session) {
        prefs.edit().putString(KEY_DEVICE_ID, session.deviceId).putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken).apply()
    }

    private fun setState(
        phase: String,
        detail: String,
        deviceId: String? = ProbeRuntime.state.value.deviceId,
        userCode: String? = ProbeRuntime.state.value.userCode,
        verificationUri: String? = ProbeRuntime.state.value.verificationUri,
        socketSinceMs: Long? = ProbeRuntime.state.value.socketSinceMs,
        lastHeartbeatAtMs: Long? = ProbeRuntime.state.value.lastHeartbeatAtMs
    ) {
        ProbeRuntime.update {
            it.copy(
                phase = phase,
                detail = detail,
                deviceId = deviceId,
                userCode = userCode,
                verificationUri = verificationUri,
                socketSinceMs = socketSinceMs,
                lastHeartbeatAtMs = lastHeartbeatAtMs
            )
        }
        onStateChanged()
        ProbeLog.d(TAG, "state=$phase detail=$detail")
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val exp = (attempt - 1).coerceIn(0, 6)
        return min(RECONNECT_MAX_DELAY_MS, RECONNECT_BASE_DELAY_MS * (1L shl exp))
    }

    private fun deviceName(): String = "FreecessProbe ${BuildConfig.PROBE_LABEL} ${Build.MODEL}".take(80)
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
    private fun sha256UrlSafe(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.US_ASCII))
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
    private fun nowIso(): String = synchronized(formatter) {
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        formatter.format(Date())
    }

    private data class McpInfo(val supabaseUrl: String, val anonKey: String)
    private data class Session(val deviceId: String, val accessToken: String, val refreshToken: String)
    private data class DeviceAuth(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresInSeconds: Long,
        val pollIntervalSeconds: Long,
        val verifier: String
    )
    private data class HttpResponse(val code: Int, val body: String)
    private class UnauthorizedException : IllegalStateException()
    private class SchedulerGapException(val gapMs: Long) : IllegalStateException("scheduler gap $gapMs")
    private class SuspendDetectedException(val deltaMs: Long) : IllegalStateException("suspend delta $deltaMs")

    companion object {
        private const val TAG = "RDC"
        private const val MCP_BASE_URL = "https://mcp.desktopcommander.app"
        private const val DEVICE_CLIENT_ID = "mcp-device"
        private const val DEVICE_SCOPE = "mcp:tools"
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val ONLINE_STALE_AFTER_MS = 45_000L
        private const val TRANSPORT_TICK_MS = 500L
        private const val SCHEDULER_GAP_RECONNECT_MS = 30_000L
        private const val SUSPEND_DETECT_THRESHOLD_MS = 5_000L
        private const val FAST_RECONNECT_DELAY_MS = 250L
        private const val REALTIME_JOIN_TIMEOUT_MS = 15_000L
        private const val REALTIME_HEARTBEAT_ACK_TIMEOUT_MS = 5_000L
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 120_000L
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val JSON_MEDIA_TYPE_STRING = "application/json; charset=utf-8"
        private val JSON_MEDIA_TYPE = JSON_MEDIA_TYPE_STRING.toMediaType()
        private val EMPTY_JSON_BODY = "{}".toRequestBody(JSON_MEDIA_TYPE)
        private val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    }
}
