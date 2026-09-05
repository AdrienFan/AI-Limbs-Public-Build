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
        host.registerProvider(PANEL_ID, panel, mapOf("kind" to "ubuntu_terminal_workbench"))
        host.registerCapability(
            STATUS_CAPABILITY,
            "Ubuntu 终端状态",
            "读取 Ubuntu Runtime、插件终端标签与兰儿共享会话状态。",
            InProcessCapabilityExecutor { panel.statusCapability() }
        )
        host.registerCapability(
            COMMAND_CAPABILITY,
            "Ubuntu 终端命令",
            "在插件持有的兰儿共享 PTY 会话中执行命令，并同步到只读共享标签。",
            InProcessCapabilityExecutor { parameters -> panel.commandCapability(parameters) }
        )
        host.registerScreen(
            InProcessScreen(
                id = SCREEN_ID,
                title = "Ubuntu命令终端",
                description = "持久 Ubuntu PTY、多标签终端、兰儿共享观察与运行时控制。",
                schemaId = PLUGIN_CENTER_UI_SCHEMA,
                documentJson = JSONObject()
                    .put("schema", 1)
                    .put("layout", "edge_to_edge")
                    .put(
                        "blocks",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "terminal_workbench")
                                .put("provider_id", PANEL_ID)
                        )
                    )
                    .toString()
            )
        )
        host.registerHomeTile(
            InProcessHomeTile(
                id = TILE_ID,
                title = "Ubuntu命令终端",
                description = "多标签持久 PTY 与兰儿共享终端",
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

private data class TerminalTab(
    val id: String,
    val title: String,
    val closable: Boolean,
    var sessionId: String? = null,
    var content: String = ""
)

private class UbuntuTerminalPanel(
    private val host: InProcessPluginHost
) : InProcessUiStateProvider {
    private val mutableState = MutableStateFlow<String?>(null)
    override val stateJson: StateFlow<String?> = mutableState.asStateFlow()

    private val tabs = mutableListOf(
        TerminalTab(id = LOCAL_TAB_ID, title = "Local", closable = false)
    )
    private var activeTabId = LOCAL_TAB_ID
    private var nextTabNumber = 2
    private var sharedVisible = true
    private var sharedSessionId: String? = null
    private var sharedContent = ""
    private var sharedOnline = false
    private var ubuntuState = "UNKNOWN"
    private var ubuntuDetail = "尚未读取 Ubuntu Runtime 状态"
    private var idleMode = "UNKNOWN"
    private var idleTimeoutMinutes: Int? = null
    private var statusMessage = "正在读取 Ubuntu Runtime 状态…"
    private var pollJob: Job? = null

    init {
        publishState()
    }

    fun start() {
        host.scope.launch {
            runCatching {
                refreshStatusInternal()
                if (ubuntuState == RUNNING) ensureSession(tabs.first())
                refreshAllScreens()
                statusMessage = if (ubuntuState == RUNNING) {
                    "Ubuntu 已运行，Local 会话已就绪。"
                } else {
                    "Ubuntu 尚未运行。"
                }
            }.onFailure { statusMessage = "状态读取失败：" + (it.message ?: "未知错误") }
            publishState()
        }
        pollJob = host.scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                var changed = false
                tabs.filter { it.sessionId != null }.forEach { tab ->
                    runCatching { refreshTabScreen(tab) }.onSuccess { changed = true }
                }
                if (sharedSessionId != null) {
                    runCatching { refreshSharedScreen() }.onSuccess { changed = true }
                }
                if (changed) publishState()
            }
        }
    }

    suspend fun stop() {
        pollJob?.cancel()
        closeAllSessions()
    }

    override suspend fun perform(eventId: String, payloadJson: String): String {
        val payload = runCatching { JSONObject(payloadJson) }.getOrElse { JSONObject() }
        return runCatching {
            when (eventId) {
                ACTION_START -> startUbuntu()
                ACTION_STOP -> stopUbuntu()
                ACTION_ADD_TAB -> addTab()
                ACTION_SELECT_TAB -> selectTab(payload.textRequired("tab_id"))
                ACTION_CLOSE_TAB -> closeTab(payload.textRequired("tab_id"))
                ACTION_SHOW_SHARED -> showShared()
                ACTION_EXECUTE -> sendCommand(payload.textRequired("command"))
                ACTION_CTRL_C -> sendControlC()
                ACTION_SET_IDLE -> setIdlePolicy(payload.textRequired("mode"))
                else -> error("未知操作：" + eventId)
            }
        }.getOrElse { failure(eventId, it) }
    }

    suspend fun statusCapability(): String {
        refreshStatusInternal()
        refreshAllScreens()
        publishState()
        return JSONObject()
            .put("ubuntu_state", ubuntuState)
            .put("ubuntu_detail", ubuntuDetail)
            .put("idle_mode", idleMode)
            .put("idle_timeout_minutes", idleTimeoutMinutes ?: JSONObject.NULL)
            .put("active_tab_id", activeTabId)
            .put("shared_online", sharedOnline)
            .put(
                "tabs",
                JSONArray().apply {
                    tabs.forEach { tab ->
                        put(
                            JSONObject()
                                .put("id", tab.id)
                                .put("title", tab.title)
                                .put("session_id", tab.sessionId ?: JSONObject.NULL)
                        )
                    }
                }
            )
            .toString()
    }

    suspend fun commandCapability(parametersJson: String): String {
        val parameters = runCatching { JSONObject(parametersJson) }.getOrElse { JSONObject() }
        val command = parameters.optString("command").trim()
        if (command.isBlank()) return errorJson(IllegalArgumentException("command 不能为空"))

        sharedVisible = true
        sharedOnline = true
        sharedContent = "$ " + command + "\n"
        statusMessage = "兰儿正在共享会话中执行命令。"
        publishState()
        return try {
            refreshStatusInternal()
            require(ubuntuState == RUNNING) { "Ubuntu 当前未运行，请先启动 Ubuntu" }
            ensureSharedSession()
            val root = invokeProcess(
                "session_execute",
                JSONObject()
                    .put("session_id", sharedSessionId)
                    .put("command", command)
            )
            refreshSharedScreen()
            root.toString()
        } catch (error: Throwable) {
            sharedContent += "\n[ERROR] " + (error.message ?: error::class.java.simpleName)
            errorJson(error)
        } finally {
            sharedOnline = false
            statusMessage = "兰儿共享命令已结束。"
            publishState()
        }
    }

    private suspend fun startUbuntu(): String {
        invokeUbuntu("start")
        refreshStatusInternal()
        require(ubuntuState == RUNNING) { "Ubuntu 启动后状态不是 RUNNING：" + ubuntuState }
        val local = activeLocalTab() ?: tabs.first()
        activeTabId = local.id
        ensureSession(local)
        refreshTabScreen(local)
        statusMessage = "Ubuntu 已启动，终端会话已打开。"
        return result(statusMessage)
    }

    private suspend fun stopUbuntu(): String {
        require(activeTabId != SHARED_TAB_ID) { "兰儿共享标签为只读，不能在此停止 Ubuntu" }
        closeAllSessions()
        invokeUbuntu("stop")
        refreshStatusInternal()
        tabs.forEach { it.content = "" }
        sharedContent = ""
        statusMessage = "Ubuntu Runtime 已停止。"
        return result(statusMessage)
    }

    private suspend fun addTab(): String {
        val number = nextTabNumber++
        val tab = TerminalTab(
            id = "local-" + number,
            title = "Ubuntu" + number,
            closable = true
        )
        tabs += tab
        activeTabId = tab.id
        refreshStatusInternal()
        if (ubuntuState == RUNNING) {
            ensureSession(tab)
            refreshTabScreen(tab)
        }
        statusMessage = tab.title + " 已创建。"
        return result(statusMessage)
    }

    private suspend fun selectTab(tabId: String): String {
        if (tabId == SHARED_TAB_ID) {
            require(sharedVisible) { "兰儿共享标签尚未打开" }
            activeTabId = SHARED_TAB_ID
            statusMessage = "兰儿共享为只读观察页。"
            return result(statusMessage)
        }
        val tab = tabs.firstOrNull { it.id == tabId } ?: error("终端标签不存在：" + tabId)
        activeTabId = tab.id
        refreshStatusInternal()
        if (ubuntuState == RUNNING) {
            ensureSession(tab)
            refreshTabScreen(tab)
        }
        statusMessage = tab.title + " 已选中。"
        return result(statusMessage)
    }

    private suspend fun closeTab(tabId: String): String {
        if (tabId == SHARED_TAB_ID) {
            sharedVisible = false
            if (activeTabId == SHARED_TAB_ID) activeTabId = tabs.first().id
            statusMessage = "兰儿共享标签已关闭；再次点击眼睛可重新打开。"
            return result(statusMessage)
        }
        val tab = tabs.firstOrNull { it.id == tabId } ?: error("终端标签不存在：" + tabId)
        require(tab.closable) { "Local 主标签不能关闭" }
        tab.sessionId?.let { closeSession(it) }
        tabs.remove(tab)
        if (activeTabId == tabId) activeTabId = tabs.first().id
        statusMessage = tab.title + " 已关闭。"
        return result(statusMessage)
    }

    private fun showShared(): String {
        sharedVisible = true
        activeTabId = SHARED_TAB_ID
        statusMessage = if (sharedOnline) {
            "兰儿正在使用共享终端。"
        } else {
            "兰儿共享当前空闲；这里会显示插件命令能力的执行记录。"
        }
        return result(statusMessage)
    }

    private suspend fun sendCommand(command: String): String {
        require(command.isNotBlank()) { "请输入命令" }
        val tab = activeLocalTab() ?: error("兰儿共享标签为只读，不能输入命令")
        refreshStatusInternal()
        require(ubuntuState == RUNNING) { "Ubuntu 当前未运行，请先启动 Ubuntu" }
        ensureSession(tab)
        invokeProcess(
            "session_input",
            JSONObject()
                .put("session_id", tab.sessionId)
                .put("input", command)
                .put("control", "enter")
        )
        delay(120)
        refreshTabScreen(tab)
        statusMessage = "命令已发送到 " + tab.title + "。"
        return result(statusMessage, clearInput = true)
    }

    private suspend fun sendControlC(): String {
        val tab = activeLocalTab() ?: error("兰儿共享标签为只读，不能发送 Ctrl+C")
        val sessionId = tab.sessionId ?: error("当前标签没有打开 PTY 会话")
        invokeProcess(
            "session_input",
            JSONObject()
                .put("session_id", sessionId)
                .put("input", "c")
                .put("control", "ctrl")
        )
        delay(80)
        runCatching { refreshTabScreen(tab) }
        statusMessage = "已向 " + tab.title + " 发送 Ctrl+C。"
        return result(statusMessage)
    }

    private suspend fun setIdlePolicy(mode: String): String {
        require(activeTabId != SHARED_TAB_ID) { "兰儿共享标签为只读，不能修改环境配置" }
        require(mode in IDLE_MODES) { "不支持的空闲策略：" + mode }
        invokeUbuntu("idle_set", JSONObject().put("mode", mode))
        refreshStatusInternal()
        statusMessage = "Ubuntu 空闲策略已更新：" + idleLabel()
        return result(statusMessage)
    }

    private suspend fun refreshStatusInternal() {
        val data = payloadObject(invokeUbuntu("status"))
        ubuntuState = data.optString("state", "UNKNOWN")
        ubuntuDetail = data.optString("detail").ifBlank { "Ubuntu Runtime 状态未知" }
        idleMode = data.optString("idleMode", data.optString("idle_mode", "UNKNOWN"))
        idleTimeoutMinutes = when {
            data.has("idleTimeoutMinutes") && !data.isNull("idleTimeoutMinutes") ->
                data.optInt("idleTimeoutMinutes")
            data.has("idle_timeout_minutes") && !data.isNull("idle_timeout_minutes") ->
                data.optInt("idle_timeout_minutes")
            else -> null
        }
        if (ubuntuState != RUNNING) {
            tabs.forEach { it.sessionId = null }
            sharedSessionId = null
            sharedOnline = false
        }
    }

    private suspend fun ensureSession(tab: TerminalTab) {
        if (tab.sessionId != null) return
        val data = payloadObject(
            invokeProcess(
                "create_session",
                JSONObject().put("session_name", "AI Limbs " + tab.title)
            )
        )
        tab.sessionId = data.sessionIdRequired()
    }

    private suspend fun ensureSharedSession() {
        if (sharedSessionId != null) return
        val data = payloadObject(
            invokeProcess(
                "create_session",
                JSONObject().put("session_name", "AI Limbs Laner Shared")
            )
        )
        sharedSessionId = data.sessionIdRequired()
    }

    private suspend fun refreshAllScreens() {
        tabs.filter { it.sessionId != null }.forEach { tab ->
            runCatching { refreshTabScreen(tab) }
        }
        if (sharedSessionId != null) runCatching { refreshSharedScreen() }
    }

    private suspend fun refreshTabScreen(tab: TerminalTab) {
        val sessionId = tab.sessionId ?: return
        val data = payloadObject(
            invokeProcess("session_screen", JSONObject().put("session_id", sessionId))
        )
        tab.content = data.optString("content")
    }

    private suspend fun refreshSharedScreen() {
        val sessionId = sharedSessionId ?: return
        val data = payloadObject(
            invokeProcess("session_screen", JSONObject().put("session_id", sessionId))
        )
        sharedContent = data.optString("content")
    }

    private suspend fun closeAllSessions() {
        val sessionIds = buildList {
            tabs.mapNotNullTo(this) { it.sessionId }
            sharedSessionId?.let(::add)
        }.distinct()
        tabs.forEach { it.sessionId = null }
        sharedSessionId = null
        sharedOnline = false
        sessionIds.forEach { sessionId -> runCatching { closeSession(sessionId) } }
    }

    private suspend fun closeSession(sessionId: String) {
        invokeProcess("session_close", JSONObject().put("session_id", sessionId))
    }

    private fun activeLocalTab(): TerminalTab? =
        tabs.firstOrNull { it.id == activeTabId }

    private suspend fun invokeProcess(
        operation: String,
        parameters: JSONObject = JSONObject()
    ): JSONObject = invokeHost(PROCESS_SCOPE, operation, parameters)

    private suspend fun invokeUbuntu(
        operation: String,
        parameters: JSONObject = JSONObject()
    ): JSONObject = invokeHost(UBUNTU_SCOPE, operation, parameters)

    private suspend fun invokeHost(
        scopeId: String,
        operation: String,
        parameters: JSONObject
    ): JSONObject {
        val request = JSONObject(parameters.toString()).put("operation", operation)
        val root = JSONObject(host.invokeHostCapability(scopeId, request.toString()))
        if (!root.optBoolean("success", true)) {
            val message = root.optString("error").takeUnless { it.isBlank() || it == "null" }
                ?: (scopeId + "/" + operation + " 调用失败")
            error(message)
        }
        return root
    }

    private fun payloadObject(root: JSONObject): JSONObject =
        root.optJSONObject("result") ?: root

    private fun buildStateJson(): String {
        val active = activeLocalTab()
        val isShared = activeTabId == SHARED_TAB_ID
        val console = if (isShared) sharedContent else active?.content.orEmpty()
        val tabState = JSONArray().apply {
            tabs.forEach { tab ->
                put(
                    JSONObject()
                        .put("id", tab.id)
                        .put("title", tab.title)
                        .put("closable", tab.closable)
                        .put("shared", false)
                        .put("online", tab.sessionId != null)
                )
            }
            if (sharedVisible) {
                put(
                    JSONObject()
                        .put("id", SHARED_TAB_ID)
                        .put("title", "兰儿共享")
                        .put("closable", true)
                        .put("shared", true)
                        .put("online", sharedOnline)
                )
            }
        }
        return JSONObject()
            .put("schema", 1)
            .put("tabs", tabState)
            .put("active_tab_id", activeTabId)
            .put("console_content", console)
            .put(
                "console_empty_text",
                if (isShared) {
                    "兰儿共享当前没有输出。AI 调用 Ubuntu 终端命令能力时，这里会实时显示。"
                } else if (ubuntuState == RUNNING) {
                    "PTY 会话正在初始化…"
                } else {
                    "Ubuntu 尚未启动。点击下方“启动 Ubuntu”即可打开 Local 会话。"
                }
            )
            .put("ubuntu_running", ubuntuState == RUNNING)
            .put("session_active", active?.sessionId != null)
            .put("input_enabled", !isShared && ubuntuState == RUNNING && active?.sessionId != null)
            .put("local_controls_enabled", !isShared)
            .put("share_online", sharedOnline)
            .put("idle_label", idleLabel())
            .put("status_message", statusMessage)
            .put("prompt", "~ $")
            .put(
                "events",
                JSONObject()
                    .put("start", ACTION_START)
                    .put("stop", ACTION_STOP)
                    .put("add_tab", ACTION_ADD_TAB)
                    .put("select_tab", ACTION_SELECT_TAB)
                    .put("close_tab", ACTION_CLOSE_TAB)
                    .put("show_shared", ACTION_SHOW_SHARED)
                    .put("execute", ACTION_EXECUTE)
                    .put("ctrl_c", ACTION_CTRL_C)
                    .put("set_idle", ACTION_SET_IDLE)
            )
            .toString()
    }

    private fun idleLabel(): String =
        if (idleTimeoutMinutes == null) idleMode else idleMode + "（" + idleTimeoutMinutes + " 分钟）"

    private fun publishState() {
        mutableState.value = buildStateJson()
    }

    private fun result(message: String, clearInput: Boolean = false): String {
        publishState()
        return JSONObject()
            .put("message", message)
            .put("clear_input", clearInput)
            .toString()
    }

    private fun failure(action: String, error: Throwable): String {
        val message = error.message ?: error::class.java.simpleName
        statusMessage = "❌ " + message
        publishState()
        return JSONObject()
            .put("message", action + " 失败：" + message)
            .toString()
    }

    private fun errorJson(error: Throwable): String = JSONObject()
        .put("status", "ERROR")
        .put("message", error.message ?: error::class.java.simpleName)
        .toString()

    private fun JSONObject.textRequired(key: String): String =
        optString(key).trim().ifBlank { error(key + " 不能为空") }

    private fun JSONObject.sessionIdRequired(): String =
        optString("sessionId", optString("session_id")).ifBlank {
            error("Host 未返回终端 session id")
        }

    private companion object {
        const val PROCESS_SCOPE = "host.process@1"
        const val UBUNTU_SCOPE = "host.ubuntu.runtime@1"
        const val RUNNING = "RUNNING"
        const val LOCAL_TAB_ID = "local-1"
        const val SHARED_TAB_ID = "laner-shared"
        const val POLL_INTERVAL_MS = 650L
        const val ACTION_START = "start_ubuntu"
        const val ACTION_STOP = "stop_ubuntu"
        const val ACTION_ADD_TAB = "add_tab"
        const val ACTION_SELECT_TAB = "select_tab"
        const val ACTION_CLOSE_TAB = "close_tab"
        const val ACTION_SHOW_SHARED = "show_shared"
        const val ACTION_EXECUTE = "execute_command"
        const val ACTION_CTRL_C = "ctrl_c"
        const val ACTION_SET_IDLE = "set_idle_policy"
        val IDLE_MODES = setOf(
            "KEEP_RUNNING",
            "MINUTES_10",
            "MINUTES_15",
            "MINUTES_30",
            "MINUTES_60"
        )
    }
}
