package com.ai.limbs.extensions.chatgptprobe

import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GatewayEventsTest {
    @Test fun transportFailureDetailsSurviveRestartAndNeverCommitSubscription() = runBlocking {
        val store = EventMemoryStore()
        val transport = object : GatewayEventTransport {
            override suspend fun post(url: String, headers: Map<String, String>, body: String): GatewayWebhookReply {
                throw GatewayWebhookFailure("dns_resolution_failed", "dns", "UnknownHostException")
            }
        }
        val events = GatewayEvents(store, transport)
        try { events.subscribe("a", subscription()); fail() }
        catch (error: GatewayEventFailure) {
            assertEquals(-32015, error.code)
            assertEquals("dns_resolution_failed", error.reason)
            assertEquals("dns", error.diagnostic!!.getString("stage"))
        }
        val restarted = GatewayEvents(store, transport)
        restarted.recover("a")
        val status = restarted.snapshot("a")
        assertEquals(0, status.getInt("active_subscriptions"))
        assertEquals("UnknownHostException", status.getJSONObject("last_diagnostic").getString("exception_type"))
        assertFalse(status.toString().contains(secret))
        assertFalse(status.toString().contains("callback.example"))
        assertTrue(store.names().none { it.startsWith("eventsub_") })
    }

    @Test fun verificationHttpStatusIsDistinctFromChallengeMismatch() = runBlocking {
        val f = Fixture()
        f.transport.verificationCode = 403
        try { f.events.subscribe("a", subscription()); fail() }
        catch (error: GatewayEventFailure) {
            assertEquals("verification_http_failed", error.reason)
            assertEquals(403, error.diagnostic!!.getInt("http_status"))
        }
        f.transport.verificationCode = 200
        f.transport.verify = false
        try { f.events.subscribe("a", subscription()); fail() }
        catch (error: GatewayEventFailure) { assertEquals("challenge_failed", error.reason) }
    }

    private val secret = "whsec_" + Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private fun subscription(url: String = "https://callback.example/events", signing: String = secret) = JSONObject()
        .put("name", GatewayEvents.NAME).put("arguments", JSONObject().put("source_id", "manual"))
        .put("delivery", JSONObject().put("mode", "webhook").put("url", url).put("secret", signing)).put("cursor", JSONObject.NULL)
    private fun unsubscribe(p: JSONObject) = JSONObject(p.toString()).apply { getJSONObject("delivery").remove("secret"); remove("cursor") }

    @Test fun standardWebhookSignatureMatchesIndependentHmacVector() {
        val h = GatewayWebhookSecurity.headers("sub_1", "msg_123", 1_234_567_890_000L, secret, "{\"x\":1}")
        assertEquals("v1,WbaS4qQxMBNBKmcLGKIoRgp4Vw056zFAm0ASDd7AWNk=", h["webhook-signature"])
        assertEquals("1234567890", h["webhook-timestamp"])
        assertEquals("sub_1", h["X-MCP-Subscription-Id"])
    }

    @Test fun verificationCommitsOnlyAfterChallengeEchoAndDoesNotLeakSecret() = runBlocking {
        val f = Fixture()
        val result = f.events.subscribe("a", subscription())
        val sent = f.transport.posts.single()
        assertEquals("verification", JSONObject(sent.body).getString("type"))
        assertEquals(result.getString("id"), sent.headers["X-MCP-Subscription-Id"])
        assertEquals(1, f.events.snapshot("a").getInt("active_subscriptions"))
        assertFalse(result.toString().contains(secret))
        assertFalse(f.events.snapshot("a").toString().contains("callback.example"))
        assertFalse(f.events.snapshot("a").getBoolean("model_response_verified"))
    }

    @Test fun failedChallengeNeverCreatesSubscriptionOrTestDelivery() = runBlocking {
        val f = Fixture()
        f.transport.verify = false
        try { f.events.subscribe("a", subscription()); fail() } catch (e: GatewayEventFailure) { assertEquals(-32015, e.code) }
        assertTrue(f.store.names().none { it.startsWith("eventsub_") })
        try { f.events.testWake("a"); fail() } catch (e: GatewayEventFailure) { assertEquals("no_verified_subscription", e.reason) }
        assertEquals(1, f.transport.posts.size)
    }

    @Test fun invalidSourceSecretCursorAndPrivateLiteralFailBeforeNetwork() = runBlocking {
        val f = Fixture()
        val cases = listOf(
            subscription().apply { getJSONObject("arguments").put("source_id", "camera") },
            subscription(signing = "whsec_AA=="), subscription().put("cursor", "old"),
            subscription("http://callback.example/events"), subscription("https://127.0.0.1/events"),
            subscription("https://[::1]/events"), subscription("https://user:pass@callback.example/events"),
            subscription().put("unexpected", true))
        for (p in cases) try { f.events.subscribe("a", p); fail(p.toString()) } catch (e: GatewayEventFailure) { assertEquals(-32602, e.code) }
        assertTrue(f.transport.posts.isEmpty())
    }

    @Test fun publicDestinationCheckBlocksIpv4Ipv6AndMappedPrivateAddresses() {
        val blocked = listOf("0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1",
            "192.168.0.1", "198.18.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "224.0.0.1",
            "::1", "fe80::1", "fc00::1", "::ffff:127.0.0.1", "2001:db8::1", "2002:7f00:1::1")
        blocked.forEach { assertFalse(it, GatewayWebhookSecurity.publicAddress(InetAddress.getByName(it))) }
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach {
            assertTrue(it, GatewayWebhookSecurity.publicAddress(InetAddress.getByName(it)))
        }
    }

    @Test fun refreshIsDeterministicAndReplacementSecretSignsFutureDelivery() = runBlocking {
        val f = Fixture()
        val first = f.events.subscribe("a", subscription())
        val replacement = "whsec_" + Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val second = f.events.subscribe("a", subscription(signing = replacement))
        assertEquals(first.getString("id"), second.getString("id"))
        assertEquals(1, f.events.snapshot("a").getInt("active_subscriptions"))
        f.events.testWake("a"); f.events.deliverOne("a")
        val sent = f.transport.posts.last()
        val expected = GatewayWebhookSecurity.headers(second.getString("id"), sent.headers.getValue("webhook-id"), f.clock, replacement, sent.body)
        val old = GatewayWebhookSecurity.headers(second.getString("id"), sent.headers.getValue("webhook-id"), f.clock, secret, sent.body)
        assertEquals(expected["webhook-signature"] + " " + old["webhook-signature"], sent.headers["webhook-signature"])
        f.clock += 60_001
        f.events.testWake("a"); f.events.deliverOne("a")
        val after = f.transport.posts.last()
        assertEquals(GatewayWebhookSecurity.headers(second.getString("id"), after.headers.getValue("webhook-id"),
            f.clock, replacement, after.body)["webhook-signature"], after.headers["webhook-signature"])
        assertTrue(f.store.names().filter { it.startsWith("eventsub_") }.all {
            !JSONObject(f.store.read(it)!!).has("previous_secret")
        })
    }

    @Test fun verificationCacheIsBoundedAndSubscriptionLifetimeIsNotExtendedPastRequest() = runBlocking {
        val f = Fixture()
        val p = subscription().put("ttlMs", 600_000)
        f.events.subscribe("a", p)
        f.clock += 120_000
        val renewed = f.events.subscribe("a", p)
        assertEquals(1, f.transport.posts.size)
        assertEquals(f.clock + 600_000, java.time.Instant.parse(renewed.getString("refreshBefore")).toEpochMilli())
        f.clock += 180_001
        f.events.subscribe("a", p)
        assertEquals(2, f.transport.posts.size)
    }

    @Test fun failedKeyRotationDoesNotActivateUnverifiedReplacement() = runBlocking {
        val f = Fixture()
        val original = f.events.subscribe("a", subscription())
        f.transport.verify = false
        val replacement = "whsec_" + Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        try { f.events.subscribe("a", subscription(signing = replacement)); fail() }
        catch (e: GatewayEventFailure) { assertEquals(-32015, e.code) }
        f.events.testWake("a"); f.events.deliverOne("a")
        val sent = f.transport.posts.last()
        assertEquals(GatewayWebhookSecurity.headers(original.getString("id"), sent.headers.getValue("webhook-id"),
            f.clock, secret, sent.body)["webhook-signature"], sent.headers["webhook-signature"])
    }

    @Test fun subscriptionCapacityAndOldBindingExpiryAreGlobal() = runBlocking {
        val f = Fixture()
        repeat(16) { f.events.subscribe("binding-$it", subscription().put("ttlMs", 60_000)) }
        try { f.events.subscribe("extra", subscription()); fail() }
        catch (e: GatewayEventFailure) { assertEquals("subscription_capacity_reached", e.reason) }
        f.clock += 60_001
        f.events.subscribe("extra", subscription())
        assertEquals(1, f.store.names().count { it.startsWith("eventsub_") })
    }

    @Test fun queuedEventExpiresWhileItsSubscriptionRemainsActive() = runBlocking {
        val f = Fixture()
        f.events.subscribe("a", subscription()); f.events.testWake("a")
        f.clock += 15 * 60_000 + 1
        f.events.deliverOne("a")
        assertEquals(1, f.transport.posts.size)
        assertEquals(1, f.events.snapshot("a").getInt("active_subscriptions"))
        assertEquals(0, f.events.snapshot("a").getInt("pending_deliveries"))
    }

    @Test fun restartRetainsEventIdentityAndRetryNeverRecapturesOrRecreatesEvent() = runBlocking {
        val f = Fixture()
        f.events.subscribe("a", subscription())
        val queued = f.events.testWake("a")
        f.transport.code = 503
        f.events.deliverOne("a")
        val restarted = GatewayEvents(f.store, f.transport) { f.clock }
        f.clock += 30_000
        f.transport.code = 200
        restarted.recover("a"); restarted.deliverOne("a")
        val deliveries = f.transport.posts.filter { JSONObject(it.body).has("eventId") }
        assertEquals(2, deliveries.size)
        assertEquals(deliveries[0].body, deliveries[1].body)
        assertEquals(queued.getString("event_id"), deliveries[1].headers["webhook-id"])
        assertNotEquals(deliveries[0].headers["webhook-signature"], deliveries[1].headers["webhook-signature"])
        assertEquals(0, restarted.snapshot("a").getInt("pending_deliveries"))
        assertEquals(1L, restarted.snapshot("a").getLong("accepted_count"))
        assertFalse(restarted.snapshot("a").getBoolean("model_response_verified"))
    }

    @Test fun retryAttemptsAreBoundedAnd410413NeverRetry() = runBlocking {
        val f = Fixture()
        f.events.subscribe("a", subscription()); f.events.testWake("a")
        f.transport.code = 503
        repeat(6) { f.events.deliverOne("a"); f.clock += 30_000 }
        assertEquals(4, f.transport.posts.count { JSONObject(it.body).has("eventId") })
        assertEquals("delivery_failed", f.events.snapshot("a").getString("phase"))
        for (code in listOf(413, 410)) {
            f.events.subscribe("a", subscription()); f.events.testWake("a"); f.transport.code = code
            val before = f.transport.posts.size
            f.events.deliverOne("a"); f.clock += 30_000; f.events.deliverOne("a")
            assertEquals(before + 1, f.transport.posts.size)
        }
        assertEquals(0, f.events.snapshot("a").getInt("active_subscriptions"))
    }

    @Test fun unsubscribeDropsPendingEventsAndIsIdempotent() = runBlocking {
        val f = Fixture(); val p = subscription()
        f.events.subscribe("a", p); f.events.testWake("a")
        f.events.unsubscribe("a", unsubscribe(p)); f.events.unsubscribe("a", unsubscribe(p))
        f.events.deliverOne("a")
        assertEquals(1, f.transport.posts.size)
        assertEquals(0, f.events.snapshot("a").getInt("pending_deliveries"))
    }

    @Test fun expiredEventsAndSubscriptionsArePhysicallyPrunedWithoutDelivery() = runBlocking {
        val f = Fixture()
        f.events.subscribe("a", subscription().put("ttlMs", 60_000)); f.events.testWake("a")
        f.clock += 60_001
        f.events.deliverOne("a")
        assertTrue(f.store.names().none { it.startsWith("eventsub_") || it.startsWith("eventout_") })
        assertEquals(1, f.transport.posts.size)
    }

    @Test fun bindingIsolationAndQueueCapacityPreventExtraDelivery() = runBlocking {
        val f = Fixture(); f.events.subscribe("a", subscription())
        try { f.events.testWake("b"); fail() } catch (e: GatewayEventFailure) { assertEquals("no_verified_subscription", e.reason) }
        repeat(32) { f.events.testWake("a") }
        try { f.events.testWake("a"); fail() } catch (e: GatewayEventFailure) { assertEquals("event_queue_capacity_reached", e.reason) }
        f.events.deliverOne("b")
        assertEquals(1, f.transport.posts.size)
        assertEquals(32, f.store.names().count { it.startsWith("eventout_") })
    }

    @Test fun cancellationBeforeVerificationCommitCannotActivateSubscription() = runBlocking {
        val f = Fixture(); f.transport.cancelVerification = true
        try { f.events.subscribe("a", subscription()); fail() } catch (_: CancellationException) { }
        assertTrue(f.store.names().none { it.startsWith("eventsub_") })
    }

    private class Fixture {
        var clock = 1_800_000_000_000L
        val store = EventMemoryStore()
        val transport = FakeEventTransport()
        val events = GatewayEvents(store, transport) { clock }
    }
}

internal class EventMemoryStore : GatewayBlobStore {
    private val data = ConcurrentHashMap<String, String>()
    override fun read(name: String) = data[name]
    override fun write(name: String, value: String) { data[name] = value }
    override fun delete(name: String) { data.remove(name) }
    override fun names() = data.keys.toList()
}
internal class FakeEventTransport : GatewayEventTransport {
    data class Post(val url: String, val headers: Map<String, String>, val body: String)
    val posts = java.util.concurrent.CopyOnWriteArrayList<Post>()
    var verify = true
    var code = 200
    var verificationCode = 200
    var cancelVerification = false
    override suspend fun post(url: String, headers: Map<String, String>, body: String): GatewayWebhookReply {
        posts.add(Post(url, headers, body))
        val data = JSONObject(body)
        if (data.optString("type") == "verification") {
            if (cancelVerification) throw CancellationException()
            return GatewayWebhookReply(verificationCode, JSONObject().put("challenge", if (verify) data.getString("challenge") else "wrong").toString())
        }
        return GatewayWebhookReply(code, "{}")
    }
}
