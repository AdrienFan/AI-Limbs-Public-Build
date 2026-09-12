package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal

import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.RuntimeLog as Log
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.TerminalSessionData
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.TerminalState
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.provider.type.TerminalType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 终端会话管理器
 * 负责管理多个终端会话的生命周期
 */
class SessionManager(private val terminalManager: TerminalManager) {

    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    /**
     * 创建新会话
     *
     * @param title 会话标题
     * @param terminalType 终端类型
     */
    fun createNewSession(
        title: String? = null,
        terminalType: TerminalType,
        select: Boolean = true,
        isBackground: Boolean = false
    ): TerminalSessionData {
        lateinit var newSession: TerminalSessionData
        _state.update { currentState ->
            val displayNumber =
                if (isBackground) {
                    null
                } else {
                    val usedNumbers = currentState.sessions
                        .asSequence()
                        .filter { !it.isBackground }
                        .mapNotNull { it.displayNumber }
                        .toSet()
                    generateSequence(1) { it + 1 }.first { it !in usedNumbers }
                }
            val defaultTitle =
                if (isBackground) {
                    when (terminalType) {
                        TerminalType.LOCAL -> "Ubuntu Background"
                        TerminalType.SSH -> "SSH Background"
                        else -> "Terminal Background"
                    }
                } else {
                    when (terminalType) {
                        TerminalType.LOCAL -> "Ubuntu ${requireNotNull(displayNumber)}"
                        TerminalType.SSH -> "SSH ${requireNotNull(displayNumber)}"
                        else -> "Terminal ${requireNotNull(displayNumber)}"
                    }
                }
            newSession = TerminalSessionData(
                title = title ?: defaultTitle,
                terminalType = terminalType,
                isBackground = isBackground,
                displayNumber = displayNumber
            )
            currentState.copy(
                sessions = currentState.sessions + newSession,
                currentSessionId = if (select && !isBackground) newSession.id else currentState.currentSessionId
            )
        }

        Log.d("SessionManager", "Created new session: ${newSession.id} (type: $terminalType)")
        return newSession
    }

    /**
     * 切换到指定会话
     */
    fun switchToSession(sessionId: String): Boolean {
        var switched = false
        _state.update { currentState ->
            if (currentState.sessions.any { it.id == sessionId }) {
                switched = true
                currentState.copy(currentSessionId = sessionId)
            } else {
                currentState
            }
        }
        if (switched) {
            Log.d("SessionManager", "Switched to session: $sessionId")
        } else {
            Log.w("SessionManager", "Session not found: $sessionId")
        }
        return switched
    }

    /**
     * 关闭会话
     */
    fun closeSession(sessionId: String) {
        _state.update { currentState ->
            val sessionToClose = currentState.sessions.find { it.id == sessionId }

            sessionToClose?.let { session ->
                try {
                    // 清理资源
                    session.readJob?.cancel()
                    session.sessionWriter?.close()
                    terminalManager.closeTerminalSession(session.id)
                } catch (e: Exception) {
                    Log.e("SessionManager", "Error cleaning up session", e)
                }
            }

            val updatedSessions = currentState.sessions.filter { it.id != sessionId }
            val newCurrentSessionId = if (currentState.currentSessionId == sessionId) {
                updatedSessions.firstOrNull { !it.isBackground }?.id
            } else {
                currentState.currentSessionId
            }

            currentState.copy(
                sessions = updatedSessions,
                currentSessionId = newCurrentSessionId
            )
        }

        terminalManager.onSessionClosed(sessionId)

        Log.d("SessionManager", "Closed session: $sessionId")
    }

    /**
     * 更新会话数据
     */
    fun updateSession(sessionId: String, updater: (TerminalSessionData) -> TerminalSessionData) {
        _state.update { currentState ->
            val updatedSessions = currentState.sessions.map { session ->
                if (session.id == sessionId) {
                    updater(session)
                } else {
                    session
                }
            }
            currentState.copy(sessions = updatedSessions)
        }
    }

    /**
     * 保存会话的滚动位置
     */
    fun saveScrollOffset(sessionId: String, scrollOffset: Float) {
        updateSession(sessionId) { session ->
            session.copy(scrollOffsetY = scrollOffset)
        }
    }

    /**
     * 获取会话的滚动位置
     */
    fun getScrollOffset(sessionId: String): Float {
        return getSession(sessionId)?.scrollOffsetY ?: 0f
    }

    /**
     * 获取当前会话
     */
    fun getCurrentSession(): TerminalSessionData? {
        return _state.value.currentSession
    }

    /**
     * 获取指定会话
     */
    fun getSession(sessionId: String): TerminalSessionData? {
        return _state.value.sessions.find { it.id == sessionId }
    }

    /**
     * 清理会话资源
     */
     /*
    private fun cleanupSession(session: TerminalSessionData) {
        // 首先取消读取协程
        session.readJob?.cancel()

        // 然后关闭流和进程
        session.sessionWriter?.close()
        session.terminalSession?.process?.destroy()
    }
    */

    /**
     * 清理所有会话
     */
    fun cleanup() {
        val currentState = _state.value
        currentState.sessions.forEach { session ->
            try {
                closeSession(session.id)
            } catch (e: Exception) {
                Log.e("SessionManager", "Error cleaning up session ${session.id}", e)
            }
        }

        _state.value = TerminalState()
        Log.d("SessionManager", "All sessions cleaned up")
    }
}
