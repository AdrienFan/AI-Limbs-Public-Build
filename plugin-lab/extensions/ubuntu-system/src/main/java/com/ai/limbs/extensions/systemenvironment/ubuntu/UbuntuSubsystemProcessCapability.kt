package com.ai.limbs.extensions.systemenvironment.ubuntu

import com.ai.limbs.plugin.runtime.InProcessCapabilityDomain
import com.ai.limbs.plugin.runtime.InProcessCapabilityEffect
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessCapabilityParameterSpec
import com.ai.limbs.plugin.runtime.InProcessCapabilityReceipt
import com.ai.limbs.plugin.runtime.InProcessCapabilitySpec
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.TerminalManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class UbuntuSubsystemProcessCapability(
    private val scope: CoroutineScope,
    private val terminal: TerminalManager
) {
    companion object {
        const val ID = "plugin.system_environment.process"
        private const val MAX_INITIAL_WAIT_MS = 30_000L
        private const val MAX_READ_WAIT_MS = 30_000L
        private const val MAX_INTERACT_WAIT_MS = 30_000L
        private const val MAX_BUFFER_LINES = 4_000
        private const val MAX_BUFFER_CHARS = 2 * 1024 * 1024
        private const val MAX_LINE_CHARS = 64 * 1024
        private const val MAX_COMPLETION_RECONCILE_CHARS = 512 * 1024
        private const val MAX_READ_LINES = 1_000
        private const val MAX_INTERACT_LINES = 1_000
        private const val INITIAL_OUTPUT_LINES = 200
        private const val INTERACT_POLL_MS = 100L
        private const val PROCESS_STATE_POLL_MS = 100L
        private const val TERMINATE_SETTLE_MS = 200L
        private const val COMPLETED_RETENTION_MS = 5 * 60_000L
    }

    private data class ProcessRecord(
        val pid: Int,
        val sessionId: String,
        val ownsSession: Boolean,
        val commandId: String,
        val sharedOperationId: String,
        val command: String,
        val startedAtMillis: Long,
        val released: AtomicBoolean = AtomicBoolean(false),
        val lines: MutableList<String> = mutableListOf(),
        var bufferedChars: Int = 0,
        var baseLine: Int = 0,
        var nextLine: Int = 0,
        var readCursor: Int = 0,
        var running: Boolean = true,
        var collectorJob: Job? = null,
        var terminalError: String? = null
    )

    private val nextPid = AtomicInteger(30_000)
    private val records = ConcurrentHashMap<Int, ProcessRecord>()

    fun register(host: InProcessPluginHost) {
        host.registerCapability(
            InProcessCapabilitySpec(
                id = ID,
                displayName = "Ubuntu 后台进程",
                description = "插件自持有的后台 Ubuntu 进程协议，提供启动、增量读取、交互、列表和终止；供任意已授权入口通过统一能力层调用，不暴露 Base Terminal 实现。",
                keywords = listOf("Ubuntu", "process", "后台", "PTY", "交互"),
                parameters = listOf(
                    param("operation", "string", "start/read/interact/list/terminate"),
                    param("command", "string", "start 的命令", false),
                    param("session_id", "string", "start 可复用的既有持久会话；省略时创建临时后台会话", false),
                    param("pid", "integer", "进程 PID", false),
                    param("offset", "integer", "read 起始行；0 为增量读取，负数为尾部读取", false),
                    param("length", "integer", "read 最大行数", false),
                    param("input", "string", "interact 输入", false),
                    param("timeout_ms", "integer", "等待毫秒数", false),
                    param("wait_for_prompt", "boolean", "interact 是否等待提示符", false),
                    param("work_context", "boolean", "仅当本次 Ubuntu 调用属于开发、调试、开发环境管理或会改变项目/设备内容的工作任务时设为 true；普通 Ubuntu 使用保持 false。", false)
                ),
                effect = InProcessCapabilityEffect.PROCESS_EXECUTION,
                domain = InProcessCapabilityDomain.SYSTEM_ENVIRONMENT,
                workContextRequiredReceipts = setOf(InProcessCapabilityReceipt.WORK_MANUAL),
                executor = InProcessCapabilityExecutor { raw ->
                    val request = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                    runCatching { execute(request) }
                        .getOrElse { error -> failure(error.message ?: error::class.java.simpleName) }
                        .toString()
                }
            )
        )
    }

    suspend fun shutdown() {
        records.values.toList().forEach { record ->
            record.collectorJob?.cancel()
            runCatching { terminal.sendInterruptSignalToSessionNow(record.sessionId) }
            finish(record, null, "Ubuntu subsystem process service stopped")
            releaseTerminalResources(record)
        }
        records.clear()
    }

    private suspend fun execute(request: JSONObject): JSONObject = when (request.requiredText("operation")) {
        "start" -> start(
            request.requiredText("command"),
            request.optLong("timeout_ms", 10_000L),
            request.optString("session_id").trim().takeIf { it.isNotEmpty() }
        )
        "read" -> read(
            request.requiredInt("pid"),
            request.optInt("offset", 0),
            request.optInt("length", MAX_READ_LINES),
            request.optLong("timeout_ms", 5_000L)
        )
        "read_structured" -> readStructured(
            request.requiredInt("pid"),
            request.optInt("offset", 0),
            request.optInt("length", MAX_READ_LINES),
            request.optLong("timeout_ms", 5_000L)
        )
        "interact" -> interact(
            request.requiredInt("pid"),
            request.optString("input"),
            request.optLong("timeout_ms", 8_000L),
            request.optBoolean("wait_for_prompt", true)
        )
        "list" -> list()
        "terminate" -> terminate(request.requiredInt("pid"))
        else -> failure("Unsupported Ubuntu process operation: ${request.optString("operation")}")
    }

    private suspend fun start(command: String, requestedWaitMs: Long, requestedSessionId: String?): JSONObject {
        if (!terminal.registerHiddenAiOperation()) {
            return failure("Ubuntu is not running. Start Ubuntu before start_process.")
        }
        val sharedOperationId = terminal.beginSharedHiddenOperation(command)
        val pid = nextPid.updateAndGet { current -> if (current >= 999_999) 30_000 else current + 1 }
        val ownsSession = requestedSessionId == null
        val sessionId = if (requestedSessionId != null) {
            val existing = terminal.terminalState.value.sessions.firstOrNull { it.id == requestedSessionId }
            if (existing == null) {
                terminal.finishSharedHiddenOperation(sharedOperationId, null, "Persistent session not found: $requestedSessionId")
                terminal.unregisterHiddenAiOperation()
                return failure("Persistent system-environment session was not found: $requestedSessionId")
            }
            existing.id
        } else {
            try {
                terminal.createBackgroundSession("Process $pid").id
            } catch (error: Throwable) {
                terminal.finishSharedHiddenOperation(sharedOperationId, null, error.message)
                terminal.unregisterHiddenAiOperation()
                return failure("Failed to create Ubuntu background process session: ${error.message}")
            }
        }
        val commandId = UUID.randomUUID().toString()
        val record = ProcessRecord(
            pid = pid,
            sessionId = sessionId,
            ownsSession = ownsSession,
            commandId = commandId,
            sharedOperationId = sharedOperationId,
            command = command,
            startedAtMillis = System.currentTimeMillis()
        )
        records[pid] = record
        record.collectorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                terminal.commandExecutionEvents
                    .filter { event -> event.sessionId == sessionId && event.commandId == commandId }
                    .takeWhile { event ->
                        if (event.outputChunk.isNotEmpty()) appendOutput(record, event.outputChunk)
                        if (event.isCompleted) finish(record, null, null)
                        !event.isCompleted
                    }
                    .collect {}
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                finish(record, null, error.message ?: error::class.java.simpleName)
            } finally {
                releaseTerminalResources(record)
            }
        }
        try {
            terminal.sendCommandToSession(sessionId, command, commandId)
        } catch (error: Throwable) {
            record.collectorJob?.cancel()
            finish(record, null, error.message ?: error::class.java.simpleName)
            releaseTerminalResources(record)
            return failure("Failed to start Ubuntu process: ${error.message}")
        }
        val waitMs = requestedWaitMs.coerceIn(0L, MAX_INITIAL_WAIT_MS)
        if (waitMs > 0L) {
            val deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline) {
                if (!synchronized(record) { record.running } || terminal.isSessionWaitingForInput(sessionId)) break
                delay(PROCESS_STATE_POLL_MS)
            }
        }
        return ok(formatStart(record))
            .put("pid", pid)
            .put("session_id", sessionId)
            .put("owns_session", ownsSession)
            .put("running", synchronized(record) { record.running })
    }

    private suspend fun read(pid: Int, offset: Int, requestedLength: Int, requestedWaitMs: Long): JSONObject {
        val record = records[pid] ?: return failure("Process $pid was not found")
        val length = requestedLength.coerceIn(1, MAX_READ_LINES)
        val waitMs = requestedWaitMs.coerceIn(0L, MAX_READ_WAIT_MS)
        if (offset == 0 && waitMs > 0L) {
            val deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline) {
                val ready = synchronized(record) { !record.running || record.readCursor < record.nextLine }
                if (ready || terminal.isSessionWaitingForInput(record.sessionId)) break
                delay(PROCESS_STATE_POLL_MS)
            }
        }
        return ok(formatRead(record, offset, length, offset == 0)).put("pid", pid)
    }

    private suspend fun readStructured(pid: Int, offset: Int, requestedLength: Int, requestedWaitMs: Long): JSONObject {
        val record = records[pid] ?: return failure("Process $pid was not found")
        val length = requestedLength.coerceIn(1, MAX_READ_LINES)
        val waitMs = requestedWaitMs.coerceIn(0L, MAX_READ_WAIT_MS)
        if (offset == 0 && waitMs > 0L) {
            val deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline) {
                val ready = synchronized(record) { !record.running || record.readCursor < record.nextLine }
                if (ready || terminal.isSessionWaitingForInput(record.sessionId)) break
                delay(PROCESS_STATE_POLL_MS)
            }
        }
        val snapshot = synchronized(record) {
            val start = when {
                offset < 0 -> (record.nextLine + offset).coerceAtLeast(record.baseLine)
                offset > 0 -> offset.coerceAtLeast(record.baseLine).coerceAtMost(record.nextLine)
                else -> record.readCursor.coerceAtLeast(record.baseLine)
            }
            val end = (start + length).coerceAtMost(record.nextLine)
            val localStart = (start - record.baseLine).coerceAtLeast(0)
            val localEnd = (end - record.baseLine).coerceAtLeast(localStart)
            val selected = record.lines.subList(localStart, localEnd).toList()
            if (offset == 0) record.readCursor = end
            ReadSnapshot(start, end, record.nextLine, record.running, selected, record.terminalError)
        }
        val waiting = snapshot.running && terminal.isSessionWaitingForInput(record.sessionId)
        val output = snapshot.lines.joinToString("\n")
        return ok(output)
            .put("pid", pid)
            .put("session_id", record.sessionId)
            .put("output", output)
            .put("start_line", snapshot.start)
            .put("end_line", snapshot.end)
            .put("total_lines", snapshot.total)
            .put("running", snapshot.running)
            .put("waiting_for_input", waiting)
            .put("error", snapshot.error ?: JSONObject.NULL)
    }

    private suspend fun interact(pid: Int, input: String, requestedWaitMs: Long, waitForPrompt: Boolean): JSONObject {
        val record = records[pid] ?: return failure("Process $pid was not found")
        if (!synchronized(record) { record.running }) return failure("Process $pid has already finished")
        val beforeLine = synchronized(record) { record.nextLine }
        val payload = if (input.endsWith("\n") || input.endsWith("\r")) input else "$input\n"
        if (!terminal.sendInputToSessionNow(record.sessionId, payload)) return failure("Failed to send input to process $pid")
        if (!waitForPrompt) return ok("✅ Input sent to process $pid. Use read_process_output to get the response.").put("pid", pid)
        val deadline = System.currentTimeMillis() + requestedWaitMs.coerceIn(0L, MAX_INTERACT_WAIT_MS)
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            val snapshot = outputFrom(record, beforeLine, MAX_INTERACT_LINES)
            if (!synchronized(record) { record.running } || terminal.isSessionWaitingForInput(record.sessionId) || looksLikePrompt(snapshot)) break
            delay(INTERACT_POLL_MS)
        }
        return ok(buildInteractionResult(record, outputFrom(record, beforeLine, MAX_INTERACT_LINES))).put("pid", pid)
    }

    private fun list(): JSONObject {
        val items = records.values.sortedBy { it.pid }
        if (items.isEmpty()) return ok("No active or recently completed Ubuntu process sessions.")
        val text = buildString {
            items.forEach { record ->
                val running = synchronized(record) { record.running }
                val waiting = running && terminal.isSessionWaitingForInput(record.sessionId)
                val status = if (waiting) "waiting" else if (running) "running" else "finished"
                append("PID: ${record.pid}, Status: $status, Runtime: ${System.currentTimeMillis() - record.startedAtMillis}ms")
                append(", Command: ${record.command.lineSequence().firstOrNull().orEmpty().take(160)}\n")
            }
        }.trimEnd()
        return ok(text)
    }

    private suspend fun terminate(pid: Int): JSONObject {
        val record = records[pid] ?: return failure("Process $pid was not found")
        if (!synchronized(record) { record.running }) return ok("Process $pid has already finished.").put("pid", pid)
        terminal.sendInterruptSignalToSessionNow(record.sessionId)
        delay(TERMINATE_SETTLE_MS)
        record.collectorJob?.cancel()
        finish(record, null, "Terminated by capability caller")
        releaseTerminalResources(record)
        return ok("Process $pid terminated.").put("pid", pid)
    }

    private fun appendOutput(record: ProcessRecord, chunk: String) {
        if (chunk.isEmpty()) return
        val newLines = splitLines(chunk)
        if (newLines.isEmpty()) return
        synchronized(record) {
            newLines.forEach { addBufferedLine(record, it) }
            trimBuffer(record)
        }
        terminal.appendSharedHiddenOperationOutput(record.sharedOperationId, newLines.joinToString("\n"))
    }

    private fun finish(record: ProcessRecord, finalOutput: String?, error: String?) {
        val sharedOutput = synchronized(record) {
            if (!record.running) return
            if (!finalOutput.isNullOrBlank()) {
                val finalLines = splitLines(finalOutput.takeLast(MAX_COMPLETION_RECONCILE_CHARS))
                finalLines.forEach { line -> if (record.nextLine == 0 || line != record.lines.lastOrNull()) addBufferedLine(record, line) }
                trimBuffer(record)
            }
            record.running = false
            record.terminalError = error
            record.lines.joinToString("\n")
        }
        terminal.finishSharedHiddenOperation(record.sharedOperationId, sharedOutput, error)
        scheduleCompletedCleanup(record)
    }

    private fun addBufferedLine(record: ProcessRecord, line: String) {
        record.lines += line
        record.bufferedChars += line.length + 1
        record.nextLine += 1
    }

    private fun trimBuffer(record: ProcessRecord) {
        var removedLines = 0
        while (record.lines.size > 1 && (record.lines.size > MAX_BUFFER_LINES || record.bufferedChars > MAX_BUFFER_CHARS)) {
            val removed = record.lines.removeAt(0)
            record.bufferedChars = (record.bufferedChars - removed.length - 1).coerceAtLeast(0)
            removedLines += 1
        }
        if (removedLines > 0) record.baseLine += removedLines
        record.readCursor = record.readCursor.coerceAtLeast(record.baseLine)
    }

    private fun formatStart(record: ProcessRecord): String {
        val running = synchronized(record) { record.running }
        val waiting = running && terminal.isSessionWaitingForInput(record.sessionId)
        val output = outputFrom(record, (record.nextLine - INITIAL_OUTPUT_LINES).coerceAtLeast(record.baseLine), INITIAL_OUTPUT_LINES)
        return buildString {
            append("Process started with PID ${record.pid} (shell: linux)\nInitial output:\n")
            append(if (output.isBlank()) "(no output)" else output)
            when {
                waiting -> append("\n⌨️ Process is waiting for input. Use interact_with_process.")
                running -> append("\n⏳ Process is running. Use read_process_output to get more output.")
                else -> append("\n✅ Process finished.")
            }
        }
    }

    private fun formatRead(record: ProcessRecord, offset: Int, length: Int, advanceCursor: Boolean): String {
        val snapshot = synchronized(record) {
            val start = when {
                offset < 0 -> (record.nextLine + offset).coerceAtLeast(record.baseLine)
                offset > 0 -> offset.coerceAtLeast(record.baseLine).coerceAtMost(record.nextLine)
                else -> record.readCursor.coerceAtLeast(record.baseLine)
            }
            val end = (start + length).coerceAtMost(record.nextLine)
            val localStart = (start - record.baseLine).coerceAtLeast(0)
            val localEnd = (end - record.baseLine).coerceAtLeast(localStart)
            val selected = record.lines.subList(localStart, localEnd).toList()
            if (advanceCursor) record.readCursor = end
            ReadSnapshot(start, end, record.nextLine, record.running, selected, record.terminalError)
        }
        val waiting = snapshot.running && terminal.isSessionWaitingForInput(record.sessionId)
        return buildString {
            append("[Reading ${snapshot.lines.size} lines from line ${snapshot.start} (total: ${snapshot.total} lines, ${(snapshot.total - snapshot.end).coerceAtLeast(0)} remaining)]\n")
            append(if (snapshot.lines.isEmpty()) "(no new output)" else snapshot.lines.joinToString("\n"))
            when {
                waiting -> append("\n⌨️ Process ${record.pid} is waiting for input.")
                snapshot.running -> append("\n⏳ Process ${record.pid} is running.")
                else -> append("\n✅ Process ${record.pid} finished.")
            }
            snapshot.error?.let { append("\nTerminal status: $it") }
        }
    }

    private fun outputFrom(record: ProcessRecord, absoluteStart: Int, maxLines: Int): String = synchronized(record) {
        val start = absoluteStart.coerceAtLeast(record.baseLine).coerceAtMost(record.nextLine)
        val end = (start + maxLines).coerceAtMost(record.nextLine)
        record.lines.subList(start - record.baseLine, end - record.baseLine).joinToString("\n")
    }

    private fun buildInteractionResult(record: ProcessRecord, output: String): String = buildString {
        append(if (output.isBlank()) "(no new output)" else output)
        val running = synchronized(record) { record.running }
        val waiting = running && terminal.isSessionWaitingForInput(record.sessionId)
        when {
            waiting -> append("\nProcess ${record.pid} is waiting for input.")
            running -> append("\nProcess ${record.pid} is still running.")
            else -> append("\nProcess ${record.pid} finished.")
        }
    }

    private fun looksLikePrompt(output: String): Boolean {
        val trimmed = output.trimEnd()
        if (trimmed.isEmpty()) return false
        if (trimmed.endsWith(">>>")) return true
        return trimmed.lastOrNull() in setOf('>', '$', '#')
    }

    private fun splitLines(text: String): List<String> {
        val logicalLines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        if (logicalLines.size == 1 && logicalLines[0].isEmpty()) return emptyList()
        val result = ArrayList<String>(logicalLines.size)
        logicalLines.forEach { line ->
            if (line.length <= MAX_LINE_CHARS) result += line
            else {
                var start = 0
                while (start < line.length) {
                    val end = (start + MAX_LINE_CHARS).coerceAtMost(line.length)
                    result += line.substring(start, end)
                    start = end
                }
            }
        }
        return result
    }

    private fun releaseTerminalResources(record: ProcessRecord) {
        if (!record.released.compareAndSet(false, true)) return
        terminal.unregisterHiddenAiOperation()
        if (record.ownsSession) {
            runCatching { terminal.closeSession(record.sessionId) }
        }
    }

    private fun scheduleCompletedCleanup(record: ProcessRecord) {
        scope.launch {
            delay(COMPLETED_RETENTION_MS)
            if (!synchronized(record) { record.running }) records.remove(record.pid, record)
        }
    }

    private data class ReadSnapshot(
        val start: Int,
        val end: Int,
        val total: Int,
        val running: Boolean,
        val lines: List<String>,
        val error: String?
    )

    private fun ok(text: String): JSONObject = JSONObject().put("success", true).put("text", text)
    private fun failure(message: String): JSONObject = JSONObject().put("success", false).put("text", message).put("error", message)
    private fun param(name: String, type: String, description: String, required: Boolean = true) =
        InProcessCapabilityParameterSpec(name, type, description, required)
    private fun JSONObject.requiredText(key: String): String = optString(key).trim().ifBlank { throw IllegalArgumentException("$key is required") }
    private fun JSONObject.requiredInt(key: String): Int {
        require(has(key)) { "$key is required" }
        return getInt(key)
    }
}
