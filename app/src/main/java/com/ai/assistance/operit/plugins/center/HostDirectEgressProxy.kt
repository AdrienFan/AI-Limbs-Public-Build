package com.ai.assistance.operit.plugins.center

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.EOFException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Host-owned loopback SOCKS5 proxy whose outbound sockets are pinned to a
 * validated physical Android Network with NET_CAPABILITY_NOT_VPN.
 *
 * The listener is loopback-only and exposes CONNECT without authentication.
 * It is intended only for same-device AI Limbs runtimes such as Ubuntu.
 */
internal object HostDirectEgressProxy {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val BACKLOG = 32

    private val lock = Any()
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ai-limbs-direct-egress").apply { isDaemon = true }
    }

    @Volatile
    private var running: Running? = null

    private data class Candidate(
        val network: Network,
        val transport: String,
        val validated: Boolean,
        val metered: Boolean,
        val score: Int
    )

    private data class Running(
        val server: ServerSocket,
        val network: Network,
        val networkHandle: Long,
        val transport: String,
        val validated: Boolean,
        val metered: Boolean
    )

    fun snapshot(context: Context): JSONObject = synchronized(lock) {
        val appContext = context.applicationContext
        val connectivity =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidate = selectDirectNetwork(connectivity)
        if (candidate == null) {
            stopLocked()
            return JSONObject()
                .put("available", false)
                .put("scheme", "socks5h")
                .put("host", LOOPBACK_HOST)
                .put("updated_at_epoch_ms", System.currentTimeMillis())
                .put("reason", "No validated NOT_VPN Wi-Fi, Ethernet, or cellular network is available")
        }

        val current = running
        val selected = if (
            current != null &&
            !current.server.isClosed &&
            current.networkHandle == candidate.network.networkHandle
        ) {
            current
        } else {
            stopLocked()
            startLocked(candidate)
        }

        return JSONObject()
            .put("available", true)
            .put("scheme", "socks5h")
            .put("host", LOOPBACK_HOST)
            .put("port", selected.server.localPort)
            .put("transport", selected.transport)
            .put("validated", selected.validated)
            .put("metered", selected.metered)
            .put("network_handle", selected.networkHandle)
            .put("network_not_vpn", true)
            .put("bypass_verified", false)
            .put("updated_at_epoch_ms", System.currentTimeMillis())
    }

    private fun selectDirectNetwork(connectivity: ConnectivityManager): Candidate? =
        connectivity.allNetworks
            .mapNotNull { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null

                val transport = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    else -> return@mapNotNull null
                }
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                val score = when (transport) {
                    "wifi" -> 300
                    "ethernet" -> 250
                    "cellular" -> 200
                    else -> 0
                } + (if (validated) 100 else 0) + (if (!metered) 10 else 0)
                Candidate(network, transport, validated, metered, score)
            }
            .filter { it.validated }
            .maxByOrNull { it.score }

    private fun startLocked(candidate: Candidate): Running {
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0), BACKLOG)
        }
        val state = Running(
            server = server,
            network = candidate.network,
            networkHandle = candidate.network.networkHandle,
            transport = candidate.transport,
            validated = candidate.validated,
            metered = candidate.metered
        )
        running = state
        workers.execute { acceptLoop(state) }
        return state
    }

    private fun stopLocked() {
        val old = running
        running = null
        runCatching { old?.server?.close() }
    }

    private fun acceptLoop(state: Running) {
        while (!state.server.isClosed && running === state) {
            val client = try {
                state.server.accept()
            } catch (_: Throwable) {
                break
            }
            workers.execute { handleClient(state.network, client) }
        }
    }

    private fun handleClient(network: Network, client: Socket) {
        client.use { local ->
            local.tcpNoDelay = true
            val input = local.getInputStream()
            val output = local.getOutputStream()

            try {
                val version = input.readRequired()
                val methodCount = input.readRequired()
                if (version != 0x05 || methodCount <= 0) throw SocksFailure(0x01)
                val methods = input.readExact(methodCount)
                if (methods.none { (it.toInt() and 0xff) == 0x00 }) {
                    output.write(byteArrayOf(0x05, 0xff.toByte()))
                    output.flush()
                    return
                }
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                val request = input.readExact(4)
                if ((request[0].toInt() and 0xff) != 0x05) throw SocksFailure(0x01)
                if ((request[1].toInt() and 0xff) != 0x01) throw SocksFailure(0x07)

                val addressType = request[3].toInt() and 0xff
                val target = when (addressType) {
                    0x01 -> InetAddress.getByAddress(input.readExact(4)).hostAddress
                    0x03 -> {
                        val length = input.readRequired()
                        String(input.readExact(length), StandardCharsets.UTF_8)
                    }
                    0x04 -> InetAddress.getByAddress(input.readExact(16)).hostAddress
                    else -> throw SocksFailure(0x08)
                }
                val portBytes = input.readExact(2)
                val port = ((portBytes[0].toInt() and 0xff) shl 8) or
                    (portBytes[1].toInt() and 0xff)

                val remote = connect(network, target, port)
                remote.use { outbound ->
                    val bound = outbound.localAddress
                    writeReply(output, 0x00, bound, outbound.localPort)

                    val upstream = workers.submit {
                        try {
                            local.getInputStream().copyTo(outbound.getOutputStream())
                            runCatching { outbound.shutdownOutput() }
                        } catch (_: Throwable) {
                            runCatching { outbound.close() }
                        }
                    }
                    try {
                        outbound.getInputStream().copyTo(local.getOutputStream())
                        runCatching { local.shutdownOutput() }
                    } finally {
                        upstream.cancel(true)
                    }
                }
            } catch (failure: SocksFailure) {
                runCatching { writeReply(output, failure.replyCode, null, 0) }
            } catch (_: Throwable) {
                runCatching { writeReply(output, 0x01, null, 0) }
            }
        }
    }

    private fun connect(network: Network, target: String, port: Int): Socket {
        require(port in 1..65535) { "Invalid target port" }
        val addresses = network.getAllByName(target)
        var lastError: Throwable? = null
        for (address in addresses) {
            val socket = network.socketFactory.createSocket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                return socket
            } catch (error: Throwable) {
                lastError = error
                runCatching { socket.close() }
            }
        }
        throw lastError ?: IllegalStateException("No address resolved for $target")
    }

    private fun writeReply(
        output: java.io.OutputStream,
        reply: Int,
        boundAddress: InetAddress?,
        boundPort: Int
    ) {
        val address = when (boundAddress) {
            is Inet4Address -> boundAddress.address
            is Inet6Address -> boundAddress.address
            else -> byteArrayOf(0, 0, 0, 0)
        }
        val atyp = if (address.size == 16) 0x04 else 0x01
        output.write(byteArrayOf(0x05, reply.toByte(), 0x00, atyp.toByte()))
        output.write(address)
        output.write(byteArrayOf(
            ((boundPort ushr 8) and 0xff).toByte(),
            (boundPort and 0xff).toByte()
        ))
        output.flush()
    }

    private fun InputStream.readRequired(): Int {
        val value = read()
        if (value < 0) throw EOFException("Unexpected end of SOCKS5 request")
        return value
    }

    private fun InputStream.readExact(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(result, offset, size - offset)
            if (count < 0) throw EOFException("Unexpected end of SOCKS5 request")
            offset += count
        }
        return result
    }

    private class SocksFailure(val replyCode: Int) : Exception()

    private const val LOOPBACK_HOST = "127.0.0.1"
}
