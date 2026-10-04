package com.ai.limbs.extensions.sentinelx.runtime

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * SentinelX native-op compatibility adapter.
 *
 * This layer translates SentinelX wire operations to existing AI Limbs
 * capabilities. It never bypasses BridgeRemoteIngress, Dispatcher or Policy
 * Engine, and it does not own Android/Linux filesystem or process authority.
 */
internal class SentinelXOfficialOps(
    private val executor: SentinelXRemoteInvocationExecutor
) {
    suspend fun execute(op: String, payload: JSONObject): JSONObject = when (op) {
        "read" -> read(payload)
        "list" -> list(payload)
        "search" -> search(payload)
        "edit" -> edit(payload)
        "script_run" -> scriptRun(payload)
        else -> throw SentinelXOpException("unsupported_op", "Unsupported native SentinelX op: $op")
    }

    suspend fun read(payload: JSONObject): JSONObject {
        val path = requiredString(payload, "path")
        val environment = SentinelXEnvironmentResolver.resolve(path, payload)
        val range = parseViewRange(payload)
        val maxBytes = payload.optInt("max_bytes", DEFAULT_MAX_READ_BYTES)
            .takeIf { it > 0 }?.coerceAtMost(MAX_READ_BYTES) ?: DEFAULT_MAX_READ_BYTES
        val start = range?.first ?: 1
        val requestedEnd = range?.second ?: Int.MAX_VALUE
        var current = start
        var totalLines = 0
        var lastLine = start - 1
        var truncatedByBytes = false
        val body = StringBuilder()

        while (current <= requestedEnd && utf8Size(body.toString()) < maxBytes) {
            val end = if (requestedEnd == Int.MAX_VALUE) {
                current.toLong().plus(READ_PAGE_LINES - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                minOf(
                    requestedEnd,
                    current.toLong().plus(READ_PAGE_LINES - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
            }
            val raw = hostTool(
                "read_file_part",
                JSONObject()
                    .put("path", path)
                    .put("environment", environment)
                    .put("text_only", "true")
                    .put("start_line", current)
                    .put("end_line", end)
            )
            requireSuccess(raw, "read_failed")
            val structured = raw.optJSONObject("structured_result")
                ?: throw SentinelXOpException(
                    "invalid_bridge_result",
                    "AI Limbs read_file_part did not return structured_result"
                )
            val pageText = stripNumberPrefixes(structured.optString("content"))
            totalLines = structured.optInt("totalLines", totalLines)
            val pageEnd = structured.optInt("endLine", current - 1)
            if (pageEnd < current) break

            val separator =
                if (body.isNotEmpty() && pageText.isNotEmpty() && body[body.length - 1] != '\n') "\n" else ""
            val candidate = separator + pageText
            val remaining = maxBytes - utf8Size(body.toString())
            val clipped = clipUtf8(candidate, remaining)
            body.append(clipped)
            if (clipped != candidate) {
                truncatedByBytes = true
                lastLine = current + countLines(clipped).coerceAtLeast(1) - 1
                break
            }
            lastLine = pageEnd
            if (totalLines > 0 && pageEnd >= totalLines) break
            if (pageEnd >= requestedEnd) break
            current = pageEnd + 1
        }

        val resultText = body.toString()
        val fullRangeReached = when {
            totalLines <= 0 -> !truncatedByBytes
            requestedEnd == Int.MAX_VALUE -> lastLine >= totalLines
            else -> lastLine >= minOf(requestedEnd, totalLines)
        }
        return JSONObject()
            .put("ok", true)
            .put("path", path)
            .put("environment", environment)
            .put("encoding", "utf-8")
            .put("content", resultText)
            .put("total_lines", totalLines)
            .put("total_lines_exact", totalLines > 0)
            .put("lines_returned", countLines(resultText))
            .put("view_range", JSONArray().put(start).put(lastLine.coerceAtLeast(start - 1)))
            .put("truncated", truncatedByBytes || !fullRangeReached)
    }

    suspend fun list(payload: JSONObject): JSONObject {
        val path = requiredString(payload, "path")
        val environment = SentinelXEnvironmentResolver.resolve(path, payload)
        val depth = payload.optInt("depth", 1).coerceIn(1, 5)
        val glob = payload.optString("glob").takeIf { payload.has("glob") && it.isNotBlank() }
        val showHidden = payload.optBoolean("show_hidden", false)
        val entries = JSONArray()
        var truncated = false

        suspend fun walk(directory: String, relativePrefix: String, remainingDepth: Int) {
            if (truncated) return
            val raw = hostTool(
                "list_files",
                JSONObject().put("path", directory).put("environment", environment)
            )
            requireSuccess(raw, "list_failed")
            val hostEntries = raw.optJSONObject("structured_result")?.optJSONArray("entries")
                ?: throw SentinelXOpException(
                    "invalid_bridge_result",
                    "AI Limbs list_files did not return structured entries"
                )
            for (index in 0 until hostEntries.length()) {
                if (entries.length() >= MAX_LIST_ENTRIES) {
                    truncated = true
                    return
                }
                val item = hostEntries.optJSONObject(index) ?: continue
                val name = item.optString("name")
                if (name.isBlank()) continue
                val isDirectory = item.optBoolean("isDirectory", false)
                if (!showHidden && name.startsWith(".")) continue
                if (isDirectory && name in ALWAYS_SKIP_DIRS) continue
                val relative = if (relativePrefix.isBlank()) name else "$relativePrefix/$name"
                if (glob == null || globMatches(name, glob)) {
                    entries.put(
                        JSONObject()
                            .put("name", relative)
                            .put("type", if (isDirectory) "dir" else "file")
                            .put("size", item.optLong("size", 0L))
                            .put("mtime", item.optString("lastModified"))
                    )
                }
                if (isDirectory && remainingDepth > 1) {
                    walk(joinPath(directory, name), relative, remainingDepth - 1)
                }
            }
        }

        walk(path, "", depth)
        return JSONObject()
            .put("ok", true)
            .put("path", path)
            .put("environment", environment)
            .put("entries", entries)
            .put("total", entries.length())
            .put("truncated", truncated)
            .put("truncated_reason", if (truncated) "max_entries" else JSONObject.NULL)
    }

    suspend fun search(payload: JSONObject): JSONObject {
        val path = requiredString(payload, "path")
        val pattern = requiredString(payload, "pattern")
        val environment = SentinelXEnvironmentResolver.resolve(path, payload)
        val regex = payload.optBoolean("regex", false)
        val caseSensitive = payload.optBoolean("case_sensitive", false)
        val fileGlob =
            payload.optString("file_glob").takeIf { payload.has("file_glob") && it.isNotBlank() } ?: "*"
        val maxResults = payload.optInt("max_results", DEFAULT_SEARCH_RESULTS)
            .takeIf { it > 0 }?.coerceAtMost(MAX_SEARCH_RESULTS) ?: DEFAULT_SEARCH_RESULTS
        return if (environment == "linux") {
            searchLinux(path, pattern, regex, caseSensitive, fileGlob, maxResults)
        } else {
            searchAndroid(path, pattern, regex, caseSensitive, fileGlob, maxResults)
        }
    }

    suspend fun edit(payload: JSONObject): JSONObject {
        val path = requiredString(payload, "path")
        val mode = requiredString(payload, "mode")
        val environment = SentinelXEnvironmentResolver.resolve(path, payload)
        rejectUnsupportedEditOptions(payload)
        val count = payload.optInt("count", 0)
        if (count < 0) {
            throw SentinelXOpException("invalid_payload", "count cannot be negative")
        }
        if (mode in MODES_REQUIRING_NEW_TEXT &&
            (!payload.has("new_text") || payload.isNull("new_text"))
        ) {
            throw SentinelXOpException("invalid_payload", "mode=$mode requires 'new_text'")
        }
        if (mode == "replace" && (!payload.has("old") || payload.isNull("old"))) {
            throw SentinelXOpException("invalid_payload", "mode=replace requires 'old'")
        }
        val rawNew = payload.optString("new_text")
        val newText =
            if (payload.optBoolean("interpret_escapes", false)) interpretEscapes(rawNew) else rawNew
        val allowNoChange = payload.optBoolean("allow_no_change", false)
        val dryRun = payload.optBoolean("dry_run", false)
        val create = payload.optBoolean("create", false)

        val original = when (mode) {
            "write" -> if (create) readWholeOrNull(path, environment) ?: "" else readWhole(path, environment)
            "append", "prepend", "replace", "regex", "replace-block" -> readWhole(path, environment)
            else -> throw SentinelXOpException(
                "invalid_payload",
                "mode must be one of: replace, regex, replace-block, append, prepend, write"
            )
        }
        val updated = when (mode) {
            "write" -> newText
            "append" -> original + newText
            "prepend" -> newText + original
            "replace" -> replaceLiteral(original, payload.optString("old"), newText, count)
            "regex" -> replaceRegex(
                original,
                requiredString(payload, "pattern"),
                newText,
                count,
                payload.optBoolean("multiline", false),
                payload.optBoolean("dotall", false)
            )
            "replace-block" -> replaceBlocks(
                original,
                requiredString(payload, "start_marker"),
                requiredString(payload, "end_marker"),
                newText,
                count
            )
            else -> original
        }
        val changed = updated != original
        if (!changed && !allowNoChange) {
            throw SentinelXOpException("no_change", "edit produced no change")
        }
        if (!dryRun && changed) {
            val raw = hostTool(
                "write_file",
                JSONObject()
                    .put("path", path)
                    .put("content", updated)
                    .put("append", false)
                    .put("environment", environment)
            )
            requireSuccess(raw, "edit_failed")
        }
        return JSONObject()
            .put("ok", true)
            .put("path", path)
            .put("environment", environment)
            .put("mode", mode)
            .put("changed", changed)
            .put("dry_run", dryRun)
            .put("bytes_before", utf8Size(original))
            .put("bytes_after", utf8Size(updated))
            .put(
                "output",
                when {
                    dryRun && changed -> "dry-run: change validated but not written"
                    changed -> "edit applied through AI Limbs Host file capability"
                    else -> "no change"
                }
            )
            .put("returncode", 0)
    }

    suspend fun scriptRun(payload: JSONObject): JSONObject {
        rejectUnsupportedScriptOptions(payload)
        val script = requiredString(payload, "content", allowEmpty = true)
        val interpreter = payload.optString("interpreter", "bash").trim().lowercase()
        if (interpreter !in SCRIPT_INTERPRETERS) {
            throw SentinelXOpException(
                "invalid_payload",
                "interpreter must be bash, python3, powershell or pwsh"
            )
        }
        if (interpreter == "powershell" || interpreter == "pwsh") {
            throw SentinelXOpException(
                "unsupported_environment",
                "$interpreter is not available in the current Linux System Environment adapter"
            )
        }
        val cwd = payload.optString("cwd").trim().takeIf { it.isNotBlank() }
        if (cwd != null && SentinelXEnvironmentResolver.resolve(cwd) != "linux") {
            throw SentinelXOpException(
                "unsupported_environment",
                "script_run targets the active AI Limbs Linux System Environment; cwd must resolve to linux"
            )
        }
        val args = payload.optJSONArray("args") ?: JSONArray()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_8))
        val envPrefix = buildEnvironmentPrefix(payload.optJSONObject("env"))
        val runner = if (interpreter == "bash") "bash -s --" else "python3 -"
        val command = buildString {
            if (cwd != null) append("cd ").append(shellQuote(cwd)).append(" && ")
            if (envPrefix.isNotBlank()) append(envPrefix).append(' ')
            append("printf %s ").append(shellQuote(encoded)).append(" | base64 -d | ").append(runner)
            for (index in 0 until args.length()) {
                append(' ').append(shellQuote(args.optString(index)))
            }
        }
        val background = payload.optBoolean("background", false)
        val maxTimeoutSeconds = if (background) BACKGROUND_TIMEOUT_SECONDS else FOREGROUND_TIMEOUT_SECONDS
        val timeoutSeconds = payload.optInt("timeout", 60)
        if (timeoutSeconds !in 1..maxTimeoutSeconds) {
            throw SentinelXOpException(
                "invalid_payload",
                "timeout must be between 1 and $maxTimeoutSeconds seconds"
            )
        }
        val raw = executor.execute(
            SYSTEM_ENVIRONMENT_COMMAND,
            JSONObject()
                .put("command", command)
                .put("timeout_ms", timeoutSeconds * 1000)
                .put("work_context", true)
        )
        requireSuccess(raw, "script_run_failed")
        val exit = raw.optInt("exit_code", 0)
        val response = JSONObject()
            .put("ok", exit == 0)
            .put("background", background)
            .put("output", raw.optString("output"))
            .put("returncode", exit)
        bridgeErrorText(raw)?.let { response.put("error", it) }
        return response
    }

    private suspend fun searchAndroid(
        path: String,
        pattern: String,
        regex: Boolean,
        caseSensitive: Boolean,
        fileGlob: String,
        maxResults: Int
    ): JSONObject {
        val raw = hostTool(
            "grep_code",
            JSONObject()
                .put("path", path)
                .put("environment", "android")
                .put("pattern", if (regex) pattern else escapeRegexLiteral(pattern))
                .put("file_pattern", fileGlob)
                .put("case_insensitive", !caseSensitive)
                .put("context_lines", 0)
                .put("max_results", maxResults)
        )
        requireSuccess(raw, "search_failed")
        val structured = raw.optJSONObject("structured_result")
            ?: throw SentinelXOpException(
                "invalid_bridge_result",
                "AI Limbs grep_code did not return structured_result"
            )
        val matches = JSONArray()
        val files = structured.optJSONArray("matches") ?: JSONArray()
        for (fileIndex in 0 until files.length()) {
            val file = files.optJSONObject(fileIndex) ?: continue
            val filePath = file.optString("filePath")
            val lineMatches = file.optJSONArray("lineMatches") ?: JSONArray()
            for (lineIndex in 0 until lineMatches.length()) {
                if (matches.length() >= maxResults) break
                val line = lineMatches.optJSONObject(lineIndex) ?: continue
                matches.put(
                    JSONObject()
                        .put("file", relativize(path, filePath))
                        .put("line", line.optInt("lineNumber", 1))
                        .put("column", 1)
                        .put(
                            "text",
                            line.optString("matchContext")
                                .ifBlank { line.optString("lineContent") }
                                .lineSequence().firstOrNull().orEmpty().take(200)
                        )
                )
            }
            if (matches.length() >= maxResults) break
        }
        val total = structured.optInt("totalMatches", matches.length())
        val truncated = total > matches.length() || matches.length() >= maxResults
        return JSONObject()
            .put("ok", true)
            .put("path", path)
            .put("environment", "android")
            .put("pattern", pattern)
            .put("matches", matches)
            .put("files_searched", structured.optInt("filesSearched", 0))
            .put("truncated", truncated)
            .put("truncated_reason", if (truncated) "max_results" else JSONObject.NULL)
    }

    private suspend fun searchLinux(
        path: String,
        pattern: String,
        regex: Boolean,
        caseSensitive: Boolean,
        fileGlob: String,
        maxResults: Int
    ): JSONObject {
        val rgParts = mutableListOf("rg", "--json", "--line-number", "--column")
        if (!regex) rgParts += "--fixed-strings"
        if (!caseSensitive) rgParts += "--ignore-case"
        if (fileGlob.isNotBlank() && fileGlob != "*") {
            rgParts += "--glob"
            rgParts += fileGlob
        }
        rgParts += "--"
        rgParts += pattern
        rgParts += path
        val rgCommand = rgParts.joinToString(" ") { shellQuote(it) }
        val filterScript = """
import json, sys
limit = $maxResults
count = 0
for raw in sys.stdin:
    try:
        event = json.loads(raw)
    except Exception:
        continue
    if event.get("type") != "match":
        continue
    data = event.get("data", {})
    subs = data.get("submatches") or []
    first = subs[0] if subs else {}
    item = {
        "file": (data.get("path") or {}).get("text", ""),
        "line": data.get("line_number") or 1,
        "column": (first.get("start") or 0) + 1,
        "text": ((data.get("lines") or {}).get("text", "")).rstrip("\\r\\n")[:200],
    }
    print(json.dumps(item, ensure_ascii=False))
    count += 1
    if count >= limit:
        break
""".trimIndent()
        val raw = executor.execute(
            SYSTEM_ENVIRONMENT_COMMAND,
            JSONObject()
                .put("command", "set -o pipefail; $rgCommand | python3 -c ${shellQuote(filterScript)}")
                .put("timeout_ms", SEARCH_TIMEOUT_MS)
                .put("work_context", true)
        )
        requireSuccess(raw, "search_failed")
        val exit = raw.optInt("exit_code", 0)
        if (exit !in setOf(0, 1)) {
            throw SentinelXOpException(
                "search_failed",
                raw.optString("error").ifBlank {
                    raw.optString("output").ifBlank { "rg exited with $exit" }
                }
            )
        }
        val matches = JSONArray()
        val files = linkedSetOf<String>()
        raw.optString("output").lineSequence().filter { it.isNotBlank() }.forEach { line ->
            if (matches.length() >= maxResults) return@forEach
            val item = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            val absolute = item.optString("file")
            files += absolute
            item.put("file", relativize(path, absolute))
            matches.put(item)
        }
        val truncated = matches.length() >= maxResults
        return JSONObject()
            .put("ok", true)
            .put("path", path)
            .put("environment", "linux")
            .put("pattern", pattern)
            .put("matches", matches)
            .put("files_searched", files.size)
            .put("truncated", truncated)
            .put("truncated_reason", if (truncated) "max_results" else JSONObject.NULL)
    }

    private suspend fun readWhole(path: String, environment: String): String =
        readWholeOrNull(path, environment)
            ?: throw SentinelXOpException("not_found", "path does not exist: $path")

    private suspend fun readWholeOrNull(path: String, environment: String): String? {
        val content = StringBuilder()
        var current = 1
        var total = Int.MAX_VALUE
        while (current <= total) {
            val raw = hostTool(
                "read_file_part",
                JSONObject()
                    .put("path", path)
                    .put("environment", environment)
                    .put("text_only", "true")
                    .put("start_line", current)
                    .put("end_line", current + READ_PAGE_LINES - 1)
            )
            if (!raw.optBoolean("success", false)) {
                val message = errorMessage(raw)
                if (message.contains("not exist", ignoreCase = true) ||
                    message.contains("No such file", ignoreCase = true)
                ) return null
                requireSuccess(raw, "read_failed")
            }
            val structured = raw.optJSONObject("structured_result")
                ?: throw SentinelXOpException(
                    "invalid_bridge_result",
                    "read_file_part returned no structured_result"
                )
            total = structured.optInt("totalLines", total)
            val end = structured.optInt("endLine", current - 1)
            if (end < current) break
            val page = stripNumberPrefixes(structured.optString("content"))
            if (content.isNotEmpty() && page.isNotEmpty() && content[content.length - 1] != '\n') {
                content.append('\n')
            }
            content.append(page)
            if (utf8Size(content.toString()) > MAX_EDIT_BYTES) {
                throw SentinelXOpException(
                    "result_too_large",
                    "edit source exceeds ${MAX_EDIT_BYTES / (1024 * 1024)} MiB"
                )
            }
            if (end >= total) break
            current = end + 1
        }
        return content.toString()
    }

    private suspend fun hostTool(name: String, parameters: JSONObject): JSONObject =
        executor.execute(
            HOST_TOOL_EXECUTE,
            JSONObject().put("name", name).put("parameters", parameters)
        )

    private fun requireSuccess(result: JSONObject, defaultCode: String) {
        val success = !result.has("success") || result.optBoolean("success", false)
        if (success && bridgeErrorText(result) == null) return
        val code =
            jsonTextOrNull(result, "error_code")
                ?: jsonTextOrNull(result.optJSONObject("execution_policy"), "reason_code")
                ?: inferErrorCode(errorMessage(result), defaultCode)
        val details = JSONObject()
        result.optJSONObject("next_action")?.let { details.put("next_action", it) }
        result.optJSONObject("execution_policy")?.let { details.put("execution_policy", it) }
        throw SentinelXOpException(
            code,
            errorMessage(result),
            if (details.length() > 0) details else null
        )
    }

    private fun errorMessage(result: JSONObject): String =
        bridgeErrorText(result)
            ?: jsonTextOrNull(result.optJSONObject("result"), "value")
            ?: jsonTextOrNull(result, "reason")
            ?: "AI Limbs capability returned an unsuccessful result"

    private fun inferErrorCode(message: String, defaultCode: String): String = when {
        message.contains("No such file", true) || message.contains("not exist", true) -> "not_found"
        message.contains("not running", true) || message.contains("stopped", true) -> "target_not_running"
        message.contains("unknown capability", true) ||
            message.contains("not registered", true) ||
            message.contains("capability not found", true) -> "capability_not_found"
        message.contains("permission", true) ||
            message.contains("forbid", true) ||
            message.contains("denied", true) -> "permission_denied"
        message.contains("directory", true) -> "is_directory"
        message.contains("not supported", true) -> "unsupported_op"
        message.contains("timeout", true) || message.contains("timed out", true) -> "timeout"
        else -> defaultCode
    }

    private fun parseViewRange(payload: JSONObject): Pair<Int, Int>? {
        val array = payload.optJSONArray("view_range") ?: return null
        if (array.length() != 2) {
            throw SentinelXOpException("invalid_payload", "view_range must contain [start, end]")
        }
        val start = array.optInt(0, 1).coerceAtLeast(1)
        val rawEnd = array.optInt(1, -1)
        val end = if (rawEnd == -1) Int.MAX_VALUE else rawEnd
        if (end < start) {
            throw SentinelXOpException("invalid_payload", "view_range end must be >= start or -1")
        }
        return start to end
    }

    private fun rejectUnsupportedEditOptions(payload: JSONObject) {
        if (payload.optBoolean("sudo", false)) {
            throw SentinelXOpException(
                "unsupported_option",
                "edit sudo=true is not supported by the AI Limbs receiver"
            )
        }
        for (name in listOf("backup_dir", "validator", "validator_preset")) {
            if (payload.has(name) && !payload.isNull(name) && payload.optString(name).isNotBlank()) {
                throw SentinelXOpException("unsupported_option", "$name is not supported by the AI Limbs receiver")
            }
        }
        if (payload.optBoolean("diff", false)) {
            throw SentinelXOpException(
                "unsupported_option",
                "edit diff=true is not supported by the AI Limbs receiver"
            )
        }
    }

    private fun rejectUnsupportedScriptOptions(payload: JSONObject) {
        if (payload.optBoolean("sudo", false)) {
            throw SentinelXOpException(
                "unsupported_option",
                "script_run sudo=true is not supported; privileged execution must use an explicitly authorized AI Limbs capability"
            )
        }
        if (payload.has("filename") && !payload.isNull("filename") && payload.optString("filename").isNotBlank()) {
            throw SentinelXOpException(
                "unsupported_option",
                "script_run filename is not supported by the AI Limbs receiver"
            )
        }
        if (payload.has("cleanup") && !payload.optBoolean("cleanup", true)) {
            throw SentinelXOpException(
                "unsupported_option",
                "script_run cleanup=false is not supported by the AI Limbs receiver"
            )
        }
        if (payload.has("notify_telegram") && payload.opt("notify_telegram") != false) {
            throw SentinelXOpException(
                "unsupported_option",
                "script_run notify_telegram is not supported by the AI Limbs receiver"
            )
        }
        if (payload.has("notify_resend") && payload.opt("notify_resend") != false) {
            throw SentinelXOpException(
                "unsupported_option",
                "script_run notify_resend is not supported by the AI Limbs receiver"
            )
        }
    }

    private fun buildEnvironmentPrefix(env: JSONObject?): String {
        if (env == null || env.length() == 0) return ""
        val parts = mutableListOf<String>()
        val keys = env.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!ENV_NAME.matches(key)) {
                throw SentinelXOpException("invalid_payload", "invalid environment variable name: $key")
            }
            parts += "$key=${shellQuote(env.optString(key))}"
        }
        return "env " + parts.joinToString(" ")
    }

    companion object {
        val NATIVE_OPS: Set<String> = linkedSetOf(
            "read", "list", "search", "edit", "script_run"
        )
        const val HOST_TOOL_EXECUTE = "ai_limbs.host_tool.execute"
        const val SYSTEM_ENVIRONMENT_COMMAND = "plugin.system_environment.command"
        const val DEFAULT_MAX_READ_BYTES = 128 * 1024
        const val MAX_READ_BYTES = 512 * 1024
        const val READ_PAGE_LINES = 1_000
        const val MAX_LIST_ENTRIES = 1_000
        const val DEFAULT_SEARCH_RESULTS = 100
        const val MAX_SEARCH_RESULTS = 1_000
        const val MAX_EDIT_BYTES = 4 * 1024 * 1024
        const val SEARCH_TIMEOUT_MS = 50_000
        const val FOREGROUND_TIMEOUT_SECONDS = 600
        const val BACKGROUND_TIMEOUT_SECONDS = 3_600
        private val ALWAYS_SKIP_DIRS = setOf(
            ".git", "__pycache__", "node_modules", ".venv", "venv",
            ".pytest_cache", ".mypy_cache", ".ruff_cache", "dist", "build", ".tox", ".eggs"
        )
        private val MODES_REQUIRING_NEW_TEXT =
            setOf("replace", "regex", "replace-block", "append", "prepend", "write")
        private val SCRIPT_INTERPRETERS = setOf("bash", "python3", "powershell", "pwsh")
        private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

internal class SentinelXOpException(
    val code: String,
    override val message: String,
    val details: JSONObject? = null
) : RuntimeException(message)

internal object SentinelXEnvironmentResolver {
    private val linuxPrefixes = listOf("/root", "/home", "/etc", "/usr", "/var", "/tmp")

    fun resolve(path: String, payload: JSONObject? = null): String {
        val explicit = payload?.optString("target")?.trim().orEmpty()
            .ifBlank { payload?.optString("environment")?.trim().orEmpty() }
        if (explicit.isNotBlank()) {
            return when (explicit.lowercase()) {
                "android" -> "android"
                "linux", "ubuntu" -> "linux"
                else -> throw SentinelXOpException(
                    "target_not_found",
                    "Unknown execution target '$explicit'; expected android, linux or ubuntu"
                )
            }
        }
        return if (linuxPrefixes.any { prefix -> path == prefix || path.startsWith("$prefix/") }) {
            "linux"
        } else {
            "android"
        }
    }

    fun describe(): JSONObject = JSONObject()
        .put("strategy", "explicit-target-with-path-namespace-fallback")
        .put("explicit_fields", JSONArray().put("target").put("environment"))
        .put("targets", JSONArray().put("android").put("linux"))
        .put("aliases", JSONObject().put("ubuntu", "linux"))
        .put("default", "android")
        .put("linux_prefixes", JSONArray(linuxPrefixes))
        .put(
            "note",
            "Explicit target/environment wins. Standard SentinelX tools do not expose that field, so absolute Linux namespaces fall back to the same prefix mapping used by the AI Limbs RDC bridge."
        )
}

internal fun jsonTextOrNull(json: JSONObject?, key: String): String? {
    if (json == null || !json.has(key) || json.isNull(key)) return null
    val raw = json.opt(key)
    if (raw == null || raw == JSONObject.NULL) return null
    return raw.toString()
        .trim()
        .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}

internal fun bridgeErrorText(result: JSONObject): String? =
    jsonTextOrNull(result, "error")

internal fun requiredString(payload: JSONObject, name: String, allowEmpty: Boolean = false): String {
    if (!payload.has(name) || payload.isNull(name)) {
        throw SentinelXOpException("invalid_payload", "missing '$name'")
    }
    val value = payload.optString(name)
    if (!allowEmpty && value.isBlank()) {
        throw SentinelXOpException("invalid_payload", "missing or empty '$name'")
    }
    return value
}

internal fun stripNumberPrefixes(value: String): String =
    value.replace(Regex("(?m)^\\s*\\d+\\| ?"), "")

internal fun clipUtf8(value: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size <= maxBytes) return value
    var end = maxBytes
    while (end > 0) {
        val candidate = String(bytes, 0, end, StandardCharsets.UTF_8)
        if (!candidate.endsWith('\uFFFD')) return candidate
        end--
    }
    return ""
}

internal fun utf8Size(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

internal fun countLines(value: String): Int =
    if (value.isEmpty()) 0 else value.count { it == '\n' } + if (value.endsWith("\n")) 0 else 1

internal fun globMatches(name: String, glob: String): Boolean {
    val regex = buildString {
        append('^')
        glob.forEach { ch ->
            when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                '.', '\\', '+', '(', ')', '^', '$', '|', '{', '}', '[', ']' -> {
                    append('\\').append(ch)
                }
                else -> append(ch)
            }
        }
        append('$')
    }
    return Regex(regex).matches(name)
}

internal fun joinPath(parent: String, child: String): String =
    if (parent.endsWith('/')) parent + child else "$parent/$child"

internal fun relativize(root: String, path: String): String {
    val normalized = root.trimEnd('/')
    return if (path == normalized) {
        path.substringAfterLast('/')
    } else if (path.startsWith("$normalized/")) {
        path.removePrefix("$normalized/")
    } else {
        path
    }
}

internal fun escapeRegexLiteral(value: String): String = buildString {
    value.forEach { ch ->
        if (ch in setOf('\\', '.', '^', '$', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}')) {
            append('\\')
        }
        append(ch)
    }
}

internal fun replaceLiteral(original: String, old: String, replacement: String, count: Int): String {
    if (count == 0) return original.replace(old, replacement)
    var remaining = count
    var cursor = 0
    val out = StringBuilder(original.length)
    while (remaining > 0) {
        val index = original.indexOf(old, cursor)
        if (index < 0) break
        out.append(original, cursor, index).append(replacement)
        cursor = index + old.length
        remaining--
    }
    out.append(original, cursor, original.length)
    return out.toString()
}

internal fun replaceRegex(
    original: String,
    pattern: String,
    replacement: String,
    count: Int,
    multiline: Boolean,
    dotAll: Boolean
): String {
    val options = mutableSetOf<RegexOption>()
    if (multiline) options += RegexOption.MULTILINE
    if (dotAll) options += RegexOption.DOT_MATCHES_ALL
    val regex = try {
        Regex(pattern, options)
    } catch (error: Exception) {
        throw SentinelXOpException("invalid_payload", "invalid regex: ${error.message}")
    }
    if (count == 0) return regex.replace(original) { replacement }
    var remaining = count
    return regex.replace(original) { match ->
        if (remaining > 0) {
            remaining--
            replacement
        } else {
            match.value
        }
    }
}

internal fun replaceBlocks(
    original: String,
    startMarker: String,
    endMarker: String,
    replacement: String,
    count: Int
): String {
    val limit = if (count == 0) Int.MAX_VALUE else count
    var cursor = 0
    var replaced = 0
    val out = StringBuilder(original.length)
    while (replaced < limit) {
        val start = original.indexOf(startMarker, cursor)
        if (start < 0) break
        val endStart = original.indexOf(endMarker, start + startMarker.length)
        if (endStart < 0) {
            throw SentinelXOpException("invalid_payload", "end_marker was not found after start_marker")
        }
        val end = endStart + endMarker.length
        out.append(original, cursor, start).append(replacement)
        cursor = end
        replaced++
    }
    out.append(original, cursor, original.length)
    return out.toString()
}

internal fun interpretEscapes(value: String): String = buildString {
    var index = 0
    while (index < value.length) {
        val ch = value[index]
        if (ch == '\\' && index + 1 < value.length) {
            when (val next = value[index + 1]) {
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                '\\' -> append('\\')
                else -> {
                    append('\\')
                    append(next)
                }
            }
            index += 2
        } else {
            append(ch)
            index++
        }
    }
}

internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
