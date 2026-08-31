package com.ai.assistance.operit.core.tools.packTool

import com.ai.assistance.operit.core.tools.ToolPackage
import com.ai.assistance.operit.util.AppLogger
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.hjson.JsonValue
import org.json.JSONArray
import org.json.JSONObject

/** Minimal compatibility parser for ToolPkg subpackage JS metadata. */
internal object LegacyJsToolPackageParser {
    private const val TAG = "LegacyJsToolPackageParser"

    fun parse(
        jsContent: String,
        onError: (key: String, error: String) -> Unit = { _, _ -> }
    ): ToolPackage? {
        return try {
            val metadata = extractMetadata(jsContent)
            val metadataJson = JSONObject(JsonValue.readHjson(metadata).toString())
            normalizeMetadata(metadataJson)
            ensureToolScripts(metadataJson)
            val packageMetadata =
                Json { ignoreUnknownKeys = true }
                    .decodeFromString<ToolPackage>(metadataJson.toString())
            val tools = packageMetadata.tools.map { tool ->
                if (!tool.advice) validateToolFunctionExists(jsContent, tool.name)
                tool.copy(script = jsContent)
            }
            val states = packageMetadata.states.map { state ->
                state.copy(
                    tools = state.tools.map { tool ->
                        if (!tool.advice) validateToolFunctionExists(jsContent, tool.name)
                        tool.copy(script = jsContent)
                    }
                )
            }
            packageMetadata.copy(tools = tools, states = states)
        } catch (error: Exception) {
            AppLogger.e(TAG, "Error parsing JS package: ${error.message}", error)
            val errorKey = runCatching {
                val metadataJson =
                    JSONObject(JsonValue.readHjson(extractMetadata(jsContent)).toString())
                metadataJson.optString("name").takeIf(String::isNotBlank) ?: "unknown"
            }.getOrDefault("unknown")
            onError(errorKey, error.stackTraceToString())
            null
        }
    }

    private fun ensureToolScripts(metadataJson: JSONObject) {
        ensureScriptField(metadataJson.optJSONArray("tools"))
        val states = metadataJson.optJSONArray("states") ?: return
        for (index in 0 until states.length()) {
            val state = states.optJSONObject(index) ?: continue
            ensureScriptField(state.optJSONArray("tools"))
        }
    }

    private fun ensureScriptField(tools: JSONArray?) {
        if (tools == null) return
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: continue
            if (!tool.has("script")) tool.put("script", "")
        }
    }

    private fun normalizeMetadata(metadataJson: JSONObject) {
        normalizeBooleanAlias(metadataJson, "enabledByDefault", "enabled_by_default")
        normalizeBooleanAlias(metadataJson, "isBuiltIn", "is_built_in")
        val category = metadataJson.opt("category")?.toString()?.trim().orEmpty()
        metadataJson.put("category", category.ifBlank { "Other" })
    }

    private fun normalizeBooleanAlias(
        metadataJson: JSONObject,
        canonicalKey: String,
        legacyAlias: String
    ) {
        if (!metadataJson.has(canonicalKey) && metadataJson.has(legacyAlias)) {
            metadataJson.put(canonicalKey, metadataJson.opt(legacyAlias))
        }
        if (!metadataJson.has(canonicalKey)) return
        normalizeBoolean(metadataJson.opt(canonicalKey))?.let { value ->
            metadataJson.put(canonicalKey, value)
        }
    }

    private fun normalizeBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
        else -> null
    }

    private fun validateToolFunctionExists(jsContent: String, toolName: String): Boolean {
        val escapedName = Regex.escape(toolName)
        val patterns = listOf(
            "async\\s+function\\s+$escapedName\\s*\\(",
            "function\\s+$escapedName\\s*\\(",
            "exports\\.$escapedName\\s*=\\s*(?:async\\s+)?function",
            "(?:const|let|var)\\s+$escapedName\\s*=\\s*(?:async\\s+)?\\(",
            "exports\\.$escapedName\\s*=\\s*(?:async\\s+)?\\(?"
        )
        if (patterns.any { Regex(it).containsMatchIn(jsContent) }) return true
        AppLogger.w(TAG, "Could not find function '$toolName' in JavaScript file")
        return false
    }

    private fun extractMetadata(jsContent: String): String {
        val match = Regex("""/\*\s*METADATA\s*([\s\S]*?)\*/""").find(jsContent)
        return match?.groupValues?.get(1)?.trim() ?: "{}"
    }
}
