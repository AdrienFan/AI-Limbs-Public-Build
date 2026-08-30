package com.ai.assistance.operit.integrations.ailimbs

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Desktop Commander streaming-search compatibility for the Android RDC bridge.
 * Search work is delegated through the normal AI Limbs dispatcher so host-tool
 * permission, receipts, and lifecycle policy remain authoritative.
 */
internal class AiLimbsRdcSearchCompat(
    private val remoteExecutor: AiLimbsRemoteInvocationExecutor,
    private val scope: CoroutineScope
) {
    private val sessions = ConcurrentHashMap<String, SearchSession>()

    suspend fun start(args: JSONObject): JSONObject {
        pruneExpired()
        val path = args.optString("path").trim()
        val pattern = args.optString("pattern").trim()
        val searchType = args.optString("searchType", "files").trim().lowercase()

        if (path.isBlank()) return mcpError("start_search requires path")
        if (pattern.isBlank()) return mcpError("start_search requires pattern")
        if (searchType !in SUPPORTED_SEARCH_TYPES) {
            return mcpError("start_search searchType must be 'files' or 'content'")
        }

        val sessionId = "search_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val session = SearchSession(
            id = sessionId,
            searchType = searchType,
            pattern = pattern,
            path = path,
            createdAtMs = System.currentTimeMillis()
        )
        sessions[sessionId] = session
        session.job = scope.launch(Dispatchers.IO) {
            runSearch(session, args)
        }

        return mcpText(
            buildString {
                appendLine("Started $searchType search session: $sessionId")
                appendLine(" Pattern: \"$pattern\"")
                appendLine(" Path: $path")
                appendLine(" Status: RUNNING")
                appendLine(" Total results: 0")
                append("\n🔄 Search in progress. Use get_more_search_results to get more results.")
            }
        )
    }

    fun getMore(args: JSONObject): JSONObject {
        pruneExpired()
        val sessionId = args.optString("sessionId").trim()
        val session = sessions[sessionId]
            ?: return mcpError("Unknown search session: $sessionId")
        val lines = session.output.lines().filterNot { it.isEmpty() }
        val offset = args.optInt("offset", 0)
        val length = args.optInt("length", DEFAULT_PAGE_SIZE).coerceAtLeast(1)
        val start = when {
            offset < 0 -> (lines.size + offset).coerceAtLeast(0)
            else -> offset.coerceAtMost(lines.size)
        }
        val end = if (offset < 0) lines.size else (start + length).coerceAtMost(lines.size)
        val selected = if (start < end) lines.subList(start, end) else emptyList()

        val text = buildString {
            appendLine("Search session: ${session.id}")
            appendLine("Status: ${session.status}")
            appendLine("Results: $start-${(end - 1).coerceAtLeast(start)} of ${lines.size}")
            if (selected.isNotEmpty()) append(selected.joinToString("\n"))
            if (session.status == SearchStatus.RUNNING) {
                append("\n\n🔄 Search in progress. Call get_more_search_results again.")
            }
            session.error?.let { append("\n\nError: $it") }
        }
        return if (session.status == SearchStatus.FAILED) mcpError(text) else mcpText(text)
    }

    fun stop(args: JSONObject): JSONObject {
        pruneExpired()
        val sessionId = args.optString("sessionId").trim()
        val session = sessions[sessionId]
            ?: return mcpError("Unknown search session: $sessionId")
        if (session.status == SearchStatus.RUNNING) {
            session.status = SearchStatus.STOPPED
            session.job?.cancel()
        }
        return mcpText("Stopped search session: $sessionId\nStatus: ${session.status}")
    }

    private suspend fun runSearch(session: SearchSession, args: JSONObject) {
        val hostTool = if (session.searchType == "content") "grep_code" else "find_files"
        val parameters = if (session.searchType == "content") {
            contentSearchParameters(session, args)
        } else {
            fileSearchParameters(session, args)
        }
        try {
            val result = executeHostTool(hostTool, parameters)
            if (session.status == SearchStatus.STOPPED) return
            if (result.optBoolean("success", false)) {
                session.output = extractResultText(result)
                session.status = SearchStatus.COMPLETED
            } else {
                session.output = extractResultText(result)
                session.error = extractError(result)
                session.status = SearchStatus.FAILED
            }
        } catch (error: Exception) {
            if (session.status == SearchStatus.STOPPED) return
            session.error = error.message ?: error::class.java.simpleName
            session.status = SearchStatus.FAILED
        }
    }

    private fun fileSearchParameters(session: SearchSession, args: JSONObject): JSONObject {
        val literal = args.optBoolean("literalSearch", false)
        val rawPattern = session.pattern
        val pattern = when {
            literal -> rawPattern
            rawPattern.any { it in "*?[]" } -> rawPattern
            else -> "*$rawPattern*"
        }
        return JSONObject()
            .put("path", session.path)
            .put("environment", resolveEnvironment(session.path))
            .put("pattern", pattern)
            .put("case_insensitive", args.optBoolean("ignoreCase", true))
    }

    private fun contentSearchParameters(session: SearchSession, args: JSONObject): JSONObject {
        val pattern = if (args.optBoolean("literalSearch", false)) {
            escapeRegexLiteral(session.pattern)
        } else {
            session.pattern
        }
        return JSONObject()
            .put("path", session.path)
            .put("environment", resolveEnvironment(session.path))
            .put("pattern", pattern)
            .put("file_pattern", args.optString("filePattern", "*"))
            .put("case_insensitive", args.optBoolean("ignoreCase", true))
            .put("context_lines", args.optInt("contextLines", 5).coerceAtLeast(0))
            .put("max_results", args.optInt("maxResults", 100).coerceIn(1, 1000))
    }

    private suspend fun executeHostTool(name: String, parameters: JSONObject): JSONObject =
        remoteExecutor.execute(
            AiLimbsCoreCapabilityRegistry.invokeNameForLocalOperation(
                AiLimbsCoreLocalOperation.HOST_TOOL_EXECUTE
            ),
            JSONObject()
                .put("name", name)
                .put("parameters", parameters)
        )

    private fun extractResultText(result: JSONObject): String {
        val nested = result.opt("result")
        if (nested is JSONObject) {
            nested.optString("value").takeIf { it.isNotBlank() }?.let { return it }
        }
        if (nested is String && nested.isNotBlank()) return nested
        result.optString("value").takeIf { it.isNotBlank() }?.let { return it }
        return result.toString(2)
    }

    private fun extractError(result: JSONObject): String =
        result.optString("error").takeIf { it.isNotBlank() }
            ?: result.optString("reason").takeIf { it.isNotBlank() }
            ?: "AI Limbs search backend returned an unsuccessful result"

    private fun resolveEnvironment(path: String): String =
        if (LINUX_PREFIXES.any { prefix -> path == prefix || path.startsWith("$prefix/") }) {
            "linux"
        } else {
            "android"
        }

    private fun escapeRegexLiteral(value: String): String = buildString {
        value.forEach { ch ->
            if (ch in REGEX_META_CHARS) append('\\')
            append(ch)
        }
    }

    private fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - SESSION_TTL_MS
        sessions.entries.removeIf { (_, session) ->
            if (session.createdAtMs >= cutoff) return@removeIf false
            session.job?.cancel()
            true
        }
    }

    private fun mcpText(text: String): JSONObject =
        JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", text)
                )
            )
            .put("isError", false)

    private fun mcpError(text: String): JSONObject =
        mcpText(text).put("isError", true)

    private data class SearchSession(
        val id: String,
        val searchType: String,
        val pattern: String,
        val path: String,
        val createdAtMs: Long,
        @Volatile var status: SearchStatus = SearchStatus.RUNNING,
        @Volatile var output: String = "",
        @Volatile var error: String? = null,
        @Volatile var job: Job? = null
    )

    private enum class SearchStatus {
        RUNNING,
        COMPLETED,
        FAILED,
        STOPPED
    }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 100
        private const val SESSION_TTL_MS = 5 * 60 * 1000L
        private val SUPPORTED_SEARCH_TYPES = setOf("files", "content")
        private val LINUX_PREFIXES = listOf("/root", "/home", "/etc", "/usr", "/var", "/tmp")
        private val REGEX_META_CHARS = setOf('\\', '.', '^', '$', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}')
    }
}
