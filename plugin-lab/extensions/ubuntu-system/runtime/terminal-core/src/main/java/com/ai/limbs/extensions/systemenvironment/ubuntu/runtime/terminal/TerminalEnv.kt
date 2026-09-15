package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.TerminalSessionData
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuIdlePolicy
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuRuntimePhase
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuRuntimeState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.RuntimeLog as Log
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.view.domain.ansi.AnsiTerminalEmulator

@Stable
class TerminalEnv(
    sessionsState: State<List<TerminalSessionData>>,
    currentSessionIdState: State<String?>,
    currentDirectoryState: State<String>,
    isFullscreenState: State<Boolean>,
    terminalEmulatorState: State<AnsiTerminalEmulator>,
    ubuntuRuntimeState: State<UbuntuRuntimeState>,
    ubuntuIdlePolicyState: State<UbuntuIdlePolicy>,
    internal val terminalController: TerminalUiController,
    val forceShowSetup: Boolean = false
) {
    val sessions by sessionsState
    val currentSessionId by currentSessionIdState
    val currentDirectory by currentDirectoryState
    val isFullscreen by isFullscreenState
    val terminalEmulator by terminalEmulatorState
    val ubuntuRuntimeState by ubuntuRuntimeState
    val ubuntuIdlePolicy by ubuntuIdlePolicyState

    var command by mutableStateOf("")

    fun onCommandChange(newCommand: String) {
        command = newCommand
    }

    fun onSendInput(inputText: String, isCommand: Boolean) {
        // 允许空输入（用于交互式场景发送回车）
        if (isCommand) {
            // 命令模式：也允许空命令（用于 SSH 等交互场景）
            terminalController.coroutineScope.launch {
                terminalController.sendCommand(inputText)
            }
            if (inputText == command) {
                command = ""
            }
        } else {
            // 输入模式：允许空输入（例如 ssh-keygen 直接回车使用默认路径）
            terminalController.sendInput(inputText)
        }
    }

    fun onSetup(commands: List<String>) {
        val fullCommand = commands.joinToString(separator = " && ")
        terminalController.coroutineScope.launch {
            terminalController.sendCommand(fullCommand)
        }
    }

    fun onInterrupt() = terminalController.sendInterruptSignal()
    fun onStartUbuntu() {
        terminalController.coroutineScope.launch {
            terminalController.startUbuntu(offerDevelopmentPrompt = true)
        }
    }

    fun onStopUbuntu(onBlocked: (String) -> Unit = {}) {
        terminalController.coroutineScope.launch {
            val state = terminalController.stopUbuntu()
            if (state.phase != UbuntuRuntimePhase.STOPPED) {
                state.error?.takeIf { it.isNotBlank() }?.let { message ->
                    withContext(Dispatchers.Main) {
                        onBlocked(message)
                    }
                }
            }
        }
    }

    fun onUbuntuIdlePolicyChange(policy: UbuntuIdlePolicy) {
        terminalController.updateUbuntuIdlePolicy(policy)
    }

    fun onNewSession() {
        // 在terminalManager的协程作用域中异步创建会话
        terminalController.coroutineScope.launch {
            try {
                terminalController.createNewSession()
                Log.d("TerminalEnv", "New session created successfully")
            } catch (e: Exception) {
                Log.e("TerminalEnv", "Failed to create new session", e)
            }
        }
    }
    fun onSwitchSession(sessionId: String) = terminalController.switchToSession(sessionId)
    fun onCloseSession(sessionId: String) = terminalController.closeSession(sessionId)

    fun saveScrollOffset(sessionId: String, scrollOffset: Float) = terminalController.saveScrollOffset(sessionId, scrollOffset)
    fun getScrollOffset(sessionId: String): Float = terminalController.getScrollOffset(sessionId)
}

@Composable
fun rememberTerminalEnv(terminalController: TerminalUiController, forceShowSetup: Boolean = false): TerminalEnv {
    val terminalState = terminalController.terminalState.collectAsState()
    val sessionsState = androidx.compose.runtime.derivedStateOf { terminalState.value.sessions }
    val currentSessionIdState = androidx.compose.runtime.derivedStateOf { terminalState.value.currentSessionId }
    val currentDirectoryState = androidx.compose.runtime.derivedStateOf { terminalState.value.currentSession?.currentDirectory ?: "$ " }
    val isFullscreenState = androidx.compose.runtime.derivedStateOf { terminalState.value.currentSession?.isFullscreen ?: false }
    val placeholderEmulator = remember { AnsiTerminalEmulator(screenWidth = 1, screenHeight = 1, historySize = 0) }
    val terminalEmulatorState = androidx.compose.runtime.derivedStateOf { terminalState.value.currentSession?.ansiParser ?: placeholderEmulator }
    val ubuntuRuntimeState = terminalController.ubuntuRuntimeState.collectAsState()
    val ubuntuIdlePolicyState = terminalController.ubuntuIdlePolicy.collectAsState()

    return remember(terminalController, forceShowSetup) {
        TerminalEnv(
            sessionsState = sessionsState,
            currentSessionIdState = currentSessionIdState,
            currentDirectoryState = currentDirectoryState,
            isFullscreenState = isFullscreenState,
            terminalEmulatorState = terminalEmulatorState,
            ubuntuRuntimeState = ubuntuRuntimeState,
            ubuntuIdlePolicyState = ubuntuIdlePolicyState,
            terminalController = terminalController,
            forceShowSetup = forceShowSetup
        )
    }
}
