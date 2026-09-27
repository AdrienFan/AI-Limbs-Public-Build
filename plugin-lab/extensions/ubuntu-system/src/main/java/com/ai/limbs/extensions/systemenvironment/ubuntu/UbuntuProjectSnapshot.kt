package com.ai.limbs.extensions.systemenvironment.ubuntu

import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.TerminalManager
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuRuntimePhase
import java.time.Instant
import org.json.JSONObject

/**
 * A bounded, live handoff record for AI Limbs development.
 *
 * The registry decides which source is authoritative. This capability never
 * selects a project by scanning directories or writes a second source index.
 */
internal object UbuntuProjectSnapshot {
    private const val MAX_OUTPUT_CHARS = 12_000
    private val componentPattern = Regex("""[\p{L}\p{N} ._+-]{1,80}""")

    suspend fun capture(terminal: TerminalManager, parameters: JSONObject): JSONObject {
        val runtime = terminal.currentUbuntuRuntimeState()
        require(runtime.phase == UbuntuRuntimePhase.RUNNING) {
            runtime.error ?: "Ubuntu is ${runtime.phase.name}. Call plugin.ubuntu.start first."
        }

        val component = parameters.optString("component").trim()
        require(!parameters.has("component") || component.isNotEmpty()) {
            "component cannot be blank."
        }
        require(component.isEmpty() || componentPattern.matches(component)) {
            "component must be a registered name of at most 80 letters, digits, spaces, dots, underscores, plus signs or hyphens."
        }

        val startedAt = Instant.now().toString()
        val installed = run(terminal, "ail-current")
        val sources = run(terminal, "ail-source")
        val sourceCheck = run(terminal, "ail-source check")
        val mapHash = run(terminal, "sha256sum /root/laner/docs/AI_LIMBS_ARCHITECTURE_MAP.md")
        val snapshot = JSONObject()
            .put("success", installed.optBoolean("ok") && sources.optBoolean("ok"))
            .put("schema", "ai_limbs.project_snapshot.v1")
            .put("started_at_utc", startedAt)
            .put("installed_runtime", installed)
            .put("source_registry", sources)
            .put("source_check", sourceCheck)
            .put("architecture_map_sha256", mapHash)
            .put("source_identity_rule", "Use ail-source dev/where; do not infer from directory names.")

        if (component.isNotEmpty()) {
            val current = run(terminal, "ail-source where '$component'")
            val development = run(terminal, "ail-source dev '$component'")
            val detail = JSONObject()
                .put("name", component)
                .put("current_source", current)
                .put("development_source", development)

            if (development.optBoolean("ok")) {
                val path = development.optString("output").trim()
                if (path.startsWith("/root/laner/projects/") && '\n' !in path) {
                    val quotedPath = shellQuote(path)
                    detail.put("git_root", run(terminal, "git -C $quotedPath rev-parse --show-toplevel"))
                    detail.put("git_head", run(terminal, "git -C $quotedPath rev-parse HEAD"))
                    detail.put("git_status", run(terminal, "git -C $quotedPath status --porcelain=v1 --branch"))
                } else {
                    detail.put("git_unavailable_reason", "Resolved development path is outside the registered projects root.")
                }
            } else {
                detail.put("git_unavailable_reason", "Development source is not uniquely resolved.")
            }
            snapshot.put("component", detail)
        }
        return snapshot.put("finished_at_utc", Instant.now().toString())
    }

    private suspend fun run(terminal: TerminalManager, command: String): JSONObject {
        val result = terminal.executeHiddenCommand(
            command,
            executorKey = "project-snapshot",
            timeoutMs = 30_000L
        )
        val output = result.output.ifBlank { result.rawOutputPreview }
        return JSONObject()
            .put("ok", result.isOk)
            .put("exit_code", result.exitCode)
            .put("status", result.state.name)
            .put("output", output.take(MAX_OUTPUT_CHARS))
            .put("truncated", output.length > MAX_OUTPUT_CHARS)
            .put("error", result.error.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
