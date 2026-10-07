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

    @Test fun tlsCertificateCausesHaveSpecificCategoriesAndSafeReasonCodes() {
        val cases = listOf(
            java.security.cert.CertificateExpiredException("private-token") to "tls_certificate_expired",
            java.security.cert.CertificateNotYetValidException("private-token") to "tls_certificate_not_yet_valid",
            java.security.cert.CertPathValidatorException("private-token") to "tls_certificate_validation_failed",
            java.security.cert.CertificateException("private-token") to "tls_certificate_failed")
        for ((cause, reason) in cases) {
            val error = SSLHandshakeException("callback-private-token").apply { initCause(cause) }
            val result = GatewayWebhookFailure.classify(error, "tls")
            assertEquals(reason, result.reason)
            assertEquals(2, result.diagnostic().getJSONArray("cause_types").length())
            assertFalse(result.diagnostic().toString().contains("private-token"))
            assertNull(result.cause)
        }
    }

    @Test fun tlsSocketAndProtocolCausesDoNotMasqueradeAsCertificateFailures() {
        val cases = listOf(
            java.io.EOFException("private-token") to "tls_connection_closed",
            java.net.SocketException("private-token") to "tls_connection_interrupted",
            javax.net.ssl.SSLProtocolException("private-token") to "tls_protocol_failed")
        for ((cause, reason) in cases) {
            val result = GatewayWebhookFailure.classify(SSLHandshakeException("callback-private-token").apply { initCause(cause) }, "tls")
            assertEquals(reason, result.reason)
            assertFalse(result.diagnostic().toString().contains("private-token"))
        }
        assertEquals("io_failed", GatewayWebhookFailure.classify(java.io.EOFException(), "response_body").reason)
    }

    @Test fun standardCertificateReasonAndDohTlsStageAreRetained() {
        val cert = java.security.cert.CertPathValidatorException("private-token", null, null, -1,
            java.security.cert.CertPathValidatorException.BasicReason.INVALID_SIGNATURE)
        val wrapped = UnknownHostException("callback-token").apply { initCause(SSLHandshakeException("private-token").apply { initCause(cert) }) }
        val result = GatewayWebhookFailure.classify(wrapped, "dns_https")
        assertEquals("tls_certificate_validation_failed", result.reason)
        assertEquals("INVALID_SIGNATURE", result.diagnostic().getString("certificate_reason"))
        assertEquals("dns_https", result.stage)
    }

    @Test fun causeChainIsBoundedAndClassifiedFailureIsNotExpandedAgain() {
        var error: Exception = IOException("sensitive-URL")
        repeat(20) { error = IOException("whsec_sensitive", error) }
        val result = GatewayWebhookFailure.classify(error, "tls")
        assertEquals(8, result.diagnostic().getJSONArray("cause_types").length())
        assertFalse(result.diagnostic().toString().contains("sensitive"))
        assertSame(result, GatewayWebhookFailure.classify(result, "transport"))
    }

    @Test fun onlyFixedTlsSignalsCrossTheDiagnosticBoundary() {
        for ((text, signal) in listOf(
            "SSLV3_ALERT_HANDSHAKE_FAILURE callback-secret" to "alert_handshake_failure",
            "TLSV1_ALERT_PROTOCOL_VERSION callback-secret" to "alert_protocol_version",
            "Trust anchor for certification path not found callback-secret" to "trust_anchor_missing",
            "Connection reset by peer callback-secret" to "connection_reset")) {
            val result = GatewayWebhookFailure.classify(SSLHandshakeException(text), "tls")
            assertEquals(signal, result.diagnostic().getString("tls_signal"))
            assertFalse(result.diagnostic().toString().contains("callback-secret"))
        }
        assertFalse(GatewayWebhookFailure.classify(SSLHandshakeException("unknown-secret"), "tls").diagnostic().has("tls_signal"))
        assertFalse(GatewayWebhookFailure.classify(IOException("connection reset by peer"), "response_body").diagnostic().has("tls_signal"))
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
