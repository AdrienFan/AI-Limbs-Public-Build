package com.ai.limbs.extensions.systemenvironment.ubuntu

import com.ai.limbs.plugin.runtime.InProcessPluginHost
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mirrors a narrow Host-owned Android TCP listener snapshot into the Ubuntu rootfs.
 * No Android Shell command or raw /proc content crosses the plugin ABI.
 */
internal class UbuntuSubsystemHostListenerSync(
    private val host: InProcessPluginHost
) : AutoCloseable {
    private val rootfsDir = File(
        host.dataDir,
        "usr/var/lib/proot-distro/installed-rootfs/ubuntu"
    )
    private val stateFile = File(
        rootfsDir,
        "root/laner/tools/laner-net/host-listeners.json"
    )
    private val job: Job = host.scope.launch(Dispatchers.IO) {
        while (isActive) {
            try {
                syncOnce()
            } catch (_: Throwable) {
                // Best-effort helper: never take the Ubuntu plugin down with it.
            }
            delay(SYNC_INTERVAL_MS)
        }
    }

    private suspend fun syncOnce() {
        // Do not create a fake rootfs before Ubuntu has actually been installed.
        if (!rootfsDir.isDirectory) return

        val listeners = try {
            JSONObject(
                host.invokeHostCapability(
                    HOST_NETWORK_CAPABILITY,
                    JSONObject().put("operation", "listeners").toString()
                )
            )
        } catch (error: Throwable) {
            JSONObject()
                .put("available", false)
                .put("ports", JSONArray())
                .put("reason", error.message ?: error::class.java.simpleName)
        }
        val directProxy = try {
            JSONObject(
                host.invokeHostCapability(
                    HOST_NETWORK_CAPABILITY,
                    JSONObject().put("operation", "direct_proxy").toString()
                )
            )
        } catch (error: Throwable) {
            JSONObject()
                .put("available", false)
                .put("reason", error.message ?: error::class.java.simpleName)
        }
        val snapshot = sanitize(listeners, directProxy)
        writeSnapshot(snapshot)
        writeDirectRoute(snapshot.getJSONObject("direct_proxy"))
    }

    private fun sanitize(raw: JSONObject, directRaw: JSONObject): JSONObject {
        val ports = mutableSetOf<Int>()
        val sourcePorts = raw.optJSONArray("ports") ?: JSONArray()
        for (index in 0 until sourcePorts.length()) {
            sourcePorts.optInt(index, -1)
                .takeIf { it in 1..65535 }
                ?.let(ports::add)
        }

        val direct = JSONObject()
            .put("available", directRaw.optBoolean("available", false))
            .put("scheme", directRaw.optString("scheme", "socks5h"))
            .put("host", directRaw.optString("host", "127.0.0.1"))
            .put("port", directRaw.optInt("port", 0))
            .put("transport", directRaw.optString("transport"))
            .put("validated", directRaw.optBoolean("validated", false))
            .put("metered", directRaw.optBoolean("metered", true))
            .put("network_not_vpn", directRaw.optBoolean("network_not_vpn", false))
            .put("bypass_verified", directRaw.optBoolean("bypass_verified", false))
            .put("network_handle", directRaw.optLong("network_handle", 0L))
            .put("host_updated_at_epoch_ms", directRaw.optLong("updated_at_epoch_ms", 0L))
        directRaw.optString("reason").trim().takeIf { it.isNotEmpty() }?.let {
            direct.put("reason", it.take(MAX_REASON_CHARS))
        }

        val result = JSONObject()
            .put("schema", 2)
            .put("source", "host.network@1")
            .put("available", raw.optBoolean("available", false))
            .put("ports", JSONArray(ports.sorted()))
            .put("direct_proxy", direct)
            .put("updated_at_epoch_ms", System.currentTimeMillis())
            .put(
                "host_updated_at_epoch_ms",
                raw.optLong("updated_at_epoch_ms", 0L)
            )
        raw.optString("reason").trim().takeIf { it.isNotEmpty() }?.let {
            result.put("reason", it.take(MAX_REASON_CHARS))
        }
        return result
    }
    private fun writeSnapshot(snapshot: JSONObject) {
        val parent = stateFile.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) return

        val temporary = File(parent, ".${stateFile.name}.tmp")
        temporary.writeText(snapshot.toString(), Charsets.UTF_8)
        if (!temporary.renameTo(stateFile)) {
            temporary.copyTo(stateFile, overwrite = true)
            temporary.delete()
        }
    }

    private fun writeDirectRoute(direct: JSONObject) {
        // Fresh child execs re-read this path after a physical-network/port change.
        // Without a DIRECT network, fail the SOCKS route on loopback, never use VPN.
        val available = direct.optBoolean("available") && direct.optBoolean("validated") &&
            direct.optBoolean("network_not_vpn") && direct.optString("host") == "127.0.0.1" &&
            direct.optString("scheme") == "socks5h" && direct.optInt("port") in 1..65535
        val port = if (available) direct.getInt("port") else 1
        val route = File(stateFile.parentFile, "direct-proxychains.conf")
        val temporary = File(route.parentFile, ".direct-proxychains.conf.tmp")
        temporary.writeText(
            "strict_chain\nquiet_mode\nproxy_dns\nremote_dns_subnet 224\n" +
                "tcp_read_time_out 15000\ntcp_connect_time_out 12000\n" +
                "localnet 127.0.0.0/255.0.0.0\nlocalnet ::1/128\n" +
                "[ProxyList]\nsocks5 127.0.0.1 $port\n",
            Charsets.UTF_8
        )
        check(temporary.renameTo(route)) { "Could not publish DIRECT route configuration" }
    }

    override fun close() {
        job.cancel()
    }

    private companion object {
        const val HOST_NETWORK_CAPABILITY = "host.network@1"
        const val SYNC_INTERVAL_MS = 15_000L
        const val MAX_REASON_CHARS = 300
    }
}
