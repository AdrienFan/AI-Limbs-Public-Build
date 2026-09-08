package com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu

import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessCapabilityDomain
import com.ai.limbs.plugin.runtime.InProcessCapabilityEffect
import com.ai.limbs.plugin.runtime.InProcessCapabilityParameterSpec
import com.ai.limbs.plugin.runtime.InProcessCapabilityReceipt
import com.ai.limbs.plugin.runtime.InProcessCapabilitySpec
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.runtime.terminal.TerminalManager
import com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.runtime.terminal.data.UbuntuIdleMode
import com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.runtime.terminal.data.UbuntuIdlePolicy
import com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.runtime.terminal.data.UbuntuRuntimePhase
import com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.runtime.terminal.data.UbuntuStopRequester
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal object UbuntuSubsystemCapabilities {
    fun register(host: InProcessPluginHost, terminal: TerminalManager) {
        capabilities(terminal).forEach(host::registerCapability)
    }

    private fun capabilities(terminal: TerminalManager): List<InProcessCapabilitySpec> = listOf(
        spec(
            id = "plugin.system_environment.ubuntu.status",
            name = "查询 Ubuntu 状态",
            description = "读取 系统环境中心内置 Ubuntu 子系统持有 Runtime 的生命周期、空闲策略与当前使用者状态。",
            keywords = listOf("Ubuntu", "Linux", "状态", "生命周期", "runtime"),
            effect = InProcessCapabilityEffect.READ_ONLY
        ) { status(terminal) },
        spec(
            id = "plugin.system_environment.ubuntu.start",
            name = "启动 Ubuntu",
            description = "启动 系统环境中心内置 Ubuntu 子系统持有的本地 Runtime。AI 调用不会触发前台开发环境提示。",
            keywords = listOf("Ubuntu", "Linux", "启动", "开机", "runtime"),
            effect = InProcessCapabilityEffect.STATE_CHANGE
        ) { start(terminal) },
        spec(
            id = "plugin.system_environment.ubuntu.stop",
            name = "停止 Ubuntu",
            description = "停止 Ubuntu Runtime；若前台 UI 或其他隐藏 AI 操作仍在使用则拒绝。",
            keywords = listOf("Ubuntu", "Linux", "停止", "关机", "并发保护"),
            effect = InProcessCapabilityEffect.STATE_CHANGE
        ) { stop(terminal) },
        spec(
            id = "plugin.system_environment.ubuntu.idle.get",
            name = "查询 Ubuntu 空闲策略",
            description = "读取 Ubuntu Runtime 的空闲自动停止策略。",
            keywords = listOf("Ubuntu", "空闲", "自动关机", "idle"),
            effect = InProcessCapabilityEffect.READ_ONLY
        ) { idleGet(terminal) },
        spec(
            id = "plugin.system_environment.ubuntu.idle.set",
            name = "修改 Ubuntu 空闲策略",
            description = "修改 Ubuntu Runtime 的空闲自动停止策略；CUSTOM 时 custom_minutes 为 1 到 1440。",
            keywords = listOf("Ubuntu", "空闲", "自动关机", "保持开机", "idle"),
            params = listOf(
                param("mode", "string", "KEEP_RUNNING, MINUTES_10, MINUTES_15, MINUTES_30, MINUTES_60, or CUSTOM"),
                param("custom_minutes", "integer", "CUSTOM 模式的分钟数，1 到 1440", required = false)
            ),
            suggested = """{"mode":"MINUTES_30"}""",
            effect = InProcessCapabilityEffect.STATE_CHANGE
        ) { p -> idleSet(terminal, p) },
        spec(
            id = "plugin.system_environment.ubuntu.session.create",
            name = "创建 Ubuntu 终端会话",
            description = "创建或复用一个插件自持有的持久终端会话。",
            keywords = listOf("Ubuntu", "terminal", "终端", "会话", "PTY"),
            params = listOf(param("session_name", "string", "会话名称；同名活动会话将被复用", required = false)),
            suggested = """{"session_name":"AI"}""",
            effect = InProcessCapabilityEffect.STATE_CHANGE
        ) { p -> sessionCreate(terminal, p) },
        spec(
            id = "plugin.system_environment.ubuntu.session.execute",
            name = "在 Ubuntu 会话中执行命令",
            description = "向指定持久终端会话发送命令并等待该命令完成；适合需要保留 shell 状态的任务。",
            keywords = listOf("Ubuntu", "terminal", "命令", "shell", "持久会话"),
            params = listOf(
                param("session_id", "string", "目标终端会话 ID"),
                param("command", "string", "要执行的 shell 命令"),
                param("timeout_ms", "integer", "等待命令完成的超时毫秒数", required = false, default = "1800000")
            ),
            suggested = """{"session_id":"<session_id>","command":"pwd"}""",
            effect = InProcessCapabilityEffect.PROCESS_EXECUTION
        ) { p -> sessionExecute(terminal, p) },
        spec(
            id = "plugin.system_environment.ubuntu.command",
            name = "执行 Ubuntu 隐藏命令",
            description = "在插件自持有的后台 Ubuntu shell 中执行一次命令，不占用前台终端标签。",
            keywords = listOf("Ubuntu", "terminal", "后台", "隐藏命令", "shell"),
            params = listOf(
                param("command", "string", "要执行的 shell 命令"),
                param("executor_key", "string", "后台执行器隔离键", required = false, default = "default"),
                param("timeout_ms", "integer", "执行超时毫秒数", required = false, default = "120000")
            ),
            suggested = """{"command":"pwd"}""",
            effect = InProcessCapabilityEffect.PROCESS_EXECUTION
        ) { p -> hiddenExecute(terminal, p) },
        spec(
            id = "plugin.system_environment.ubuntu.session.input",
            name = "向 Ubuntu 会话输入",
            description = "向持久 PTY 会话写入文本或终端控制键；支持 Ctrl/Alt/Shift 组合。",
            keywords = listOf("Ubuntu", "terminal", "输入", "PTY", "交互"),
            params = listOf(
                param("session_id", "string", "目标终端会话 ID"),
                param("input", "string", "普通输入或修饰键组合字符", required = false),
                param("control", "string", "enter/tab/esc/up/down/left/right/home/end/pageup/pagedown/backspace/delete/ctrl/alt/shift", required = false)
            ),
            effect = InProcessCapabilityEffect.PROCESS_EXECUTION
        ) { p -> sessionInput(terminal, p) },
        spec(
            id = "plugin.system_environment.ubuntu.session.interrupt",
            name = "中断 Ubuntu 会话",
            description = "向指定终端会话发送 Ctrl+C/SIGINT 语义的中断。",
            keywords = listOf("Ubuntu", "terminal", "中断", "Ctrl+C", "SIGINT"),
            params = listOf(param("session_id", "string", "目标终端会话 ID")),
            effect = InProcessCapabilityEffect.STATE_CHANGE
        ) { p -> sessionInterrupt(terminal, p) },
        spec(
            id = "plugin.system_environment.ubuntu.session.screen",
            name = "读取 Ubuntu 会话屏幕",
            description = "读取指定持久终端当前可见屏幕，不包含历史滚动缓冲。",
            keywords = listOf("Ubuntu", "terminal", "屏幕", "输出", "PTY"),
            params = listOf(param("session_id", "string", "目标终端会话 ID")),
            effect = InProcessCapabilityEffect.READ_ONLY
        ) { p -> sessionScreen(terminal, p) },
        spec(
            id = "plugin.system_environment.ubuntu.session.close",
            name = "关闭 Ubuntu 终端会话",
            description = "关闭指定插件终端会话并释放对应 PTY。",
            keywords = listOf("Ubuntu", "terminal", "关闭会话", "PTY"),
            params = listOf(param("session_id", "string", "目标终端会话 ID")),
            effect = InProcessCapabilityEffect.STATE_CHANGE
        ) { p -> sessionClose(terminal, p) }
    )

    private fun spec(
        id: String,
        name: String,
        description: String,
        keywords: List<String>,
        aliases: List<String> = emptyList(),
        params: List<InProcessCapabilityParameterSpec> = emptyList(),
        suggested: String? = null,
        effect: InProcessCapabilityEffect = InProcessCapabilityEffect.EXTERNAL_CAPABILITY,
        domain: InProcessCapabilityDomain = InProcessCapabilityDomain.SYSTEM_ENVIRONMENT,
        executor: suspend (JSONObject) -> JSONObject
    ) = InProcessCapabilitySpec(
        id = id,
        displayName = name,
        description = description,
        invokeAliases = aliases,
        keywords = keywords,
        parameters = params + workContextParam(),
        suggestedParamsJson = suggested,
        effect = effect,
        domain = domain,
        workContextRequiredReceipts = setOf(InProcessCapabilityReceipt.WORK_MANUAL),
        executor = InProcessCapabilityExecutor { raw ->
            val parameters = parse(raw)
            runCatching { executor(parameters) }
                .getOrElse(::failure)
                .toString()
        }
    )

    private fun workContextParam() =
        InProcessCapabilityParameterSpec(
            name = "work_context",
            type = "boolean",
            description = "仅当本次 Ubuntu 调用属于开发、调试、开发环境管理或会改变项目/设备内容的工作任务时设为 true；普通 Ubuntu 使用保持 false。",
            required = false,
            default = "false"
        )

    private fun param(
        name: String,
        type: String,
        description: String,
        required: Boolean = true,
        default: String? = null
    ) = InProcessCapabilityParameterSpec(name, type, description, required, default)

    private fun status(terminal: TerminalManager): JSONObject {
        val runtime = terminal.currentUbuntuRuntimeState()
        val idle = terminal.currentUbuntuIdlePolicy()
        val usage = terminal.currentUbuntuUsageState()
        return ok()
            .put("state", runtime.phase.name)
            .put("detail", runtime.detail)
            .put("error", runtime.error ?: JSONObject.NULL)
            .put("idle_mode", idle.mode.name)
            .put("custom_minutes", idle.customMinutes)
            .put("idle_timeout_minutes", idle.timeoutMinutes ?: JSONObject.NULL)
            .put("user_interface_clients", usage.userInterfaceClients)
            .put("hidden_ai_operations", usage.hiddenAiOperations)
            .put("participant_count", usage.participantCount)
            .put("runtime_owner", "plugin")
    }

    private suspend fun start(terminal: TerminalManager): JSONObject {
        val state = terminal.startUbuntu(offerDevelopmentPrompt = false)
        return stateJson(state.phase == UbuntuRuntimePhase.RUNNING, state.phase.name, state.detail, state.error)
    }

    private suspend fun stop(terminal: TerminalManager): JSONObject {
        val state = terminal.stopUbuntu(UbuntuStopRequester.AI_TOOL)
        return stateJson(state.phase == UbuntuRuntimePhase.STOPPED, state.phase.name, state.detail, state.error)
    }

    private fun idleGet(terminal: TerminalManager): JSONObject {
        val idle = terminal.currentUbuntuIdlePolicy()
        return ok()
            .put("mode", idle.mode.name)
            .put("custom_minutes", idle.customMinutes)
            .put("timeout_minutes", idle.timeoutMinutes ?: JSONObject.NULL)
    }

    private fun idleSet(terminal: TerminalManager, p: JSONObject): JSONObject {
        val modeName = p.requiredText("mode").uppercase()
        val mode = runCatching { UbuntuIdleMode.valueOf(modeName) }
            .getOrElse { throw IllegalArgumentException("Unsupported Ubuntu idle mode: $modeName") }
        val existing = terminal.currentUbuntuIdlePolicy()
        val customMinutes = if (p.has("custom_minutes")) {
            p.optInt("custom_minutes", -1)
        } else {
            existing.customMinutes
        }
        val policy = UbuntuIdlePolicy(mode = mode, customMinutes = customMinutes)
        terminal.updateUbuntuIdlePolicy(policy)
        return idleGet(terminal)
    }

    private suspend fun sessionCreate(terminal: TerminalManager, p: JSONObject): JSONObject {
        requireRunning(terminal)
        val name = p.optString("session_name").trim().ifBlank { "AI" }
        val existing = terminal.terminalState.value.sessions.firstOrNull { it.title == name }
        val session = existing ?: terminal.createNewSession(name)
        return ok()
            .put("session_id", session.id)
            .put("session_name", session.title)
            .put("reused", existing != null)
            .put("background", session.isBackground)
    }

    private suspend fun sessionExecute(terminal: TerminalManager, p: JSONObject): JSONObject = coroutineScope {
        requireRunning(terminal)
        val sessionId = p.requiredText("session_id")
        val command = p.requiredText("command")
        val timeoutMs = p.longInRange("timeout_ms", 1_000L, 3_600_000L, 1_800_000L)
        val session = requireSession(terminal, sessionId)
        if (session.isInteractiveMode) {
            val written = terminal.sendInputToSessionNow(sessionId, command + "\r")
            require(written) { "Could not write command to interactive terminal session: $sessionId" }
            return@coroutineScope ok()
                .put("session_id", sessionId)
                .put("command_id", JSONObject.NULL)
                .put("interactive", true)
                .put("completed", false)
                .put("output", "")
        }

        val output = StringBuilder()
        val completed = CompletableDeferred<Unit>()
        val commandId = UUID.randomUUID().toString()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            terminal.commandExecutionEvents.collect { event ->
                if (event.sessionId == sessionId && event.commandId == commandId) {
                    if (event.outputChunk.isNotEmpty()) output.append(event.outputChunk)
                    if (event.isCompleted && !completed.isCompleted) completed.complete(Unit)
                }
            }
        }
        try {
            terminal.sendCommandToSession(sessionId, command, commandId)
            val finished = withTimeoutOrNull(timeoutMs) { completed.await(); true } ?: false
            if (!finished) {
                terminal.sendInterruptSignalToSessionNow(sessionId)
                return@coroutineScope JSONObject()
                    .put("success", false)
                    .put("session_id", sessionId)
                    .put("command_id", commandId)
                    .put("completed", false)
                    .put("timed_out", true)
                    .put("output", output.toString())
                    .put("error", "Terminal command timed out after ${timeoutMs}ms and was interrupted.")
            }
            ok()
                .put("session_id", sessionId)
                .put("command_id", commandId)
                .put("completed", true)
                .put("timed_out", false)
                .put("output", output.toString())
        } finally {
            collector.cancel()
        }
    }

    private suspend fun hiddenExecute(terminal: TerminalManager, p: JSONObject): JSONObject {
        requireRunning(terminal)
        val command = p.requiredText("command")
        val executorKey = p.optString("executor_key").trim().ifBlank { "default" }
        val timeoutMs = p.longInRange("timeout_ms", 1_000L, 3_600_000L, 120_000L)
        val result = terminal.executeHiddenCommand(command, executorKey = executorKey, timeoutMs = timeoutMs)
        return JSONObject()
            .put("success", result.isOk)
            .put("status", result.state.name)
            .put("exit_code", result.exitCode)
            .put("output", result.output.ifBlank { result.rawOutputPreview })
            .put("error", result.error.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
    }

    private suspend fun sessionInput(terminal: TerminalManager, p: JSONObject): JSONObject {
        requireRunning(terminal)
        val sessionId = p.requiredText("session_id")
        requireSession(terminal, sessionId)
        val hasInput = p.has("input")
        val input = if (hasInput) p.optString("input") else ""
        val control = normalizeControl(p.optString("control").takeIf { p.has("control") })
        require(hasInput || control != null) { "Either input or control is required." }
        val payload = buildInputPayload(terminal, sessionId, hasInput, input, control)
        if (payload != null && payload.isNotEmpty()) {
            require(terminal.sendInputToSessionNow(sessionId, payload)) { "Could not write input to terminal session: $sessionId" }
        }
        return ok()
            .put("session_id", sessionId)
            .put("control", control ?: JSONObject.NULL)
            .put("input_length", if (hasInput) input.length else 0)
    }

    private suspend fun sessionInterrupt(terminal: TerminalManager, p: JSONObject): JSONObject {
        requireRunning(terminal)
        val sessionId = p.requiredText("session_id")
        requireSession(terminal, sessionId)
        require(terminal.sendInterruptSignalToSessionNow(sessionId)) { "Could not interrupt terminal session: $sessionId" }
        return ok().put("session_id", sessionId).put("interrupted", true)
    }

    private fun sessionScreen(terminal: TerminalManager, p: JSONObject): JSONObject {
        val sessionId = p.requiredText("session_id")
        val session = requireSession(terminal, sessionId)
        val screen = session.ansiParser.getScreenContent()
        val content = renderScreen(screen)
        return ok()
            .put("session_id", sessionId)
            .put("rows", screen.size)
            .put("cols", if (screen.isNotEmpty()) screen[0].size else 0)
            .put("content", content)
    }

    private fun sessionClose(terminal: TerminalManager, p: JSONObject): JSONObject {
        val sessionId = p.requiredText("session_id")
        requireSession(terminal, sessionId)
        terminal.closeSession(sessionId)
        return ok().put("session_id", sessionId).put("closed", true)
    }

    private fun requireRunning(terminal: TerminalManager) {
        val state = terminal.currentUbuntuRuntimeState()
        require(state.phase == UbuntuRuntimePhase.RUNNING) {
            state.error ?: "Ubuntu is ${state.phase.name}. Call plugin.system_environment.ubuntu.start first."
        }
    }

    private fun requireSession(terminal: TerminalManager, sessionId: String) =
        terminal.terminalState.value.sessions.firstOrNull { it.id == sessionId }
            ?: throw IllegalArgumentException("Terminal session does not exist: $sessionId")

    private suspend fun buildInputPayload(
        terminal: TerminalManager,
        sessionId: String,
        hasInput: Boolean,
        input: String,
        control: String?
    ): String? {
        if (control == null) return if (hasInput) input else null
        if (control in setOf("ctrl", "control")) {
            require(hasInput && input.length == 1) { "Ctrl requires exactly one input character." }
            val value = input[0]
            if (value.equals('c', ignoreCase = true)) {
                require(terminal.sendInterruptSignalToSessionNow(sessionId)) {
                    "Could not interrupt terminal session: $sessionId"
                }
                return null
            }
            val upper = value.uppercaseChar()
            val code = when (upper) {
                in 'A'..'Z' -> upper.code - 'A'.code + 1
                '@' -> 0
                '[' -> 27
                '\\' -> 28
                ']' -> 29
                '^' -> 30
                '_' -> 31
                '?' -> 127
                else -> throw IllegalArgumentException("Unsupported Ctrl combination: $input")
            }
            return code.toChar().toString()
        }
        if (control in setOf("alt", "meta", "cmd")) {
            require(hasInput) { "$control requires input." }
            return "\u001b$input"
        }
        if (control == "shift") {
            require(hasInput) { "shift requires input." }
            return input.uppercase()
        }
        val sequence = controlToSequence(control)
            ?: throw IllegalArgumentException("Unsupported terminal control: $control")
        return (if (hasInput) input else "") + sequence
    }

    private fun normalizeControl(raw: String?): String? {
        val value = raw?.trim()?.lowercase().orEmpty()
        if (value.isBlank()) return null
        return when (value) {
            "return" -> "enter"
            "escape" -> "esc"
            "arrowup" -> "up"
            "arrowdown" -> "down"
            "arrowleft" -> "left"
            "arrowright" -> "right"
            "pgup", "page_up" -> "pageup"
            "pgdn", "page_down" -> "pagedown"
            "del" -> "delete"
            else -> value
        }
    }

    private fun controlToSequence(control: String): String? = when (control) {
        "enter" -> "\r"
        "tab" -> "\t"
        "esc" -> "\u001b"
        "up" -> "\u001b[A"
        "down" -> "\u001b[B"
        "left" -> "\u001b[D"
        "right" -> "\u001b[C"
        "home" -> "\u001b[H"
        "end" -> "\u001b[F"
        "pageup" -> "\u001b[5~"
        "pagedown" -> "\u001b[6~"
        "backspace" -> "\u007f"
        "delete" -> "\u001b[3~"
        else -> null
    }

    private fun renderScreen(
        screen: Array<Array<com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.runtime.terminal.view.domain.ansi.TerminalChar>>
    ): String {
        val lines = screen.map { row ->
            buildString { row.forEach { append(it.char) } }.trimEnd()
        }.toMutableList()
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n")
    }

    private fun stateJson(success: Boolean, state: String, detail: String, error: String?) =
        JSONObject()
            .put("success", success)
            .put("state", state)
            .put("detail", detail)
            .put("error", error ?: JSONObject.NULL)

    private fun ok() = JSONObject().put("success", true)

    private fun failure(error: Throwable) = JSONObject()
        .put("success", false)
        .put("error", error.message ?: error::class.java.simpleName)

    private fun parse(raw: String): JSONObject =
        runCatching { JSONObject(raw) }.getOrElse { throw IllegalArgumentException("Capability parameters must be JSON.") }

    private fun JSONObject.requiredText(key: String): String =
        optString(key).trim().ifBlank { throw IllegalArgumentException("$key is required.") }

    private fun JSONObject.longInRange(key: String, min: Long, max: Long, fallback: Long): Long {
        val value = if (has(key)) optLong(key, Long.MIN_VALUE) else fallback
        require(value in min..max) { "$key must be between $min and $max." }
        return value
    }
}
