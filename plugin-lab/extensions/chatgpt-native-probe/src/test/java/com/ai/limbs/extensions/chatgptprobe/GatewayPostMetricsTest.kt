package com.ai.limbs.extensions.chatgptprobe

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test

class GatewayPostMetricsTest {
    private val request = Request.Builder().url("https://private.test/private-path").build()
    private fun response() = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
    private val address = InetSocketAddress("127.0.0.1", 443)

    @Test fun setupUploadAndHeaderWaitAreSeparateAndRetainNoDestination() {
        var tick = 0L
        fun advance(ms: Long) { tick += ms * 1_000_000L }
        val metrics = GatewayPostMetrics { tick }
        val call = OkHttpClient().newCall(request)
        metrics.callStart(call)
        advance(2); metrics.dnsStart(call, "private.test")
        advance(3); metrics.dnsEnd(call, "private.test", emptyList())
        metrics.connectStart(call, address, Proxy.NO_PROXY)
        advance(4); metrics.secureConnectStart(call)
        advance(6); metrics.secureConnectEnd(call, null)
        advance(2); metrics.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_1_1)
        advance(1); metrics.requestHeadersStart(call)
        metrics.requestHeadersEnd(call, request)
        metrics.requestBodyStart(call)
        advance(7); metrics.requestBodyEnd(call, 100L)
        metrics.responseHeadersStart(call)
        advance(40); metrics.responseHeadersEnd(call, response())
        advance(3); metrics.finish()
        val values = metrics.snapshot()
        assertEquals(3L, values.getValue("post_dns_ms"))
        assertEquals(12L, values.getValue("post_connect_ms"))
        assertEquals(6L, values.getValue("post_tls_ms"))
        assertEquals(18L, values.getValue("post_request_start_ms"))
        assertEquals(7L, values.getValue("post_upload_ms"))
        assertEquals(40L, values.getValue("post_wait_headers_ms"))
        assertEquals(3L, values.getValue("post_completion_ms"))
        assertEquals(1L, values.getValue("post_connect_attempts"))
        assertEquals(100L, values.getValue("post_body_bytes"))
        assertEquals(GatewayPostMetrics.FIELDS, values.keys)
        assertFalse(values.toString().contains("private"))
        assertFalse(values.toString().contains("127.0.0.1"))
    }

    @Test fun reusedConnectionDoesNotInventSetupAndHeadersStartDoesNotEndWait() {
        var tick = 0L
        val metrics = GatewayPostMetrics { tick }
        val call = OkHttpClient().newCall(request)
        metrics.callStart(call)
        tick = 2_000_000L; metrics.requestHeadersStart(call)
        metrics.requestHeadersEnd(call, request)
        metrics.requestBodyStart(call)
        tick = 7_000_000L; metrics.requestBodyEnd(call, 10L)
        tick = 12_000_000L; metrics.responseHeadersStart(call)
        assertFalse(metrics.snapshot().containsKey("post_wait_headers_ms"))
        tick = 19_000_000L; metrics.responseHeadersEnd(call, response())
        tick = 21_000_000L; metrics.finish()
        assertEquals(12L, metrics.snapshot().getValue("post_wait_headers_ms"))
        assertFalse(metrics.snapshot().containsKey("post_dns_ms"))
        assertFalse(metrics.snapshot().containsKey("post_connect_ms"))
        assertFalse(metrics.snapshot().containsKey("post_tls_ms"))
        assertFalse(metrics.snapshot().containsKey("post_connect_attempts"))
    }

    @Test fun failedConnectionsAccumulateAndFailedUploadRetainsPartialTime() {
        var tick = 0L
        val metrics = GatewayPostMetrics { tick }
        val call = OkHttpClient().newCall(request)
        metrics.callStart(call)
        tick = 2_000_000L; metrics.connectStart(call, address, Proxy.NO_PROXY)
        tick = 5_000_000L; metrics.connectFailed(call, address, Proxy.NO_PROXY, null, IOException("private error"))
        metrics.connectStart(call, address, Proxy.NO_PROXY)
        tick = 12_000_000L; metrics.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_1_1)
        metrics.requestHeadersStart(call)
        metrics.requestHeadersEnd(call, request)
        metrics.requestBodyStart(call)
        tick = 16_000_000L; metrics.callFailed(call, IOException("private failure"))
        metrics.finish()
        val values = metrics.snapshot()
        assertEquals(10L, values.getValue("post_connect_ms"))
        assertEquals(2L, values.getValue("post_connect_attempts"))
        assertEquals(4L, values.getValue("post_upload_ms"))
        assertFalse(values.containsKey("post_body_bytes"))
        assertFalse(values.containsKey("post_wait_headers_ms"))
        assertFalse(values.containsKey("post_completion_ms"))
        assertFalse(values.toString().contains("private"))
        tick = 30_000_000L; metrics.finish(); metrics.requestBodyEnd(call, 999L)
        assertEquals(values, metrics.snapshot())
    }
}
