package com.ai.limbs.extensions.chatgptprobe

import java.net.InetAddress
import java.time.Instant
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal class GatewayEventFailure(val code: Int, val reason: String, val diagnostic: JSONObject? = null) : IllegalStateException(reason)
internal data class GatewayWebhookReply(val code: Int, val body: String, val retryAfterMs: Long = 0)
internal interface GatewayEventTransport {
    suspend fun post(url: String, headers: Map<String, String>, body: String): GatewayWebhookReply
    fun dnsStatus(): JSONObject = JSONObject()
    fun cancel() {}
    fun close() {}
}

/** A phone event is not an MCP notification: ChatGPT supplies a verified, signed webhook. */
internal class GatewayEvents(
    private val store: GatewayBlobStore,
    private val transport: GatewayEventTransport,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val lock = Mutex()
    @Volatile private var diagnostics: Pair<String, JSONObject>? = null
    companion object {
        const val NAME = "ai_limbs.wake_requested"
        const val SOURCE = "manual"
        private const val SUB_PREFIX = "eventsub_"
        private const val QUEUE_PREFIX = "eventout_"
        private const val STATE_PREFIX = "eventstate_"
        private const val DEFAULT_TTL = 6 * 60 * 60 * 1000L
        private const val MAX_TTL = 24 * 60 * 60 * 1000L
        private const val EVENT_TTL = 15 * 60 * 1000L
        private const val MAX_SUBSCRIPTIONS = 16
        private const val MAX_PENDING = 32
        private const val MAX_ATTEMPTS = 4
    }

    fun definitions(): JSONObject = JSONObject().put("events", JSONArray().put(JSONObject()
        .put("name", NAME)
        .put("description", "An explicitly requested phone wake test. Subscribe with source_id=manual. This release does not continuously sample a camera. Follow the user's monitoring instructions; use tools to inspect current state when needed.")
        .put("delivery", JSONArray().put("webhook"))
        .put("inputSchema", JSONObject().put("type", "object").put("additionalProperties", false)
            .put("properties", JSONObject().put("source_id", JSONObject().put("type", "string").put("enum", JSONArray().put(SOURCE))))
            .put("required", JSONArray().put("source_id")))
        .put("payloadSchema", JSONObject().put("type", "object").put("additionalProperties", false)
            .put("properties", JSONObject().put("source_id", JSONObject().put("type", "string"))
                .put("reason", JSONObject().put("type", "string").put("enum", JSONArray().put("manual_test")))
                .put("occurred_at_ms", JSONObject().put("type", "integer")))
            .put("required", JSONArray().put("source_id").put("reason").put("occurred_at_ms")))))

    fun snapshot(binding: String): JSONObject = diagnostics?.takeIf { it.first == binding }?.let { JSONObject(it.second.toString()) }
        ?: JSONObject().put("loaded", false).put("model_response_verified", false)

    suspend fun recover(binding: String) = lock.withLock { prune(); publish(binding) }

    suspend fun subscribe(binding: String, p: JSONObject): JSONObject = lock.withLock {
        requireKeys(p, setOf("name", "arguments", "delivery", "cursor", "ttlMs", "_meta"))
        requireEvent(p)
        val delivery = p.optJSONObject("delivery") ?: invalid("delivery_required")
        requireKeys(delivery, setOf("mode", "url", "secret"))
        if (delivery.opt("mode") != "webhook") invalid("webhook_required")
        val url = delivery.opt("url") as? String ?: invalid("callback_url_required")
        GatewayWebhookSecurity.requireUrl(url)
        val secret = delivery.opt("secret") as? String ?: invalid("signing_secret_required")
        GatewayWebhookSecurity.key(secret)
        val ttlValue = p.opt("ttlMs")
        val ttl = if (ttlValue == null || ttlValue == JSONObject.NULL) DEFAULT_TTL else {
            if (ttlValue !is Number || ttlValue.toDouble() != ttlValue.toLong().toDouble() || ttlValue.toLong() <= 0) invalid("invalid_ttl")
            ttlValue.toLong().coerceIn(60_000L, MAX_TTL)
        }
        prune()
        val id = subscriptionId(binding, url, p.getJSONObject("arguments"))
        val previous = store.read(SUB_PREFIX + id)?.let(::JSONObject)
        if (previous == null && store.names().count { it.startsWith(SUB_PREFIX) } >= MAX_SUBSCRIPTIONS)
            throw GatewayEventFailure(-32000, "subscription_capacity_reached")
        val cachedVerification = previous != null && previous.getString("secret") == secret &&
            now() - previous.getLong("verified_at") in 0L..300_000L
        if (!cachedVerification) {
            val challenge = UUID.randomUUID().toString()
            val body = JSONObject().put("type", "verification").put("challenge", challenge).toString()
            val reply = try {
                transport.post(url, GatewayWebhookSecurity.headers(id, "verify_" + UUID.randomUUID(), now(), secret, body), body)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                val failure = GatewayWebhookFailure.classify(error, "transport")
                recordDiagnostic(binding, "verification_failed", now(), failure.reason, failure.diagnostic()); publish(binding)
                throw GatewayEventFailure(-32015, failure.reason, failure.diagnostic())
            }
            val echoed = try { JSONObject(reply.body).opt("challenge") as? String } catch (_: Exception) { null }
            if (reply.code !in 200..299 || echoed == null || !MessageDigest.isEqual(challenge.toByteArray(Charsets.UTF_8), echoed.toByteArray(Charsets.UTF_8))) {
                val reason = if (reply.code !in 200..299) "verification_http_failed" else "challenge_failed"
                val diagnostic = JSONObject().put("stage", "http_response").put("http_status", reply.code)
                recordDiagnostic(binding, "verification_failed", now(), reason, diagnostic); publish(binding)
                throw GatewayEventFailure(-32015, reason, diagnostic)
            }
        }
        currentCoroutineContext().ensureActive()
        val expires = now() + ttl
        // Only a verified subscription is committed. Callback addresses and signing keys stay encrypted.
        val subscription = JSONObject().put("binding", binding).put("id", id)
            .put("name", NAME).put("arguments", JSONObject(p.getJSONObject("arguments").toString()))
            .put("url", url).put("secret", secret).put("expires", expires)
            .put("verified_at", if (cachedVerification) previous!!.getLong("verified_at") else now())
        // A short dual-signature window is part of the Events signing-key rotation contract.
        if (previous != null && previous.getString("secret") != secret) {
            subscription.put("previous_secret", previous.getString("secret")).put("rotation_until", now() + 60_000L)
        } else if (previous != null && previous.optLong("rotation_until") > now()) {
            subscription.put("previous_secret", previous.getString("previous_secret")).put("rotation_until", previous.getLong("rotation_until"))
        }
        store.write(SUB_PREFIX + id, subscription.toString())
        recordDiagnostic(binding, "callback_verified", now())
        publish(binding)
        JSONObject().put("id", id).put("refreshBefore", Instant.ofEpochMilli(expires).toString())
            .put("cursor", JSONObject.NULL).put("truncated", false)
    }

    suspend fun unsubscribe(binding: String, p: JSONObject): JSONObject = lock.withLock {
        requireKeys(p, setOf("name", "arguments", "delivery", "_meta"))
        requireEvent(p)
        val d = p.optJSONObject("delivery") ?: invalid("delivery_required")
        requireKeys(d, setOf("mode", "url"))
        if (d.opt("mode") != "webhook") invalid("webhook_required")
        val url = d.opt("url") as? String ?: invalid("callback_url_required")
        GatewayWebhookSecurity.requireUrl(url)
        val id = subscriptionId(binding, url, p.getJSONObject("arguments"))
        store.delete(SUB_PREFIX + id)
        queue(binding).filter { it.second.getString("subscription") == id }.forEach { store.delete(it.first) }
        publish(binding)
        JSONObject()
    }

    suspend fun testWake(binding: String): JSONObject = lock.withLock {
        prune()
        val recipients = subscriptions(binding)
        if (recipients.isEmpty()) throw GatewayEventFailure(-32000, "no_verified_subscription")
        if (store.names().count { it.startsWith(QUEUE_PREFIX) } + recipients.size > MAX_PENDING) throw GatewayEventFailure(-32000, "event_queue_capacity_reached")
        val eventId = "evt_" + UUID.randomUUID().toString().replace("-", "")
        val created = now()
        val event = JSONObject().put("eventId", eventId).put("name", NAME)
            .put("timestamp", Instant.ofEpochMilli(created).toString())
            .put("data", JSONObject().put("source_id", SOURCE).put("reason", "manual_test").put("occurred_at_ms", created))
            .put("cursor", JSONObject.NULL).toString()
        val written = mutableListOf<String>()
        try {
            recipients.forEach { sub ->
                val name = QUEUE_PREFIX + gatewayHash(binding + "\n" + sub.getString("id") + "\n" + eventId)
                store.write(name, JSONObject().put("binding", binding).put("subscription", sub.getString("id"))
                    .put("event_id", eventId).put("body", event).put("expires", created + EVENT_TTL)
                    .put("next_at", created).put("attempts", 0).toString())
                written.add(name)
            }
        } catch (error: Exception) {
            written.forEach { store.delete(it) }
            throw error
        }
        recordDiagnostic(binding, "queued", created)
        publish(binding)
        JSONObject().put("success", true).put("event_id", eventId).put("queued_deliveries", recipients.size)
            .put("model_response_verified", false)
    }

    /** Serialize delivery with unsubscribe: once unsubscribe returns no old callback can begin. */
    suspend fun deliverOne(binding: String) = lock.withLock {
        prune()
        val item = queue(binding).firstOrNull { it.second.getLong("next_at") <= now() }
        if (item != null) {
            val (name, record) = item
            val sub = store.read(SUB_PREFIX + record.getString("subscription"))?.let(::JSONObject)
            if (sub == null || sub.getString("binding") != binding || sub.getLong("expires") <= now()) {
                store.delete(name)
            } else {
                // Persist attempt admission before POST. A restart cannot create an unbounded retry loop.
                val attempts = record.getInt("attempts") + 1
                record.put("attempts", attempts).put("next_at", now() + 30_000L)
                store.write(name, record.toString())
                var reply: GatewayWebhookReply? = null
                var failure: String? = null
                var diagnostic: JSONObject? = null
                try {
                    val body = record.getString("body")
                    val signedAt = now()
                    val headers = GatewayWebhookSecurity.headers(sub.getString("id"),
                        record.getString("event_id"), signedAt, sub.getString("secret"), body).toMutableMap()
                    if (sub.optLong("rotation_until") > signedAt) {
                        headers["webhook-signature"] = headers.getValue("webhook-signature") + " " +
                            GatewayWebhookSecurity.headers(sub.getString("id"), record.getString("event_id"),
                                signedAt, sub.getString("previous_secret"), body).getValue("webhook-signature")
                    }
                    reply = transport.post(sub.getString("url"), headers, body)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    val problem = GatewayWebhookFailure.classify(error, "transport")
                    failure = problem.reason; diagnostic = problem.diagnostic()
                }
                val code = reply?.code
                val retryable = code == null || code == 408 || code == 429 || code in 500..599
                when {
                    code != null && code in 200..299 -> { store.delete(name); recordDiagnostic(binding, "webhook_accepted", now()) }
                    retryable && attempts < MAX_ATTEMPTS -> {
                        val backoff = (1_000L shl (attempts - 1)).coerceAtMost(30_000L)
                        val wait = maxOf(backoff, reply?.retryAfterMs ?: 0L).coerceAtMost(300_000L)
                        record.put("next_at", now() + wait)
                        store.write(name, record.toString())
                        recordDiagnostic(binding, "retry_pending", now(), failure ?: "http_$code", diagnostic)
                    }
                    else -> {
                        store.delete(name)
                        if (code == 410) store.delete(SUB_PREFIX + sub.getString("id"))
                        recordDiagnostic(binding, "delivery_failed", now(), failure ?: "http_$code", diagnostic)
                    }
                }
            }
        }
        publish(binding)
    }

    private fun subscriptions(binding: String): List<JSONObject> = store.names().filter { it.startsWith(SUB_PREFIX) }
        .mapNotNull { store.read(it)?.let(::JSONObject) }.filter { it.getString("binding") == binding }
    private fun queue(binding: String): List<Pair<String, JSONObject>> = store.names().filter { it.startsWith(QUEUE_PREFIX) }
        .mapNotNull { name -> store.read(name)?.let { name to JSONObject(it) } }.filter { it.second.getString("binding") == binding }

    private fun prune() {
        // Sweep every binding so changing a tunnel cannot strand expired encrypted files.
        store.names().filter { it.startsWith(SUB_PREFIX) }.forEach { name ->
            val item = store.read(name)?.let(::JSONObject) ?: return@forEach
            if (item.getLong("expires") <= now()) store.delete(name)
            else if (item.has("previous_secret") && item.getLong("rotation_until") <= now()) {
                item.remove("previous_secret"); item.remove("rotation_until")
                store.write(name, item.toString())
            }
        }
        store.names().filter { it.startsWith(QUEUE_PREFIX) }.forEach { name ->
            val item = store.read(name)?.let(::JSONObject) ?: return@forEach
            if (item.getLong("expires") <= now() || item.getInt("attempts") >= MAX_ATTEMPTS ||
                store.read(SUB_PREFIX + item.getString("subscription")) == null)
                store.delete(name)
        }
    }
    private fun recordDiagnostic(binding: String, phase: String, at: Long, error: String? = null, diagnostic: JSONObject? = null) {
        val key = STATE_PREFIX + gatewayHash(binding)
        val other = store.names().filter { it.startsWith(STATE_PREFIX) && it != key }
            .sortedBy { store.read(it)?.let(::JSONObject)?.getLong("updated_at_ms") ?: 0L }
        // Diagnostics are bounded independently of subscriptions and pending deliveries.
        other.take((other.size - 15).coerceAtLeast(0)).forEach { store.delete(it) }
        val state = store.read(key)?.let(::JSONObject) ?: JSONObject()
        state.put("phase", phase).put("updated_at_ms", at).put("last_error", error ?: JSONObject.NULL)
            .put("last_diagnostic", diagnostic ?: JSONObject.NULL)
        if (phase == "webhook_accepted") state.put("accepted_count", state.optLong("accepted_count") + 1)
        store.write(key, state.toString())
    }
    private fun publish(binding: String) {
        val state = store.read(STATE_PREFIX + gatewayHash(binding))?.let(::JSONObject) ?: JSONObject().put("phase", "awaiting_subscription")
        state.put("loaded", true).put("active_subscriptions", subscriptions(binding).size).put("pending_deliveries", queue(binding).size)
            .put("event_name", NAME).put("source_id", SOURCE).put("model_response_verified", false)
            .put("delivery_semantics", "Webhook acknowledgement only; ChatGPT processes events asynchronously")
        diagnostics = binding to state
    }
    private fun subscriptionId(binding: String, url: String, args: JSONObject): String =
        gatewayHash(binding + "\n" + url + "\n" + NAME + "\n" + canonicalJson(args))
    private fun requireEvent(p: JSONObject) {
        if (p.has("_meta") && p.opt("_meta") !is JSONObject) invalid("invalid_metadata")
        if (p.opt("name") != NAME) invalid("unknown_event")
        val args = p.optJSONObject("arguments") ?: invalid("arguments_required")
        requireKeys(args, setOf("source_id"))
        if (args.opt("source_id") != SOURCE) invalid("unknown_source")
        if (p.has("cursor") && !p.isNull("cursor")) invalid("replay_not_supported")
    }
    private fun requireKeys(p: JSONObject, allowed: Set<String>) { if (p.keys().asSequence().any { it !in allowed }) invalid("unknown_argument") }
    private fun invalid(reason: String): Nothing = throw GatewayEventFailure(-32602, reason)
}

internal object GatewayWebhookSecurity {
    fun key(secret: String): ByteArray {
        if (!secret.startsWith("whsec_")) throw GatewayEventFailure(-32602, "invalid_signing_secret")
        val bytes = try { Base64.getDecoder().decode(secret.removePrefix("whsec_")) }
            catch (_: IllegalArgumentException) { throw GatewayEventFailure(-32602, "invalid_signing_secret") }
        if (bytes.size !in 24..64) throw GatewayEventFailure(-32602, "invalid_signing_secret")
        return bytes
    }
    fun requireUrl(value: String) {
        val u = try { java.net.URI(value) } catch (_: Exception) { throw GatewayEventFailure(-32602, "invalid_callback_url") }
        if (value.length > 2048 || u.scheme != "https" || u.host.isNullOrBlank() || u.userInfo != null || u.fragment != null ||
            u.port !in -1..65535 || u.port == 0) throw GatewayEventFailure(-32602, "invalid_callback_url")
        val host = u.host.removePrefix("[").removeSuffix("]")
        // OkHttp does not call custom DNS for an IP literal, so validate literals here as well.
        if (host.contains('%')) throw GatewayEventFailure(-32602, "non_public_callback_url")
        if (host.contains(':') || host.matches(Regex("[0-9.]+"))) {
            val address = try { InetAddress.getByName(host) }
                catch (_: Exception) { throw GatewayEventFailure(-32602, "invalid_callback_url") }
            if (!publicAddress(address)) throw GatewayEventFailure(-32602, "non_public_callback_url")
        }
    }
    fun headers(subscription: String, eventId: String, at: Long, secret: String, body: String): Map<String, String> {
        require(body.toByteArray(Charsets.UTF_8).size <= 262_144) { "Event payload exceeds 256 KiB" }
        val timestamp = (at / 1000).toString()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key(secret), "HmacSHA256")) }
        val signature = Base64.getEncoder().encodeToString(mac.doFinal("$eventId.$timestamp.$body".toByteArray(Charsets.UTF_8)))
        return mapOf("Content-Type" to "application/json", "webhook-id" to eventId, "webhook-timestamp" to timestamp,
            "webhook-signature" to "v1,$signature", "X-MCP-Subscription-Id" to subscription)
    }
    /** Check resolved bytes, not a hostname prefix. This DNS result is used for the actual TLS connection. */
    fun publicAddress(address: InetAddress): Boolean {
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            val a = b[0]; val c = b[1]; val d = b[2]
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && c in 64..127) ||
                (a == 169 && c == 254) || (a == 172 && c in 16..31) || (a == 192 && c == 168) ||
                (a == 192 && c == 0 && d in 0..2) || (a == 192 && c == 88 && d == 99) ||
                (a == 198 && c in 18..19) || (a == 198 && c == 51 && d == 100) || (a == 203 && c == 0 && d == 113))
        }
        if (b.size != 16 || b[0] !in 0x20..0x3f) return false
        // Exclude special-purpose 2001::/23, documentation 2001:db8::/32 and 6to4 tunnels.
        return !((b[0] == 0x20 && b[1] == 1 && b[2] < 2) ||
            (b[0] == 0x20 && b[1] == 1 && b[2] == 0x0d && b[3] == 0xb8) || (b[0] == 0x20 && b[1] == 2) ||
            (b[0] == 0x3f && b[1] == 0xff && b[2] < 16))
    }
}
