package com.ai.limbs.extensions.chatgptprobe

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class GatewayCallbackDnsTest {
    @Test fun endpointValidationRejectsInsecureOrCredentialBearingConfiguration() {
        for (url in listOf("", "http://dns.example.com/query", "https://u:p@dns.example.com/query",
            "https://dns.example.com/query?token=secret", "https://dns.example.com/query#secret",
            "https://localhost/query", "https://dns.local/query", "https://127.0.0.1/query", "https://198.18.0.1/query")) {
            try { GatewayCallbackDns.validateEndpoint(url); fail("Invalid endpoint accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(GatewayCallbackDns.DEFAULT_URL, GatewayCallbackDns.validateEndpoint("  ${GatewayCallbackDns.DEFAULT_URL}  ").toString())
        assertEquals("https://dns.example.com:8443/query", GatewayCallbackDns.validateEndpoint("https://dns.example.com:8443/query").toString())
        assertEquals(GatewayCallbackDns.DEFAULT_URL, ChatGptProbeConfig(true, true, "tunnel_old", "https://api.openai.com").callbackDnsUrl)
    }

    @Test fun realHttpsDnsUsesNewAnswersWithoutAnApplicationAddressCache() {
        Fixture().use { f ->
            GatewayCallbackDns({ f.url }, f.client).useDns { dns ->
                assertEquals("8.8.8.8", dns.lookup("callback.example.com").single().hostAddress)
                f.answer.set("1.1.1.1")
                assertEquals("1.1.1.1", dns.lookup("callback.example.com").single().hostAddress)
                assertEquals(4, f.server.requestCount)
                repeat(4) { assertEquals("POST", f.server.takeRequest(2, TimeUnit.SECONDS)!!.method) }
                assertEquals(2L, dns.status().getLong("successful_lookups"))
                assertFalse(dns.status().getBoolean("pinned_addresses"))
            }
        }
    }

    @Test fun changingTheConfiguredProviderUsesTheNewUrlOnTheNextLookup() {
        Fixture().use { first -> Fixture().use { second ->
            val endpoint = AtomicReference(first.url)
            GatewayCallbackDns(endpoint::get, first.client).useDns { dns ->
                assertEquals("8.8.8.8", dns.lookup("callback.example.com").single().hostAddress)
                second.answer.set("9.9.9.9")
                endpoint.set(second.url)
                assertEquals("9.9.9.9", dns.lookup("callback.example.com").single().hostAddress)
                assertEquals(2, first.server.requestCount)
                assertEquals(2, second.server.requestCount)
            }
        } }
    }

    @Test fun fakeIpFromHttpsDnsIsStillRejectedBeforeCallbackConnection() = runBlocking {
        Fixture().use { f ->
            f.answer.set("198.18.0.4")
            val transport = GatewayWebhookHttp(GatewayCallbackDns({ f.url }, f.client))
            try {
                transport.post("https://callback.example.com/events?token=sensitive", emptyMap(), "{}")
                fail("Fake IP must not become a callback route")
            } catch (error: GatewayWebhookFailure) {
                assertEquals("non_public_destination", error.reason)
                assertEquals("dns_https", error.stage)
                assertFalse(error.diagnostic().toString().contains("sensitive"))
                assertEquals(2, f.server.requestCount)
            } finally { transport.close() }
        }
    }

    @Test fun httpFailureDoesNotUseSystemDnsForTheCallbackOrRotateProviders() {
        Fixture().use { f ->
            f.code.set(503)
            GatewayCallbackDns({ f.url }, f.client).useDns { dns ->
                try { dns.lookup("callback.example.com"); fail("HTTP 503 cannot resolve") }
                catch (error: GatewayWebhookFailure) { assertEquals("dns_https", error.stage) }
                assertEquals(2, f.server.requestCount)
                assertEquals(1L, dns.status().getLong("failed_lookups"))
                assertEquals(0L, dns.status().getLong("successful_lookups"))
            }
        }
    }

    @Test fun resolverRedirectIsNotFollowed() {
        Fixture().use { f ->
            f.code.set(302)
            GatewayCallbackDns({ f.url }, f.client).useDns { dns ->
                try { dns.lookup("callback.example.com"); fail("Redirect cannot resolve") }
                catch (error: GatewayWebhookFailure) { assertEquals("dns_https", error.stage) }
                assertEquals(2, f.server.requestCount)
            }
        }
    }

    @Test fun resolverTlsCertificateRemainsVerified() {
        Fixture().use { f ->
            val trust = HandshakeCertificates.Builder().addPlatformTrustedCertificates().build()
            val untrusted = f.client.newBuilder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            GatewayCallbackDns({ f.url }, untrusted).useDns { dns ->
                try { dns.lookup("callback.example.com"); fail("Untrusted TLS cannot resolve") }
                catch (error: GatewayWebhookFailure) {
                    assertEquals("dns_https", error.stage)
                    assertEquals("tls_handshake_failed", error.reason)
                }
            }
        }
    }

    private inline fun GatewayCallbackDns.useDns(block: (GatewayCallbackDns) -> Unit) {
        try { block(this) } finally { close() }
    }

    companion object {
        private val TEST_CERTIFICATE = HeldCertificate.Builder().addSubjectAlternativeName("resolver.example.com").build()
    }

    private class Fixture : AutoCloseable {
        val server = MockWebServer()
        val answer = AtomicReference("8.8.8.8")
        val code = AtomicReference(200)
        val url: String
        val client: OkHttpClient
        init {
            val certificate = TEST_CERTIFICATE
            val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (code.get() != 200) return MockResponse().setResponseCode(code.get())
                        .setHeader("Location", "https://must-not-follow.example.com/query")
                    val query = request.body.readByteArray()
                    val type = ((query[query.size - 4].toInt() and 255) shl 8) or (query[query.size - 3].toInt() and 255)
                    val bytes = query.copyOf().apply {
                        this[2] = 0x81.toByte(); this[3] = 0x80.toByte()
                        this[6] = 0; this[7] = if (type == 1) 1 else 0
                    }
                    val body = Buffer().write(bytes)
                    if (type == 1) body.writeShort(0xc00c).writeShort(1).writeShort(1).writeInt(0)
                        .writeShort(4).write(InetAddress.getByName(answer.get()).address)
                    return MockResponse().setHeader("Content-Type", "application/dns-message").setBody(body)
                }
            }
            server.start()
            url = server.url("/dns-query").newBuilder().host("resolver.example.com").build().toString()
            client = OkHttpClient.Builder().dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    check(hostname == "resolver.example.com") { "System DNS must only bootstrap the configured service" }
                    return listOf(InetAddress.getByName("127.0.0.1"))
                }
            }).sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .callTimeout(3, TimeUnit.SECONDS).retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).build()
        }
        override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); server.shutdown() }
    }
}
