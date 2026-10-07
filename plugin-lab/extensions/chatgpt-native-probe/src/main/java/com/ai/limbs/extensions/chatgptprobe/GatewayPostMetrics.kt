package com.ai.limbs.extensions.chatgptprobe

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** Per response POST. Retain numeric costs only, never destinations, headers or bodies. */
internal class GatewayPostMetrics(private val clock: () -> Long = System::nanoTime) : EventListener() {
    private val starts = linkedMapOf<String, Long>()
    private val nanos = linkedMapOf<String, Long>()
    private val counts = linkedMapOf<String, Long>()
    private var callStarted: Long? = null
    private var headersReceived: Long? = null
    private var finished = false

    private fun start(field: String) { if (!finished) starts[field] = clock() }
    private fun end(field: String) {
        if (finished) return
        val began = starts.remove(field) ?: return
        nanos[field] = (nanos[field] ?: 0L) + (clock() - began).coerceAtLeast(0L)
    }
    private fun count(field: String, value: Long = 1L) {
        if (!finished) counts[field] = (counts[field] ?: 0L) + value.coerceAtLeast(0L)
    }

    @Synchronized override fun callStart(call: Call) { callStarted = clock() }
    @Synchronized override fun dnsStart(call: Call, domainName: String) { start("post_dns_ms") }
    @Synchronized override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) { end("post_dns_ms") }
    @Synchronized override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        count("post_connect_attempts"); start("post_connect_ms")
    }
    @Synchronized override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { end("post_connect_ms") }
    @Synchronized override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        end("post_tls_ms"); end("post_connect_ms")
    }
    @Synchronized override fun secureConnectStart(call: Call) { start("post_tls_ms") }
    @Synchronized override fun secureConnectEnd(call: Call, handshake: Handshake?) { end("post_tls_ms") }
    @Synchronized override fun requestHeadersStart(call: Call) {
        if (finished) return
        callStarted?.let { nanos.putIfAbsent("post_request_start_ms", (clock() - it).coerceAtLeast(0L)) }
    }
    @Synchronized override fun requestHeadersEnd(call: Call, request: Request) { start("post_wait_headers_ms") }
    @Synchronized override fun requestBodyStart(call: Call) {
        // Header completion does not mean upload completion; exclude body writes from wait time.
        starts.remove("post_wait_headers_ms"); start("post_upload_ms")
    }
    @Synchronized override fun requestBodyEnd(call: Call, byteCount: Long) {
        end("post_upload_ms"); count("post_body_bytes", byteCount); start("post_wait_headers_ms")
    }
    @Synchronized override fun requestFailed(call: Call, ioe: IOException) {
        end("post_upload_ms"); end("post_wait_headers_ms")
    }
    @Synchronized override fun responseHeadersEnd(call: Call, response: Response) {
        // responseHeadersStart fires BEFORE the blocking header read, so it cannot measure TTFB.
        end("post_wait_headers_ms")
        if (!finished) headersReceived = clock()
    }
    @Synchronized override fun responseFailed(call: Call, ioe: IOException) { end("post_wait_headers_ms") }
    @Synchronized override fun callFailed(call: Call, ioe: IOException) {
        starts.keys.toList().forEach(::end)
    }

    @Synchronized fun finish() {
        if (finished) return
        starts.keys.toList().forEach(::end)
        headersReceived?.let { nanos["post_completion_ms"] = (clock() - it).coerceAtLeast(0L) }
        finished = true
    }
    @Synchronized fun snapshot(): Map<String, Long> = linkedMapOf<String, Long>().apply {
        nanos.forEach { (field, value) -> put(field, value / 1_000_000L) }
        putAll(counts)
    }
    companion object {
        val FIELDS = setOf("post_dns_ms", "post_connect_ms", "post_tls_ms", "post_request_start_ms",
            "post_upload_ms", "post_wait_headers_ms", "post_completion_ms", "post_connect_attempts", "post_body_bytes")
    }
}
