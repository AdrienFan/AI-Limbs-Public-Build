package com.ai.limbs.plugins.ubuntu

import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessCapabilitySpec
import com.ai.limbs.plugin.runtime.InProcessCapabilityDomain
import com.ai.limbs.plugin.runtime.InProcessCapabilityEffect
import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import com.ai.limbs.plugins.ubuntu.runtime.terminal.Pty
import com.ai.limbs.plugins.ubuntu.runtime.terminal.TerminalManager
import com.ai.limbs.plugins.ubuntu.runtime.terminal.TerminalRuntimeAssets
import com.ai.limbs.plugins.ubuntu.runtime.terminal.data.UbuntuIdleMode
import com.ai.limbs.plugins.ubuntu.runtime.terminal.data.UbuntuIdlePolicy
import com.ai.limbs.plugins.ubuntu.runtime.terminal.data.UbuntuRuntimePhase
import com.ai.limbs.plugins.ubuntu.runtime.terminal.data.UbuntuStopRequester
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
        val nativeRuntime = UbuntuNativeRuntimeInstaller.prepare(host)
        TerminalRuntimeAssets.configure(host.runtimeEntryFile)
        Pty.configureNativeLibrary(nativeRuntime.ptyLibrary)
        val runtimeContext = UbuntuPluginRuntimeContext(
            base = host.applicationContext,
            pluginId = host.pluginId,
            pluginDataDir = host.dataDir,
            pluginCacheDir = host.cacheDir,
            nativeLibraryDir = nativeRuntime.directory
        )
        val terminal = TerminalManager.getInstance(runtimeContext)
        val panel = UbuntuTerminalPanel(host, terminal)
        host.registerProvider(PANEL_ID, panel, mapOf("kind" to "ubuntu_terminal_workbench"))
        UbuntuToolCapabilities.register(host, terminal)
        UbuntuFileSystemCapability.register(host, terminal)
        val processCapability = UbuntuProcessCapability(host.scope, terminal)
        processCapability.register(host)
        host.registerCapability(
            InProcessCapabilitySpec(
                id = UI_ACTION_CAPABILITY,
                displayName = "Ubuntu 终端前台操作",
                description = "承接 Plugin Center 终端工作台的明确用户操作。",
                effect = InProcessCapabilityEffect.UI_INTERACTION,
                domain = InProcessCapabilityDomain.SYSTEM_ENVIRONMENT,
                executor = InProcessCapabilityExecutor { parameters -> panel.uiActionCapability(parameters) }
            )
        )
        host.registerScreen(
            InProcessScreen(
                id = SCREEN_ID,
                title = "Ubuntu命令终端",
                description = "插件自持有 Ubuntu Runtime、持久 PTY、多标签终端与兰儿共享观察。",
                schemaId = PLUGIN_CENTER_UI_SCHEMA,
                documentJson = JSONObject().put("schema", 1).put("layout", "edge_to_edge").put("blocks", JSONArray().put(JSONObject().put("type", "terminal_workbench").put("provider_id", PANEL_ID).put("action_capability_id", UI_ACTION_CAPABILITY).put("metrics_profile", "ai_limbs_06478"))).toString()
            )
        )
        host.registerHomeTile(InProcessHomeTile(id = TILE_ID, title = "Ubuntu命令终端", description = "插件自持有 Ubuntu 与多标签持久 PTY", screenId = SCREEN_ID))
        panel.start()
        return InProcessPluginHandle {
            panel.stop()
            processCapability.shutdown()
            terminal.prepareForMaintenance()
        }
    }

    private companion object {
        const val PANEL_ID = "plugin.ubuntu.terminal_panel"
        const val SCREEN_ID = "plugin.system_environment.screen"
        const val TILE_ID = "plugin.system.ubuntu_terminal.tile"
        const val UI_ACTION_CAPABILITY = "plugin.ubuntu.ui_action"
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
    private val host: InProcessPluginHost,
    private val terminal: TerminalManager
) : InProcessUiStateProvider {
    private val mutableState = MutableStateFlow<String?>(null)
    override val stateJson: StateFlow<String?> = mutableState.asStateFlow()

    private val tabs = mutableListOf(
        TerminalTab(id = LOCAL_TAB_ID, title = "Local", closable = false)
    )
    private var activeTabId = LOCAL_TAB_ID
    private var nextTabNumber = 2
    private var sharedVisible = true
    private var sharedContent = ""
    private var sharedOnline = false
    private var ubuntuState = "UNKNOWN"
    private var ubuntuDetail = "尚未读取 Ubuntu Runtime 状态"
    private var idleMode = "UNKNOWN"
    private var idleTimeoutMinutes: Int? = null
    private var statusMessage = "正在读取 Ubuntu Runtime 状态…"
    private var pollJob: Job? = null
    private var uiClientCount = 0

    init {
        publishState()
    }

    fun start() {
        host.scope.launch {
            runCatching {
                refreshStatusInternal()
                refreshAllScreens()
                refreshSharedScreen()
                statusMessage = if (ubuntuState == RUNNING) "Ubuntu 已运行；Local 会话将在首次前台操作时创建。" else "Ubuntu 尚未运行。"
            }.onFailure { statusMessage = "状态读取失败：" + (it.message ?: "未知错误") }
            publishState()
        }
        pollJob = host.scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                runCatching { refreshStatusInternal() }
                runCatching { refreshAllScreens() }
                runCatching { refreshSharedScreen() }
                publishState()
            }
        }
    }

    suspend fun stop() {
        pollJob?.cancel()
        detachAllUiClients()
        closeAllSessions()
    }

    override suspend fun perform(eventId: String, payloadJson: String): String {
        val payload = runCatching { JSONObject(payloadJson) }.getOrElse { JSONObject() }
        return runCatching {
            when (eventId) {
                ACTION_UI_ATTACH -> attachUiClient()
                ACTION_UI_DETACH -> detachUiClient()
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

    @Synchronized
    private fun attachUiClient(): String {
        terminal.registerUbuntuUiClient()
        uiClientCount += 1
        return result("Ubuntu 终端前台已连接。")
    }

    @Synchronized
    private fun detachUiClient(): String {
        if (uiClientCount > 0) {
            uiClientCount -= 1
            terminal.unregisterUbuntuUiClient()
        }
        return result("Ubuntu 终端前台已离开。")
    }

    @Synchronized
    private fun detachAllUiClients() {
        repeat(uiClientCount) { terminal.unregisterUbuntuUiClient() }
        uiClientCount = 0
    }

    suspend fun uiActionCapability(parametersJson: String): String {
        val parameters = runCatching { JSONObject(parametersJson) }.getOrElse { JSONObject() }
        val eventId = parameters.optString("event_id").trim()
        require(eventId.isNotBlank()) { "event_id 不能为空" }
        val payload = parameters.optJSONObject("payload") ?: JSONObject()
        return perform(eventId, payload.toString())
    }

    suspend fun statusCapability(): String {
        refreshStatusInternal()
        refreshAllScreens()
        refreshSharedScreen()
        publishState()
        return JSONObject()
            .put("ubuntu_state", ubuntuState)
            .put("ubuntu_detail", ubuntuDetail)
            .put("idle_mode", idleMode)
            .put("idle_timeout_minutes", idleTimeoutMinutes ?: JSONObject.NULL)
            .put("active_tab_id", activeTabId)
            .put("shared_online", sharedOnline)
            .put("runtime_owner", "plugin")
            .put("tabs", JSONArray().apply { tabs.forEach { tab -> put(JSONObject().put("id", tab.id).put("title", tab.title).put("session_id", tab.sessionId ?: JSONObject.NULL)) } })
            .toString()
    }

    suspend fun commandCapability(parametersJson: String): String {
        val parameters = runCatching { JSONObject(parametersJson) }.getOrElse { JSONObject() }
        val command = parameters.optString("command").trim()
        if (command.isBlank()) return errorJson(IllegalArgumentException("command 不能为空"))
        sharedVisible = true
        sharedOnline = true
        statusMessage = "兰儿正在 Ubuntu 插件共享 shell 中执行命令。"
        publishState()
        return try {
            refreshStatusInternal()
            require(ubuntuState == RUNNING) { "Ubuntu 当前未运行，请先启动 Ubuntu" }
            val exec = terminal.executeHiddenCommand(command, executorKey = "plugin.ubuntu.command")
            refreshSharedScreen()
            JSONObject()
                .put("status", exec.state.name)
                .put("success", exec.isOk)
                .put("exit_code", exec.exitCode)
                .put("output", exec.output)
                .put("error", exec.error.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .toString()
        } catch (error: Throwable) {
            errorJson(error)
        } finally {
            sharedOnline = terminal.sharedHiddenTerminalState.value.isActive
            statusMessage = "兰儿共享命令已结束。"
            publishState()
        }
    }

    private suspend fun startUbuntu(): String {
        val state = terminal.startUbuntu(offerDevelopmentPrompt = true)
        refreshStatusInternal()
        require(state.phase == UbuntuRuntimePhase.RUNNING) { state.error ?: state.detail }
        val local = activeLocalTab() ?: tabs.first()
        activeTabId = local.id
        ensureSession(local)
        refreshTabScreen(local)
        statusMessage = "Ubuntu 已启动，插件 PTY 会话已打开。"
        return result(statusMessage)
    }

    private suspend fun stopUbuntu(): String {
        require(activeTabId != SHARED_TAB_ID) { "兰儿共享标签为只读，不能在此停止 Ubuntu" }
        closeAllSessions()
        val state = terminal.stopUbuntu(UbuntuStopRequester.USER_INTERFACE)
        refreshStatusInternal()
        tabs.forEach { it.content = "" }
        sharedContent = ""
        statusMessage = state.error ?: state.detail
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
        tab.sessionId?.let { terminal.closeSession(it) }
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
        val ok = terminal.sendInputToSessionNow(tab.sessionId ?: error("PTY 会话未创建"), command + "\r")
        require(ok) { "命令未能写入插件 PTY" }
        delay(120)
        refreshTabScreen(tab)
        statusMessage = "命令已发送到 " + tab.title + "。"
        return result(statusMessage, clearInput = true)
    }

    private suspend fun sendControlC(): String {
        val tab = activeLocalTab() ?: error("兰儿共享标签为只读，不能发送 Ctrl+C")
        val sessionId = tab.sessionId ?: error("当前标签没有打开 PTY 会话")
        require(terminal.sendInterruptSignalToSessionNow(sessionId)) { "Ctrl+C 未能写入插件 PTY" }
        delay(80)
        runCatching { refreshTabScreen(tab) }
        statusMessage = "已向 " + tab.title + " 发送 Ctrl+C。"
        return result(statusMessage)
    }

    private suspend fun setIdlePolicy(mode: String): String {
        require(activeTabId != SHARED_TAB_ID) { "兰儿共享标签为只读，不能修改环境配置" }
        val idleModeValue = runCatching { UbuntuIdleMode.valueOf(mode) }.getOrElse { error("不支持的空闲策略：$mode") }
        terminal.updateUbuntuIdlePolicy(UbuntuIdlePolicy(mode = idleModeValue))
        refreshStatusInternal()
        statusMessage = "Ubuntu 空闲策略已更新：" + idleLabel()
        return result(statusMessage)
    }

    private suspend fun refreshStatusInternal() {
        val runtime = terminal.currentUbuntuRuntimeState()
        val idle = terminal.currentUbuntuIdlePolicy()
        ubuntuState = runtime.phase.name
        ubuntuDetail = runtime.error ?: runtime.detail
        idleMode = idle.mode.name
        idleTimeoutMinutes = idle.timeoutMinutes
        if (ubuntuState != RUNNING) {
            tabs.forEach { it.sessionId = null }
            sharedOnline = false
        }
    }

    private suspend fun ensureSession(tab: TerminalTab) {
        if (tab.sessionId != null && terminal.terminalState.value.sessions.any { it.id == tab.sessionId }) return
        val created = terminal.createNewSession(tab.title)
        tab.sessionId = created.id
    }

    private suspend fun refreshAllScreens() {
        tabs.forEach { tab ->
            if (tab.sessionId != null) runCatching { refreshTabScreen(tab) }
        }
    }

    private suspend fun refreshTabScreen(tab: TerminalTab) {
        val sessionId = tab.sessionId ?: return
        val session = terminal.terminalState.value.sessions.firstOrNull { it.id == sessionId }
        if (session == null) {
            tab.sessionId = null
            return
        }
        tab.content = renderScreen(session.ansiParser.getScreenContent())
    }

    private suspend fun refreshSharedScreen() {
        val shared = terminal.sharedHiddenTerminalState.value
        sharedOnline = shared.isActive
        sharedContent = buildString {
            if (shared.command.isNotBlank()) append("$ " + shared.command + "\n")
            if (shared.output.isNotBlank()) append(shared.output)
            if (!shared.error.isNullOrBlank()) append("\n[ERROR] " + shared.error)
            if (shared.exitCode != null && !shared.isActive) append("\n[exit " + shared.exitCode + "]")
        }
    }

    private suspend fun closeAllSessions() {
        tabs.mapNotNull { it.sessionId }.distinct().forEach { terminal.closeSession(it) }
        tabs.forEach { it.sessionId = null }
        sharedOnline = false
    }

    private fun activeLocalTab(): TerminalTab? =
        tabs.firstOrNull { it.id == activeTabId }

    private fun renderScreen(screen: Array<Array<com.ai.limbs.plugins.ubuntu.runtime.terminal.view.domain.ansi.TerminalChar>>): String {
        val lines = screen.map { row -> buildString { row.forEach { cell -> append(cell.char) } }.trimEnd() }.toMutableList()
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n")
    }

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
                    .put("ui_attach", ACTION_UI_ATTACH)
                    .put("ui_detach", ACTION_UI_DETACH)
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

    private companion object {
        const val RUNNING = "RUNNING"
        const val LOCAL_TAB_ID = "local-1"
        const val SHARED_TAB_ID = "laner-shared"
        const val POLL_INTERVAL_MS = 650L
        const val ACTION_UI_ATTACH = "ui_attach"
        const val ACTION_UI_DETACH = "ui_detach"
        const val ACTION_START = "start_ubuntu"
        const val ACTION_STOP = "stop_ubuntu"
        const val ACTION_ADD_TAB = "add_tab"
        const val ACTION_SELECT_TAB = "select_tab"
        const val ACTION_CLOSE_TAB = "close_tab"
        const val ACTION_SHOW_SHARED = "show_shared"
        const val ACTION_EXECUTE = "execute_command"
        const val ACTION_CTRL_C = "ctrl_c"
        const val ACTION_SET_IDLE = "set_idle_policy"
    }
}
