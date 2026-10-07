package com.ai.limbs.extensions.chatgptprobe

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GatewayWebhookFailureTest {
    @Test fun networkFailuresHaveDistinctSafeCategoriesAndStages() {
        val cases = listOf(
            Triple(UnknownHostException("secret-host"), "dns", "dns_resolution_failed"),
            Triple(ConnectException("secret-ip"), "connect", "connection_failed"),
            Triple(SocketTimeoutException("secret-url"), "connect", "timeout"),
            Triple(SSLHandshakeException("secret-certificate"), "tls", "tls_handshake_failed"),
            Triple(SSLPeerUnverifiedException("secret-hostname"), "tls", "tls_peer_verification_failed"),
            Triple(IOException("secret-body"), "response_body", "io_failed"),
            Triple(GatewayNonPublicDestination(), "dns", "non_public_destination"))
        for ((error, stage, reason) in cases) {
            val result = GatewayWebhookFailure.classify(error, stage)
            assertEquals(reason, result.reason)
            assertEquals(stage, result.diagnostic().getString("stage"))
            assertFalse(result.diagnostic().toString().contains("secret"))
            assertEquals(reason, result.message)
            assertNull(result.cause)
        }
    }

    @Test fun wrappedDnsCauseIsReportedWithoutLeakingItsMessage() {
        val result = GatewayWebhookFailure.classify(IOException("whsec_sensitive", UnknownHostException("callback_token")), "dns")
        assertEquals("dns_resolution_failed", result.reason)
        assertEquals("UnknownHostException", result.exceptionType)
        assertFalse(result.diagnostic().toString().contains("callback_token"))
        assertSame(result, GatewayWebhookFailure.classify(result, "transport"))
    }

    @Test fun dohWrappingKeepsTheSpecificTlsFailureInsteadOfOuterDnsError() {
        val cause = UnknownHostException("callback-private-token").apply { initCause(SSLHandshakeException("private-certificate")) }
        val result = GatewayWebhookFailure.classify(cause, "dns_https")
        assertEquals("tls_handshake_failed", result.reason)
        assertEquals("dns_https", result.stage)
        assertFalse(result.diagnostic().toString().contains("private"))
    }

    @Test fun productionTransportReportsDnsFailureWithRealListenerStage() = runBlocking {
        val transport = GatewayWebhookHttp { throw UnknownHostException("private-callback-token") }
        try {
            transport.post("https://diagnostics.invalid/callback?token=sensitive", emptyMap(), "{}")
            fail("DNS lookup must fail")
        } catch (error: GatewayWebhookFailure) {
            assertEquals("dns_resolution_failed", error.reason)
            assertEquals("dns", error.stage)
            assertFalse(error.diagnostic().toString().contains("sensitive"))
        } finally { transport.close() }
    }

    @Test fun productionTransportStillBlocksAnyNonPublicResolvedAddress() = runBlocking {
        val transport = GatewayWebhookHttp { listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("198.18.0.1")) }
        try {
            transport.post("https://diagnostics.invalid/callback", emptyMap(), "{}")
            fail("Mixed public and non-public answers must be rejected before connecting")
        } catch (error: GatewayWebhookFailure) {
            assertEquals("non_public_destination", error.reason)
            assertEquals("dns", error.stage)
        } finally { transport.close() }
    }

    @Test fun emptyDnsAnswerIsResolutionFailureRatherThanNonPublicAddress() = runBlocking {
        val transport = GatewayWebhookHttp { emptyList() }
        try {
            transport.post("https://diagnostics.invalid/callback", emptyMap(), "{}")
            fail("Empty DNS answer cannot connect")
        } catch (error: GatewayWebhookFailure) {
            assertEquals("dns_resolution_failed", error.reason)
            assertEquals("dns", error.stage)
        } finally { transport.close() }
    }
}
