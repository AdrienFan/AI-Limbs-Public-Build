package com.ai.limbs.extensions.chatgptprobe

import com.ai.assistance.operit.integrations.ailimbs.BridgeRemoteIngress
import com.ai.limbs.plugin.runtime.ChildExtensionHost
import android.graphics.BitmapFactory
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeHostSignal
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeNetworkState
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.update
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class McpGatewayState(
    val running: Boolean = false,
    val phase: String = "STOPPED",
    val detail: String = "AI Limbs-ChatGPT 已停止",
    val pollCount: Long = 0,
    val commandCount: Long = 0,
    val responseCount: Long = 0,
    val lastMethod: String? = null,
    val lastStatusCode: Int? = null,
    val lastServiceRequestId: String? = null,
    val lastError: String? = null,
    val lastSuccessfulPollAtMs: Long? = null,
    val lastResponseAckAtMs: Long? = null,
    val lastToolsListAtMs: Long? = null,
    val lastCapabilityResultAtMs: Long? = null,
    val lastDeliveryError: String? = null,
    val rejectedCommandCount: Long = 0,
    val lastCommandError: String? = null,
    val access: GatewayAccessObservation = GatewayAccessObservation()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("running", running)
        .put("phase", phase)
        .put("detail", detail)
        .put("transport", "ANDROID_OKHTTP_MCP_TUNNEL")
        .put("poll_count", pollCount)
        .put("command_count", commandCount)
        .put("response_count", responseCount)
        .put("last_method", lastMethod ?: JSONObject.NULL)
        .put("last_status_code", lastStatusCode ?: JSONObject.NULL)
        .put("last_service_request_id", lastServiceRequestId ?: JSONObject.NULL)
        .put("last_error", lastError ?: JSONObject.NULL)
        .put("last_successful_poll_at_ms", lastSuccessfulPollAtMs ?: JSONObject.NULL)
        .put("last_response_ack_at_ms", lastResponseAckAtMs ?: JSONObject.NULL)
        .put("last_tools_list_at_ms", lastToolsListAtMs ?: JSONObject.NULL)
        .put("last_capability_result_at_ms", lastCapabilityResultAtMs ?: JSONObject.NULL)
        .put("last_delivery_error", lastDeliveryError ?: JSONObject.NULL)
        .put("rejected_command_count", rejectedCommandCount)
        .put("last_command_error", lastCommandError ?: JSONObject.NULL)
        .put("access_observations", access.toJson())
        .put("runtime_key_exposed", false)
        .put("wire_protocol_version", WIRE_PROTOCOL_VERSION)
        .put("probe_version", PROBE_VERSION)

    companion object {
        const val WIRE_PROTOCOL_VERSION = "2026-08-25"
        // Package metadata is authoritative; copied version literals drift on upgrade.
        const val PROBE_VERSION = BuildConfig.VERSION_NAME
    }
}

internal class ChatGptNativeProbeEngine(
    private val storage: GatewayConfiguration,
    private val encryptedStore: GatewayBlobStore,
    private val httpClient: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build(),
    private val imageValidator: ((ByteArray, String) -> Boolean)? = null,
    private val warn: (String) -> Unit = {}
) {
    constructor(host: ChildExtensionHost) : this(
        ChatGptNativeProbeStorage(host.applicationContext), GatewayEncryptedStore(host.applicationContext),
        imageValidator = { bytes, mime ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000L && bounds.outMimeType == mime
        }, warn = { message -> host.logger.w(TAG, message) }
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(McpGatewayState())
    private val lifecycleLock = Any()
    private val instanceId = newInstanceId()
    private val generation = AtomicLong()
    private val admissionLock = Any()
    private val receiptJournal = lazy { GatewayReceipts(encryptedStore) }
    private val receipts by receiptJournal
    private data class InvokeIdEntry(val invokeId: String, val observedAtMs: Long)
    private val invokeIds = ConcurrentHashMap<String, InvokeIdEntry>()
    private val resultAdapters = ConcurrentHashMap<String, GatewayResults>()
    private fun results(identity: String): GatewayResults = resultAdapters.computeIfAbsent(identity) {
        if (imageValidator == null) GatewayResults(encryptedStore, binding = identity)
        else GatewayResults(encryptedStore, binding = identity, validImage = imageValidator)
    }
    private val businessSlots = Semaphore(4)
    private val deliveryWake = Channel<Unit>(Channel.CONFLATED)
    private val requestTimings = GatewayTimings()
    private data class ActiveRequest(val job: Job, val rpcKey: String, val channel: String, val binding: String)
    private val activeRequests = ConcurrentHashMap<String, ActiveRequest>()
    @Volatile private var pollCall: Call? = null

    @Volatile
    private var loopJob: Job? = null

    @Volatile
    private var remoteIngress: BridgeRemoteIngress? = null

    fun bindRemoteIngress(value: BridgeRemoteIngress) {
        remoteIngress = value
    }

    val state: StateFlow<McpGatewayState> = mutableState

    val activeRequestCount: Int get() = activeRequests.size

    // Rendering an unopened configuration must not initialize Keystore or the receipt journal.
    fun uiReceiptCounts(config: ChatGptProbeConfig): JSONObject? =
        if (receiptJournal.isInitialized()) receipts.countsFor(binding(config)) else null

    fun start(): Boolean = synchronized(lifecycleLock) {
        if (loopJob?.isActive == true) return false

        val config = storage.readConfig()
        require(config.secureStorageAvailable) { "Android secure storage is unavailable" }
        require(config.configured) { "Tunnel ID / Runtime API Key 尚未配置" }
        require(remoteIngress != null) { "BridgeRemoteIngress 尚未绑定；Bridge Provider 尚未就绪" }

        invokeIds.clear()
        mutableState.value = mutableState.value.copy(
            running = true,
            phase = "STARTING",
            detail = "正在连接 AI Limbs-ChatGPT",
            lastError = null,
            lastToolsListAtMs = null,
            lastCapabilityResultAtMs = null,
            access = GatewayAccessObservation(startedAtMs = System.currentTimeMillis())
        )
        val epoch = generation.incrementAndGet()
        loopJob = scope.launch {
            try {
                // Ledger recovery/migration and Keystore I/O must not block the UI thread.
                receipts.counts()
                encryptedStore.write("storage_probe", "{}")
                check(encryptedStore.read("storage_probe") == "{}") { "Encrypted result storage verification failed" }
                encryptedStore.delete("storage_probe")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                update(epoch) { it.copy(running = false, phase = "ERROR",
                    detail = "Durable gateway storage could not be initialized", lastError = error.javaClass.simpleName) }
                return@launch
            }
            supervisorScope {
                val delivery = launch { deliveryLoop(epoch) }
                try { pollLoop(epoch, this) } finally { delivery.cancel() }
            }
        }
        true
    }

    fun stop(): Boolean = synchronized(lifecycleLock) {
        val job = loopJob
        val wasRunning = job?.isActive == true
        loopJob = null
        generation.incrementAndGet()
        job?.cancel()
        httpClient.dispatcher.cancelAll()
        mutableState.value = mutableState.value.copy(
            running = false,
            phase = "STOPPED",
            detail = "AI Limbs-ChatGPT 已停止"
        )
        wasRunning
    }

    fun statusJson(): JSONObject {
        val config = storage.readConfig()
        return JSONObject()
            .put("success", true)
            .put("configured", config.configured)
            .put("secure_storage_available", config.secureStorageAvailable)
            .put("tunnel_id_present", config.tunnelId.isNotBlank())
            .put("base_url", config.baseUrl)
            .put("runtime_key_exposed", false)
            .put("state", mutableState.value.toJson())
            .put("ingress_bound", remoteIngress != null)
            .put("active_requests", activeRequests.size)
            .put("request_timings", requestTimings.snapshot())
            .put("receipts", receipts.counts())
            .put("dedup_retention_ms", GatewayReceipts.RETENTION_MS)
            .put("supported_mcp_versions", JSONArray(GatewayProtocol.supportedVersions.toList()))
            .put("tool_catalog_sha256", gatewayHash(toolDefinitions().toString()))
            .put("tools_list_requested", mutableState.value.lastToolsListAtMs != null)
            .put("client_catalog_refresh_verified", false)
            .put("access", accessStatus(config).toJson())
    }

    fun accessStatus(config: ChatGptProbeConfig): GatewayAccessStatus =
        GatewayAdmission.evaluate(mutableState.value, config, remoteIngress != null)

    fun close() {
        stop()
        scope.coroutineContext[Job]?.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    fun verifyLiveness() {
        if (!state.value.running) { start(); return }
        val last = state.value.lastSuccessfulPollAtMs ?: 0L
        if (System.currentTimeMillis() - last > 45_000L) pollCall?.cancel()
    }

    fun onHostSignal(signal: AiLimbsBridgeHostSignal) {
        when (signal) {
            is AiLimbsBridgeHostSignal.NetworkChanged -> {
                // Interrupt transport only; an in-progress Host capability must not be restarted.
                if (signal.state == AiLimbsBridgeNetworkState.LOST || signal.state == AiLimbsBridgeNetworkState.VALIDATED) pollCall?.cancel()
            }
            AiLimbsBridgeHostSignal.ScreenOn -> if (state.value.running) verifyLiveness()
            else -> Unit
        }
    }

    private fun update(epoch: Long, transform: (McpGatewayState) -> McpGatewayState) {
        if (generation.get() == epoch) mutableState.update { if (generation.get() == epoch) transform(it) else it }
    }

    private fun binding(config: ChatGptProbeConfig): String = gatewayHash(config.baseUrl + "\n" + config.tunnelId)

    private class TunnelHttpException(val status: Int, val retryAfterMs: Long = 0L) : IOException("Tunnel HTTP $status") {
        val authenticationFailure get() = status == 401 || status == 403
        val retryable get() = status == 408 || status == 429 || status >= 500
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun execute(request: Request, polling: Boolean = false): Response {
        val call = httpClient.newCall(request)
        if (polling) pollCall = call
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
    }

    private fun retryDelay(attempt: Long, error: Throwable): Long {
        val requested = (error as? TunnelHttpException)?.retryAfterMs ?: 0L
        return maxOf(requested.coerceAtMost(300_000L), attempt + java.util.concurrent.ThreadLocalRandom.current().nextLong(250L))
    }

    private fun httpFailure(response: Response): TunnelHttpException = TunnelHttpException(
        response.code, response.header("Retry-After")?.toLongOrNull()?.times(1_000L)?.coerceAtLeast(0L) ?: 0L
    )

    private suspend fun pollLoop(epoch: Long, workers: CoroutineScope) {
        var backoff = INITIAL_RETRY_MS
        while (currentCoroutineContext().isActive) {
            try {
                val config = storage.readConfig()
                val apiKey = storage.readApiKey() ?: error("Runtime API Key unavailable")
                val detail = pollOnce(config, apiKey, epoch, workers)
                backoff = INITIAL_RETRY_MS
                delay(250L)
                update(epoch) { it.copy(running = true, phase = "ONLINE", detail = detail, lastError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!currentCoroutineContext().isActive) return
                val http = error as? TunnelHttpException
                if (http != null && !http.retryable) {
                    update(epoch) { it.copy(running = false, phase = if (http.authenticationFailure) "AUTH_REQUIRED" else "ERROR",
                        detail = "Tunnel configuration requires attention", lastError = http.message) }
                    return
                }
                update(epoch) { it.copy(phase = "RETRYING", detail = "Tunnel poll interrupted; completed results remain queued",
                    lastError = error.javaClass.simpleName) }
                delay(retryDelay(backoff, error))
                backoff = (backoff * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    private suspend fun pollOnce(config: ChatGptProbeConfig, apiKey: String, epoch: Long, workers: CoroutineScope): String {
        val url = config.baseUrl.toHttpUrl().newBuilder().addPathSegments("v1/tunnels").addPathSegment(config.tunnelId)
            .addPathSegment("poll").addQueryParameter("limit", POLL_LIMIT.toString())
            .addQueryParameter("timeout_ms", POLL_TIMEOUT_MS.toString()).build()
        execute(commonHeaders(Request.Builder().url(url).get(), apiKey).build(), polling = true).use { response ->
            update(epoch) { it.copy(pollCount = it.pollCount + 1, lastStatusCode = response.code,
                lastServiceRequestId = response.header("x-request-id")) }
            if (response.code != 200 && response.code != 204) throw httpFailure(response)
            update(epoch) { it.copy(lastSuccessfulPollAtMs = System.currentTimeMillis()) }
            if (response.code == 204) return "Tunnel poll healthy; no commands"
            val commands = JSONObject(response.body?.string().orEmpty()).optJSONArray("commands") ?: JSONArray()
            val receivedAt = System.nanoTime()
            for (index in 0 until commands.length()) {
                try { acceptCommand(config, commands.getJSONObject(index), epoch, workers, receivedAt) }
                catch (error: Exception) {
                    update(epoch) { it.copy(rejectedCommandCount = it.rejectedCommandCount + 1, lastCommandError = "Command rejected before execution: ${error.javaClass.simpleName}") }
                    warn("Gateway command rejected before execution: ${error.javaClass.simpleName}")
                }
            }
            return "Tunnel poll healthy; received ${commands.length()} commands; inspect rejection and delivery counters"
        }
    }

    private fun acceptCommand(config: ChatGptProbeConfig, input: JSONObject, epoch: Long, workers: CoroutineScope, receivedAt: Long): Unit = synchronized(admissionLock) {
        val command = JSONObject(input.toString())
        require(command.opt("request_id") is String && command.opt("shard_token") is String && command.optString("request_id").isNotBlank() && command.optString("shard_token").isNotBlank()) { "Invalid tunnel command identity" }
        command.put("channel", command.optString("channel").ifBlank { DEFAULT_CHANNEL })
        require(command.getString("command_type") in setOf("jsonrpc", "session_termination")) { "Unsupported tunnel command type" }
        val rpc = command.optJSONObject("jsonrpc")
        val control = command.getString("command_type") != "jsonrpc" || rpc?.optString("method") != "tools/call" ||
            rpc?.optJSONObject("params")?.optString("name") !in setOf(TOOL_SEARCH, TOOL_DESCRIBE, TOOL_INVOKE)
        // Never accept more business work than can be retained in memory. Control traffic stays responsive.
        val identity = binding(config)
        val (receiptId, fresh) = receipts.claim(identity, command)
        update(epoch) { it.copy(commandCount = it.commandCount + 1) }
        if (!fresh) {
            deliveryWake.trySend(Unit)
            return@synchronized
        }
        requestTimings.accepted(receiptId, rpc?.optJSONObject("params")?.optString("name")
            ?.takeIf { it in setOf(TOOL_SEARCH, TOOL_DESCRIBE, TOOL_INVOKE, TOOL_RESULT_READ, TOOL_MEDIA_READ, TOOL_STATUS) }
            ?: "protocol", receivedAt)
        if (!control && activeRequests.size >= 32) {
            val answer = GatewayProtocol.delivery(command, GatewayProtocol.error(rpc?.opt("id"), -32001, "Gateway busy; this request was not executed"))
            observeProtocolResponse(answer, epoch)
            receipts.ready(receiptId, answer)
            deliveryWake.trySend(Unit)
            return@synchronized
        }
        val rpcKey = canonicalJson(rpc?.opt("id"))
        val job = workers.launch(start = CoroutineStart.LAZY) {
            try {
                val answer = if (control) {
                    requestTimings.mark(receiptId, "started")
                    processCommand(command, identity, epoch)
                } else businessSlots.withPermit {
                    requestTimings.mark(receiptId, "started")
                    val arguments = rpc?.optJSONObject("params")?.optJSONObject("arguments")
                    val requestedTimeout = arguments?.opt("timeout_ms") as? Number
                    val deadline = if (rpc?.optJSONObject("params")?.optString("name") == TOOL_INVOKE && requestedTimeout != null)
                        requestedTimeout.toLong().coerceIn(1_000L, 1_800_000L) else 300_000L
                    withTimeout(deadline) { processCommand(command, identity, epoch) }
                }
                requestTimings.mark(receiptId, "processed")
                receipts.ready(receiptId, answer)
                requestTimings.mark(receiptId, "ready")
                deliveryWake.trySend(Unit)
            } catch (cancelled: CancellationException) {
                val reason = if (cancelled is TimeoutCancellationException) "TIMEOUT" else "CANCELLED_OR_STOPPED"
                receipts.ready(receiptId, GatewayProtocol.delivery(command, rpc?.opt("id")?.takeIf { it != JSONObject.NULL }?.let { GatewayProtocol.uncertain(it, reason) }))
                deliveryWake.trySend(Unit)
                throw cancelled
            } catch (error: Exception) {
                // Do not convert persistence failure into an automatic capability retry.
                warn("Gateway request failed: ${error.javaClass.simpleName}; receipt retained")
                update(epoch) { it.copy(lastError = "Request failed: ${error.javaClass.simpleName}; inspect retained receipt") }
            } finally { activeRequests.remove(receiptId) }
        }
        activeRequests[receiptId] = ActiveRequest(job, rpcKey, command.getString("channel"), identity)
        job.invokeOnCompletion { activeRequests.remove(receiptId) }
        job.start()
        Unit
    }

    private suspend fun processCommand(command: JSONObject, identity: String, epoch: Long): JSONObject {
        val answer = processRpcCommand(command, identity, epoch)
        observeProtocolResponse(answer, epoch)
        return answer
    }

    private fun observeProtocolResponse(answer: JSONObject, epoch: Long) {
        val error = answer.optJSONObject("resp_json")?.optJSONObject("error")
        if (error != null) {
            val unadvertised = error.optJSONObject("data")?.optString("gateway_error_code") == "TOOL_NOT_ADVERTISED"
            update(epoch) { probe -> probe.copy(access = probe.access.copy(
                protocolErrorCount = probe.access.protocolErrorCount + 1,
                lastProtocolErrorCode = error.getInt("code"),
                unadvertisedToolCount = probe.access.unadvertisedToolCount + if (unadvertised) 1 else 0,
                lastUnadvertisedToolAtMs = if (unadvertised) System.currentTimeMillis() else probe.access.lastUnadvertisedToolAtMs,
                catalogMismatchSuspected = unadvertised || probe.access.catalogMismatchSuspected
            )) }
        }
    }

    private suspend fun processRpcCommand(command: JSONObject, identity: String, epoch: Long): JSONObject {
        val channel = command.getString("channel")
        if (command.getString("command_type") == "session_termination") {
            activeRequests.values.filter { it.binding == identity && it.channel == channel && it.rpcKey != "null" }.forEach { it.job.cancel() }
            return GatewayProtocol.delivery(command)
        }
        val rpc = command.optJSONObject("jsonrpc") ?: return GatewayProtocol.delivery(command, GatewayProtocol.error(null, -32600, "Missing JSON-RPC payload"))
        if (!GatewayProtocol.validRequest(rpc)) return GatewayProtocol.delivery(command, GatewayProtocol.error(null, -32600, "Invalid JSON-RPC request"))
        val method = rpc.getString("method")
        update(epoch) { it.copy(lastMethod = method) }
        if (!rpc.has("id")) {
            if (method == "notifications/cancelled") {
                val target = rpc.optJSONObject("params")?.opt("requestId")
                if (target is String || target is Number) activeRequests.values.filter {
                    it.binding == identity && it.channel == channel && it.rpcKey == canonicalJson(target)
                }.forEach { it.job.cancel() }
            }
            return GatewayProtocol.delivery(command)
        }
        val id = rpc.get("id")
        val answer = when (method) {
            "initialize" -> {
                val version = rpc.optJSONObject("params")?.optString("protocolVersion").orEmpty()
                if (version !in GatewayProtocol.supportedVersions) rpcError(id, -32602, "Unsupported protocolVersion; supported: ${GatewayProtocol.supportedVersions.joinToString()}")
                else {
                    update(epoch) { it.copy(access = it.access.copy(initializeCount = it.access.initializeCount + 1,
                        lastInitializeAtMs = System.currentTimeMillis())) }
                    rpcSuccess(id, initializeResult(version))
                }
            }
            "ping" -> rpcSuccess(id, JSONObject())
            "tools/list" -> {
                update(epoch) { it.copy(lastToolsListAtMs = System.currentTimeMillis()) }
                rpcSuccess(id, JSONObject().put("tools", toolDefinitions()))
            }
            "tools/call" -> handleToolCall(id, rpc.optJSONObject("params"), epoch, identity)
            else -> rpcError(id, -32601, "Method not found: $method")
        }
        return GatewayProtocol.delivery(command, answer)
    }

    private data class DeliveryRetry(val shardToken: String, val nextAttemptAtNs: Long, val backoffMs: Long)

    private suspend fun deliveryLoop(epoch: Long): Unit = supervisorScope {
        val deliveries = ConcurrentHashMap<String, Job>()
        val retries = ConcurrentHashMap<String, DeliveryRetry>()
        val slots = Semaphore(4)
        var backoff = INITIAL_RETRY_MS
        while (currentCoroutineContext().isActive) {
            try {
                val config = storage.readConfig()
                val identity = binding(config)
                synchronized(admissionLock) {
                    receipts.list(identity, "EXECUTING").filter { !activeRequests.containsKey(it.first) }.forEach { (id, record) ->
                        val command = record.getJSONObject("command")
                        val rpcId = command.optJSONObject("jsonrpc")?.opt("id")?.takeIf { it != JSONObject.NULL }
                        receipts.ready(id, GatewayProtocol.delivery(command, rpcId?.let { GatewayProtocol.uncertain(it, "PROCESS_INTERRUPTED") }))
                    }
                }
                val apiKey = storage.readApiKey() ?: error("Runtime API Key unavailable")
                // Independent completed responses must not wait behind a slow HTTP POST.
                // Backoff occupies no HTTP slot, and only persisted responses are retried.
                for ((id, record) in receipts.list(identity, "READY")) {
                    if (deliveries.containsKey(id)) continue
                    val shard = record.getJSONObject("command").getString("shard_token")
                    val retry = retries[id]?.takeIf { it.shardToken == shard }
                    if (retry != null && System.nanoTime() < retry.nextAttemptAtNs) continue
                    if (!slots.tryAcquire()) continue
                    val job = launch(start = CoroutineStart.LAZY) {
                        try {
                            val next = deliverReceipt(id, record, config, apiKey, epoch, retry?.backoffMs ?: INITIAL_RETRY_MS)
                            if (next == null) retries.remove(id) else retries[id] = next
                        } finally { deliveries.remove(id); slots.release(); deliveryWake.trySend(Unit) }
                    }
                    deliveries[id] = job
                    job.start()
                }
                backoff = INITIAL_RETRY_MS
                withTimeoutOrNull(750L) { deliveryWake.receive() }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                update(epoch) { it.copy(lastDeliveryError = "Delivery interrupted: ${error.javaClass.simpleName}; results retained") }
                delay(retryDelay(backoff, error))
                backoff = (backoff * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    private suspend fun deliverReceipt(id: String, record: JSONObject, config: ChatGptProbeConfig, apiKey: String,
        epoch: Long, backoff: Long): DeliveryRetry? {
        val shard = record.getJSONObject("command").getString("shard_token")
        fun retry(error: Exception, waitMs: Long = retryDelay(backoff, error)): DeliveryRetry =
            DeliveryRetry(shard, System.nanoTime() + waitMs * 1_000_000L, (backoff * 2).coerceAtMost(MAX_RETRY_MS))
        try {
            requestTimings.mark(id, "posting")
            postTunnelResponse(config, apiKey, shard, record.getJSONObject("response"), epoch)
            requestTimings.mark(id, "posted")
            if (receipts.delivered(id, shard)) requestTimings.mark(id, "acked")
            return null
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            update(epoch) { it.copy(lastDeliveryError = "Result delivery ${error.javaClass.simpleName}; execution will not be repeated") }
            if (error is TunnelHttpException && !error.retryable) {
                if (error.status == 401) return retry(error, 30_000L)
                try {
                    receipts.mark(id, "DELIVERY_FAILED", shard)
                    return null
                } catch (persistence: Exception) {
                    warn("Delivery rejection could not be persisted: ${persistence.javaClass.simpleName}; receipt retained")
                    return retry(persistence)
                }
            }
            return retry(error)
        }
    }

    private fun initializeResult(protocol: String): JSONObject = JSONObject()
        .put("protocolVersion", protocol)
        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
        .put("serverInfo", JSONObject().put("name", "ai-limbs-chatgpt-gateway")
            .put("title", ChatGptNativeProbePanel.TITLE).put("version", McpGatewayState.PROBE_VERSION))
        .put("instructions", "Use only the advertised ai_limbs_* tools. If tool metadata is stale, refresh the custom MCP connection in ChatGPT and start a new conversation. Search capabilities, describe the exact ID, then invoke with its schema. Host policy and next_action are authoritative. Read saved pages/images instead of repeating actions. Inspect domain state when execution_state is UNKNOWN.")

    // Derive the guide from the advertised catalog so UI and protocol cannot drift apart.
    internal fun advertisedToolNames(): List<String> {
        val tools = toolDefinitions()
        return (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
    }

    private fun toolDefinitions(): JSONArray = JSONArray()
        .put(
            tool(
                name = TOOL_SEARCH,
                description = "Search the current live AI Limbs capability catalog without executing a capability. Use this whenever the exact capability ID is unknown.",
                inputSchema = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "query",
                                JSONObject()
                                    .put("type", "string")
                                    .put("minLength", 1)
                                    .put("description", "Capability intent or known tool/module name")
                            )
                            .put(
                                "scope",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "Optional dynamic capability scope ID such as plugin:plugin.example")
                            )
                            .put(
                                "limit",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 1)
                                    .put("maximum", 20)
                                    .put("default", 8)
                            )
                    )
                    .put("required", JSONArray().put("query"))
                    .put("additionalProperties", false)
            )
        )
        .put(
            tool(
                name = TOOL_DESCRIBE,
                description = "Describe one live AI Limbs capability, including its exact invocation ID, schema, permissions, prerequisites, and availability.",
                inputSchema = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "capability_id",
                            JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("description", "Capability ID returned by ai_limbs_capability_search")
                        )
                    )
                    .put("required", JSONArray().put("capability_id"))
                    .put("additionalProperties", false)
            )
        )
        .put(
            tool(
                name = TOOL_INVOKE,
                description = "Invoke any current AI Limbs capability by catalog capability ID or exact invoke ID. Catalog IDs are resolved through the live AI Limbs resolver; Host execution policy remains authoritative.",
                inputSchema = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "capability_id",
                                JSONObject()
                                    .put("type", "string")
                                    .put("minLength", 1)
                                    .put("description", "Capability ID returned by search, or an exact invoke ID")
                            )
                            .put("timeout_ms", JSONObject().put("type", "integer").put("minimum", 1000).put("maximum", 1800000).put("default", 300000)
                                .put("description", "Gateway execution deadline; does not undo effects already applied. Prefer domain background tasks for long operations."))
                            .put(
                                "parameters",
                                JSONObject()
                                    .put("type", "object")
                                    .put("description", "Parameters matching the capability schema")
                                    .put("additionalProperties", true)
                            )
                    )
                    .put("required", JSONArray().put("capability_id"))
                    .put("additionalProperties", false)
            )
        )
        .put(tool(TOOL_RESULT_READ, "Read another immutable result page. Offsets use UTF-16 units; use next_offset from the previous page. Never reinvoke the original action to retrieve a result.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("cursor", JSONObject().put("type", "string").put("minLength", 1))
                .put("offset", JSONObject().put("type", "integer").put("minimum", 0)))
                .put("required", JSONArray().put("cursor").put("offset")).put("additionalProperties", false)))
        .put(tool(TOOL_MEDIA_READ, "Retrieve a saved image by media_id without executing its originating capability. Cached media expires after ten minutes.",
            JSONObject().put("type", "object").put("properties", JSONObject().put("media_id", JSONObject().put("type", "string").put("minLength", 1)))
                .put("required", JSONArray().put("media_id")).put("additionalProperties", false)))
        .put(tool(TOOL_STATUS, "Inspect gateway transport, current-listener access observations, protocol errors, capability outcomes and delivery health. Includes official custom MCP metadata refresh steps; cannot refresh ChatGPT's catalog automatically.",
            JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false)))

    private suspend fun handleToolCall(id: Any, params: JSONObject?, epoch: Long, identity: String): JSONObject {
        val results = results(identity)
        val name = params?.optString("name")?.trim().orEmpty()
        if (params == null || params.opt("name") !is String || (params.has("arguments") && params.opt("arguments") !is JSONObject)) return rpcError(id, -32602, "Invalid tools/call parameters")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()

        var executionStarted = false
        var receivedInvokeResult: JSONObject? = null
        return try {
            val allowed = when (name) {
                TOOL_SEARCH -> setOf("query", "scope", "limit")
                TOOL_DESCRIBE -> setOf("capability_id")
                TOOL_INVOKE -> setOf("capability_id", "parameters", "timeout_ms")
                TOOL_RESULT_READ -> setOf("cursor", "offset")
                TOOL_MEDIA_READ -> setOf("media_id")
                TOOL_STATUS -> emptySet()
                else -> return unadvertisedToolError(id)
            }
            require(arguments.keys().asSequence().all { it in allowed }) { "Unknown gateway argument" }
            listOf("query", "scope", "capability_id", "cursor", "media_id").forEach { field ->
                require(!arguments.has(field) || arguments.opt(field) is String) { "$field must be a string" }
            }
            listOf("limit", "offset", "timeout_ms").forEach { field ->
                if (arguments.has(field)) {
                    val number = arguments.opt(field)
                    require(number is Number && number.toDouble() == number.toLong().toDouble()) { "$field must be an integer" }
                }
            }
            when (name) {
                TOOL_RESULT_READ -> {
                    val offset = arguments.getLong("offset")
                    require(offset in 0L..Int.MAX_VALUE.toLong()) { "offset is outside the supported range" }
                    val cursor = arguments.getString("cursor")
                    require(cursor.isNotBlank()) { "cursor is required" }
                    recordAdvertisedToolCall(epoch)
                    return rpcSuccess(id, results.pageResult(cursor, offset.toInt()))
                }
                TOOL_MEDIA_READ -> {
                    val mediaId = arguments.getString("media_id")
                    require(mediaId.isNotBlank()) { "media_id is required" }
                    recordAdvertisedToolCall(epoch)
                    return rpcSuccess(id, results.readMedia(mediaId))
                }
                TOOL_STATUS -> {
                    recordAdvertisedToolCall(epoch)
                    return rpcSuccess(id, results.adapt(statusJson()))
                }
            }
            val result = when (name) {
                TOOL_SEARCH -> {
                    val query = arguments.optString("query").trim()
                    require(query.isNotBlank()) { "query is required" }
                    val request = JSONObject().put("query", query)
                    val scope = arguments.optString("scope").trim()
                    if (scope.isNotBlank()) request.put("scope", scope)
                    if (arguments.has("limit")) {
                        val limit = arguments.getLong("limit")
                        require(limit in 1L..20L) { "limit must be in 1..20" }
                        request.put("limit", limit.toInt())
                    }
                    executionStarted = true
                    recordAdvertisedToolCall(epoch)
                    invokeAiLimbs("capability.search", request).also(::rememberInvokeIds)
                }

                TOOL_DESCRIBE -> {
                    val capabilityId = arguments.optString("capability_id").trim()
                    require(capabilityId.isNotBlank()) { "capability_id is required" }
                    executionStarted = true
                    recordAdvertisedToolCall(epoch)
                    invokeAiLimbs(
                        "capability.describe",
                        JSONObject().put("capability_id", capabilityId)
                    ).also(::rememberInvokeIds)
                }

                TOOL_INVOKE -> {
                    val capabilityId = arguments.optString("capability_id").trim()
                    require(capabilityId.isNotBlank()) { "capability_id is required" }
                    if (arguments.has("timeout_ms")) {
                        val timeout = arguments.opt("timeout_ms")
                        require(timeout is Number && timeout.toDouble() == timeout.toLong().toDouble() && timeout.toLong() in 1000L..1800000L) { "timeout_ms must be an integer in 1000..1800000" }
                    }
                    require(!arguments.has("parameters") || arguments.opt("parameters") is JSONObject) { "parameters must be an object" }
                    val parameters = arguments.optJSONObject("parameters") ?: JSONObject()
                    recordAdvertisedToolCall(epoch)
                    val invokeId = resolveInvokeId(capabilityId)
                    executionStarted = true
                    update(epoch) { it.copy(access = it.access.copy(capabilityInvokeCount = it.access.capabilityInvokeCount + 1)) }
                    invokeAiLimbs(invokeId, JSONObject(parameters.toString()))
                }

                else -> return unadvertisedToolError(id)
            }
            if (name == TOOL_INVOKE) {
                receivedInvokeResult = result
                val failed = GatewayResults.failed(result)
                val uncertain = result.optString("execution_state") == "UNKNOWN"
                update(epoch) { it.copy(access = it.access.copy(
                    capabilitySuccessCount = it.access.capabilitySuccessCount + if (failed || uncertain) 0 else 1,
                    capabilityFailureCount = it.access.capabilityFailureCount + if (failed && !uncertain) 1 else 0,
                    capabilityUncertainCount = it.access.capabilityUncertainCount + if (uncertain) 1 else 0,
                    lastSuccessfulInvokeAtMs = if (failed || uncertain) it.access.lastSuccessfulInvokeAtMs else System.currentTimeMillis()
                )) }
            }
            update(epoch) { it.copy(lastCapabilityResultAtMs = System.currentTimeMillis()) }
            rpcSuccess(id, results.adapt(result))
        } catch (cancelled: CancellationException) {
            if (executionStarted && name == TOOL_INVOKE && receivedInvokeResult == null) recordUncertainInvoke(epoch)
            throw cancelled
        } catch (error: GatewayResultUnavailable) {
            // A missing freshly written page is preparation failure, not a new read request.
            val received = receivedInvokeResult
            if (received != null) return receivedResultDeliveryError(id, received, epoch)
            rpcSuccess(id, toolError(error.message ?: "Cached result is unavailable", JSONObject()
                .put("success", false).put("result_delivery_error", "CACHE_UNAVAILABLE")
                .put("automatic_reexecution", false)
                .put("next_action", "Inspect domain state or an existing output artifact. Do not repeat the original action automatically.")))
        } catch (error: IllegalArgumentException) {
            val received = receivedInvokeResult
            if (received != null) receivedResultDeliveryError(id, received, epoch)
            else if (executionStarted) {
                if (name == TOOL_INVOKE) recordUncertainInvoke(epoch)
                GatewayProtocol.uncertain(id, "INVOCATION_OR_RESULT_ERROR")
            }
            else rpcError(id, -32602, (error.message ?: "Invalid tool arguments").take(200))
        } catch (error: org.json.JSONException) {
            val received = receivedInvokeResult
            if (received != null) receivedResultDeliveryError(id, received, epoch)
            else if (executionStarted) {
                if (name == TOOL_INVOKE) recordUncertainInvoke(epoch)
                GatewayProtocol.uncertain(id, "INVOCATION_OR_RESULT_ERROR")
            }
            else rpcError(id, -32602, "Invalid tool argument type")
        } catch (error: Exception) {
            val received = receivedInvokeResult
            if (received != null) return receivedResultDeliveryError(id, received, epoch)
            if (name == TOOL_INVOKE && !executionStarted) {
                val safeMessage = "Capability ID resolution failed: ${error.javaClass.simpleName}; no business capability was started"
                return rpcSuccess(
                    id,
                    toolError(
                        message = safeMessage,
                        structured = JSONObject()
                            .put("success", false)
                            .put("bridge_error", safeMessage)
                            .put("gateway_error_code", "RESOLUTION_FAILED")
                            .put("execution_state", "NOT_STARTED")
                            .put("automatic_reexecution", false)
                            .put("next_action", "Search or describe the capability again, then retry explicitly.")
                    )
                )
            }
            if (executionStarted && name == TOOL_INVOKE) recordUncertainInvoke(epoch)
            val safeMessage = "Capability invocation failed: ${error.javaClass.simpleName}; inspect domain state before retrying"
            rpcSuccess(
                id,
                toolError(
                    message = safeMessage,
                    structured = JSONObject()
                        .put("success", false)
                        .put("bridge_error", safeMessage)
                        .put("execution_state", "UNKNOWN")
                        .put("automatic_reexecution", false)
                )
            )
        }
    }

    private fun recordAdvertisedToolCall(epoch: Long) = update(epoch) {
        it.copy(access = it.access.copy(advertisedToolCallCount = it.access.advertisedToolCallCount + 1,
            lastAdvertisedToolCallAtMs = System.currentTimeMillis(), catalogMismatchSuspected = false))
    }

    private fun recordUncertainInvoke(epoch: Long) = update(epoch) {
        it.copy(access = it.access.copy(capabilityUncertainCount = it.access.capabilityUncertainCount + 1))
    }

    private fun receivedResultDeliveryError(id: Any, source: JSONObject, epoch: Long): JSONObject {
        // Preserve known Host outcome even if caching/formatting failed after it returned.
        update(epoch) { it.copy(access = it.access.copy(resultPreparationFailureCount = it.access.resultPreparationFailureCount + 1)) }
        val details = JSONObject().put("success", false).put("execution_state", "RESULT_RECEIVED")
            .put("host_result_received", true).put("host_result_failed", GatewayResults.failed(source))
            .put("result_delivery_error", "RESULT_ADAPTATION_FAILED").put("automatic_reexecution", false)
            .put("next_action", "Inspect existing domain state or output artifacts; do not repeat the action automatically.")
        source.optJSONObject("execution_policy")?.let { details.put("execution_policy", it) }
        return rpcSuccess(id, toolError("Host returned a result, but result delivery preparation failed.", details))
    }

    private fun unadvertisedToolError(id: Any): JSONObject {
        // A stale client catalog is a possible cause, not proof. Never echo arbitrary input names.
        val tools = toolDefinitions()
        val names = JSONArray()
        for (index in 0 until tools.length()) names.put(tools.getJSONObject(index).getString("name"))
        return rpcError(id, -32602, "Tool is not advertised by AI Limbs-ChatGPT. Refresh custom MCP metadata and start a new conversation.")
            .apply { getJSONObject("error").put("data", JSONObject()
                .put("gateway_error_code", "TOOL_NOT_ADVERTISED")
                .put("possible_cause", "STALE_CLIENT_TOOL_CATALOG")
                .put("advertised_tools", names)
                .put("tool_catalog_sha256", gatewayHash(tools.toString()))
                .put("metadata_refresh_steps", JSONArray(GatewayAdmission.REFRESH_STEPS))
                .put("documentation_url", GatewayAdmission.DOCUMENTATION_URL)) }
    }

    private fun rememberInvokeIds(result: JSONObject) {
        rememberInvokeId(result)
        result.optJSONArray("results")?.let { items ->
            for (index in 0 until items.length()) items.optJSONObject(index)?.let(::rememberInvokeId)
        }
    }

    private fun rememberInvokeId(item: JSONObject) {
        if (item.has("success") && !item.optBoolean("success", true)) return
        val capabilityId = item.optString("capability_id").trim()
        val invokeId = item.optString("invoke_id").trim()
        if (capabilityId.isBlank() || invokeId.isBlank()) return
        val now = System.currentTimeMillis()
        val entry = InvokeIdEntry(invokeId, now)
        invokeIds[capabilityId] = entry
        invokeIds[invokeId] = entry
    }

    private fun cachedInvokeId(id: String): String? {
        val entry = invokeIds[id] ?: return null
        if (System.currentTimeMillis() - entry.observedAtMs <= INVOKE_ID_CACHE_TTL_MS) return entry.invokeId
        invokeIds.remove(id, entry)
        return null
    }

    private suspend fun resolveInvokeId(requestedId: String): String {
        cachedInvokeId(requestedId)?.let { return it }
        val descriptor = invokeAiLimbs(
            "capability.describe",
            JSONObject().put("capability_id", requestedId)
        )
        rememberInvokeIds(descriptor)
        return cachedInvokeId(requestedId) ?: requestedId
    }

    private suspend fun invokeAiLimbs(
        capabilityId: String,
        parameters: JSONObject
    ): JSONObject {
        val ingress = remoteIngress
            ?: error("BridgeRemoteIngress is unavailable")
        return ingress.invoke(capabilityId, parameters)
    }

    private fun toolError(message: String, structured: JSONObject): JSONObject {
        val result = JSONObject(structured.toString()).put("bridge_message", message)
        return JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", result.toString())))
            .put("structuredContent", result).put("isError", true)
    }

    private fun tool(
        name: String,
        description: String,
        inputSchema: JSONObject
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("title", when (name) {
            TOOL_SEARCH -> "Search AI Limbs capabilities"
            TOOL_DESCRIBE -> "Describe an AI Limbs capability"
            TOOL_INVOKE -> "Invoke an AI Limbs capability"
            TOOL_RESULT_READ -> "Read a saved result page"
            TOOL_MEDIA_READ -> "Read a saved image"
            TOOL_STATUS -> "Inspect AI Limbs-ChatGPT access"
            else -> error("Tool title is missing")
        })
        .put("description", description)
        .put("inputSchema", inputSchema)
        .put("outputSchema", JSONObject().put("type", "object").put("additionalProperties", true))
        .put("annotations", JSONObject().put("readOnlyHint", name != TOOL_INVOKE)
            .put("destructiveHint", name == TOOL_INVOKE).put("idempotentHint", name != TOOL_INVOKE)
            .put("openWorldHint", name == TOOL_INVOKE))

    private fun rpcSuccess(id: Any, result: JSONObject): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)

    private fun rpcError(id: Any, code: Int, message: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("message", message)
            )

    private suspend fun postTunnelResponse(config: ChatGptProbeConfig, apiKey: String, shardToken: String, payload: JSONObject, epoch: Long) {
        val url = config.baseUrl.toHttpUrl().newBuilder().addPathSegments("v1/tunnels").addPathSegment(config.tunnelId).addPathSegment("response").build()
        val request = commonHeaders(Request.Builder().url(url).post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)), apiKey)
            .header("Content-Type", "application/json").header("X-Tunnel-Shard-Token", shardToken).build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw httpFailure(response)
            update(epoch) { it.copy(responseCount = it.responseCount + 1, lastStatusCode = response.code,
                lastServiceRequestId = response.header("x-request-id"), lastResponseAckAtMs = System.currentTimeMillis(), lastDeliveryError = null) }
        }
    }

    private fun commonHeaders(builder: Request.Builder, apiKey: String): Request.Builder =
        builder
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("User-Agent", "AI-Limbs-ChatGPT/${McpGatewayState.PROBE_VERSION}")
            .header("X-Tunnel-Client-Name", "ai-limbs-chatgpt")
            .header("X-Tunnel-Client-Version", McpGatewayState.PROBE_VERSION)
            .header(
                "X-Tunnel-Client-Wire-Protocol-Version",
                McpGatewayState.WIRE_PROTOCOL_VERSION
            )
            .header("X-Tunnel-Client-Instance-Id", instanceId)

    private fun newInstanceId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ChatGptDynamicCapabilityGateway"
        private const val DEFAULT_CHANNEL = "main"
        private const val TOOL_SEARCH = "ai_limbs_capability_search"
        private const val TOOL_DESCRIBE = "ai_limbs_capability_describe"
        private const val TOOL_INVOKE = "ai_limbs_capability_invoke"
        private const val TOOL_RESULT_READ = "ai_limbs_result_read"
        private const val TOOL_MEDIA_READ = "ai_limbs_media_read"
        private const val TOOL_STATUS = "ai_limbs_gateway_status"
        private const val POLL_LIMIT = 8
        private const val POLL_TIMEOUT_MS = 20_000L
        private const val INITIAL_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 15_000L
        private const val INVOKE_ID_CACHE_TTL_MS = 60_000L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
