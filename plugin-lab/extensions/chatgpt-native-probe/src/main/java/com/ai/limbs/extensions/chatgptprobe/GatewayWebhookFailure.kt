package com.ai.limbs.extensions.chatgptprobe

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import org.json.JSONArray
import org.json.JSONObject

internal class GatewayNonPublicDestination : IOException("Non-public callback destination")

/** Only class names and curated categories are retained; messages may contain callback credentials. */
internal class GatewayWebhookFailure(
    val reason: String, val stage: String, val exceptionType: String,
    private val causeTypes: List<String> = emptyList(), private val certificateReason: String? = null,
    private val tlsSignal: String? = null
) : IOException(reason) {
    fun diagnostic(): JSONObject = JSONObject().put("stage", stage).put("exception_type", exceptionType)
        .put("cause_types", JSONArray(causeTypes)).apply {
            if (certificateReason != null) put("certificate_reason", certificateReason)
            if (tlsSignal != null) put("tls_signal", tlsSignal)
        }

    companion object {
        fun classify(error: Exception, stage: String): GatewayWebhookFailure {
            if (error is GatewayWebhookFailure) return error
            val causes = generateSequence(error as Throwable?) { it.cause }.take(8).toList()
            val cause = causes.lastOrNull { it is GatewayNonPublicDestination || it is UnknownHostException ||
                it is SSLPeerUnverifiedException || it is SSLHandshakeException || it is SSLException ||
                it is InterruptedIOException || it is ConnectException || it is NoRouteToHostException || it is ProtocolException }
                ?: error
            // SSLHandshakeException alone cannot distinguish trust failures from a closed socket.
            val tls = stage == "tls" || causes.any { it is SSLException }
            val reason = when {
                tls && causes.any { it is CertificateExpiredException } -> "tls_certificate_expired"
                tls && causes.any { it is CertificateNotYetValidException } -> "tls_certificate_not_yet_valid"
                tls && causes.any { it is CertPathValidatorException } -> "tls_certificate_validation_failed"
                tls && causes.any { it is CertificateException } -> "tls_certificate_failed"
                tls && causes.any { it is SSLPeerUnverifiedException } -> "tls_peer_verification_failed"
                tls && causes.any { it is SSLProtocolException } -> "tls_protocol_failed"
                tls && causes.any { it is EOFException } -> "tls_connection_closed"
                tls && causes.any { it is SocketException } -> "tls_connection_interrupted"
                else -> when (cause) {
                    is GatewayNonPublicDestination -> "non_public_destination"
                    is UnknownHostException -> "dns_resolution_failed"
                    is SSLPeerUnverifiedException -> "tls_peer_verification_failed"
                    is SSLHandshakeException -> "tls_handshake_failed"
                    is SSLException -> "tls_failed"
                    is InterruptedIOException -> "timeout"
                    is ConnectException, is NoRouteToHostException -> "connection_failed"
                    is ProtocolException -> "http_protocol_failed"
                    is IOException -> "io_failed"
                    else -> "transport_exception"
                }
            }
            val cert = causes.filterIsInstance<CertPathValidatorException>().lastOrNull()
            val certReason = when (val value = cert?.reason) {
                is CertPathValidatorException.BasicReason -> value.name
                is java.security.cert.PKIXReason -> value.name
                else -> null
            }
            val types = causes.map { it.javaClass.simpleName.filter { c -> c.isLetterOrDigit() || c == '_' }.take(80) }
            val signals = linkedMapOf(
                "trust anchor for certification path not found" to "trust_anchor_missing",
                "certificate_verify_failed" to "certificate_verify_failed",
                "sslv3_alert_handshake_failure" to "alert_handshake_failure",
                "tlsv1_alert_protocol_version" to "alert_protocol_version",
                "sslv3_alert_bad_certificate" to "alert_bad_certificate",
                "sslv3_alert_certificate_unknown" to "alert_certificate_unknown",
                "tlsv1_alert_internal_error" to "alert_internal_error",
                "tlsv1_alert_access_denied" to "alert_access_denied",
                "connection reset by peer" to "connection_reset",
                "connection closed by peer" to "connection_closed",
                "remote host terminated the handshake" to "remote_handshake_terminated")
            // Inspect transient text only to recognize a fixed TLS token; never retain or emit it.
            val signal = if (tls) signals.entries.firstOrNull { (token, _) ->
                causes.any { it.message?.contains(token, ignoreCase = true) == true }
            }?.value else null
            return GatewayWebhookFailure(reason, stage, cause.javaClass.simpleName, types, certReason, signal)
        }
    }
}
