package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.system.Terminal
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AiLimbsRdcProcessToolExecutor(context: Context) {
    private val manager = AiLimbsRdcProcessSessionManager.getInstance(context)

    fun start(tool: AITool): ToolResult = runBlocking {
        val command = tool.value("command")
        val waitMs = tool.longValue("timeout_ms", DEFAULT_START_WAIT_MS)
        manager.start(command, waitMs).toToolResult(tool.name)
    }

    fun read(tool: AITool): ToolResult = runBlocking {
        val pid = tool.intValue("pid")
        val offset = tool.intValue("offset", 0)
        val length = tool.intValue("length", DEFAULT_READ_LINES)
        val timeoutMs = tool.longValue("timeout_ms", DEFAULT_READ_WAIT_MS)
        manager.read(pid, offset, length, timeoutMs).toToolResult(tool.name)
    }

    fun interact(tool: AITool): ToolResult = runBlocking {
        val pid = tool.intValue("pid")
        val input = tool.value("input")
        val timeoutMs = tool.longValue("timeout_ms", DEFAULT_INTERACT_WAIT_MS)
        val waitForPrompt = tool.booleanValue("wait_for_prompt", true)
        manager.interact(pid, input, timeoutMs, waitForPrompt).toToolResult(tool.name)
    }

    fun list(tool: AITool): ToolResult = runBlocking {
        manager.list().toToolResult(tool.name)
    }

    fun terminate(tool: AITool): ToolResult = runBlocking {
        val pid = tool.intValue("pid")
        manager.terminate(pid).toToolResult(tool.name)
    }

    private fun ActionResult.toToolResult(toolName: String): ToolResult =
        ToolResult(
            toolName = toolName,
            success = success,
            result = StringResultData(text),
            error = error
        )

    private fun AITool.value(name: String): String =
        parameters.find { it.name == name }?.value.orEmpty()

    private fun AITool.intValue(name: String, default: Int = -1): Int =
        parameters.find { it.name == name }?.value?.toIntOrNull() ?: default

    private fun AITool.longValue(name: String, default: Long): Long =
        parameters.find { it.name == name }?.value?.toLongOrNull() ?: default

    private fun AITool.booleanValue(name: String, default: Boolean): Boolean {
        val raw = parameters.find { it.name == name }?.value?.trim()?.lowercase() ?: return default
        return when (raw) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> default
        }
    }

    companion object {
        private const val DEFAULT_START_WAIT_MS = 10_000L
        private const val DEFAULT_READ_WAIT_MS = 5_000L
        private const val DEFAULT_INTERACT_WAIT_MS = 8_000L
        private const val DEFAULT_READ_LINES = 1_000
    }
}

internal data class ActionResult(
    val success: Boolean,
    val text: String,
    val error: String? = null
)

private data class ProcessRecord(
    val pid: Int,
    val sessionId: String,
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
    var finishedAtMillis: Long? = null,
    var collectorJob: Job? = null,
    var terminalError: String? = null
)

internal class AiLimbsRdcProcessSessionManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val terminal = Terminal.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextPid = AtomicInteger(30_000)
    private val records = ConcurrentHashMap<Int, ProcessRecord>()

    suspend fun start(command: String, requestedWaitMs: Long): ActionResult {
        if (command.isBlank()) return failure("start_process requires a command")
        if (!terminal.registerHiddenAiOperation()) {
            return failure("Ubuntu is not running. Start Ubuntu before start_process.")
        }
        val sharedOperationId = terminal.beginSharedHiddenOperation(command)

        val pid = nextPid.updateAndGet { current -> if (current >= 999_999) 30_000 else current + 1 }
        val sessionId = try {
            terminal.createBackgroundSession("RDC $pid")
        } catch (error: Throwable) {
            terminal.finishSharedHiddenOperation(sharedOperationId, null, error.message)
            terminal.unregisterHiddenAiOperation()
            return failure("Failed to create RDC process session: ${error.message}")
        }

        val record = ProcessRecord(
            pid = pid,
            sessionId = sessionId,
            sharedOperationId = sharedOperationId,
            command = command,
            startedAtMillis = System.currentTimeMillis()
        )
        records[pid] = record
        record.collectorJob = scope.launch {
            try {
                terminal.executeCommandFlow(sessionId, command).collect { event ->
                    if (event.isCompleted) {
                        finish(record, event.outputChunk, null)
                    } else {
                        appendOutput(record, event.outputChunk)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                finish(record, null, error.message ?: error::class.java.simpleName)
            } finally {
                releaseTerminalResources(record)
            }
        }

        val waitMs = requestedWaitMs.coerceIn(0L, MAX_INITIAL_WAIT_MS)
        if (waitMs > 0L) {
            val deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline) {
                if (!synchronized(record) { record.running } || terminal.isSessionWaitingForInput(sessionId)) break
                delay(PROCESS_STATE_POLL_MS)
            }
        }
        return success(formatStart(record))
    }

    suspend fun read(pid: Int, offset: Int, requestedLength: Int, requestedWaitMs: Long): ActionResult {
        val record = records[pid] ?: return failure("Process $pid was not found")
        val length = requestedLength.coerceIn(1, MAX_READ_LINES)
        val waitMs = requestedWaitMs.coerceIn(0L, MAX_READ_WAIT_MS)

        if (offset == 0 && waitMs > 0L) {
            val deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline) {
                val shouldReturn = synchronized(record) {
                    !record.running || record.readCursor < record.nextLine
                }
                if (shouldReturn || terminal.isSessionWaitingForInput(record.sessionId)) break
                delay(PROCESS_STATE_POLL_MS)
            }
        }

        return success(formatRead(record, offset, length, advanceCursor = offset == 0))
    }


    suspend fun interact(
        pid: Int,
        input: String,
        requestedWaitMs: Long,
        waitForPrompt: Boolean
    ): ActionResult {
        val record = records[pid] ?: return failure("Process $pid was not found")
        if (!synchronized(record) { record.running }) {
            return failure("Process $pid has already finished")
        }
        val beforeLine = synchronized(record) { record.nextLine }
        val payload = if (input.endsWith("\n") || input.endsWith("\r")) input else "$input\n"
        if (!terminal.sendInputToSessionNow(record.sessionId, payload)) {
            return failure("Failed to send input to process $pid")
        }
        if (!waitForPrompt) {
            return success("✅ Input sent to process $pid. Use read_process_output to get the response.")
        }

        val waitMs = requestedWaitMs.coerceIn(0L, MAX_INTERACT_WAIT_MS)
        val deadline = System.currentTimeMillis() + waitMs
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            val snapshot = outputFrom(record, beforeLine, MAX_INTERACT_LINES)
            if (!synchronized(record) { record.running } || terminal.isSessionWaitingForInput(record.sessionId) || looksLikePrompt(snapshot)) break
            delay(INTERACT_POLL_MS)
        }
        val output = outputFrom(record, beforeLine, MAX_INTERACT_LINES)
        return success(buildInteractionResult(record, output))
    }

    suspend fun list(): ActionResult {
        val items = records.values.sortedBy { it.pid }
        if (items.isEmpty()) return success("No active or recently completed RDC process sessions.")
        val text = buildString {
            items.forEach { record ->
                val running = synchronized(record) { record.running }
                val waiting = running && terminal.isSessionWaitingForInput(record.sessionId)
                val status = if (waiting) "waiting" else if (running) "running" else "finished"
                val runtimeMs = System.currentTimeMillis() - record.startedAtMillis
                append("PID: ${record.pid}, Status: $status, Runtime: ${runtimeMs}ms")
                append(", Command: ${record.command.lineSequence().firstOrNull().orEmpty().take(160)}")
                append('\n')
            }
        }.trimEnd()
        return success(text)
    }

    suspend fun terminate(pid: Int): ActionResult {
        val record = records[pid] ?: return failure("Process $pid was not found")
        if (!synchronized(record) { record.running }) {
            return success("Process $pid has already finished.")
        }
        terminal.sendInterruptSignalToSessionNow(record.sessionId)
        delay(TERMINATE_SETTLE_MS)
        record.collectorJob?.cancel()
        finish(record, null, "Terminated by RDC")
        releaseTerminalResources(record)
        return success("Process $pid terminated.")
    }

    private fun appendOutput(record: ProcessRecord, chunk: String) {
        if (chunk.isEmpty()) return
        val newLines = splitLines(chunk)
        if (newLines.isEmpty()) return
        synchronized(record) {
            newLines.forEach { line -> addBufferedLine(record, line) }
            trimBuffer(record)
        }
        terminal.appendSharedHiddenOperationOutput(
            record.sharedOperationId,
            newLines.joinToString("\n")
        )
    }

    private fun finish(record: ProcessRecord, finalOutput: String?, error: String?) {
        val sharedOutput = synchronized(record) {
            if (!record.running) return
            if (!finalOutput.isNullOrBlank()) {
                val finalLines = splitLines(finalOutput.takeLast(MAX_COMPLETION_RECONCILE_CHARS))
                if (record.nextLine == 0) {
                    finalLines.forEach { line -> addBufferedLine(record, line) }
                } else {
                    val lastFinalLine = finalLines.lastOrNull()
                    if (lastFinalLine != null && lastFinalLine != record.lines.lastOrNull()) {
                        addBufferedLine(record, lastFinalLine)
                    }
                }
                trimBuffer(record)
            }
            record.running = false
            record.finishedAtMillis = System.currentTimeMillis()
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
        while (
            record.lines.size > 1 &&
                (record.lines.size > MAX_BUFFER_LINES || record.bufferedChars > MAX_BUFFER_CHARS)
        ) {
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
            append("Process started with PID ${record.pid} (shell: linux)\n")
            append("Initial output:\n")
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
            val availableEnd = (start + length).coerceAtMost(record.nextLine)
            val localStart = (start - record.baseLine).coerceAtLeast(0)
            val localEnd = (availableEnd - record.baseLine).coerceAtLeast(localStart)
            val selected = record.lines.subList(localStart, localEnd).toList()
            if (advanceCursor) record.readCursor = availableEnd
            ReadSnapshot(
                start = start,
                end = availableEnd,
                total = record.nextLine,
                running = record.running,
                lines = selected,
                error = record.terminalError
            )
        }
        val remaining = (snapshot.total - snapshot.end).coerceAtLeast(0)
        val waiting = snapshot.running && terminal.isSessionWaitingForInput(record.sessionId)
        return buildString {
            append("[Reading ${snapshot.lines.size} lines from line ${snapshot.start} (total: ${snapshot.total} lines, $remaining remaining)]\n")
            append(if (snapshot.lines.isEmpty()) "(no new output)" else snapshot.lines.joinToString("\n"))
            when {
                waiting -> append("\n⌨️ Process ${record.pid} is waiting for input.")
                snapshot.running -> append("\n⏳ Process ${record.pid} is running.")
                else -> append("\n✅ Process ${record.pid} finished.")
            }
            snapshot.error?.let { append("\nTerminal status: $it") }
        }
    }

    private fun outputFrom(record: ProcessRecord, absoluteStart: Int, maxLines: Int): String =
        synchronized(record) {
            val start = absoluteStart.coerceAtLeast(record.baseLine).coerceAtMost(record.nextLine)
            val end = (start + maxLines).coerceAtMost(record.nextLine)
            val localStart = start - record.baseLine
            val localEnd = end - record.baseLine
            record.lines.subList(localStart, localEnd).joinToString("\n")
        }

    private fun buildInteractionResult(record: ProcessRecord, output: String): String =
        buildString {
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
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val logicalLines = normalized.split('\n')
        if (logicalLines.size == 1 && logicalLines[0].isEmpty()) return emptyList()
        val result = ArrayList<String>(logicalLines.size)
        logicalLines.forEach { line ->
            if (line.length <= MAX_LINE_CHARS) {
                result += line
            } else {
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
        runCatching { terminal.closeSession(record.sessionId) }
            .onFailure { AppLogger.w(TAG, "Failed to close RDC terminal session ${record.sessionId}", it) }
    }

    private fun scheduleCompletedCleanup(record: ProcessRecord) {
        scope.launch {
            delay(COMPLETED_RETENTION_MS)
            if (!synchronized(record) { record.running }) {
                records.remove(record.pid, record)
            }
        }
    }

    private fun success(text: String): ActionResult = ActionResult(true, text)

    private fun failure(message: String): ActionResult =
        ActionResult(false, message, message)

    private data class ReadSnapshot(
        val start: Int,
        val end: Int,
        val total: Int,
        val running: Boolean,
        val lines: List<String>,
        val error: String?
    )

    companion object {
        private const val TAG = "AiLimbsRdcProcess"
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

        @Volatile
        private var INSTANCE: AiLimbsRdcProcessSessionManager? = null

        fun getInstance(context: Context): AiLimbsRdcProcessSessionManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AiLimbsRdcProcessSessionManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
    }
}
