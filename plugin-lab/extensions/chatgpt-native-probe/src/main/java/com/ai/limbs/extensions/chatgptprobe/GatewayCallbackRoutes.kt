package com.ai.limbs.extensions.chatgptprobe

import java.net.InetAddress
import org.json.JSONObject

internal data class GatewayCallbackRoute(val prefix: ByteArray, val prefixLength: Int, val unicast: Boolean) {
    fun matches(address: ByteArray): Boolean {
        if (address.size != prefix.size) return false
        val full = prefixLength / 8
        for (i in 0 until full) if (address[i] != prefix[i]) return false
        val remaining = prefixLength % 8
        if (remaining == 0) return true
        val mask = (255 shl (8 - remaining)) and 255
        return (address[full].toInt() and mask) == (prefix[full].toInt() and mask)
    }
}

internal data class GatewayCallbackRouteSnapshot(val sourceFamilies: Set<Int>, val routes: List<GatewayCallbackRoute>)

/** Select before connecting: a DNS AAAA answer is not proof that the current VPN routes IPv6. */
internal class GatewayCallbackRoutes(private val snapshot: () -> GatewayCallbackRouteSnapshot) {
    @Volatile private var observation = JSONObject().put("checked", false)

    fun select(addresses: List<InetAddress>): List<InetAddress> {
        // Validate the complete answer, including addresses that routing would otherwise omit.
        if (addresses.any { !GatewayWebhookSecurity.publicAddress(it) }) throw GatewayNonPublicDestination()
        val current = snapshot()
        val selected = addresses.filter { address ->
            val bytes = address.address
            bytes.size in current.sourceFamilies && current.routes.filter { it.matches(bytes) }
                .maxByOrNull { it.prefixLength }?.unicast == true
        }
        observation = JSONObject().put("checked", true)
            .put("ipv4_candidates", addresses.count { it.address.size == 4 })
            .put("ipv6_candidates", addresses.count { it.address.size == 16 })
            .put("ipv4_selected", selected.count { it.address.size == 4 })
            .put("ipv6_selected", selected.count { it.address.size == 16 })
        if (selected.isEmpty()) throw GatewayWebhookFailure("no_usable_callback_route", "route", "NoRouteToHostException")
        return selected
    }

    fun status(): JSONObject = JSONObject(observation.toString())
}
