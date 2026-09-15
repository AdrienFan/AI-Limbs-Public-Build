package com.ai.limbs.extensions.systemenvironment.ubuntu

import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.SharedHiddenTerminalState
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.TerminalUiController
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.SessionInitState
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.TerminalSessionData
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.TerminalState
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuIdleMode
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuIdlePolicy
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuRuntimePhase
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuRuntimeState
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.provider.type.TerminalType
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.view.domain.ansi.AnsiTerminalEmulator
import com.ai.limbs.plugin.runtime.ChildExtensionPresentationHost
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Host-side mirror. It never creates a PTY, TerminalManager or Linux process. */
internal class UbuntuResidentPresentationController(
    private val host: ChildExtensionPresentationHost
) : TerminalUiController {
    override val coroutineScope: CoroutineScope get() = host.scope
    override val localBusinessSettingsAvailable: Boolean = false

    private val mutableTerminalState = MutableStateFlow(TerminalState())
    override val terminalState: StateFlow<TerminalState> = mutableTerminalState.asStateFlow()
    private val mutableRuntime = MutableStateFlow(UbuntuRuntimeState())
    override val ubuntuRuntimeState: StateFlow<UbuntuRuntimeState> = mutableRuntime.asStateFlow()
    private val mutableIdle = MutableStateFlow(UbuntuIdlePolicy())
    override val ubuntuIdlePolicy: StateFlow<UbuntuIdlePolicy> = mutableIdle.asStateFlow()
    private val mutableSharedHidden = MutableStateFlow(SharedHiddenTerminalState())
    override val sharedHiddenTerminalState: StateFlow<SharedHiddenTerminalState> =
        mutableSharedHidden.asStateFlow()

    private val refreshMutex = Mutex()
    private val emulators = ConcurrentHashMap<String, AnsiTerminalEmulator>()
    private val localUiClients = AtomicInteger(0)
    private val uiLeaseId = UUID.randomUUID().toString()
    private val scrollOffsets = ConcurrentHashMap<String, Float>()

    init {
        host.scope.launch {
            while (isActive) {
                runCatching { refresh() }.onFailure { error ->
                    host.logger.w("ResidentUbuntuUi", "Mirror refresh failed: ${error.message}")
                }
                delay(500L)
            }
        }
    }

    override fun registerUbuntuUiClient() {
        if (localUiClients.incrementAndGet() == 1) {
            host.scope.launch { sendUiLease("attach") }
        }
    }

    override fun unregisterUbuntuUiClient() {
        val remaining = localUiClients.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        if (remaining == 0) {
            host.scope.launch { sendUiLease("detach") }
        }
    }

    override fun updateUbuntuIdlePolicy(policy: UbuntuIdlePolicy): UbuntuIdlePolicy {
        mutableIdle.value = policy
        host.scope.launch {
            invoke(
                "plugin.ubuntu.idle.set",
                JSONObject()
                    .put("mode", policy.mode.name)
                    .put("custom_minutes", policy.customMinutes)
            )
            refresh()
        }
        return policy
    }

    override suspend fun startUbuntu(offerDevelopmentPrompt: Boolean): UbuntuRuntimeState {
        invoke("plugin.ubuntu.start", JSONObject().put("_resident_ui_request", true))
        refresh()
        return mutableRuntime.value
    }

    override suspend fun stopUbuntu(): UbuntuRuntimeState {
        invoke("plugin.ubuntu.stop", JSONObject().put("_resident_ui_request", true))
        refresh()
        return mutableRuntime.value
    }

    override suspend fun createNewSession(title: String?): TerminalSessionData {
        val result = invoke(
            "plugin.ubuntu.session.create",
            JSONObject().apply { if (!title.isNullOrBlank()) put("session_name", title) }
        )
        val id = result.getString("session_id")
        refresh(preferredSessionId = id)
        return mutableTerminalState.value.sessions.first { it.id == id }
    }

    override fun switchToSession(sessionId: String) {
        val current = mutableTerminalState.value
        if (current.sessions.none { it.id == sessionId }) return
        mutableTerminalState.value = current.copy(currentSessionId = sessionId)
        host.scope.launch { refreshScreen(sessionId) }
    }

    override suspend fun prepareDevelopmentEnvironmentInstaller(): Boolean =
        invoke("plugin.ubuntu.development.prepare").optBoolean("prepared", false)

    override fun closeSession(sessionId: String) {
        host.scope.launch {
            invoke("plugin.ubuntu.session.close", JSONObject().put("session_id", sessionId))
            emulators.remove(sessionId)
            scrollOffsets.remove(sessionId)
            refresh()
        }
    }

    override fun saveScrollOffset(sessionId: String, scrollOffset: Float) {
        scrollOffsets[sessionId] = scrollOffset
    }

    override fun getScrollOffset(sessionId: String): Float = scrollOffsets[sessionId] ?: 0f

    override suspend fun sendCommand(command: String, commandId: String?): String {
        val id = mutableTerminalState.value.currentSessionId ?: return commandId ?: UUID.randomUUID().toString()
        return sendCommandToSession(id, command, commandId)
    }

    override suspend fun sendCommandToSession(
        sessionId: String,
        command: String,
        commandId: String?
    ): String {
        invoke("plugin.ubuntu.session.input", JSONObject().put("session_id", sessionId).put("input", command))
        invoke("plugin.ubuntu.session.input", JSONObject().put("session_id", sessionId).put("control", "enter"))
        return commandId ?: UUID.randomUUID().toString()
    }

    override fun sendInput(input: String) {
        val id = mutableTerminalState.value.currentSessionId ?: return
        host.scope.launch {
            invoke("plugin.ubuntu.session.input", JSONObject().put("session_id", id).put("input", input))
        }
    }

    override fun sendInterruptSignal() {
        val id = mutableTerminalState.value.currentSessionId ?: return
        host.scope.launch {
            invoke("plugin.ubuntu.session.interrupt", JSONObject().put("session_id", id))
        }
    }

    suspend fun refresh(preferredSessionId: String? = null) = refreshMutex.withLock {
        val status = invoke(
            "plugin.ubuntu.status",
            if (localUiClients.get() > 0) {
                JSONObject()
                    .put("_resident_ui_lease_id", uiLeaseId)
                    .put("_resident_ui_event", "heartbeat")
            } else {
                JSONObject()
            }
        )
        mutableRuntime.value = UbuntuRuntimeState(
            phase = runCatching { UbuntuRuntimePhase.valueOf(status.optString("state")) }
                .getOrDefault(UbuntuRuntimePhase.ERROR),
            detail = status.optString("detail"),
            error = status.nullableString("error")
        )
        val mode = runCatching { UbuntuIdleMode.valueOf(status.optString("idle_mode")) }
            .getOrDefault(UbuntuIdleMode.KEEP_RUNNING)
        mutableIdle.value = UbuntuIdlePolicy(
            mode = mode,
            customMinutes = status.optInt("custom_minutes", UbuntuIdlePolicy.DEFAULT_CUSTOM_MINUTES)
                .coerceIn(UbuntuIdlePolicy.MIN_CUSTOM_MINUTES, UbuntuIdlePolicy.MAX_CUSTOM_MINUTES)
        )
        status.optJSONObject("shared_hidden")?.let { shared ->
            mutableSharedHidden.value = SharedHiddenTerminalState(
                operationId = shared.nullableString("operation_id"),
                command = shared.optString("command"),
                output = shared.optString("output"),
                isActive = shared.optBoolean("active"),
                activeOperationCount = shared.optInt("active_operation_count"),
                exitCode = if (shared.isNull("exit_code")) null else shared.optInt("exit_code"),
                error = shared.nullableString("error"),
                updatedAtMillis = shared.optLong("updated_at_millis")
            )
        }

        val sessionsArray = status.optJSONArray("sessions")
        val previous = mutableTerminalState.value
        val sessions = buildList {
            if (sessionsArray != null) {
                for (index in 0 until sessionsArray.length()) {
                    val item = sessionsArray.getJSONObject(index)
                    val id = item.getString("id")
                    add(
                        TerminalSessionData(
                            id = id,
                            title = item.optString("title", "Terminal"),
                            terminalType = runCatching { TerminalType.valueOf(item.optString("terminal_type")) }
                                .getOrDefault(TerminalType.LOCAL),
                            isBackground = item.optBoolean("background"),
                            currentDirectory = item.optString("current_directory", "$ "),
                            isInteractiveMode = item.optBoolean("interactive"),
                            interactivePrompt = item.optString("interactive_prompt"),
                            initState = runCatching { SessionInitState.valueOf(item.optString("init_state")) }
                                .getOrDefault(SessionInitState.READY),
                            isFullscreen = item.optBoolean("fullscreen"),
                            ansiParser = emulators.computeIfAbsent(id) { AnsiTerminalEmulator() },
                            scrollOffsetY = scrollOffsets[id] ?: 0f
                        )
                    )
                }
            }
        }
        val coreCurrent = status.nullableString("current_session_id")
        val desired = preferredSessionId
            ?: previous.currentSessionId?.takeIf { selected -> sessions.any { it.id == selected } }
            ?: coreCurrent?.takeIf { selected -> sessions.any { it.id == selected } }
            ?: sessions.firstOrNull { !it.isBackground }?.id
        mutableTerminalState.value = TerminalState(
            sessions = sessions,
            currentSessionId = desired,
            isLoading = false,
            error = null
        )
        desired?.let { refreshScreen(it) }
    }

    private suspend fun refreshScreen(sessionId: String) {
        if (mutableTerminalState.value.sessions.none { it.id == sessionId }) return
        val screen = invoke(
            "plugin.ubuntu.session.screen",
            JSONObject().put("session_id", sessionId)
        )
        val rows = screen.optInt("rows", 1).coerceAtLeast(1)
        val cols = screen.optInt("cols", 1).coerceAtLeast(1)
        val emulator = AnsiTerminalEmulator(screenWidth = cols, screenHeight = rows, historySize = 200)
        emulator.parse(screen.optString("content"))
        emulators[sessionId] = emulator
        val current = mutableTerminalState.value
        mutableTerminalState.value = current.copy(
            sessions = current.sessions.map { session ->
                if (session.id == sessionId) session.copy(ansiParser = emulator) else session
            }
        )
    }

    internal suspend fun invokeCapability(id: String, parametersJson: String): String =
        invoke(id, runCatching { JSONObject(parametersJson) }.getOrElse { JSONObject() }).toString()

    private suspend fun sendUiLease(event: String) {
        runCatching {
            invoke(
                "plugin.ubuntu.status",
                JSONObject()
                    .put("_resident_ui_lease_id", uiLeaseId)
                    .put("_resident_ui_event", event)
            )
        }.onFailure { error ->
            host.logger.w("ResidentUbuntuUi", "UI lease $event failed: ${error.message}")
        }
    }

    private suspend fun invoke(id: String, params: JSONObject = JSONObject()): JSONObject {
        val result = JSONObject(host.invokeChildCapability(id, params.toString()))
        if (result.has("success") && !result.optBoolean("success", false)) {
            error(result.nullableString("error") ?: "Ubuntu Core operation failed: $id")
        }
        return result
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
