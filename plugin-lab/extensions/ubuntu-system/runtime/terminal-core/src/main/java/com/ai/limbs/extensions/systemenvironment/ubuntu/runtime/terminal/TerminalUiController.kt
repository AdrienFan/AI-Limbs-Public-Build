package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal

import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.TerminalSessionData
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.TerminalState
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuIdlePolicy
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuRuntimeState
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuStopRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/** UI-facing terminal contract. Implementations may be local or a Resident Core mirror. */
interface TerminalUiController {
    val coroutineScope: CoroutineScope
    val terminalState: StateFlow<TerminalState>
    val ubuntuRuntimeState: StateFlow<UbuntuRuntimeState>
    val ubuntuIdlePolicy: StateFlow<UbuntuIdlePolicy>
    val sharedHiddenTerminalState: StateFlow<SharedHiddenTerminalState>
    val localBusinessSettingsAvailable: Boolean get() = true

    fun registerUbuntuUiClient()
    fun unregisterUbuntuUiClient()
    fun updateUbuntuIdlePolicy(policy: UbuntuIdlePolicy): UbuntuIdlePolicy
    suspend fun startUbuntu(offerDevelopmentPrompt: Boolean = false): UbuntuRuntimeState
    suspend fun stopUbuntu(): UbuntuRuntimeState
    suspend fun createNewSession(title: String? = null): TerminalSessionData
    fun switchToSession(sessionId: String)
    suspend fun prepareDevelopmentEnvironmentInstaller(): Boolean
    fun closeSession(sessionId: String)
    fun saveScrollOffset(sessionId: String, scrollOffset: Float)
    fun getScrollOffset(sessionId: String): Float
    suspend fun sendCommand(command: String, commandId: String? = null): String
    suspend fun sendCommandToSession(sessionId: String, command: String, commandId: String? = null): String
    fun sendInput(input: String)
    fun sendInterruptSignal()
    fun updateSessionSize(sessionId: String, rows: Int, cols: Int)
}

/** Legacy/single-process adapter; all business calls still land on the one local TerminalManager. */
class LocalTerminalUiController(
    private val terminal: TerminalManager
) : TerminalUiController {
    override val coroutineScope: CoroutineScope get() = terminal.coroutineScope
    override val terminalState: StateFlow<TerminalState> get() = terminal.terminalState
    override val ubuntuRuntimeState: StateFlow<UbuntuRuntimeState> get() = terminal.ubuntuRuntimeState
    override val ubuntuIdlePolicy: StateFlow<UbuntuIdlePolicy> get() = terminal.ubuntuIdlePolicy
    override val sharedHiddenTerminalState: StateFlow<SharedHiddenTerminalState> get() = terminal.sharedHiddenTerminalState

    override fun registerUbuntuUiClient() = terminal.registerUbuntuUiClient()
    override fun unregisterUbuntuUiClient() = terminal.unregisterUbuntuUiClient()
    override fun updateUbuntuIdlePolicy(policy: UbuntuIdlePolicy): UbuntuIdlePolicy = terminal.updateUbuntuIdlePolicy(policy)
    override suspend fun startUbuntu(offerDevelopmentPrompt: Boolean): UbuntuRuntimeState = terminal.startUbuntu(offerDevelopmentPrompt)
    override suspend fun stopUbuntu(): UbuntuRuntimeState = terminal.stopUbuntu(UbuntuStopRequester.USER_INTERFACE)
    override suspend fun createNewSession(title: String?): TerminalSessionData = terminal.createNewSession(title)
    override fun switchToSession(sessionId: String) = terminal.switchToSession(sessionId)
    override suspend fun prepareDevelopmentEnvironmentInstaller(): Boolean = terminal.prepareDevelopmentEnvironmentInstaller()
    override fun closeSession(sessionId: String) = terminal.closeSession(sessionId)
    override fun saveScrollOffset(sessionId: String, scrollOffset: Float) = terminal.saveScrollOffset(sessionId, scrollOffset)
    override fun getScrollOffset(sessionId: String): Float = terminal.getScrollOffset(sessionId)
    override suspend fun sendCommand(command: String, commandId: String?): String = terminal.sendCommand(command, commandId)
    override suspend fun sendCommandToSession(sessionId: String, command: String, commandId: String?): String =
        terminal.sendCommandToSession(sessionId, command, commandId)
    override fun sendInput(input: String) = terminal.sendInput(input)
    override fun sendInterruptSignal() = terminal.sendInterruptSignal()
    override fun updateSessionSize(sessionId: String, rows: Int, cols: Int) {
        terminal.coroutineScope.launch { terminal.resizeSessionNow(sessionId, rows, cols) }
    }
}
