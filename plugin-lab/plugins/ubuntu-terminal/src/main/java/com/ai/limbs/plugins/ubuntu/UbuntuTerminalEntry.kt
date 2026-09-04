package com.ai.limbs.plugins.ubuntu

import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class UbuntuTerminalEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        val panel = UbuntuTerminalPanel(host)
        host.registerProvider(PANEL_ID, panel, mapOf("kind" to "ubuntu_terminal_panel"))
        host.registerCapability(
            STATUS_CAPABILITY,
            "Ubuntu 终端状态",
            "读取 Ubuntu Runtime 与当前插件终端会话状态。",
            InProcessCapabilityExecutor { panel.statusCapability() }
        )
        host.registerCapability(
            COMMAND_CAPABILITY,
            "Ubuntu 终端命令",
            "在插件持有的 Ubuntu PTY 会话中执行一条命令。",
            InProcessCapabilityExecutor { parameters -> panel.commandCapability(parameters) }
        )
        host.registerScreen(
            InProcessScreen(
                id = SCREEN_ID,
                title = "Ubuntu命令终端",
                description = "持久 Ubuntu PTY 会话、实时屏幕、运行时控制与命令输入。",
                schemaId = PLUGIN_CENTER_UI_SCHEMA,
                documentJson = JSONObject()
                    .put("schema", 1)
                    .put("blocks", JSONArray()
                        .put(JSONObject().put("type", "dynamic_panel").put("provider_id", PANEL_ID)))
                    .toString()
            )
        )
        host.registerHomeTile(
            InProcessHomeTile(
                id = TILE_ID,
                title = "Ubuntu命令终端",
                description = "功能强大的命令行终端，执行系统指令",
                screenId = SCREEN_ID
            )
        )
        panel.start()
        return InProcessPluginHandle { panel.stop() }
    }

    private companion object {
        const val PANEL_ID = "plugin.ubuntu.terminal_panel"
        const val SCREEN_ID = "plugin.system.ubuntu_terminal.screen"
        const val TILE_ID = "plugin.system.ubuntu_terminal.tile"
        const val STATUS_CAPABILITY = "plugin.ubuntu.status"
        const val COMMAND_CAPABILITY = "plugin.ubuntu.command"
        const val PLUGIN_CENTER_UI_SCHEMA = "ai_limbs.plugin_center.ui.v1"
    }
}

private class UbuntuTerminalPanel(
    private val host: InProcessPluginHost
) : InProcessUiStateProvider {
    private val mutableState = MutableStateFlow<String?>(null)
    override val stateJson: StateFlow<String?> = mutableState.asStateFlow()

    private var sessionId: String? = null
    private var ubuntuState = "UNKNOWN"
    private var ubuntuDetail = "尚未读取 Ubuntu Runtime 状态"
    private var idleMode = "UNKNOWN"
    private var idleTimeoutMinutes: Int? = null
    private var consoleContent = ""
    private var statusMessage = "准备就绪。先启动 Ubuntu，再打开终端会话。"
    private var pollJob: Job? = null

    init {
        publishState()
    }

    fun start() {
        host.scope.launch {
            refreshStatusInternal()
            publishState()
        }
        pollJob = host.scope.launch {
            while (isActive) {
                delay(700)
                if (sessionId != null) {
                    runCatching { refreshScreenInternal() }
                    publishState()
                }
            }
        }
    }
    suspend fun stop() {
        pollJob?.cancel()
        val activeSession = sessionId
        sessionId = null
        if (!activeSession.isNullOrBlank()) {
            runCatching {
                invokeProcess("session_close", JSONObject().put("session_id", activeSession))
            }
        }
    }

    override suspend fun perform(eventId: String, payloadJson: String): String {
        val payload = runCatching { JSONObject(payloadJson) }.getOrElse { JSONObject() }
        val fields = payload.optJSONObject("field_values")
        val command = fields?.optString(FIELD_COMMAND)?.trim().orEmpty()
        val customIdleText = fields?.optString(FIELD_CUSTOM_IDLE)?.trim().orEmpty()
        return runCatching {
            when (eventId) {
                ACTION_REFRESH_STATUS -> refreshStatus()
                ACTION_START -> startUbuntu()
                ACTION_STOP -> stopUbuntu()
                ACTION_OPEN_SESSION -> openSession()
                ACTION_REFRESH_SCREEN -> refreshScreen()
                ACTION_EXECUTE -> sendCommand(command)
                ACTION_CTRL_C -> sendControlC()
                ACTION_CLOSE_SESSION -> closeSession()
                ACTION_IDLE_KEEP -> setIdlePolicy("KEEP_RUNNING")
                ACTION_IDLE_10 -> setIdlePolicy("MINUTES_10")
                ACTION_IDLE_15 -> setIdlePolicy("MINUTES_15")
                ACTION_IDLE_30 -> setIdlePolicy("MINUTES_30")
                ACTION_IDLE_60 -> setIdlePolicy("MINUTES_60")
                ACTION_IDLE_CUSTOM -> {
                    val minutes = customIdleText.toIntOrNull()
                    require(minutes != null && minutes in 1..1440) { "自定义空闲分钟必须是 1-1440" }
                    setIdlePolicy("CUSTOM", minutes)
                }
                else -> result("未知操作：$eventId")
            }
        }.getOrElse { failure(eventId, it) }
    }
    suspend fun statusCapability(): String {
        refreshStatusInternal()
        sessionId?.let { runCatching { refreshScreenInternal() } }
        publishState()
        return JSONObject()
            .put("ubuntu_state", ubuntuState)
            .put("idle_mode", idleMode)
            .put("idle_timeout_minutes", idleTimeoutMinutes ?: JSONObject.NULL)
            .put("session_id", sessionId ?: JSONObject.NULL)
            .put("session_active", sessionId != null)
            .toString()
    }

    suspend fun commandCapability(parametersJson: String): String = runCatching {
        val parameters = JSONObject(parametersJson)
        val command = parameters.optString("command").trim()
        require(command.isNotBlank()) { "command 不能为空" }
        refreshStatusInternal()
        require(ubuntuState == "RUNNING") { "Ubuntu 当前未运行，请先调用启动操作" }
        ensureSession()
        val root = invokeProcess(
            "session_execute",
            JSONObject().put("session_id", sessionId).put("command", command)
        )
        refreshScreenInternal()
        publishState()
        root.toString()
    }.getOrElse { errorJson(it) }


    private suspend fun refreshStatus(): String {
        refreshStatusInternal()
        publishState()
        return result("Ubuntu 状态已刷新")
    }

    private suspend fun startUbuntu(): String {
        invokeUbuntu("start")
        refreshStatusInternal()
        statusMessage = "Ubuntu Runtime 已启动。"
        publishState()
        return result(statusMessage)
    }

    private suspend fun stopUbuntu(): String {
        if (sessionId != null) closeSessionInternal()
        invokeUbuntu("stop")
        refreshStatusInternal()
        consoleContent = ""
        statusMessage = "Ubuntu Runtime 已停止。"
        publishState()
        return result(statusMessage)
    }
    private suspend fun openSession(): String {
        refreshStatusInternal()
        require(ubuntuState == "RUNNING") { "Ubuntu 当前未运行，请先启动" }
        ensureSession()
        refreshScreenInternal()
        statusMessage = "终端会话已打开。"
        publishState()
        return result(statusMessage)
    }

    private suspend fun closeSession(): String {
        closeSessionInternal()
        consoleContent = ""
        statusMessage = "终端会话已关闭。"
        publishState()
        return result(statusMessage)
    }

    private suspend fun closeSessionInternal() {
        val active = sessionId ?: return
        invokeProcess("session_close", JSONObject().put("session_id", active))
        sessionId = null
    }

    private suspend fun refreshScreen(): String {
        require(sessionId != null) { "当前没有打开的终端会话" }
        refreshScreenInternal()
        publishState()
        return result("终端屏幕已刷新")
    }

    private suspend fun sendCommand(command: String): String {
        require(command.isNotBlank()) { "请输入命令" }
        refreshStatusInternal()
        require(ubuntuState == "RUNNING") { "Ubuntu 当前未运行，请先启动" }
        ensureSession()
        invokeProcess(
            "session_input",
            JSONObject()
                .put("session_id", sessionId)
                .put("input", command)
                .put("control", "enter")
        )
        delay(120)
        refreshScreenInternal()
        statusMessage = "命令已发送到当前 PTY 会话。"
        publishState()
        return result(statusMessage, clearCommand = true)
    }
    private suspend fun sendControlC(): String {
        val active = sessionId ?: error("当前没有打开的终端会话")
        invokeProcess(
            "session_input",
            JSONObject()
                .put("session_id", active)
                .put("input", "c")
                .put("control", "ctrl")
        )
        delay(80)
        runCatching { refreshScreenInternal() }
        statusMessage = "已发送 Ctrl+C。"
        publishState()
        return result(statusMessage)
    }

    private suspend fun setIdlePolicy(mode: String, customMinutes: Int? = null): String {
        val parameters = JSONObject().put("mode", mode)
        if (customMinutes != null) parameters.put("custom_minutes", customMinutes)
        invokeUbuntu("idle_set", parameters)
        refreshStatusInternal()
        statusMessage = "Ubuntu 空闲策略已更新：$idleMode"
        publishState()
        return result(statusMessage)
    }

    private suspend fun refreshStatusInternal() {
        val root = invokeUbuntu("status")
        val data = payloadObject(root)
        ubuntuState = data.optString("state", "UNKNOWN")
        ubuntuDetail = data.optString("detail").ifBlank { "Ubuntu Runtime 状态未知" }
        idleMode = data.optString("idleMode", data.optString("idle_mode", "UNKNOWN"))
        idleTimeoutMinutes = when {
            data.has("idleTimeoutMinutes") && !data.isNull("idleTimeoutMinutes") -> data.optInt("idleTimeoutMinutes")
            data.has("idle_timeout_minutes") && !data.isNull("idle_timeout_minutes") -> data.optInt("idle_timeout_minutes")
            else -> null
        }
        if (ubuntuState != "RUNNING" && sessionId != null) {
            sessionId = null
            consoleContent = ""
        }
    }

    private suspend fun ensureSession() {
        if (sessionId != null) return
        val root = invokeProcess(
            "create_session",
            JSONObject().put("session_name", SESSION_NAME)
        )
        val data = payloadObject(root)
        sessionId = data.optString("sessionId", data.optString("session_id")).ifBlank {
            error("Host 未返回终端 session id")
        }
    }
    private suspend fun refreshScreenInternal() {
        val active = sessionId ?: return
        val root = invokeProcess(
            "session_screen",
            JSONObject().put("session_id", active)
        )
        val data = payloadObject(root)
        consoleContent = data.optString("content")
    }

    private suspend fun invokeProcess(operation: String, parameters: JSONObject = JSONObject()): JSONObject =
        invokeHost(PROCESS_SCOPE, operation, parameters)

    private suspend fun invokeUbuntu(operation: String, parameters: JSONObject = JSONObject()): JSONObject =
        invokeHost(UBUNTU_SCOPE, operation, parameters)

    private suspend fun invokeHost(scopeId: String, operation: String, parameters: JSONObject): JSONObject {
        val request = JSONObject(parameters.toString()).put("operation", operation)
        val root = JSONObject(host.invokeHostCapability(scopeId, request.toString()))
        if (!root.optBoolean("success", true)) {
            val message = root.optString("error").takeUnless { it.isBlank() || it == "null" }
                ?: "$scopeId/$operation 调用失败"
            error(message)
        }
        return root
    }

    private fun payloadObject(root: JSONObject): JSONObject = root.optJSONObject("result") ?: root

    private fun buildStateJson(): String = JSONObject()
        .put("schema", 1)
        .put("title", "Ubuntu命令终端")
        .put("description", "复用 AI Limbs Ubuntu Runtime 与持久 PTY，会话、屏幕和输入均通过 Host Primitive。")
        .put("status_lines", JSONArray().apply {
            put("Ubuntu：$ubuntuState · $ubuntuDetail")
            put("空闲策略：${idleLabel()}")
            put("终端会话：${sessionId ?: "未打开"}")
            put(statusMessage)
        })
        .put("console", JSONObject()
            .put("title", "终端屏幕")
            .put("content", consoleContent)
            .put("empty_text", "终端尚无输出。启动 Ubuntu 并打开会话后即可使用。"))
        .put("leading_actions", JSONArray()
            .put(action(ACTION_REFRESH_STATUS, "刷新 Ubuntu 状态"))
            .put(action(ACTION_START, "启动 Ubuntu", enabled = ubuntuState != "RUNNING"))
            .put(action(ACTION_STOP, "停止 Ubuntu", enabled = ubuntuState == "RUNNING"))
            .put(action(ACTION_OPEN_SESSION, "打开终端会话", enabled = ubuntuState == "RUNNING" && sessionId == null))
            .put(action(ACTION_REFRESH_SCREEN, "刷新终端屏幕", enabled = sessionId != null))
            .put(action(ACTION_CTRL_C, "发送 Ctrl+C", enabled = sessionId != null))
            .put(action(ACTION_CLOSE_SESSION, "关闭终端会话", enabled = sessionId != null)))
        .put("fields", JSONArray()
            .put(textField(FIELD_COMMAND, "命令", "", "例如：pwd、ls -la、htop"))
            .put(textField(FIELD_CUSTOM_IDLE, "自定义空闲分钟", "", "1-1440")))
        .put("actions", JSONArray()
            .put(action(ACTION_EXECUTE, "发送命令", enabled = ubuntuState == "RUNNING", requiredFields = listOf(FIELD_COMMAND)))
            .put(action(ACTION_IDLE_KEEP, "空闲策略：保持运行"))
            .put(action(ACTION_IDLE_10, "空闲策略：10 分钟"))
            .put(action(ACTION_IDLE_15, "空闲策略：15 分钟"))
            .put(action(ACTION_IDLE_30, "空闲策略：30 分钟"))
            .put(action(ACTION_IDLE_60, "空闲策略：60 分钟"))
            .put(action(ACTION_IDLE_CUSTOM, "空闲策略：自定义", requiredFields = listOf(FIELD_CUSTOM_IDLE))))
        .toString()

    private fun idleLabel(): String =
        if (idleTimeoutMinutes == null) idleMode else "$idleMode（${idleTimeoutMinutes} 分钟）"

    private fun publishState() {
        mutableState.value = buildStateJson()
    }
    private fun result(message: String, clearCommand: Boolean = false): String {
        publishState()
        return JSONObject()
            .put("message", message)
            .put("field_values", JSONObject().apply {
                if (clearCommand) put(FIELD_COMMAND, "")
            })
            .toString()
    }

    private fun failure(action: String, error: Throwable): String {
        val message = error.message ?: error::class.java.simpleName
        statusMessage = "❌ $message"
        publishState()
        return JSONObject()
            .put("message", "$action 失败：$message")
            .toString()
    }

    private fun errorJson(error: Throwable): String = JSONObject()
        .put("status", "ERROR")
        .put("message", error.message ?: error::class.java.simpleName)
        .toString()
    private fun textField(id: String, label: String, value: String, placeholder: String) = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("kind", "text")
        .put("value", value)
        .put("placeholder", placeholder)
        .put("enabled", true)

    private fun action(
        id: String,
        label: String,
        enabled: Boolean = true,
        requiredFields: List<String> = emptyList()
    ) = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("kind", "invoke")
        .put("enabled", enabled)
        .put("required_field_ids", JSONArray(requiredFields))

    private companion object {
        const val PROCESS_SCOPE = "host.process@1"
        const val UBUNTU_SCOPE = "host.ubuntu.runtime@1"
        const val SESSION_NAME = "AI Limbs Ubuntu"
        const val FIELD_COMMAND = "command"
        const val FIELD_CUSTOM_IDLE = "custom_idle_minutes"
        const val ACTION_REFRESH_STATUS = "refresh_status"
        const val ACTION_START = "start_ubuntu"
        const val ACTION_STOP = "stop_ubuntu"
        const val ACTION_OPEN_SESSION = "open_session"
        const val ACTION_REFRESH_SCREEN = "refresh_screen"
        const val ACTION_EXECUTE = "execute_command"
        const val ACTION_CTRL_C = "ctrl_c"
        const val ACTION_CLOSE_SESSION = "close_session"
        const val ACTION_IDLE_KEEP = "idle_keep"
        const val ACTION_IDLE_10 = "idle_10"
        const val ACTION_IDLE_15 = "idle_15"
        const val ACTION_IDLE_30 = "idle_30"
        const val ACTION_IDLE_60 = "idle_60"
        const val ACTION_IDLE_CUSTOM = "idle_custom"
    }
}
