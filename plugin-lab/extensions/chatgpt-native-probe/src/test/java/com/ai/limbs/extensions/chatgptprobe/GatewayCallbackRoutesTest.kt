package com.ai.limbs.extensions.chatgptprobe

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GatewayCallbackRoutesTest {
    private val ipv4 = InetAddress.getByName("8.8.8.8")
    private val ipv6 = InetAddress.getByName("2606:4700:4700::1111")
    private fun route(prefix: String, bits: Int, unicast: Boolean = true) =
        GatewayCallbackRoute(InetAddress.getByName(prefix).address, bits, unicast)
    private fun vpn4() = GatewayCallbackRouteSnapshot(setOf(4), listOf(
        route("0.0.0.0", 1), route("128.0.0.0", 1), route("::", 0, false)))

    @Test fun ipv4VpnOmitsUnreachableIpv6WithoutTryingTheConnection() {
        val routes = GatewayCallbackRoutes(::vpn4)
        assertEquals(listOf(ipv4), routes.select(listOf(ipv6, ipv4)))
        assertEquals(1, routes.status().getInt("ipv6_candidates"))
        assertEquals(0, routes.status().getInt("ipv6_selected"))
        assertEquals(1, routes.status().getInt("ipv4_selected"))
    }

    @Test fun dualStackRetainsBothFamiliesInDnsOrder() {
        val routes = GatewayCallbackRoutes { GatewayCallbackRouteSnapshot(setOf(4, 16),
            listOf(route("0.0.0.0", 0), route("::", 0))) }
        assertEquals(listOf(ipv6, ipv4), routes.select(listOf(ipv6, ipv4)))
    }

    @Test fun ipv6NetworkRetainsIpv6AndRejectsUnroutedIpv4() {
        val routes = GatewayCallbackRoutes { GatewayCallbackRouteSnapshot(setOf(16), listOf(route("::", 0))) }
        assertEquals(listOf(ipv6), routes.select(listOf(ipv4, ipv6)))
    }

    @Test fun moreSpecificUnreachableRouteOverridesBroadUnicast() {
        val routes = GatewayCallbackRoutes { GatewayCallbackRouteSnapshot(setOf(4), listOf(
            route("0.0.0.0", 0), route("8.8.8.0", 24, false))) }
        val other = InetAddress.getByName("1.1.1.1")
        assertEquals(listOf(other), routes.select(listOf(ipv4, other)))
    }

    @Test fun routeSnapshotIsReadAgainAfterNetworkChanges() {
        val current = AtomicReference(vpn4())
        val routes = GatewayCallbackRoutes(current::get)
        assertEquals(listOf(ipv4), routes.select(listOf(ipv6, ipv4)))
        current.set(GatewayCallbackRouteSnapshot(setOf(16), listOf(route("::", 0))))
        assertEquals(listOf(ipv6), routes.select(listOf(ipv6, ipv4)))
    }

    @Test fun nonPublicAnswerCannotBeHiddenByRouteSelection() {
        val routes = GatewayCallbackRoutes { fail("Public validation must precede route access"); vpn4() }
        try { routes.select(listOf(ipv4, InetAddress.getByName("fd00::1"))); fail("Non-public answer accepted") }
        catch (_: GatewayNonPublicDestination) { }
    }

    @Test fun unavailableRouteProducesSafeFailureBeforeTlsOrHttp() = runBlocking {
        val routes = GatewayCallbackRoutes(::vpn4)
        val transport = GatewayWebhookHttp(object : okhttp3.Dns {
            override fun lookup(hostname: String) = listOf(ipv6)
        }, routes)
        try {
            transport.post("https://callback.example.com/events?token=secret", emptyMap(), "{}")
            fail("Unreachable IPv6 must not connect")
        } catch (error: GatewayWebhookFailure) {
            assertEquals("no_usable_callback_route", error.reason)
            assertEquals("route", error.stage)
            assertFalse(error.diagnostic().toString().contains("secret"))
            assertTrue(transport.dnsStatus().isNull("last_connection_family"))
        } finally { transport.close() }
    }

    @Test fun partialBytePrefixesMatchExactly() {
        val prefix = route("8.0.0.0", 7)
        assertTrue(prefix.matches(InetAddress.getByName("9.255.255.255").address))
        assertFalse(prefix.matches(InetAddress.getByName("10.0.0.1").address))
        assertFalse(prefix.matches(ipv6.address))
    }
}
