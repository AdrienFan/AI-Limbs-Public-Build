package com.ai.limbs.extensions.chatgptprobe

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal class GatewayWebhookHttp : GatewayEventTransport {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY)
        .dns(Dns { host ->
            val addresses = InetAddress.getAllByName(host).toList()
            if (addresses.isEmpty() || addresses.any { !GatewayWebhookSecurity.publicAddress(it) })
                throw IOException("Non-public callback destination")
            addresses
        }).build()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun post(url: String, headers: Map<String, String>, body: String): GatewayWebhookReply {
        GatewayWebhookSecurity.requireUrl(url)
        val request = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType()))
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
    }
    override fun cancel() = client.dispatcher.cancelAll()
    override fun close() {
        cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
