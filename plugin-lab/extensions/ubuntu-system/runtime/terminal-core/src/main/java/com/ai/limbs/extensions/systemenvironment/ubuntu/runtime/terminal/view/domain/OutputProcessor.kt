package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.view.domain

import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.RuntimeLog as Log
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.CommandExecutionEvent
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.PtyInputWaitState
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.provider.type.TerminalType
import java.util.concurrent.ConcurrentHashMap
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.SessionDirectoryEvent
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.SessionManager
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.SessionInitState
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.TerminalSessionData
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.view.domain.ansi.AnsiUtils

/**
 * 终端输出的会话处理状态
 * @property justHandledCarriageReturn 如果最近处理的行分隔符是回车符（CR），则为 true
 */
private data class SessionProcessingState(
    var justHandledCarriageReturn: Boolean = false,
    val outputLines: CommandOutputLineAssembler = CommandOutputLineAssembler(),
    @Volatile var partialOutput: String = ""
)

/**
 * 终端输出处理器
 * 负责处理和解析终端输出，更新会话状态
 */
class OutputProcessor(
    private val onCommandExecutionEvent: (CommandExecutionEvent) -> Unit = {},
    private val onDirectoryChangeEvent: (SessionDirectoryEvent) -> Unit = {},
    private val onCommandCompleted: (String) -> Unit = {}
) {

    private val sessionStates = ConcurrentHashMap<String, SessionProcessingState>()

    companion object {
        private const val TAG = "OutputProcessor"
        private const val MAX_LINES_PER_HISTORY_ITEM = 10

        private const val MAX_RAW_BUFFER_CHARS = 256 * 1024
        private const val MAX_OUTPUT_PAGES_PER_COMMAND = 100
    }

    /**
     * 处理终端输出
     */
    fun processOutput(
        sessionId: String,
        chunk: String,
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        session.rawBuffer.append(chunk)

        if (session.rawBuffer.length > MAX_RAW_BUFFER_CHARS) {
            val over = session.rawBuffer.length - MAX_RAW_BUFFER_CHARS
            session.rawBuffer.delete(0, over)
        }

        Log.d(TAG, "Processing chunk for session $sessionId. New buffer size: ${session.rawBuffer.length}")

        // 始终检查全屏模式切换
        if (detectFullscreenMode(sessionId, session.rawBuffer, sessionManager)) {
            // 如果检测到模式切换，缓冲区可能已被修改，及早返回以处理下一个块
            return
        }

        // 始终更新 ANSI 解析器（用于 Canvas 渲染），包括初始化阶段
        // 这样用户可以看到初始化过程中的所有输出，包括错误信息
        session.ansiParser.parse(chunk)

        // 如果在全屏模式下，跳过行解析逻辑（全屏应用自己管理屏幕）
        if (session.isFullscreen) {
            // 不需要再次解析，ansiParser 已经更新
            return
        }

        val state = sessionStates.getOrPut(sessionId) { SessionProcessingState() }

        // 从缓冲区中提取并处理行
        while (session.rawBuffer.isNotEmpty()) {
            val bufferContent = session.rawBuffer.toString()
            val newlineIndex = bufferContent.indexOf('\n')
            val carriageReturnIndex = bufferContent.indexOf('\r')

            if (carriageReturnIndex != -1 && (newlineIndex == -1 || carriageReturnIndex < newlineIndex)) {
                // We have a carriage return.
                val line = bufferContent.substring(0, carriageReturnIndex)

                val isCRLF = carriageReturnIndex + 1 < bufferContent.length && bufferContent[carriageReturnIndex + 1] == '\n'
                val consumedLength = if (isCRLF) carriageReturnIndex + 2 else carriageReturnIndex + 1

                session.rawBuffer.delete(0, consumedLength)

                if (isCRLF) {
                    // It's a CRLF, treat as a normal line. `processLine` will handle the
                    // case where this CRLF finalizes a progress-updated line.
                    Log.d(TAG, "Processing CRLF line: '$line'")
                    processLine(sessionId, line, sessionManager)
                } else {
                    // It's just CR, treat as a progress update.
                    Log.d(TAG, "Processing CR line: '$line'")
                    handleCarriageReturn(sessionId, line, sessionManager)
                }
            } else if (newlineIndex != -1) {
                // We have a newline without a preceding carriage return.
                val line = bufferContent.substring(0, newlineIndex)
                session.rawBuffer.delete(0, newlineIndex + 1)
                Log.d(TAG, "Processing LF line: '$line'")
                processLine(sessionId, line, sessionManager)
            } else {
                // No full line-terminator found in the buffer.

                // 首先检查是否是进度行（优先级最高，避免被误判为提示符）
                if (AnsiUtils.isProgressLine(bufferContent)) {
                    Log.d(TAG, "Detected progress line in buffer: '$bufferContent'")
                    val cleanContent = AnsiUtils.stripAnsi(bufferContent)
                    Log.d(TAG, "Stripped progress line: '$cleanContent'")
                    handleCarriageReturn(sessionId, bufferContent, sessionManager)
                    session.rawBuffer.clear()
                    continue // Re-check buffer in case more data came in
                }

                // 然后检查是否是提示符
                val cleanContent = AnsiUtils.stripAnsi(bufferContent)

                // AI Limbs 首次前台启动可能在普通 shell prompt 出现前展示开发环境选择菜单。
                // 这个菜单本身已经证明 Ubuntu/PTY 已经就绪，因此不能继续阻塞 Session 初始化。
                val isDevelopmentSetupPrompt =
                    session.initState == SessionInitState.AWAITING_FIRST_PROMPT &&
                        cleanContent.contains("请选择 [1/2/3]")
                if (isDevelopmentSetupPrompt) {
                    Log.d(TAG, "AI Limbs development setup prompt reached. Session is now ready.")
                    session.rawBuffer.clear()
                    sessionManager.updateSession(sessionId) { current ->
                        current.copy(initState = SessionInitState.READY)
                    }
                    handleInteractivePrompt(sessionId, cleanContent, sessionManager)
                    break
                }

                // Retain/display every unterminated physical line without labeling it as input.
                state.partialOutput = cleanContent

                // 检查是否是普通 shell 提示符
                val isShellPrompt = isPrompt(cleanContent)

                state.justHandledCarriageReturn = false
                if (isShellPrompt) {
                    processLine(sessionId, bufferContent, sessionManager)
                    session.rawBuffer.clear()
                    state.partialOutput = ""
                } else {
                    updateCommandOutput(sessionId, cleanContent, sessionManager, provisional = true)
                    refreshInputWaitState(sessionId, sessionManager)
                }
                break // Exit loop, wait for more data.
            }
        }
    }

    /**
     * 清理已关闭会话的处理状态，避免状态表长期增长。
     */
    fun clearSessionState(sessionId: String) {
        sessionStates.remove(sessionId)
    }

    private fun handleCarriageReturn(sessionId: String, line: String, sessionManager: SessionManager) {
        val cleanLine = AnsiUtils.stripAnsi(line)
        sessionStates[sessionId]?.partialOutput = ""
        val session = sessionManager.getSession(sessionId) ?: return
        if (session.initState != SessionInitState.READY) {
            processLine(sessionId, line, sessionManager)
            return
        }

        // 检查是否是命令提示符（优先级最高）
        // 即使是 CR line，如果是提示符也应该作为命令完成处理
        if (isPrompt(cleanLine.trim())) {
            Log.d(TAG, "Detected prompt in CR line: '$cleanLine'")
            handlePrompt(sessionId, cleanLine, sessionManager)
            sessionStates[sessionId]?.justHandledCarriageReturn = false
            return
        }

        // 只有在清理后的内容非空时才处理为进度更新
        // 空内容（如 ANSI 控制序列）不应影响下一行的处理
        if (cleanLine.isNotEmpty()) {
            updateProgressOutput(sessionId, cleanLine, sessionManager)
            sessionStates[sessionId]?.justHandledCarriageReturn = true
        }
        // 如果是空内容（纯 ANSI 控制序列），不设置 justHandledCarriageReturn
        // 这样下一行会被正常处理，而不是被当作进度更新
    }

    private fun processLine(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        sessionStates[sessionId]?.partialOutput = ""

        when (session.initState) {
            SessionInitState.INITIALIZING -> {
                handleInitializingState(sessionId, line, sessionManager)
            }
            SessionInitState.LOGGED_IN -> {
                handleLoggedInState(sessionId, line, sessionManager)
            }
            SessionInitState.AWAITING_FIRST_PROMPT -> {
                handleAwaitingFirstPromptState(sessionId, line, sessionManager)
            }
            SessionInitState.READY -> {
                val state = sessionStates.getOrPut(sessionId) { SessionProcessingState() }
                if (state.justHandledCarriageReturn) {
                    // A newline is received after a carriage return. This finalizes the line that was being updated.
                    state.justHandledCarriageReturn = false // Reset state immediately

                    val cleanLine = AnsiUtils.stripAnsi(line)

                    if (cleanLine.isNotEmpty()) {
                        updateProgressOutput(sessionId, cleanLine, sessionManager)
                    }

                    // Always append a newline to finalize the line and move to the next.
                    val currentItem = session.currentExecutingCommand
                    if (currentItem != null) {
                        session.currentCommandOutput.append('\n')
                        currentItem.setOutput(session.currentCommandOutput.toString())
                    }
                } else {
                    handleReadyState(sessionId, line, sessionManager)
                }
            }
        }
    }

    private fun handleInitializingState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        if (line.contains("LOGIN_SUCCESSFUL")) {
            Log.d(TAG, "Login successful marker found.")
            sessionManager.getSession(sessionId)?.let { session ->
                session.currentCommandOutput.clear()
                sessionManager.updateSession(sessionId) {
                    it.copy(initState = SessionInitState.LOGGED_IN)
                }
            }
        }
    }

    private fun handleLoggedInState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        if (AnsiUtils.stripAnsi(line).contains("TERMINAL_READY")) {
            Log.d(TAG, "TERMINAL_READY marker found.")
            sessionManager.updateSession(sessionId) { session ->
                session.copy(initState = SessionInitState.AWAITING_FIRST_PROMPT)
            }
        }
    }

    private fun handleAwaitingFirstPromptState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        val cleanLine = AnsiUtils.stripAnsi(line)
        Log.d(TAG, "handleAwaitingFirstPromptState: checking line: '$cleanLine'")
        if (handlePrompt(sessionId, cleanLine, sessionManager)) {
            Log.d(TAG, "First prompt detected. Session is now ready.")
            sessionManager.updateSession(sessionId) { session ->
                session.copy(initState = SessionInitState.READY)
            }

            // 发送欢迎语到 Canvas
            sendWelcomeMessage(sessionId, sessionManager)
        } else {
            Log.d(TAG, "Not a prompt, continuing to wait...")
        }
    }

    private fun handleReadyState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        val cleanLine = AnsiUtils.stripAnsi(line)
        Log.d(TAG, "Stripped line: '$cleanLine'")

        // 跳过TERMINAL_READY信号
        if (cleanLine.trim() == "TERMINAL_READY") {
            return
        }

        val session = sessionManager.getSession(sessionId) ?: return

        // 检测命令回显
        if (isCommandEcho(cleanLine, session)) {
            Log.d(TAG, "Ignoring command echo: '$cleanLine'")
            return
        }

        // 优先处理常规提示符，因为它表示命令结束
        if (handlePrompt(sessionId, cleanLine, sessionManager)) {
            return
        }

        // 注意：不在这里检测交互式提示符，因为这里处理的是以 CRLF 结束的完整行
        // 真正的交互式提示符通常不以换行结束，会在 buffer 末尾被检测到（第 118 行）

        // 处理普通输出
        updateCommandOutput(sessionId, cleanLine, sessionManager)
    }

    /**
     * 检测是否是提示符
     */
    fun isPrompt(line: String): Boolean {
        val cwdPromptRegex = Regex("<cwd>(.*)</cwd>.*[#$]")
        if (cwdPromptRegex.containsMatchIn(line)) {
            return true
        }

        val trimmed = line.trim()
        return trimmed.endsWith("$") ||
                trimmed.endsWith("#") ||
                trimmed.endsWith("$ ") ||
                trimmed.endsWith("# ") ||
                Regex(".*@[a-zA-Z0-9.\\-]+\\s?:\\s?~?/?.*[#$]\\s*$").matches(trimmed) ||
                Regex("root@[a-zA-Z0-9.\\-]+:\\s?~?/?.*#\\s*$").matches(trimmed)
    }

    /**
     * 处理提示符
     */
    private fun handlePrompt(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ): Boolean {
        val session = sessionManager.getSession(sessionId) ?: return false

        val cwdPromptRegex = Regex("<cwd>(.*)</cwd>.*[#$]")
        val match = cwdPromptRegex.find(line)

        val isAPrompt = if (match != null) {
            val path = match.groups[1]?.value?.trim() ?: "~"
            sessionManager.updateSession(sessionId) { session ->
                session.copy(currentDirectory = "$path $")
            }

            // 发出目录变化事件
            onDirectoryChangeEvent(SessionDirectoryEvent(
                sessionId = sessionId,
                currentDirectory = "$path $"
            ))

            Log.d(TAG, "Matched CWD prompt. Path: $path")

            val outputBeforePrompt = line.substring(0, match.range.first)
            if (outputBeforePrompt.isNotBlank()) {
                updateCommandOutput(sessionId, outputBeforePrompt, sessionManager)
            }
            true
        } else {
            val trimmed = line.trim()
            val isFallbackPrompt = trimmed.endsWith("$") ||
                    trimmed.endsWith("#") ||
                    trimmed.endsWith("$ ") ||
                    trimmed.endsWith("# ") ||
                    Regex(".*@[a-zA-Z0-9.\\-]+\\s?:\\s?~?/?.*[#$]\\s*$").matches(trimmed) ||
                    Regex("root@[a-zA-Z0-9.\\-]+:\\s?~?/?.*#\\s*$").matches(trimmed)

            if (isFallbackPrompt) {
                val regex = Regex(""".*:\s*(~?/?.*)\s*[#$]$""")
                val matchResult = regex.find(trimmed)
                val cleanPrompt = matchResult?.groups?.get(1)?.value?.trim() ?: trimmed
                sessionManager.updateSession(sessionId) { session ->
                    session.copy(currentDirectory = "${cleanPrompt} $")
                }

                // 发出目录变化事件
                onDirectoryChangeEvent(SessionDirectoryEvent(
                    sessionId = sessionId,
                    currentDirectory = "${cleanPrompt} $"
                ))

                Log.d(TAG, "Matched fallback prompt: $cleanPrompt")
                true
            } else {
                false
            }
        }

        if (isAPrompt) {
            // 检测到常规提示符，表示我们回到了shell。
            // 确保退出任何持久的交互模式。
            if (session.isInteractiveMode) {
                sessionManager.updateSession(sessionId) {
                    it.copy(
                        isInteractiveMode = false,
                        interactivePrompt = ""
                    )
                }
            }
            finishCurrentCommand(sessionId, sessionManager)
            return true
        }
        return false
    }

    fun refreshInputWaitState(sessionId: String, sessionManager: SessionManager): PtyInputWaitState {
        val session = sessionManager.getSession(sessionId) ?: return PtyInputWaitState.NOT_WAITING
        val command = session.currentExecutingCommand
        val state = when {
            command?.isExecuting != true -> PtyInputWaitState.NOT_WAITING
            session.terminalType != TerminalType.LOCAL -> PtyInputWaitState.UNKNOWN
            else -> session.pty?.getInputWaitState() ?: PtyInputWaitState.UNKNOWN
        }
        if (sessionManager.getSession(sessionId)?.currentExecutingCommand?.id != command?.id) {
            return PtyInputWaitState.NOT_WAITING
        }
        val waiting = state == PtyInputWaitState.WAITING
        val preview = sessionStates[sessionId]?.partialOutput.orEmpty()
        if (session.isWaitingForInteractiveInput != waiting || session.isInteractiveMode != waiting ||
            (waiting && session.lastInteractivePrompt != preview)) {
            sessionManager.updateSession(sessionId) { current ->
                // A sample from a completed/replaced command must not affect a newer command.
                if (current.currentExecutingCommand?.id != command?.id) current else current.copy(
                    isWaitingForInteractiveInput = waiting,
                    lastInteractivePrompt = if (waiting) preview else "",
                    isInteractiveMode = waiting,
                    interactivePrompt = if (waiting) preview else ""
                )
            }
        }
        return state
    }

    private fun handleInteractivePrompt(
        sessionId: String,
        cleanLine: String,
        sessionManager: SessionManager,
        provisional: Boolean = false
    ) {
        Log.d(TAG, "Detected interactive prompt: $cleanLine")
        val session = sessionManager.getSession(sessionId) ?: return

        sessionManager.updateSession(sessionId) { session ->
            session.copy(
                isWaitingForInteractiveInput = true,
                lastInteractivePrompt = cleanLine,
                isInteractiveMode = true, // 统一标记为交互模式
                interactivePrompt = cleanLine
            )
        }

        // Preview a growing prompt without inventing a line break between read chunks.
        if (cleanLine.isNotBlank()) {
            updateCommandOutput(sessionId, cleanLine, sessionManager, provisional)
        }
    }



    private fun isCommandEcho(cleanLine: String, session: TerminalSessionData): Boolean {
        val lastExecutingItem = session.currentExecutingCommand
        if (lastExecutingItem != null && lastExecutingItem.isExecuting) {
            val commandToCheck = lastExecutingItem.command.trim()
            val lineToCheck = cleanLine.trim()
            val isMatch = lineToCheck == commandToCheck

            if (session.currentCommandOutput.isEmpty() && isMatch) {
                return true
            }
        }
        return false
    }

    private fun updateCommandOutput(
        sessionId: String,
        cleanLine: String,
        sessionManager: SessionManager,
        provisional: Boolean = false
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        val currentItem = session.currentExecutingCommand

        if (currentItem != null && currentItem.isExecuting) {
            val builder = session.currentCommandOutput
            val state = sessionStates.getOrPut(sessionId) { SessionProcessingState() }
            val replaced = state.outputLines.append(builder, cleanLine, provisional)

            // 实时更新当前输出块
            currentItem.setOutput(builder.toString())
            if (!replaced) session.currentOutputLineCount++

            // 发出命令执行过程事件
            onCommandExecutionEvent(CommandExecutionEvent(
                commandId = currentItem.id,
                sessionId = sessionId,
                outputChunk = cleanLine,
                isCompleted = false,
                replaceLastOutputLine = replaced
            ))

            if (!provisional && session.currentOutputLineCount >= MAX_LINES_PER_HISTORY_ITEM) {
                // 当前页已满，将其添加到已完成的页面列表并开始新的一页
                while (currentItem.outputPages.size >= MAX_OUTPUT_PAGES_PER_COMMAND) {
                    currentItem.outputPages.removeAt(0)
                }
                currentItem.outputPages.add(currentItem.output)
                builder.clear()
                state.outputLines.clear()
                session.currentOutputLineCount = 0
                currentItem.setOutput("") // 为新页面清空实时输出
            }
        }
    }

    private fun updateProgressOutput(
        sessionId: String,
        cleanLine: String,
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        val builder = session.currentCommandOutput
        val lastExecutingItem = session.currentExecutingCommand

        if (lastExecutingItem != null && lastExecutingItem.isExecuting) {
            sessionStates[sessionId]?.outputLines?.clear()
             // More efficient way to replace the last line
            val lastNewlineIndex = builder.lastIndexOf('\n')
            if (lastNewlineIndex != -1) {
                // Found a newline, replace everything after it
                builder.setLength(lastNewlineIndex + 1)
                builder.append(cleanLine)
            } else {
                // No newline, replace the whole buffer
                builder.clear()
                builder.append(cleanLine)
            }
            // Update history from the builder
            lastExecutingItem.setOutput(builder.toString().trimEnd())
        }
    }

    private fun finishCurrentCommand(sessionId: String, sessionManager: SessionManager) {
        sessionManager.updateSession(sessionId) { session ->
            session.copy(
                isWaitingForInteractiveInput = false,
                lastInteractivePrompt = "",
                isInteractiveMode = false,
                interactivePrompt = ""
            )
        }

        val session = sessionManager.getSession(sessionId) ?: return
        val lastExecutingItem = session.currentExecutingCommand

        if (lastExecutingItem != null && lastExecutingItem.isExecuting) {
            val finalOutput = buildString {
                if (lastExecutingItem.outputPages.isNotEmpty()) {
                    append(lastExecutingItem.outputPages.joinToString("\n"))
                }
                val tail = session.currentCommandOutput.toString().trim()
                if (tail.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(tail)
                }
            }.trim()

            lastExecutingItem.setOutput(finalOutput)
            lastExecutingItem.setExecuting(false)

            Log.i(TAG, "Finishing command ${lastExecutingItem.id} for session $sessionId")

            // 发出命令完成事件
            onCommandExecutionEvent(CommandExecutionEvent(
                commandId = lastExecutingItem.id,
                sessionId = sessionId,
                outputChunk = finalOutput,
                isCompleted = true
            ))

            // Clear the reference since command is no longer executing
            session.currentExecutingCommand = null
            session.currentCommandOutput.clear()
            sessionStates[sessionId]?.outputLines?.clear()

            // 通知命令已完成，可以处理下一个队列命令
            onCommandCompleted(sessionId)
        }
    }

    fun handleSessionExit(
        sessionId: String,
        message: String,
        sessionManager: SessionManager
    ) {
        sessionManager.updateSession(sessionId) {
            it.copy(
                isWaitingForInteractiveInput = false,
                lastInteractivePrompt = "",
                isInteractiveMode = false,
                interactivePrompt = ""
            )
        }

        val session = sessionManager.getSession(sessionId) ?: return
        session.rawBuffer.clear()
        session.ansiParser.parse("\r\n$message\r\n")

        val lastExecutingItem = session.currentExecutingCommand
        if (lastExecutingItem != null && lastExecutingItem.isExecuting) {
            val builder = session.currentCommandOutput
            if (builder.isNotEmpty() && builder.last() != '\n') {
                builder.append('\n')
            }
            builder.append(message)

            val finalOutput = buildString {
                if (lastExecutingItem.outputPages.isNotEmpty()) {
                    append(lastExecutingItem.outputPages.joinToString("\n"))
                }
                val tail = builder.toString().trim()
                if (tail.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(tail)
                }
            }.trim()

            lastExecutingItem.setOutput(finalOutput)
            lastExecutingItem.setExecuting(false)

            onCommandExecutionEvent(
                CommandExecutionEvent(
                    commandId = lastExecutingItem.id,
                    sessionId = sessionId,
                    outputChunk = finalOutput,
                    isCompleted = true
                )
            )

            session.currentExecutingCommand = null
        }

        session.currentCommandOutput.clear()
        sessionStates[sessionId]?.outputLines?.clear()
        session.currentOutputLineCount = 0
        session.commandQueue.clear()
    }

    /**
     * 检测并处理全屏模式切换
     * @return 如果处理了全屏模式切换，则返回 true
     */
    private fun detectFullscreenMode(sessionId: String, buffer: StringBuilder, sessionManager: SessionManager): Boolean {
        // CSI ? 1049 h: 启用备用屏幕缓冲区（进入全屏模式）
        // CSI ? 1049 l: 禁用备用屏幕缓冲区（退出全屏模式）
        val enterFullscreen = "\u001B[?1049h"
        val exitFullscreen = "\u001B[?1049l"

        val bufferContent = buffer.toString()

        val enterIndex = bufferContent.indexOf(enterFullscreen)
        val exitIndex = bufferContent.indexOf(exitFullscreen)

        if (enterIndex != -1) {
            Log.d(TAG, "Entering fullscreen mode for session $sessionId")

            sessionManager.updateSession(sessionId) { session ->
                session.copy(isFullscreen = true)
            }

            // 清空缓冲区，ansiParser 已经包含所有内容
            buffer.clear()
            return true
        }

        if (exitIndex != -1) {
            Log.d(TAG, "Exiting fullscreen mode for session $sessionId")
            val outputBeforeExit = bufferContent.substring(0, exitIndex)

            // 更新最后一个命令的输出
            if (outputBeforeExit.isNotEmpty()) {
                updateCommandOutput(sessionId, outputBeforeExit, sessionManager)
            }

            sessionManager.updateSession(sessionId) { session ->
                session.copy(isFullscreen = false)
            }

            // 消耗包括退出代码在内的所有内容
            buffer.delete(0, exitIndex + exitFullscreen.length)

            // 退出全屏后，我们可能需要重新绘制提示符
            finishCurrentCommand(sessionId, sessionManager)
            return true
        }
        return false
    }

    /**
     * 发送欢迎消息到 Canvas
     * 在 READY 状态时清屏，然后显示欢迎消息
     */
    private fun sendWelcomeMessage(sessionId: String, sessionManager: SessionManager) {
        val session = sessionManager.getSession(sessionId) ?: return

        // 构建欢迎消息，包含 ANSI 控制序列
        // \u001B[2J - 清屏（清除初始化过程中的所有输出）
        // \u001B[H - 移动光标到左上角
        // 使用 \r\n 确保正确换行（\r 回车到行首，\n 换到下一行）
        val welcomeMessage = "\u001B[2J\u001B[H" +
            "     _    ___   _     ___ __  __ ___  ___ \r\n" +
            "    /_\\  |_ _| | |   |_ _|  \\/  | _ )/ __|\r\n" +
            "   / _ \\  | |  | |__  | || |\\/| | _ \\__ \\\r\n" +
            "  /_/ \\_\\|___| |____|___|_|  |_|___/|___/\r\n" +
            "\r\n" +
            "  >> AI Limbs Ubuntu sandbox on Android <<\r\n" +
            "\r\n"

        // 直接发送到 ANSI 解析器（Canvas 渲染）
        // 清屏操作会清除之前初始化过程中的所有输出
        session.ansiParser.parse(welcomeMessage)

        Log.d(TAG, "Screen cleared and welcome message sent to Canvas for session $sessionId")
    }

}
