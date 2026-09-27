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
 * Mirrors the Host-owned runtime component inventory into the Ubuntu rootfs for source tooling.
 * The Host owns component discovery; Ubuntu only consumes the stable primitive contract.
 */
internal class UbuntuSubsystemHostComponentSync(
    private val host: InProcessPluginHost
) : AutoCloseable {
    private val rootfsDir = File(
        host.dataDir,
        "usr/var/lib/proot-distro/installed-rootfs/ubuntu"
    )
    private val stateFile = File(
        rootfsDir,
        "root/laner/registry/runtime/host-components.json"
    )
    private val job: Job = host.scope.launch(Dispatchers.IO) {
        while (isActive) {
            try {
                syncOnce()
            } catch (_: Throwable) {
                // Best-effort helper: source tooling must not take Ubuntu runtime down.
            }
            delay(SYNC_INTERVAL_MS)
        }
    }

    private suspend fun syncOnce() {
        if (!rootfsDir.isDirectory) return

        val raw = JSONObject(
            host.invokeHostCapability(
                HOST_RUNTIME_COMPONENTS,
                JSONObject().put("operation", "snapshot").toString()
            )
        )
        writeSnapshot(sanitize(raw))
    }

    private fun sanitize(raw: JSONObject): JSONObject {
        val source = raw.optJSONArray("components") ?: JSONArray()
        val components = JSONArray()

        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val kind = item.optString("kind").trim().lowercase()
            if (kind !in ALLOWED_KINDS) continue
            val identity = item.optString("identity").trim()
            if (identity.isEmpty()) continue

            val clean = JSONObject()
                .put("kind", kind)
                .put("identity", identity)
                .put(
                    "version",
                    item.optString("version").trim().takeIf { it.isNotEmpty() }
                        ?: JSONObject.NULL
                )
                .put("enabled", item.optBoolean("enabled", false))
                .put(
                    "state",
                    item.optString("state").trim().takeIf { it.isNotEmpty() }
                        ?: JSONObject.NULL
                )

            if (kind == "base" && item.has("version_code")) {
                clean.put("version_code", item.optLong("version_code"))
            }

            item.optJSONArray("installed_versions")?.let { versions ->
                clean.put(
                    "installed_versions",
                    JSONArray().apply {
                        for (i in 0 until versions.length()) {
                            versions.optString(i).trim()
                                .takeIf { it.isNotEmpty() }
                                ?.let(::put)
                        }
                    }
                )
            }

            for (key in OPTIONAL_STRING_FIELDS) {
                item.optString(key).trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { clean.put(key, it) }
            }
            if (item.has("api_version")) {
                clean.put("api_version", item.optInt("api_version"))
            }
            components.put(clean)
        }

        return JSONObject()
            .put("schema_version", raw.optInt("schema_version", 1))
            .put("authority", raw.optString("authority", "AI_LIMBS_HOST_RUNTIME"))
            .put("host_updated_at_epoch_ms", raw.optLong("updated_at_epoch_ms", 0L))
            .put("received_at_epoch_ms", System.currentTimeMillis())
            .put("component_count", components.length())
            .put("components", components)
    }

    private fun writeSnapshot(snapshot: JSONObject) {
        val parent = stateFile.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) return

        val temporary = File(parent, "." + stateFile.name + ".tmp")
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
        const val HOST_RUNTIME_COMPONENTS = "host.runtime.components@1"
        const val SYNC_INTERVAL_MS = 15_000L
        val ALLOWED_KINDS = setOf("base", "parent_plugin", "child_extension")
        val OPTIONAL_STRING_FIELDS = listOf(
            "mounted_version",
            "display_name",
            "parent_plugin_id",
            "extension_point"
        )
    }
}
