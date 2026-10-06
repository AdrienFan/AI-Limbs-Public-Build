package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PtyInputWaitProbeTest {
    @get:Rule val temp = TemporaryFolder()
    private val tty = "/dev/pts/5"
    private fun stat(pid: Int, state: Char, group: Int, session: Int = 100) =
        "$pid (worker ) with spaces) $state 1 $group $session 34821 $group 0 0\n"

    private fun process(pid: Int, state: Char = 'S', group: Int = pid,
                        syscall: String = "115 0x1 0x1 0x1 0 0 0 0 0",
                        target: String = tty, children: String = "", session: Int = 100): File {
        val directory = File(temp.root, pid.toString()).apply { mkdirs() }
        File(directory, "stat").writeText(stat(pid, state, group, session))
        task(directory, pid, state, group, syscall, target, children, session)
        return directory
    }

    private fun task(directory: File, tid: Int, state: Char, group: Int,
                     syscall: String, target: String, children: String = "", session: Int = 100) {
        val task = File(directory, "task/$tid").apply { mkdirs() }
        File(task, "stat").writeText(stat(tid, state, group, session))
        File(task, "syscall").writeText(syscall)
        File(task, "children").writeText(children)
        File(task, "fd").mkdirs()
        Files.createSymbolicLink(File(task, "fd/0").toPath(), File(target).toPath())
    }

    private fun inspect(group: Int = 101) = PtyInputWaitProbe(100, setOf(63L, 65L), temp.root)
        .inspect(group, tty)
    private fun shell(children: String = "101") = process(100, group = 100, children = children)
    private val read = "63 0x0 0x1000 0x1 0 0 0 0 0"

    @Test fun silenceDuringSleepIsNotInputWait() {
        shell(); process(101)
        assertEquals(PtyInputWaitState.NOT_WAITING, inspect())
    }
    @Test fun runningComputationIsNotInputWait() {
        shell(); process(101, state = 'R', syscall = "running")
        assertEquals(PtyInputWaitState.NOT_WAITING, inspect())
    }
    @Test fun blockedReadOnThisTerminalIsInputWaitWithoutAnyPromptText() {
        shell(); process(101, syscall = read)
        assertEquals(PtyInputWaitState.WAITING, inspect())
    }
    @Test fun blockedReadvOnControllingTerminalIsInputWait() {
        shell(); process(101, syscall = "65 0 0x1000 1 0 0 0 0 0", target = "/dev/tty")
        assertEquals(PtyInputWaitState.WAITING, inspect())
    }
    @Test fun pipeReadAndAnotherTerminalReadAreNotInputWait() {
        shell(); val child = process(101, syscall = read, target = "pipe:[123]")
        assertEquals(PtyInputWaitState.NOT_WAITING, inspect())
        val fd = File(child, "task/101/fd/0").toPath()
        Files.delete(fd); Files.createSymbolicLink(fd, File("/dev/pts/6").toPath())
        assertEquals(PtyInputWaitState.NOT_WAITING, inspect())
    }
    @Test fun backgroundReadIsNotForegroundInputWait() {
        shell(); process(101, group = 102, syscall = read)
        assertEquals(PtyInputWaitState.NOT_WAITING, inspect())
    }
    @Test fun unrelatedProcessIsNeverInspected() {
        shell(children = ""); process(101, syscall = read)
        assertEquals(PtyInputWaitState.NOT_WAITING, inspect())
    }
    @Test fun pipelineMemberCanReadWhileGroupLeaderWaits() {
        shell(); process(101, children = "102"); process(102, group = 101, syscall = read)
        assertEquals(PtyInputWaitState.WAITING, inspect())
    }
    @Test fun workerThreadCanBeTheTerminalReader() {
        shell(); val child = process(101)
        task(child, 103, 'S', 101, read, tty)
        assertEquals(PtyInputWaitState.WAITING, inspect())
    }
    @Test fun unavailableSyscallObservationStaysUnknown() {
        shell(); val child = process(101, syscall = read)
        File(child, "task/101/syscall").delete()
        assertEquals(PtyInputWaitState.UNKNOWN, inspect())
    }
    @Test fun unsupportedBlockedSyscallStaysUnknown() {
        shell(); process(101, syscall = "-1 0 0")
        assertEquals(PtyInputWaitState.UNKNOWN, inspect())
    }
    @Test fun stoppedTaskAndZeroLengthReadAreNotWaiting() {
        shell(); val child = process(101, state = 'T', syscall = read)
        assertEquals(PtyInputWaitState.NOT_WAITING, inspect())
        File(child, "task/101/stat").writeText(stat(101, 'S', 101))
        File(child, "task/101/syscall").writeText("63 0 0x1000 0 0 0 0 0 0")
        assertEquals(PtyInputWaitState.NOT_WAITING, inspect())
    }
    @Test fun freshSampleClearsInputWaitWhenProgramResumesAndSleeps() {
        shell(); val child = process(101, syscall = read)
        val probe = PtyInputWaitProbe(100, setOf(63L, 65L), temp.root)
        assertEquals(PtyInputWaitState.WAITING, probe.inspect(101, tty))
        File(child, "task/101/syscall").writeText("115 1 1 1 0 0 0 0 0")
        assertEquals(PtyInputWaitState.NOT_WAITING, probe.inspect(101, tty))
    }
    @Test fun aChildWithAnotherSessionCannotProveInputWait() {
        shell(); process(101, syscall = read, session = 999)
        assertEquals(PtyInputWaitState.NOT_WAITING, inspect())
    }
    @Test fun missingRootAndInvalidForegroundAreUnknown() {
        assertEquals(PtyInputWaitState.UNKNOWN, inspect())
        shell()
        assertEquals(PtyInputWaitState.UNKNOWN, inspect(0))
    }
}
