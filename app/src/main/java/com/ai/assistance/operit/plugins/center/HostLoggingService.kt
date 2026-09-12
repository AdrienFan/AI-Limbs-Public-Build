package com.ai.assistance.operit.plugins.center

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ai.assistance.operit.util.AppLogger
import com.ai.limbs.plugin.runtime.ChildExtensionSnapshot
import com.ai.limbs.plugin.runtime.InProcessRuntimeLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal object HostLogTags {
    private const val PREFIX = "AILSRC"
    fun plugin(pluginId: String, tag: String): String = encoded("plugin", pluginId, tag)
    fun extension(extensionId: String, tag: String): String = encoded("extension", extensionId, tag)
    fun parse(tag: String): Pair<String, String>? {
        val parts = tag.split('|', limit = 4)
        if (parts.size < 4 || parts[0] != PREFIX) return null
        return parts[1] to parts[2]
    }
    private fun encoded(kind: String, id: String, rawTag: String): String {
        val tag = rawTag.trim().ifBlank { "Log" }.replace('|', '_').replace(':', '_').take(48)
        return "$PREFIX|$kind|$id|$tag"
    }
}

internal object HostRuntimeLoggerFactory {
    fun plugin(id: String): InProcessRuntimeLogger = bound("plugin", id)
    fun extension(id: String): InProcessRuntimeLogger = bound("extension", id)
    private fun bound(kind: String, id: String): InProcessRuntimeLogger = object : InProcessRuntimeLogger {
        private fun tag(raw: String) = if (kind == "plugin") HostLogTags.plugin(id, raw) else HostLogTags.extension(id, raw)
        override fun d(tag: String, message: String) = AppLogger.d(tag(tag), message)
        override fun i(tag: String, message: String) = AppLogger.i(tag(tag), message)
        override fun w(tag: String, message: String) = AppLogger.w(tag(tag), message)
        override fun w(tag: String, message: String, error: Throwable) = AppLogger.w(tag(tag), message, error)
        override fun e(tag: String, message: String) = AppLogger.e(tag(tag), message)
        override fun e(tag: String, message: String, error: Throwable) = AppLogger.e(tag(tag), message, error)
    }
}

internal class HostLoggingService(
    context: Context,
    private val pluginStore: PluginStore
) {
    private val appContext = context.applicationContext
    private val states = PluginStateRepository(pluginStore)
    @Volatile private var childSources: () -> List<ChildExtensionSnapshot> = { emptyList() }

    fun bindChildSourceProvider(provider: () -> List<ChildExtensionSnapshot>) {
        childSources = provider
    }

    fun invoke(ownerPluginId: String, operation: String, parameters: JSONObject): JSONObject = when (operation.trim().lowercase()) {
        "sources" -> sources()
        "read" -> read(parameters)
        "export" -> export(parameters)
        "clear" -> clear(parameters)
        "write" -> write(ownerPluginId, parameters)
        else -> throw PluginInstallException("HOST_OPERATION_UNSUPPORTED", "Unsupported host.logging@1 operation: $operation")
    }

    fun invoke(ownerPluginId: String, parameters: JSONObject): JSONObject {
        val copy = JSONObject(parameters.toString())
        val operation = copy.optString("operation", "read").trim().lowercase()
        copy.remove("operation")
        return invoke(ownerPluginId, operation, copy)
    }

    fun sources(): JSONObject {
        val items = JSONArray()
        items.put(sourceJson("global", "global", "全局日志", null, null, true))
        items.put(sourceJson("host", "host", "基座日志", null, null, true))
        pluginStore.listPluginIds().forEach { pluginId ->
            val state = states.read(pluginId)
            val version = state?.activeVersion ?: pluginStore.listVersions(pluginId).lastOrNull()
            val manifest = version?.let { runCatching { states.readInstalledManifest(pluginId, it) }.getOrNull() }
            items.put(sourceJson("plugin", pluginId, manifest?.display?.name ?: pluginId, version, null, state?.enabled == true))
        }
        childSources().sortedBy { it.displayName.lowercase(Locale.ROOT) }.forEach { child ->
            items.put(sourceJson("extension", child.extensionId, child.displayName, child.version, child.target.parentPluginId, child.enabled))
        }
        return JSONObject().put("sources", items)
    }

    private fun sourceJson(kind: String, id: String, name: String, version: String?, parentId: String?, enabled: Boolean) =
        JSONObject()
            .put("source_key", if (kind == "global" || kind == "host") kind else "$kind:$id")
            .put("kind", kind)
            .put("id", id)
            .put("display_name", name)
            .put("version", version ?: JSONObject.NULL)
            .put("parent_plugin_id", parentId ?: JSONObject.NULL)
            .put("enabled", enabled)

    private fun read(parameters: JSONObject): JSONObject {
        val sourceKey = sourceKey(parameters)
        val maximum = parameters.optInt("max_chars", 60_000).coerceIn(1_000, 240_000)
        val full = filtered(sourceKey)
        val content = if (full.length > maximum) full.takeLast(maximum) else full
        return JSONObject()
            .put("source_key", sourceKey)
            .put("content", content)
            .put("truncated", full.length > content.length)
            .put("characters", content.length)
    }

    private fun clear(parameters: JSONObject): JSONObject {
        val sourceKey = sourceKey(parameters)
        if (sourceKey != "global") {
            throw PluginInstallException("LOG_CLEAR_GLOBAL_ONLY", "Only global log clearing is supported")
        }
        AppLogger.resetLogFile()
        return JSONObject().put("cleared", true).put("source_key", "global")
    }

    private fun write(ownerPluginId: String, parameters: JSONObject): JSONObject {
        val message = parameters.optString("message")
        if (message.isBlank()) throw PluginInstallException("LOG_MESSAGE_REQUIRED", "write requires message")
        val logger = HostRuntimeLoggerFactory.plugin(ownerPluginId)
        val tag = parameters.optString("tag", "Plugin")
        when (parameters.optString("level", "info").trim().lowercase()) {
            "debug", "d" -> logger.d(tag, message)
            "warn", "warning", "w" -> logger.w(tag, message)
            "error", "e" -> logger.e(tag, message)
            else -> logger.i(tag, message)
        }
        return JSONObject().put("written", true).put("source_key", "plugin:$ownerPluginId")
    }

    private fun export(parameters: JSONObject): JSONObject {
        val sourceKey = sourceKey(parameters)
        val content = filtered(sourceKey)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = sourceKey.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80)
        val fileName = "ai_limbs_log_${safe}_$stamp.txt"
        val header = "AI Limbs 日志\n范围: $sourceKey\n导出时间: ${Date()}\n\n"
        val bytes = (header + content).toByteArray(Charsets.UTF_8)
        val resultPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AiLimbs")
            }
            val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw PluginInstallException("LOG_EXPORT_CREATE_FAILED", "Could not create Downloads/AiLimbs/$fileName")
            appContext.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw PluginInstallException("LOG_EXPORT_OPEN_FAILED", "Could not open exported log")
            "Downloads/AiLimbs/$fileName"
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AiLimbs").apply { mkdirs() }
            File(dir, fileName).also { it.writeBytes(bytes) }.absolutePath
        }
        return JSONObject().put("exported", true).put("source_key", sourceKey).put("path", resultPath).put("characters", content.length)
    }

    private fun sourceKey(parameters: JSONObject): String {
        parameters.optString("source_key").trim().takeIf { it.isNotEmpty() }?.let { return it }
        val scope = parameters.optString("scope", "global").trim().lowercase()
        val id = parameters.optString("source_id").trim()
        return when (scope) {
            "global", "host" -> scope
            "plugin", "extension" -> if (id.isBlank()) throw PluginInstallException("LOG_SOURCE_REQUIRED", "$scope requires source_id") else "$scope:$id"
            else -> throw PluginInstallException("LOG_SOURCE_INVALID", "Unknown log source: $scope")
        }
    }

    private fun filtered(sourceKey: String): String {
        val file = AppLogger.getLogFile()
        val full = if (file?.isFile == true) file.readText() else ""
        if (sourceKey == "global") return full
        val expected = when {
            sourceKey == "host" -> null
            sourceKey.startsWith("plugin:") -> "plugin" to sourceKey.removePrefix("plugin:")
            sourceKey.startsWith("extension:") -> "extension" to sourceKey.removePrefix("extension:")
            else -> throw PluginInstallException("LOG_SOURCE_INVALID", "Unknown source_key: $sourceKey")
        }
        return buildString {
            records(full).forEach { record ->
                val actual = recordSource(record)
                val include = if (sourceKey == "host") actual == null else actual == expected
                if (include) append(record)
            }
        }
    }

    private fun records(full: String): List<String> {
        if (full.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        full.lineSequence().forEach { line ->
            if (ENTRY_START.matches(line) && current.isNotEmpty()) {
                result += current.toString()
                current.setLength(0)
            }
            current.append(line).append('\n')
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun recordSource(record: String): Pair<String, String>? {
        val first = record.lineSequence().firstOrNull() ?: return null
        val marker = TAG_CAPTURE.find(first)?.groupValues?.getOrNull(1) ?: return null
        return HostLogTags.parse(marker)
    }

    private companion object {
        val ENTRY_START = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} [VDIWEA?]/.*")
        val TAG_CAPTURE = Regex("^[^ ]+ [^ ]+ [VDIWEA?]/([^:]+):")
    }
}
