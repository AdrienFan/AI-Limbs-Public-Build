package com.ai.limbs.extensions.chatgptprobe

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.ConnectionPool
import org.json.JSONObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal class GatewayWebhookHttp(private val resolver: Dns = GatewayCallbackDns()) : GatewayEventTransport {
    constructor(resolve: (String) -> List<InetAddress>) : this(object : Dns {
        override fun lookup(hostname: String) = resolve(hostname)
    })
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).proxy(Proxy.NO_PROXY)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .eventListenerFactory { call -> requireNotNull(call.request().tag(Trace::class.java)) }
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = resolver.lookup(hostname)
                if (addresses.isEmpty()) throw java.net.UnknownHostException("No callback addresses")
                if (addresses.any { !GatewayWebhookSecurity.publicAddress(it) })
                    throw GatewayNonPublicDestination()
                return addresses
            }
        }).build()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun post(url: String, headers: Map<String, String>, body: String): GatewayWebhookReply {
        GatewayWebhookSecurity.requireUrl(url)
        val trace = Trace()
        try {
            val request = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType()))
                .tag(Trace::class.java, trace)
                .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
            val call = client.newCall(request)
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }
            return response.use {
                val bodyStream = it.body?.byteStream()
                val bytes = ByteArray(4097)
                var size = 0
                if (bodyStream != null) while (size < bytes.size) {
                    val read = bodyStream.read(bytes, size, bytes.size - size)
                    if (read < 0) break
                    size += read
                }
                // A 2xx delivery has been accepted even if its optional response body is excessive.
                // Verification requires a bounded JSON challenge; large bodies can never verify.
                val text = if (size <= 4096) String(bytes, 0, size, Charsets.UTF_8) else ""
                val retryAfter = it.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 300)?.times(1000) ?: 0L
                GatewayWebhookReply(it.code, text, retryAfter)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (error: Exception) { throw GatewayWebhookFailure.classify(error, trace.stage) }
    }

    private class Trace : EventListener() {
        @Volatile var stage = "prepare"
        override fun dnsStart(call: Call, domainName: String) { stage = "dns" }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { stage = "connect" }
        override fun secureConnectStart(call: Call) { stage = "tls" }
        override fun requestHeadersStart(call: Call) { stage = "request_headers" }
        override fun requestBodyStart(call: Call) { stage = "request_body" }
        override fun responseHeadersStart(call: Call) { stage = "response_headers" }
        override fun responseBodyStart(call: Call) { stage = "response_body" }
    }
    override fun dnsStatus(): JSONObject = when (resolver) {
        is GatewayCallbackDns -> resolver.status()
        else -> JSONObject().put("mode", "CUSTOM")
    }
    override fun cancel() {
        client.dispatcher.cancelAll()
        if (resolver is GatewayCallbackDns) resolver.cancel()
    }
    override fun close() {
        cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        if (resolver is GatewayCallbackDns) resolver.close()
    }
}
