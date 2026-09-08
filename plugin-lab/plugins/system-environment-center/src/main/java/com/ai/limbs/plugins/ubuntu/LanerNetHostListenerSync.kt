package com.ai.limbs.plugins.ubuntu

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
internal class LanerNetHostListenerSync(
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

        val snapshot = try {
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
        writeSnapshot(sanitize(snapshot))
    }

    private fun sanitize(raw: JSONObject): JSONObject {
        val ports = mutableSetOf<Int>()
        val sourcePorts = raw.optJSONArray("ports") ?: JSONArray()
        for (index in 0 until sourcePorts.length()) {
            sourcePorts.optInt(index, -1)
                .takeIf { it in 1..65535 }
                ?.let(ports::add)
        }

        val result = JSONObject()
            .put("schema", 1)
            .put("source", "host.network@1/listeners")
            .put("available", raw.optBoolean("available", false))
            .put("ports", JSONArray(ports.sorted()))
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

    override fun close() {
        job.cancel()
    }

    private companion object {
        const val HOST_NETWORK_CAPABILITY = "host.network@1"
        const val SYNC_INTERVAL_MS = 15_000L
        const val MAX_REASON_CHARS = 300
    }
}
