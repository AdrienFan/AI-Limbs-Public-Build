package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** WAITING requires observed input on this PTY; unavailable observations remain UNKNOWN. */
enum class PtyInputWaitState { WAITING, NOT_WAITING, UNKNOWN }

/** Inspects only the subprocess tree created for this PTY, including pipeline members/threads. */
internal class PtyInputWaitProbe(
    private val rootPid: Int,
    private val readSyscalls: Set<Long>,
    private val proc: File = File("/proc")
) {
    private data class ProcessStat(val state: Char, val group: Int, val session: Int)
    private val whitespace = Regex("\\s+")

    @Synchronized
    fun inspect(foregroundGroup: Int, slavePath: String): PtyInputWaitState {
        if (rootPid <= 0 || foregroundGroup <= 0 || slavePath.isEmpty()) return PtyInputWaitState.UNKNOWN
        val rootStat = readStat(File(proc, "$rootPid/stat")) ?: return PtyInputWaitState.UNKNOWN
        if (rootStat.session != rootPid) return PtyInputWaitState.UNKNOWN
        val pending = ArrayDeque<Int>()
        val visited = HashSet<Int>()
        pending.add(rootPid)
        var incomplete = false
        while (pending.isNotEmpty()) {
            if (visited.size >= 512) return PtyInputWaitState.UNKNOWN
            val pid = pending.removeFirst()
            if (!visited.add(pid)) continue
            val directory = File(proc, pid.toString())
            val stat = readStat(File(directory, "stat"))
            if (stat == null) {
                if (directory.exists()) incomplete = true
                continue
            }
            if (stat.session != rootPid) continue
            val tasks = File(directory, "task").listFiles()
            if (tasks == null) { incomplete = true; continue }
            for (task in tasks) {
                if (task.name.toIntOrNull() == null) continue
                val children = readText(File(task, "children"))
                if (children == null) incomplete = true
                else if (children.isNotBlank()) {
                    for (child in children.trim().split(whitespace)) {
                        val childPid = child.toIntOrNull()
                        if (childPid == null) incomplete = true else pending.add(childPid)
                    }
                }
                if (stat.group != foregroundGroup) continue
                when (inspectTask(task, foregroundGroup, slavePath)) {
                    PtyInputWaitState.WAITING -> return PtyInputWaitState.WAITING
                    PtyInputWaitState.UNKNOWN -> incomplete = true
                    PtyInputWaitState.NOT_WAITING -> Unit
                }
            }
        }
        return if (incomplete) PtyInputWaitState.UNKNOWN else PtyInputWaitState.NOT_WAITING
    }

    private fun inspectTask(task: File, foregroundGroup: Int, slavePath: String): PtyInputWaitState {
        val before = readStat(File(task, "stat")) ?: return PtyInputWaitState.UNKNOWN
        if (before.group != foregroundGroup || before.session != rootPid || before.state != 'S') {
            return PtyInputWaitState.NOT_WAITING
        }
        val syscall = readText(File(task, "syscall")) ?: return PtyInputWaitState.UNKNOWN
        if (syscall.trim() == "running") return PtyInputWaitState.NOT_WAITING
        val arguments = syscall.trim().split(whitespace)
        val number = arguments.firstOrNull()?.toLongOrNull() ?: return PtyInputWaitState.UNKNOWN
        if (number < 0) return PtyInputWaitState.UNKNOWN
        if (number !in readSyscalls) return PtyInputWaitState.NOT_WAITING
        if (arguments.size < 4) return PtyInputWaitState.UNKNOWN
        val fd = parseArgument(arguments[1]) ?: return PtyInputWaitState.UNKNOWN
        val count = parseArgument(arguments[3]) ?: return PtyInputWaitState.UNKNOWN
        if (fd < 0 || count <= 0) return PtyInputWaitState.NOT_WAITING
        val target = try {
            Files.readSymbolicLink(File(task, "fd/$fd").toPath()).toString()
        } catch (_: IOException) { return PtyInputWaitState.UNKNOWN }
          catch (_: SecurityException) { return PtyInputWaitState.UNKNOWN }
        if (target != slavePath && target != "/dev/tty") return PtyInputWaitState.NOT_WAITING
        // /dev/tty resolves to this session's controlling PTY. Recheck the syscall
        // as well as ownership/state: a read can finish and become a sleep between observations.
        if (readText(File(task, "syscall")) != syscall) return PtyInputWaitState.UNKNOWN
        val after = readStat(File(task, "stat")) ?: return PtyInputWaitState.UNKNOWN
        return if (after.state == 'S' && after.group == foregroundGroup && after.session == rootPid) {
            PtyInputWaitState.WAITING
        } else PtyInputWaitState.NOT_WAITING
    }

    private fun parseArgument(value: String): Long? =
        if (value.startsWith("0x")) value.substring(2).toLongOrNull(16) else value.toLongOrNull()

    private fun readStat(file: File): ProcessStat? {
        val text = readText(file) ?: return null
        val close = text.lastIndexOf(')')
        if (close < 0) return null
        val fields = text.substring(close + 1).trim().split(whitespace)
        if (fields.size < 4 || fields[0].length != 1) return null
        return ProcessStat(fields[0][0], fields[2].toIntOrNull() ?: return null,
            fields[3].toIntOrNull() ?: return null)
    }

    private fun readText(file: File): String? = try { file.readText() } catch (_: IOException) { null }
        catch (_: SecurityException) { null }
}
