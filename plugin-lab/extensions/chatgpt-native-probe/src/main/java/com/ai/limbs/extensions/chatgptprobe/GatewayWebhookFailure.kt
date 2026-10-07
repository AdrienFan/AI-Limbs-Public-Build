package com.ai.limbs.extensions.chatgptprobe

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.json.JSONObject

internal class GatewayNonPublicDestination : IOException("Non-public callback destination")

/** Only curated categories cross the tool boundary: exception messages can contain callback tokens. */
internal class GatewayWebhookFailure(val reason: String, val stage: String, val exceptionType: String) : IOException(reason) {
    fun diagnostic(): JSONObject = JSONObject().put("stage", stage).put("exception_type", exceptionType)

    companion object {
        fun classify(error: Exception, stage: String): GatewayWebhookFailure {
            if (error is GatewayWebhookFailure) return error
            val causes = generateSequence(error as Throwable?) { it.cause }.take(8).toList()
            val cause = causes.lastOrNull { it is GatewayNonPublicDestination || it is UnknownHostException ||
                it is SSLPeerUnverifiedException || it is SSLHandshakeException || it is SSLException ||
                it is InterruptedIOException || it is ConnectException || it is NoRouteToHostException || it is ProtocolException }
                ?: error
            val reason = when (cause) {
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
            return GatewayWebhookFailure(reason, stage, cause.javaClass.simpleName)
        }
    }
}
