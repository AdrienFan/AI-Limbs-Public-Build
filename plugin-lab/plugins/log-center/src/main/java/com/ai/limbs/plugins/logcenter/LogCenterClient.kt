package com.ai.limbs.plugins.logcenter

import com.ai.limbs.plugin.runtime.InProcessPluginHost
import org.json.JSONObject

data class LogSource(
    val sourceKey: String,
    val kind: String,
    val id: String,
    val displayName: String,
    val version: String?,
    val parentPluginId: String?,
    val enabled: Boolean
) {
    val searchText: String
        get() = listOf(displayName, id, version.orEmpty(), parentPluginId.orEmpty(), kind)
            .joinToString(" ").lowercase()
}

data class LogReadResult(
    val sourceKey: String,
    val content: String,
    val truncated: Boolean,
    val characters: Int
)

internal class LogCenterClient(private val host: InProcessPluginHost) {
    suspend fun sources(): List<LogSource> {
        val root = invoke("sources")
        val array = root.getJSONArray("sources")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    LogSource(
                        sourceKey = item.getString("source_key"),
                        kind = item.getString("kind"),
                        id = item.getString("id"),
                        displayName = item.getString("display_name"),
                        version = item.optString("version").trim().ifBlank { null },
                        parentPluginId = item.optString("parent_plugin_id").trim().ifBlank { null },
                        enabled = item.optBoolean("enabled", true)
                    )
                )
            }
        }
    }

    suspend fun read(sourceKey: String, maxChars: Int = 120_000): LogReadResult {
        val root = invoke("read", JSONObject().put("source_key", sourceKey).put("max_chars", maxChars))
        return LogReadResult(
            sourceKey = root.getString("source_key"),
            content = root.optString("content"),
            truncated = root.optBoolean("truncated"),
            characters = root.optInt("characters")
        )
    }

    suspend fun export(sourceKey: String): String {
        val root = invoke("export", JSONObject().put("source_key", sourceKey))
        return root.getString("path")
    }

    suspend fun clearGlobal() {
        invoke("clear", JSONObject().put("source_key", "global"))
    }

    private suspend fun invoke(operation: String, parameters: JSONObject = JSONObject()): JSONObject {
        val payload = JSONObject(parameters.toString()).put("operation", operation)
        return JSONObject(host.invokeHostCapability(HOST_LOGGING, payload.toString()))
    }

    private companion object {
        const val HOST_LOGGING = "host.logging@1"
    }
}
