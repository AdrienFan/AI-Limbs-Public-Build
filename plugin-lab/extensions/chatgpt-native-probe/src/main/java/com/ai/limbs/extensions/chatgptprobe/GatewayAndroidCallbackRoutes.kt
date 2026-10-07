package com.ai.limbs.extensions.chatgptprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.RouteInfo
import android.os.Build

internal fun gatewayAndroidCallbackRoutes(context: Context): GatewayCallbackRoutes {
    val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    return GatewayCallbackRoutes {
        val network = connectivity.activeNetwork
            ?: throw GatewayWebhookFailure("callback_network_unavailable", "route", "NoRouteToHostException")
        val properties = connectivity.getLinkProperties(network)
            ?: throw GatewayWebhookFailure("callback_route_unavailable", "route", "NoRouteToHostException")
        // Read the caller's default network each time, including VPN. Never borrow cellular/Wi-Fi routes.
        val sourceFamilies = properties.linkAddresses.map { it.address }.filter {
            !it.isLinkLocalAddress && !it.isLoopbackAddress && !it.isAnyLocalAddress
        }.map { it.address.size }.toSet()
        val routes = properties.routes.map { route ->
            // Before API 33 getRoutes exposes unicast routes only; route type became public in API 33.
            val unicast = if (Build.VERSION.SDK_INT >= 33) route.type == RouteInfo.RTN_UNICAST else true
            GatewayCallbackRoute(route.destination.rawAddress, route.destination.prefixLength, unicast)
        }
        GatewayCallbackRouteSnapshot(sourceFamilies, routes)
    }
}
