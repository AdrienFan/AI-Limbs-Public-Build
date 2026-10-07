package com.ai.limbs.extensions.chatgptprobe

import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject

/** The resolver service uses the current phone route; only its DNS answers become callback routes. */
internal class GatewayCallbackDns(
    private val endpoint: () -> String = { DEFAULT_URL },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).proxy(Proxy.NO_PROXY)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .dispatcher(Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 }).build()
) : Dns {
    private data class Resolver(val url: HttpUrl, val dns: DnsOverHttps)
    @Volatile private var resolver: Resolver? = null
    private val successes = AtomicLong()
    private val failures = AtomicLong()
    @Volatile private var lastSuccessAt: Long? = null
    @Volatile private var lastError: String? = null

    override fun lookup(hostname: String): List<InetAddress> {
        try {
            val url = validateEndpoint(endpoint())
            val selected = synchronized(this) {
                val current = resolver
                if (current != null && current.url == url) current else {
                    // Do not bootstrap with a pinned IP or use the callback DNS recursively.
                    val dns = DnsOverHttps.Builder().client(client).systemDns(client.dns)
                        .url(url).post(true).includeIPv6(true).build()
                    Resolver(url, dns).also { resolver = it }
                }
            }
            val addresses = selected.dns.lookup(hostname)
            if (addresses.isEmpty()) throw java.net.UnknownHostException("Empty HTTPS DNS answer")
            // Validate the exact returned bytes before handing any route to OkHttp.
            if (addresses.any { !GatewayWebhookSecurity.publicAddress(it) }) throw GatewayNonPublicDestination()
            successes.incrementAndGet()
            lastSuccessAt = System.currentTimeMillis()
            lastError = null
            return addresses
        } catch (error: Exception) {
            val failure = GatewayWebhookFailure.classify(error, "dns_https")
            failures.incrementAndGet()
            lastError = failure.reason
            throw failure
        }
    }

    fun status(): JSONObject = JSONObject().put("mode", "DNS_OVER_HTTPS")
        .put("provider_host", endpoint().toHttpUrlOrNull()?.host ?: JSONObject.NULL)
        .put("successful_lookups", successes.get()).put("failed_lookups", failures.get())
        .put("last_success_at_ms", lastSuccessAt ?: JSONObject.NULL).put("last_error", lastError ?: JSONObject.NULL)
        .put("pinned_addresses", false).put("persistent_address_cache", false)

    fun cancel() = client.dispatcher.cancelAll()
    fun close() {
        cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        const val DEFAULT_URL = "https://cloudflare-dns.com/dns-query"
        fun validateEndpoint(value: String): HttpUrl {
            val url = value.trim().toHttpUrlOrNull()
            require(url != null && value.trim().length <= 2048 && url.scheme == "https" &&
                url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null) {
                "加密 DNS 地址必须为 HTTPS URL，不能包含账号、查询参数或片段"
            }
            val host = url.host
            require(host.contains('.') && !host.endsWith(".local") && !host.endsWith(".localhost") &&
                !host.endsWith(".lan") && !host.endsWith(".internal")) { "请填写公网加密 DNS 服务域名" }
            if (host.contains(':') || host.matches(Regex("[0-9.]+"))) {
                require(GatewayWebhookSecurity.publicAddress(InetAddress.getByName(host))) { "加密 DNS 地址不能指向非公网 IP" }
            }
            return url
        }
    }
}
